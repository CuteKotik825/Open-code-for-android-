package com.pocketcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
import com.pocketcode.ui.FileActions
import com.pocketcode.ui.FilesScreen
import com.pocketcode.ui.ProjectsScreen
import com.pocketcode.ui.SessionsScreen
import com.pocketcode.ui.SettingsScreen

private enum class Screen { CHAT, SETTINGS, SESSIONS, PROJECTS, FILES, DEVICES, PLUGINS }

class MainActivity : ComponentActivity() {
    private val controller: ChatController
        get() = (application as PocketCodeApp).controller

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

    private val exportZip =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) controller.exportZip(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        val vm = controller
        val actions = FileActions(
            pickFiles = { pickFiles.launch(arrayOf("*/*")) },
            pickFolderToImport = { pickFolderToImport.launch(null) },
            exportZip = { project -> exportZip.launch("$project.zip") },
            pickFolderAsProject = { pickFolderAsProject.launch(null) },
            requestStorageAccess = { requestStorageAccess() },
        )
        setContent {
            AppTheme {
                var screen by remember { mutableStateOf(Screen.CHAT) }
                when (screen) {
                    Screen.CHAT -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenSessions = { screen = Screen.SESSIONS },
                        onOpenProjects = { screen = Screen.PROJECTS },
                        onOpenFiles = { screen = Screen.FILES },
                        onPickFolder = {
                            pickFolderFresh = true
                            pickFolderAsProject.launch(null)
                        },
                    )
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.CHAT }
                        SettingsScreen(
                            vm = vm,
                            initial = vm.settings,
                            onSave = { s, p, a -> vm.saveSettings(s, p, a) },
                            onResetContext = { vm.resetContext() },
                            onBack = { screen = Screen.CHAT },
                        )
                    }
                    Screen.PROJECTS -> {
                        BackHandler { screen = Screen.CHAT }
                        ProjectsScreen(vm, actions, onOpenDevices = { screen = Screen.DEVICES }, onOpenPlugins = { screen = Screen.PLUGINS }, onBack = { screen = Screen.CHAT })
                    }
                    Screen.FILES -> FilesScreen(
                        vm.projectRoot,
                        vm.currentProject,
                        onBack = { screen = Screen.CHAT },
                        onStartHere = { dir ->
                            vm.openFolderAsProject(dir)
                            screen = Screen.CHAT
                        },
                    )
                    Screen.DEVICES -> {
                        BackHandler { screen = Screen.PROJECTS }
                        DevicesScreen(vm, onBack = { screen = Screen.PROJECTS })
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

    override fun onStart() {
        super.onStart()
        controller.appVisible = true
    }

    override fun onResume() {
        super.onResume()
        // Пользователь мог выдать доступ к файлам в системных настройках
        controller.refreshStoragePermission()
    }

    override fun onStop() {
        controller.appVisible = false
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
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
