package com.pocketcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pocketcode.ui.ChatController
import com.pocketcode.ui.ChatScreen
import com.pocketcode.ui.DevicesScreen
import com.pocketcode.ui.PluginsScreen
import com.pocketcode.ui.DiagScreen
import com.pocketcode.ui.PreviewScreen
import com.pocketcode.ui.FileActions
import com.pocketcode.ui.FilesScreen
import com.pocketcode.ui.CompareScreen
import com.pocketcode.ui.ProjectsScreen
import com.pocketcode.ui.SessionsScreen
import com.pocketcode.ui.SettingsScreen

private enum class Screen { CHAT, SETTINGS, SESSIONS, PROJECTS, FILES, DEVICES, PLUGINS, DIAG, COMPARE, CONTEXT, TERMINAL, GIT, OCSESSIONS }

class MainActivity : ComponentActivity() {
    private val controller: ChatController
        get() = (application as PocketCodeApp).current

    private val pocketApp: PocketCodeApp get() = application as PocketCodeApp

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshStoragePermission() }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            controller.importFiles(uris)
        }

    private val pickFolderToImport =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) controller.importFolder(uri)
        }

    private val pickFolderAsProject =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) controller.addExternalFolder(uri, pickFolderFresh)
            pickFolderFresh = false
        }

    /** true — папку выбрали из окна «Новый чат»: после выбора начинаем в ней новый чат. */
    private var pickFolderFresh = false

    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) controller.exportBackup(uri)
        }

    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) controller.importBackup(uri)
        }

    private val exportZip =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) controller.exportZip(uri)
        }

    private val exportMd =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) controller.exportMarkdown(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        requestNotificationPermission()
        val actions = FileActions(
            pickFiles = { pickFiles.launch(arrayOf("*/*")) },
            pickFolderToImport = { pickFolderToImport.launch(null) },
            exportZip = { project -> exportZip.launch("$project.zip") },
            pickFolderAsProject = { pickFolderAsProject.launch(null) },
            requestStorageAccess = { requestStorageAccess() },
        )
        setContent {
            AppTheme {
                val vm = pocketApp.current
                var screen by remember { mutableStateOf(Screen.CHAT) }
                val pv = vm.previewUrl
                val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
                if (pv != null) {
                    PreviewScreen(pv, "Превью", onBack = { vm.previewUrl = null })
                } else when (screen) {
                    Screen.CHAT -> {
                        val chat: @Composable () -> Unit = {
                          val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
                          val dScope = androidx.compose.runtime.rememberCoroutineScope()
                          fun go(s: Screen) { dScope.launch { drawer.close() }; screen = s }
                          androidx.compose.material3.ModalNavigationDrawer(
                            drawerState = drawer,
                            drawerContent = {
                              androidx.compose.material3.ModalDrawerSheet {
                                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 12.dp)) {
                                  Text("PocketCode", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                                  Text(vm.currentProject + " · " + vm.profileLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp))
                                  androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                  listOf(
                                    "💬 История чатов" to Screen.SESSIONS,
                                    "📁 Проекты" to Screen.PROJECTS,
                                    "🗂 Файлы" to Screen.FILES,
                                    "🤖 Диалоги OpenCode" to Screen.OCSESSIONS,
                                    "⌨️ Терминал" to Screen.TERMINAL,
                                    "🌿 Git" to Screen.GIT,
                                    "🧠 Что в контексте" to Screen.CONTEXT,
                                    "🧩 Навыки, команды, MCP" to Screen.PLUGINS,
                                    "📱 Устройства" to Screen.DEVICES,
                                    "📊 Диагностика и расход" to Screen.DIAG,
                                    "⚖️ Сравнить модели" to Screen.COMPARE,
                                    "⚙️ Настройки" to Screen.SETTINGS,
                                  ).forEach { (title, target) ->
                                    androidx.compose.material3.NavigationDrawerItem(
                                      label = { Text(title) },
                                      selected = false,
                                      onClick = { go(target) },
                                      modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                  }
                                }
                              }
                            },
                            gesturesEnabled = true,
                          ) {
                          androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                            TabBar(pocketApp)
                            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                            key(vm) {
                            ChatScreen(
                                vm,
                                onOpenSettings = { screen = Screen.SETTINGS },
                                onOpenSessions = { screen = Screen.SESSIONS },
                                onOpenProjects = { screen = Screen.PROJECTS },
                                onOpenFiles = { screen = Screen.FILES },
                                onOpenContext = { screen = Screen.CONTEXT },
                                onOpenTerminal = { screen = Screen.TERMINAL },
                                onOpenGit = { screen = Screen.GIT },
                                onOpenOcSessions = { screen = Screen.OCSESSIONS },
                                onMenu = { dScope.launch { drawer.open() } },
                                onExport = { exportMd.launch("chat-" + vm.currentProject + ".md") },
                                onPickFolder = {
                                    pickFolderFresh = true
                                    pickFolderAsProject.launch(null)
                                },
                            )
                            }
                            }
                          }
                          }
                        }
                        if (wide) {
                            // Планшет и ландшафт: чат слева, файлы проекта справа
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { chat() }
                                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                                    key(vm.projectRoot.absolutePath) {
                                        FilesScreen(
                                            vm.projectRoot,
                                            vm.currentProject,
                                            onBack = { },
                                            onStartHere = { dir -> vm.openFolderAsProject(dir) },
                                            checker = { name, text -> vm.checkText(name, text) },
                                            refreshKey = vm.items.size,
                                        )
                                    }
                                }
                            }
                        } else chat()
                    }
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.CHAT }
                        val pv0 = pocketApp.controller
                        SettingsScreen(
                            vm = pv0,
                            initial = pv0.settings,
                            onSave = { s, p, a -> pv0.saveSettings(s, p, a) },
                            onResetContext = { vm.resetContext() },
                            onOpenCompare = { screen = Screen.COMPARE },
                            onBack = { screen = Screen.CHAT },
                        )
                    }
                    Screen.OCSESSIONS -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.OcSessionsScreen(vm, onBack = { screen = Screen.CHAT }, onOpened = { screen = Screen.CHAT })
                    }
                    Screen.TERMINAL -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.TerminalScreen(vm, onBack = { screen = Screen.CHAT })
                    }
                    Screen.GIT -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.GitScreen(vm, onBack = { screen = Screen.CHAT })
                    }
                    Screen.CONTEXT -> {
                        BackHandler { screen = Screen.CHAT }
                        com.pocketcode.ui.ContextScreen(vm, onBack = { screen = Screen.CHAT })
                    }
                    Screen.COMPARE -> {
                        BackHandler { screen = Screen.SETTINGS }
                        CompareScreen(vm, onBack = { screen = Screen.SETTINGS })
                    }
                    Screen.PROJECTS -> {
                        BackHandler { screen = Screen.CHAT }
                        ProjectsScreen(vm, actions, onOpenDevices = { screen = Screen.DEVICES }, onOpenPlugins = { screen = Screen.PLUGINS }, onOpenDiag = { screen = Screen.DIAG }, onBack = { screen = Screen.CHAT })
                    }
                    Screen.FILES -> FilesScreen(
                        vm.projectRoot,
                        vm.currentProject,
                        onBack = { screen = Screen.CHAT },
                        onStartHere = { dir ->
                            vm.openFolderAsProject(dir)
                            screen = Screen.CHAT
                        },
                        checker = { name, text -> vm.checkText(name, text) },
                    )
                    Screen.DEVICES -> {
                        BackHandler { screen = Screen.PROJECTS }
                        DevicesScreen(vm, onBack = { screen = Screen.PROJECTS })
                    }
                    Screen.DIAG -> {
                        BackHandler { screen = Screen.PROJECTS }
                        DiagScreen(
                            vm,
                            onBack = { screen = Screen.PROJECTS },
                            onExport = { exportBackup.launch("pocketcode-backup.json") },
                            onImport = { importBackup.launch(arrayOf("*/*")) },
                        )
                    }
                    Screen.PLUGINS -> {
                        BackHandler { screen = Screen.PROJECTS }
                        PluginsScreen(vm, onBack = { screen = Screen.PROJECTS })
                    }
                    Screen.SESSIONS -> {
                        BackHandler { screen = Screen.CHAT }
                        SessionsScreen(vm, onBack = { screen = Screen.CHAT })
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))?.let { controller.attachImage(it) }
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { controller.incomingText = it }
            }
            "com.pocketcode.NEW_TASK" -> if (!controller.running) {
                controller.newChat()
            }
        }
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        pocketApp.tabs.forEach { it.appVisible = true }
    }

    override fun onResume() {
        super.onResume()
        // Пользователь мог выдать доступ к файлам в системных настройках
        controller.refreshStoragePermission()
    }

    override fun onStop() {
        pocketApp.tabs.forEach { it.appVisible = false }
        super.onStop()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val uri = Uri.parse("package:$packageName")
            try {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
            } catch (e: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFF1F3A5F),
            surface = androidx.compose.ui.graphics.Color(0xFF121418),
            background = androidx.compose.ui.graphics.Color(0xFF121418),
        )
        else -> lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF2F5DA8),
            primaryContainer = androidx.compose.ui.graphics.Color(0xFFD9E5FA),
        )
    }
    val st = (context.applicationContext as PocketCodeApp).controller.settings
    val d = androidx.compose.ui.platform.LocalDensity.current
    val dens = androidx.compose.ui.unit.Density(
        d.density * (if (st.compactUi) 0.9f else 1f),
        d.fontScale * st.fontScale,
    )
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides dens) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}

@androidx.compose.runtime.Composable
private fun TabBar(app: PocketCodeApp) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        app.tabs.forEachIndexed { i, t ->
            androidx.compose.material3.FilterChip(
                selected = i == app.activeTab,
                onClick = { app.activeTab = i },
                label = { Text((if (t.running) "● " else "") + t.tabTitle + " · " + t.profileLabel.take(14), maxLines = 1) },
                trailingIcon = if (i > 0) ({
                    androidx.compose.material3.Text("×", modifier = Modifier.clickable { app.closeTab(i) }.padding(horizontal = 4.dp))
                }) else null,
            )
        }
        androidx.compose.material3.TextButton(onClick = { app.addTab() }) { Text("+ чат") }
    }
}
