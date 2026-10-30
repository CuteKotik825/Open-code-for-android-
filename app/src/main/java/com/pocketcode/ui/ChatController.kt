package com.pocketcode.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.pocketcode.DEFAULT_PROJECT
import com.pocketcode.Provider
import com.pocketcode.Settings
import com.pocketcode.SettingsStore
import com.pocketcode.agent.Agent
import com.pocketcode.llm.Block
import com.pocketcode.Secrets
import kotlinx.serialization.encodeToString
import com.pocketcode.CatalogItem
import com.pocketcode.PROJECT_TEMPLATES
import com.pocketcode.tools.NotesTool
import com.pocketcode.tools.TestTool
import com.pocketcode.tools.Shell
import com.pocketcode.AppLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
import com.pocketcode.SyncManager
import com.pocketcode.DelayedScheduler
import com.pocketcode.SyncScheduler
import com.pocketcode.RemoteRow
import com.pocketcode.RemoteState
import com.pocketcode.SyncResult
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import com.pocketcode.tools.GitHubTool
import com.pocketcode.tools.SpawnTool
import com.pocketcode.tools.WorkerResultTool
import com.pocketcode.tools.Workers
import com.pocketcode.tools.BgProcesses
import com.pocketcode.tools.BgTool
import com.pocketcode.tools.CodeSearchTool
import com.pocketcode.tools.DiagnosticsTool
import com.pocketcode.tools.PreviewTool
import com.pocketcode.agent.AgentHooks
import com.pocketcode.agent.PermissionRules
import com.pocketcode.tools.PresentPlanTool
import com.pocketcode.tools.TodoTool
import com.pocketcode.tools.looksBinary
import com.pocketcode.tools.str
import kotlinx.serialization.json.JsonObject
import com.pocketcode.tools.McpClient
import com.pocketcode.tools.SubagentTool
import com.pocketcode.tools.Tool
import com.pocketcode.tools.McpServer
import com.pocketcode.tools.McpTool
import com.pocketcode.agent.Command
import com.pocketcode.agent.Extensions
import com.pocketcode.agent.Skill
import com.pocketcode.tools.SkillTool
import com.pocketcode.tools.WebFetchTool
import com.pocketcode.tools.WebSearchTool
import com.pocketcode.agent.AgentEvent
import com.pocketcode.agent.systemPrompt
import com.pocketcode.llm.AnthropicClient
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message
import com.pocketcode.llm.OpenAiClient
import com.pocketcode.Profile
import com.pocketcode.UpdateInfo
import com.pocketcode.Updater
import com.pocketcode.agent.ApprovalRules
import com.pocketcode.agent.Compactor
import com.pocketcode.linux.LinuxEnv
import com.pocketcode.linux.LinuxShell
import com.pocketcode.linux.SwitchableShell
import com.pocketcode.python.PythonRunner
import com.pocketcode.service.AgentService
import com.pocketcode.service.Notifier
import com.pocketcode.session.SessionData
import com.pocketcode.session.SessionMeta
import com.pocketcode.session.SessionStore
import com.pocketcode.session.SnapshotMeta
import com.pocketcode.session.SnapshotStore
import com.pocketcode.session.UiItem
import com.pocketcode.tools.AndroidShell
import com.pocketcode.tools.BashTool
import com.pocketcode.tools.EditFileTool
import com.pocketcode.tools.GitOps
import com.pocketcode.tools.ADDRESS_RE
import com.pocketcode.tools.AdbTool
import com.pocketcode.tools.Device
import com.pocketcode.tools.GitTool
import com.pocketcode.tools.RootShell
import com.pocketcode.tools.RootTool
import com.pocketcode.tools.wrapAdb
import com.pocketcode.tools.LinuxGitTool
import com.pocketcode.tools.GrepTool
import com.pocketcode.tools.ListDirTool
import com.pocketcode.tools.PythonTool
import com.pocketcode.tools.ReadFileTool
import com.pocketcode.tools.Workspace
import com.pocketcode.tools.WriteFileTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApprovalRequest(
    val tool: String,
    val description: String,
    val result: CompletableDeferred<Boolean>,
)

/**
 * Вся логика чата живёт здесь, на уровне приложения (а не экрана): агент продолжает работу,
 * когда Activity закрыта, пока процесс держит AgentService.
 */
class ChatController(
    private val app: Application,
    /** 0 — основной чат; остальные — дополнительные вкладки со своим профилем API. */
    val tabNo: Int = 0,
    private val startProfileId: String? = null,
    private val startProject: String? = null,
) {
    val isTab: Boolean get() = tabNo > 0

    private val settingsStore = SettingsStore(app)
    private val sessions = SessionStore(File(app.filesDir, "sessions"))
    private val snapshots = SnapshotStore(File(app.filesDir, "snapshots"))
    private val homeDir = File(app.filesDir, "home")
    val extensions = Extensions({ workspace.root }, File(app.filesDir, "global-ext").apply { mkdirs() }, File(app.filesDir, "plugins").apply { mkdirs() })
    var skillList by mutableStateOf<List<Skill>>(emptyList())
        private set
    var commandList by mutableStateOf<List<Command>>(emptyList())
        private set
    var pluginMessage by mutableStateOf<String?>(null)
    var pluginBusy by mutableStateOf(false)
        private set

    fun refreshExtensions() {
        scope.launch(Dispatchers.IO) {
            val sk = runCatching { extensions.skills() }.getOrDefault(emptyList())
            val cm = runCatching { extensions.commands() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { skillList = sk; commandList = cm }
        }
    }

    fun installPlugin(rawUrl: String) {
        if (pluginBusy) return
        var url = rawUrl.trim()
        if (url.isEmpty()) return
        if (!url.contains("://")) url = "https://" + if (url.startsWith("github.com/")) url else "github.com/$url"
        val name = sanitize(url.trimEnd('/').substringAfterLast('/').removeSuffix(".git"))
        val target = File(extensions.pluginsDir, name)
        if (name.isEmpty() || target.exists()) { pluginMessage = "Плагин «$name» уже установлен или адрес неверен."; return }
        pluginBusy = true
        pluginMessage = "Скачиваю $url…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { GitOps.clone(url, target, settings.githubToken, homeDir) } }
            pluginBusy = false
            val e = r.exceptionOrNull()
            if (e != null) { target.deleteRecursively(); pluginMessage = "Не удалось скачать: ${e.message ?: e}"; return@launch }
            File(target, ".git").deleteRecursively()
            agent = null
            refreshExtensions()
            val sk = withContext(Dispatchers.IO) { extensions.skills().count { it.source == "плагин $name" } }
            val cm = withContext(Dispatchers.IO) { extensions.commands().count { it.source == "плагин $name" } }
            pluginMessage = if (sk + cm == 0) "Скачано, но навыков (skills/*/SKILL.md) и команд (commands/*.md) не найдено."
            else "Установлено: навыков $sk, команд $cm."
        }
    }

    fun removePlugin(name: String) {
        File(extensions.pluginsDir, name).deleteRecursively()
        agent = null
        refreshExtensions()
    }

    fun createSample(kind: String, name: String, global: Boolean) {
        val base = if (global) extensions.globalDir else File(workspace.root, ".pocketcode")
        val n = sanitize(name).ifEmpty { return }
        if (kind == "skill") {
            val f = File(base, "skills/$n/SKILL.md")
            if (!f.exists()) { f.parentFile.mkdirs(); f.writeText("---\nname: $n\ndescription: Когда применять этот навык (одно предложение)\n---\n\nПодробные инструкции для агента.\n") }
        } else {
            val f = File(base, "commands/$n.md")
            if (!f.exists()) { f.parentFile.mkdirs(); f.writeText("---\ndescription: Что делает команда /$n\n---\n\nОпиши задачу для агента. \$ARGUMENTS — то, что ты напишешь после команды.\n") }
        }
        agent = null
        refreshExtensions()
    }
    private val privateDir = File(app.filesDir, "workspace").apply { mkdirs() }
    private val sharedDir = File(Environment.getExternalStorageDirectory(), "PocketCode")
    private val linuxEnv = LinuxEnv(app).also { it.noSeccomp = settingsStore.loadLinuxNoSeccomp() }
    private val androidShell = AndroidShell(homeDir)
    private val python = PythonRunner(app)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> AppLog.log("ERROR", "Необработанная ошибка в фоне: " + e.javaClass.simpleName + ": " + (e.message ?: "")) },
    )

    var settings by mutableStateOf(settingsStore.load())
        private set
    val items = mutableStateListOf<UiItem>()
    var running by mutableStateOf(false)
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var sessionList by mutableStateOf<List<SessionMeta>>(emptyList())
        private set
    var currentId by mutableStateOf<String?>(null)
        private set

    var currentProject by mutableStateOf(startProject ?: settingsStore.loadProject())
        private set
    var projects by mutableStateOf<List<String>>(emptyList())
        private set

    /** Сообщение экрана проектов: ход клонирования или ошибка. */
    var projectMessage by mutableStateOf<String?>(null)
        private set
    var projectBusy by mutableStateOf(false)
        private set

    /** true — проекты лежат в общей папке PocketCode (видна в файловых менеджерах). */
    var storageShared by mutableStateOf(settingsStore.loadSharedStorage())
        private set

    /** Выдан ли доступ ко всем файлам. Обновляется при возврате в приложение. */
    var storageGranted by mutableStateOf(hasStorageAccess())
        private set

    /** Папки телефона, подключённые как проекты (имя -> путь). Удаление проекта их не стирает. */
    private var externalProjects: Map<String, String> = settingsStore.loadExternalProjects()

    private val projectsDir: File get() = if (storageShared) sharedDir else privateDir

    private fun projectDir(name: String): File = externalProjects[name]?.let { File(it) } ?: File(projectsDir, name)

    private val workspace = Workspace(projectDir(currentProject))

    private val linuxShell = LinuxShell(linuxEnv, workspace)
    private val shell = SwitchableShell(androidShell) { if (linuxEnabled && linuxEnv.isInstalled()) linuxShell else null }

    // ---------- Linux (proot) ----------
    var linuxEnabled by mutableStateOf(settingsStore.loadLinuxEnabled())
        private set
    var linuxInstalled by mutableStateOf(linuxEnv.isInstalled())
        private set
    val linuxSupported: Boolean get() = linuxEnv.isSupported()
    private val linuxActive: Boolean get() = linuxEnabled && linuxEnv.isInstalled()
    var linuxBusy by mutableStateOf(false)
        private set
    var linuxStatus by mutableStateOf<String?>(null)
        private set

    fun chooseLinuxShell(v: Boolean) {
        if (running) {
            linuxStatus = "Дождись окончания работы агента или нажми «Стоп»."
            return
        }
        linuxEnabled = v
        settingsStore.saveLinuxEnabled(v)
        agent = null // инструмент bash и системный промпт пересоздадутся
    }

    fun installLinux() {
        if (running || linuxBusy) return
        linuxBusy = true
        linuxStatus = "Начинаю установку…"
        scope.launch {
            try {
                val r = linuxEnv.install { msg -> scope.launch { linuxStatus = msg } }
                settingsStore.saveLinuxNoSeccomp(linuxEnv.noSeccomp)
                linuxInstalled = true
                runCatching { linuxEnv.configureGit(settings.gitName, settings.gitEmail, settings.githubToken) }
                agent = null
                linuxStatus = r + ". Теперь bash работает в Linux."
            } catch (e: Exception) {
                linuxInstalled = false
                linuxStatus = "Ошибка установки: ${e.message}"
            } finally {
                linuxBusy = false
            }
        }
    }

    /** Ставит python, git, node и сборочные инструменты через apk. */
    fun installLinuxTools() {
        if (running || linuxBusy || !linuxInstalled) return
        linuxBusy = true
        linuxStatus = "Устанавливаю python, git, nodejs… Это может занять несколько минут."
        scope.launch {
            try {
                val out = linuxShell.run(
                    "apk add --no-cache bash git python3 py3-pip nodejs npm curl 2>&1 | tail -n 15",
                    linuxEnv.rootfs, 900,
                )
                linuxStatus = "Результат:\n$out"
            } catch (e: Exception) {
                linuxStatus = "Ошибка: ${e.message}"
            } finally {
                linuxBusy = false
            }
        }
    }

    fun removeLinux() {
        if (running || linuxBusy) return
        linuxEnv.uninstall()
        linuxInstalled = false
        agent = null
        linuxStatus = "Linux удалён."
    }

    /** Выставляется MainActivity: нужно ли слать уведомления, когда экран не виден. */
    @Volatile
    var appVisible = false

    val workspacePath: String get() = workspace.root.absolutePath

    /** Корень текущего проекта для экрана файлов. */
    val projectRoot: File get() = workspace.root

    private var history = mutableListOf<Message>()
    private var agent: Agent? = null
    private var job: Job? = null
    private var runId = 0
    private var streamingIndex: Int? = null

    init {
        if (!isTab) scope.launch { runCatching { cleanupOld(cleanupDays) } }
        refreshProjects()
        sessionList = sessions.list()
        if (!settings.freshOnStart && !isTab) {
            sessionList.firstOrNull { projectOf(it) == currentProject }?.let { loadInto(it.id) }
        }
    }

    private fun projectOf(meta: SessionMeta) = meta.project.ifBlank { DEFAULT_PROJECT }

    // ---------- настройки ----------

    var profiles by mutableStateOf(
        settingsStore.loadProfiles().map {
            // Старые профили без своих размеров получают прежние общие значения
            if (it.contextTokens == 0) it.copy(contextTokens = settings.contextTokens, maxOutputTokens = settings.maxOutputTokens) else it
        },
    )
        private set
    var activeProfileId by mutableStateOf(startProfileId ?: settingsStore.loadActiveId())
        private set

    init {
        Secrets.known = profiles.map { it.apiKey } + settings.githubToken
        if (isTab) profiles.firstOrNull { it.id == activeProfileId }?.let { settings = profileSettings(it) }
    }

    /** Подхватывает изменённые общие настройки (для дополнительных вкладок). */
    fun syncSettings() {
        val fresh = settingsStore.load()
        profiles = settingsStore.loadProfiles()
        if (profiles.none { it.id == activeProfileId }) activeProfileId = profiles.first().id
        settings = fresh
        settings = profileSettings(profiles.first { it.id == activeProfileId })
        if (!running) agent = null
    }

    /** Подпись вкладки. */
    val tabTitle: String get() = "Чат ${tabNo + 1}"
    val profileLabel: String get() = profiles.firstOrNull { it.id == activeProfileId }?.label ?: ""

    /** Реальный предел контекста: в эко-режиме не больше 64 000. */
    val effectiveContext: Int get() = if (settings.ecoMode) minOf(settings.contextTokens, 64_000) else settings.contextTokens

    fun profileSettings(p: Profile): Settings = settings.copy(
        provider = p.provider, baseUrl = p.baseUrl, model = p.model, apiKey = p.apiKey,
        contextTokens = if (p.contextTokens > 0) p.contextTokens else settings.contextTokens,
        maxOutputTokens = p.maxOutputTokens,
    )

    fun saveSettings(s: Settings, newProfiles: List<Profile>, activeId: String) {
        Secrets.known = newProfiles.map { it.apiKey } + s.githubToken
        profiles = newProfiles
        activeProfileId = activeId
        settingsStore.saveProfiles(newProfiles, activeId)
        settings = s
        settingsStore.save(s)
        runCatching { linuxEnv.configureGit(s.gitName, s.gitEmail, s.githubToken) }
        agent = null // пересоберём с новыми настройками, история лежит в controller
        (app as? com.pocketcode.PocketCodeApp)?.tabs?.forEach { if (it !== this) it.syncSettings() }
    }

    /** Быстрое переключение ключа/модели из чата. */
    fun switchProfile(id: String) {
        if (running || id == activeProfileId) return
        val p = profiles.firstOrNull { it.id == id } ?: return
        settings = profileSettings(p)
        if (!isTab) {
            settingsStore.save(settings)
            settingsStore.saveProfiles(profiles, id)
        }
        activeProfileId = id
        agent = null
    }

    // ---------- проекты ----------

    private fun refreshProjects() {
        val dirs = (projectsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { it.name } ?: emptyList()) + externalProjects.keys
        val all = dirs.distinct()
        projects = (if (currentProject in all) all else all + currentProject).sortedBy { it.lowercase() }
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(60)

    /** Переключение проекта: текущий диалог закрывается, открывается последняя сессия нового проекта. */
    fun selectProject(name: String) {
        if (running) {
            projectMessage = "Дождись окончания работы агента или нажми «Стоп»."
            return
        }
        switchTo(name)
        sessionList.firstOrNull { projectOf(it) == name }?.let { loadInto(it.id) }
    }

    private fun switchTo(name: String) {
        cancelRun()
        currentProject = name
        if (!isTab) settingsStore.saveProject(name)
        workspace.root = projectDir(name)
        items.clear()
        history = mutableListOf()
        agent = null
        currentId = null
        streamingIndex = null
        loadOcProject()
        refreshProjects()
    }

    fun createProject(rawName: String) {
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            projectMessage = "Имя проекта: латиница, цифры, точка, дефис, подчёркивание."
            return
        }
        if (File(projectsDir, name).exists() || name in externalProjects) {
            projectMessage = "Проект «$name» уже есть."
            return
        }
        File(projectsDir, name).mkdirs()
        projectMessage = null
        selectProject(name)
    }

    fun deleteProject(name: String) {
        if (running) {
            projectMessage = "Дождись окончания работы агента или нажми «Стоп»."
            return
        }
        val external = name in externalProjects
        scope.launch {
            if (external) {
                // Папка пользователя: убираем из списка, файлы не трогаем
                externalProjects = externalProjects - name
                settingsStore.saveExternalProjects(externalProjects)
            } else {
                withContext(Dispatchers.IO) { File(projectsDir, name).deleteRecursively() }
            }
            // сессии удалённого проекта больше не нужны
            for (meta in sessionList.filter { projectOf(it) == name }) sessions.delete(meta.id)
            sessionList = sessions.list()
            if (name == currentProject) {
                val next = (projectsDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList())
                    .plus(externalProjects.keys).minOrNull() ?: DEFAULT_PROJECT
                switchTo(next)
                sessionList.firstOrNull { projectOf(it) == next }?.let { loadInto(it.id) }
            }
            refreshProjects()
            projectMessage = if (external) "Проект «$name» убран из списка, файлы остались на месте."
            else "Проект «$name» удалён."
        }
    }

    /** Клонирование репозитория в новый проект. Принимает URL или «владелец/репозиторий». */
    fun cloneProject(rawUrl: String) {
        if (projectBusy) return
        var url = rawUrl.trim()
        if (url.isEmpty()) return
        if (!url.contains("://")) {
            url = "https://" + if (url.startsWith("github.com/")) url else "github.com/$url"
        }
        val name = sanitize(url.trimEnd('/').substringAfterLast('/').removeSuffix(".git"))
        if (name.isEmpty()) {
            projectMessage = "Не удалось определить имя проекта из адреса."
            return
        }
        val target = File(projectsDir, name)
        if (target.exists() || name in externalProjects) {
            projectMessage = "Проект «$name» уже есть."
            return
        }
        projectBusy = true
        projectMessage = "Клонирую $url…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GitOps.clone(url, target, settings.githubToken, homeDir) }
            }
            projectBusy = false
            val error = result.exceptionOrNull()
            if (error == null) {
                projectMessage = null
                selectProject(name)
            } else {
                target.deleteRecursively()
                projectMessage = "Не удалось клонировать: ${error.message ?: error}"
            }
        }
    }

    fun isExternal(name: String) = name in externalProjects

    fun projectPath(name: String): String = projectDir(name).absolutePath

    // ---------- файлы телефона ----------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Вызывается из MainActivity.onResume: пользователь мог выдать доступ в настройках. */
    fun refreshStoragePermission() {
        storageGranted = hasStorageAccess()
    }

    /** Переключение между приватной папкой приложения и общей папкой PocketCode. */
    fun selectStorage(shared: Boolean) {
        if (running) {
            projectMessage = "Дождись окончания работы агента или нажми «Стоп»."
            return
        }
        if (shared && !hasStorageAccess()) {
            projectMessage = "Сначала выдай приложению доступ к файлам."
            return
        }
        storageShared = shared
        settingsStore.saveSharedStorage(shared)
        projectMessage = null
        sharedDir.takeIf { shared }?.mkdirs()
        val name = currentProject
        switchTo(name)
        sessionList.firstOrNull { projectOf(it) == name }?.let { loadInto(it.id) }
    }

    private fun displayName(uri: Uri): String {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun safeName(name: String) = name.replace('/', '_').replace('\\', '_').ifBlank { "file" }

    /** Свободное имя: если файл уже есть, добавляем « (1)», чтобы ничего не затереть. */
    private fun uniqueTarget(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!f.exists()) return f
            i++
        }
    }

    /** Копирует выбранные файлы в корень текущего проекта. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || projectBusy) return
        projectBusy = true
        projectMessage = "Копирую файлы…"
        scope.launch {
            val root = workspace.root
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var n = 0
                    for (uri in uris) {
                        val target = uniqueTarget(root, safeName(displayName(uri)))
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                            n++
                        }
                    }
                    n
                }
            }
            projectBusy = false
            projectMessage = result.fold(
                { "Добавлено файлов: $it в проект «$currentProject»." },
                { "Не удалось скопировать: ${it.message ?: it}" },
            )
        }
    }

    /** Копирует выбранную папку целиком внутрь текущего проекта. */
    fun importFolder(treeUri: Uri) {
        if (projectBusy) return
        val tree = DocumentFile.fromTreeUri(app, treeUri)
        if (tree == null) {
            projectMessage = "Не удалось открыть выбранную папку."
            return
        }
        projectBusy = true
        projectMessage = "Копирую папку…"
        scope.launch {
            val root = workspace.root
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val counter = IntArray(1)
                    val target = uniqueTarget(root, safeName(tree.name ?: "folder"))
                    copyTree(tree, target, counter)
                    counter[0]
                }
            }
            projectBusy = false
            projectMessage = result.fold(
                { "Скопировано файлов: $it в проект «$currentProject»." },
                { "Не удалось скопировать: ${it.message ?: it}" },
            )
        }
    }

    private fun copyTree(src: DocumentFile, dst: File, counter: IntArray) {
        dst.mkdirs()
        for (child in src.listFiles()) {
            val name = safeName(child.name ?: continue)
            if (child.isDirectory) {
                copyTree(child, File(dst, name), counter)
            } else {
                app.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(dst, name).outputStream().use { input.copyTo(it) }
                    counter[0]++
                }
            }
        }
    }

    /** Упаковывает текущий проект в zip по выбранному адресу. */
    fun exportZip(target: Uri) {
        if (projectBusy) return
        projectBusy = true
        projectMessage = "Упаковываю проект…"
        val root = workspace.root
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var n = 0
                    val out = app.contentResolver.openOutputStream(target)
                        ?: error("не удалось открыть файл для записи")
                    ZipOutputStream(out.buffered()).use { zip ->
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(f.relativeTo(root).path.replace(File.separatorChar, '/')))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            n++
                        }
                    }
                    n
                }
            }
            projectBusy = false
            projectMessage = result.fold(
                { "Готово: в архив добавлено файлов: $it." },
                { "Не удалось создать архив: ${it.message ?: it}" },
            )
        }
    }

    /** Подключает существующую папку телефона как проект (нужен доступ ко всем файлам). */
    fun addExternalFolder(treeUri: Uri, fresh: Boolean = false) {
        if (!hasStorageAccess()) {
            projectMessage = "Для работы с папками телефона выдай доступ к файлам."
            return
        }
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            null
        }
        if (docId == null) {
            projectMessage = "Не удалось определить путь папки."
            return
        }
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
        val dir = File(base, relative)
        if (!dir.isDirectory) {
            projectMessage = "Папка недоступна по пути ${dir.absolutePath}."
            return
        }
        val name = registerFolder(dir)
        projectMessage = null
        selectProject(name)
        if (fresh) newChat()
    }

    /** Добавляет папку как проект (если уже добавлена, возвращает её имя). */
    private fun registerFolder(dir: File): String {
        externalProjects.entries.firstOrNull { File(it.value).canonicalPath == dir.canonicalPath }?.let { return it.key }
        var name = sanitize(dir.name).ifEmpty { "folder" }
        val taken = projects.toSet()
        var i = 2
        val original = name
        while (name in taken) name = "$original-${i++}"
        externalProjects = externalProjects + (name to dir.absolutePath)
        settingsStore.saveExternalProjects(externalProjects)
        return name
    }

    /** «Начать работу в этой папке»: подпапка проекта становится рабочей папкой нового чата. */
    fun openFolderAsProject(dir: File) {
        val name = registerFolder(dir)
        projectMessage = null
        switchTo(name)
        newChat()
    }

    /** Новый чат в выбранном проекте. */
    fun newChatIn(name: String) {
        if (name != currentProject) switchTo(name)
        newChat()
    }

    // ---------- сессии ----------

    /** Текст, который уходит первым в следующий запрос новой беседы OpenCode (перенос контекста при сбросе). */
    private var ocCarry: String? = null
    private var ocLastReasoning = ""

    /**
     * Сброс: новый чат PocketCode и новая беседа OpenCode (новый id сессии).
     * keepLast=true оставляет последнее сообщение, рассуждение и ответ: они видны в чате и уходят в первом запросе новой беседы.
     */
    fun resetSessionAndChat(keepLast: Boolean = true) {
        val lastUser = (items.lastOrNull { it is UiItem.User } as? UiItem.User)?.text
        val lastAnswer = (items.lastOrNull { it is UiItem.Assistant && !it.text.startsWith("⏳") } as? UiItem.Assistant)?.text
        val reasoning = ocLastReasoning
        newChat()
        ocSessionId = null
        ocSessionTitle = ""
        setOcContinue(false)
        ocCtxTokens = 0
        ocCarry = null
        if (keepLast && (lastUser != null || lastAnswer != null)) {
            val sb = StringBuilder("[Контекст из предыдущей беседы: сессия была сброшена, вот самое важное]\n")
            if (lastUser != null) sb.append("Моё последнее сообщение:\n").append(lastUser.take(3000)).append("\n\n")
            if (reasoning.isNotBlank()) sb.append("Твоё последнее рассуждение:\n").append(reasoning.take(2000)).append("\n\n")
            if (lastAnswer != null) sb.append("Твой последний ответ:\n").append(lastAnswer.take(3000)).append("\n")
            ocCarry = sb.toString()
            if (lastUser != null) items += UiItem.User(lastUser.take(3000), 0)
            if (lastAnswer != null) items += UiItem.Assistant(lastAnswer.take(3000))
            items += UiItem.Notice("— Сессия сброшена: новый id. Последнее сообщение и ответ перенесутся в следующий запрос —")
        } else items += UiItem.Notice("— Сессия и чат сброшены полностью: новый id, без истории —")
    }

    /** Сброс сессии, при котором всё с выбранного сообщения и ниже остаётся в чате и переносится в новую беседу. */
    fun resetSessionKeepFrom(index: Int) {
        if (running || index !in items.indices) return
        val reasoning = ocLastReasoning
        val kept = items.drop(index).filter { !(it is UiItem.Assistant && it.text.startsWith("⏳")) }
        val sb = StringBuilder("[Контекст из предыдущей беседы: сессия была сброшена, вот выбранная часть переписки]\n")
        for (it in kept) when (it) {
            is UiItem.User -> sb.append("Я: ").append(it.text.take(3000)).append("\n\n")
            is UiItem.Assistant -> sb.append("Ты: ").append(it.text.take(3000)).append("\n\n")
            is UiItem.Tool -> sb.append("(инструмент ").append(it.name).append(": ").append(it.description.take(200).replace('\n', ' ')).append(")\n")
            else -> {}
        }
        if (reasoning.isNotBlank()) sb.append("Твоё последнее рассуждение:\n").append(reasoning.take(2000)).append("\n")
        val carry = sb.toString().take(14_000)
        newChat()
        ocSessionId = null
        ocSessionTitle = ""
        setOcContinue(false)
        ocCtxTokens = 0
        items.addAll(kept.filter { it is UiItem.User || it is UiItem.Assistant || it is UiItem.Tool })
        items += UiItem.Notice("— Сессия сброшена: новый id. Сообщения выше перенесутся в следующий запрос —")
        ocCarry = carry
    }

    fun newChat() {
        ocContinue = false
        cancelRun()
        todos = emptyList()
        changes.clear()
        notes.clear()
        items.clear()
        history = mutableListOf()
        agent = null
        currentId = null
        contextUsed = 0
    }

    /**
     * Сбрасывает контекст: модель забывает всю переписку (в запросы к API она больше не попадает),
     * а на экране история остаётся. Файлы проекта не затрагиваются.
     */
    fun resetContext() {
        if (running) return
        history = mutableListOf()
        agent = null
        if (currentId == null) currentId = UUID.randomUUID().toString()
        items += UiItem.Notice("— Контекст сброшен: модель не помнит сообщения выше —")
        persist()
    }

    // ---------- ADB и root ----------

    private val rootShell = RootShell()
    var mcpServers by mutableStateOf(settingsStore.loadMcp())
        private set
    var mcpMessage by mutableStateOf<String?>(null)
    var mcpBusy by mutableStateOf(false)
        private set

    fun addMcp(name: String, url: String, headers: String) {
        val n = name.trim().ifEmpty { return }
        val u = url.trim()
        if (!u.startsWith("http")) { mcpMessage = "Адрес должен начинаться с http:// или https://"; return }
        if (mcpServers.any { it.name.equals(n, true) }) { mcpMessage = "Сервер с таким именем уже есть."; return }
        val srv = McpServer(UUID.randomUUID().toString(), n, u, headers.trim())
        mcpServers = mcpServers + srv
        settingsStore.saveMcp(mcpServers)
        refreshMcp(srv.id)
    }

    fun removeMcp(id: String) {
        mcpServers = mcpServers.filter { it.id != id }
        settingsStore.saveMcp(mcpServers)
        agent = null
    }

    fun toggleMcp(id: String) {
        mcpServers = mcpServers.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        settingsStore.saveMcp(mcpServers)
        agent = null
    }

    /** Подключается к серверу и запоминает список его инструментов. */
    fun refreshMcp(id: String) {
        val srv = mcpServers.firstOrNull { it.id == id } ?: return
        mcpBusy = true
        mcpMessage = "Подключаюсь к ${srv.name}…"
        scope.launch {
            val r = runCatching { McpClient(srv).listTools() }
            mcpBusy = false
            r.onSuccess { tools ->
                mcpServers = mcpServers.map { if (it.id == id) it.copy(tools = tools) else it }
                settingsStore.saveMcp(mcpServers)
                agent = null
                mcpMessage = "${srv.name}: инструментов ${tools.size}."
            }.onFailure { mcpMessage = "${srv.name}: ${it.message ?: it}" }
        }
    }

    var devices by mutableStateOf(settingsStore.loadDevices())
        private set
    var deviceStatus by mutableStateOf<String?>(null)
        private set
    var deviceBusy by mutableStateOf(false)
        private set
    var rootEnabled by mutableStateOf(settingsStore.loadRootEnabled())
        private set

    private fun deviceTask(start: String, block: suspend () -> String) {
        if (deviceBusy) return
        if (!linuxEnv.isInstalled()) {
            deviceStatus = "Сначала установи Linux (Проекты и файлы → Среда Linux)."
            return
        }
        deviceBusy = true
        deviceStatus = start
        scope.launch {
            try {
                deviceStatus = block()
            } catch (e: Exception) {
                deviceStatus = "Ошибка: ${e.message}"
            } finally {
                deviceBusy = false
            }
        }
    }

    private suspend fun linuxRun(cmd: String, timeout: Long) = linuxShell.run(cmd, linuxEnv.rootfs, timeout)

    fun installAdb() = deviceTask("Устанавливаю ADB…") {
        "Результат:\n" + linuxRun("apk add --no-cache android-tools 2>&1 | tail -n 8", 600)
    }

    fun adbPair(address: String, code: String) {
        val a = address.trim()
        val c = code.trim()
        if (!ADDRESS_RE.matches(a) || !Regex("^\\d{4,10}$").matches(c)) {
            deviceStatus = "Адрес сопряжения вида 192.168.1.5:37000 и числовой код."
            return
        }
        deviceTask("Сопрягаю…") { linuxRun(wrapAdb("adb pair $a $c 2>&1"), 60) }
    }

    fun addDevice(label: String, address: String) {
        val a = address.trim()
        if (!ADDRESS_RE.matches(a)) {
            deviceStatus = "Адрес вида 192.168.1.5:5555."
            return
        }
        val l = label.trim().ifEmpty { a }
        if (devices.any { it.address == a || it.label.equals(l, ignoreCase = true) }) {
            deviceStatus = "Такое устройство или подпись уже есть."
            return
        }
        devices = devices + Device(UUID.randomUUID().toString(), l, a)
        settingsStore.saveDevices(devices)
        agent = null
        deviceStatus = "Добавлено: $l"
    }

    fun removeDevice(id: String) {
        devices = devices.filter { it.id != id }
        settingsStore.saveDevices(devices)
        agent = null
    }

    fun testDevice(d: Device) = deviceTask("Подключаюсь к ${d.label}…") {
        linuxRun(wrapAdb("adb connect ${d.address} 2>&1; adb -s ${d.address} shell getprop ro.product.model 2>&1"), 60)
    }

    fun chooseRoot(v: Boolean) {
        if (running) return
        rootEnabled = v
        settingsStore.saveRootEnabled(v)
        agent = null
    }

    fun checkRoot() {
        if (deviceBusy) return
        deviceBusy = true
        deviceStatus = "Проверяю root… (менеджер root может показать запрос)"
        scope.launch {
            try {
                val out = rootShell.check()
                deviceStatus = if (out.contains("uid=0")) "Root работает: ${out.lineSequence().firstOrNull()}"
                else "Root недоступен или отклонён:\n$out"
            } finally {
                deviceBusy = false
            }
        }
    }

    // ---------- обновления ----------

    private val updater = Updater(app)
    val currentVersion: String get() = "${updater.currentName()} (сборка ${updater.currentBuild()})"
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var updateInfo by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateBusy by mutableStateOf(false)
        private set

    fun checkUpdate() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Проверяю…"
        scope.launch {
            try {
                val info = updater.latest(settings.githubToken)
                if (info != null && info.build > updater.currentBuild()) {
                    updateInfo = info
                    updateStatus = "Доступна сборка ${info.build}: ${info.notes.take(150)}"
                } else {
                    updateInfo = null
                    updateStatus = "У тебя последняя версия."
                }
            } catch (e: Exception) {
                updateInfo = null
                updateStatus = e.message ?: "Не удалось проверить обновления"
            } finally {
                updateBusy = false
            }
        }
    }

    fun installUpdate() {
        val info = updateInfo ?: return
        if (updateBusy) return
        updateBusy = true
        scope.launch {
            try {
                val apk = updater.download(info, settings.githubToken) { msg -> scope.launch { updateStatus = msg } }
                updateStatus = updater.install(apk)
            } catch (e: Exception) {
                updateStatus = "Не удалось обновиться: ${e.message}"
            } finally {
                updateBusy = false
            }
        }
    }

    // ---------- уточнения и очередь ----------

    private val notes = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Задачи, которые начнутся одна за другой после текущей. */
    val queue = mutableStateListOf<String>().apply { if (!isTab) addAll(settingsStore.loadQueue()) }

    private fun saveQueue() { if (!isTab) settingsStore.saveQueue(queue.toList()) }

    var lastRunFailed by mutableStateOf(false)
        private set

    /** Сообщение во время работы агента: он прочитает его перед следующим шагом. */
    fun sendWhileRunning(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!running) {
            send(t)
            return
        }
        notes.add(t)
        items += UiItem.User(t)
        persist()
    }

    fun enqueue(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!running) send(t) else { queue += t; saveQueue() }
    }

    fun clearQueue() {
        queue.clear()
        saveQueue()
    }

    fun removeFromQueue(i: Int) {
        if (i in queue.indices) { queue.removeAt(i); saveQueue() }
    }

    fun moveInQueue(i: Int, delta: Int) {
        val j = i + delta
        if (i !in queue.indices || j !in queue.indices) return
        val t = queue.removeAt(i)
        queue.add(j, t)
        saveQueue()
    }

    fun editQueue(i: Int, text: String) {
        val t = text.trim()
        if (i !in queue.indices) return
        if (t.isEmpty()) queue.removeAt(i) else queue[i] = t
        saveQueue()
    }

    /** Запускает очередь вручную (после остановки, ошибки или перезапуска приложения). */
    fun runQueue() {
        if (running || queue.isEmpty()) return
        val next = queue.removeAt(0)
        saveQueue()
        send(next)
    }

    // ---------- откат ----------

    var snapshotList by mutableStateOf<List<SnapshotMeta>>(emptyList())
        private set

    fun refreshSnapshots() {
        scope.launch { snapshotList = snapshots.list(currentProject) }
    }

    fun restoreSnapshot(id: Long) {
        if (running || projectBusy) {
            projectMessage = "Дождись окончания работы агента или нажми «Стоп»."
            return
        }
        projectBusy = true
        scope.launch {
            try {
                val r = snapshots.restore(currentProject, workspace.root, id)
                projectMessage = "Проект возвращён к состоянию до задачи. $r"
                items += UiItem.Notice("— Проект откатан к снимку; агент мог не знать об этом, файлы изменились —")
                persist()
            } catch (e: Exception) {
                projectMessage = "Откат не удался: ${e.message}"
            } finally {
                projectBusy = false
                snapshotList = snapshots.list(currentProject)
            }
        }
    }

    fun openSession(id: String) {
        val meta = sessionList.firstOrNull { it.id == id } ?: return
        if (projectOf(meta) != currentProject) {
            if (running) return
            switchTo(projectOf(meta))
        } else {
            cancelRun()
        }
        loadInto(id)
    }

    fun togglePin(id: String) {
        val pinned = sessionList.firstOrNull { it.id == id }?.pinned ?: return
        scope.launch {
            sessions.setPinned(id, !pinned)
            sessionList = sessions.list()
        }
    }

    suspend fun searchSessions(query: String): Set<String> = sessions.search(query)

    /** Примерный размер контекста модели в токенах (для индикатора в чате). */
    var contextUsed by mutableIntStateOf(0)
        private set

    private fun updateContextUsage() {
        contextUsed = Compactor.estimateTokens(history)
    }

    fun deleteSession(id: String) {
        if (id == currentId) newChat()
        scope.launch {
            sessions.delete(id)
            sessionList = sessions.list()
        }
    }

    private fun loadInto(id: String) {
        val data = sessions.load(id) ?: return
        items.clear()
        // Недовыполненные вызовы после падения процесса помечаем как прерванные
        items.addAll(
            data.items.map {
                if (it is UiItem.Tool && it.output == null) it.copy(output = "Прервано", isError = true) else it
            },
        )
        history = data.history.toMutableList()
        agent = null
        currentId = data.id
        updateContextUsage()
        streamingIndex = null
    }

    private fun persist() {
        if (!isTab) settingsStore.rawPut("ocSess|" + currentProject, ocSessionId.orEmpty() + "\u001f" + ocSessionTitle)
        updateContextUsage()
        val id = currentId ?: return
        val title = (items.firstOrNull { it is UiItem.User } as? UiItem.User)?.text
            ?.replace('\n', ' ')?.take(60) ?: "Новый чат"
        val data = SessionData(
            id, title, System.currentTimeMillis(), items.toList(), history.toList(), currentProject,
        )
        scope.launch {
            sessions.save(data)
            sessionList = sessions.list()
        }
    }

    // ---------- запуск агента ----------

    // ---------- режим плана, задачи, журнал правок ----------

    var planMode by mutableStateOf(false)
    var todos by mutableStateOf<List<String>>(emptyList())
        private set

    class FileChange(val path: String, val before: String?, val after: String, val time: Long)

    val changes = mutableStateListOf<FileChange>()
    private val pendingBefore = HashMap<String, String?>()

    fun revertChange(i: Int) {
        val ch = changes.getOrNull(i) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val f = workspace.resolve(ch.path)
                if (ch.before == null) f.delete() else f.writeText(ch.before)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    changes.removeAt(i)
                    items += UiItem.Notice("Правка отменена: ${ch.path}")
                }
            }.onFailure {
                withContext(Dispatchers.Main) { items += UiItem.Error("Не удалось откатить ${ch.path}: ${it.message}") }
            }
        }
    }

    private fun globToRegex(glob: String): Regex =
        Regex(glob.trim().split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    private val agentHooks = object : AgentHooks {
        override fun planMode() = this@ChatController.planMode

        override fun decide(tool: String, input: JsonObject, description: String) =
            PermissionRules.decide(settings.permissionRules, tool, description)

        override suspend fun before(tool: Tool, input: JsonObject) {
            if (tool.name != "write_file" && tool.name != "edit_file") return
            val p = input.str("path") ?: return
            runCatching {
                val f = workspace.resolve(p)
                pendingBefore[p] = if (f.isFile && f.length() < 300_000 && !looksBinary(f)) f.readText() else null
            }
        }

        override suspend fun after(tool: Tool, input: JsonObject, output: String, isError: Boolean): String? {
            if (tool.name != "write_file" && tool.name != "edit_file") return null
            val p = input.str("path") ?: return null
            return runCatching {
                val f = workspace.resolve(p)
                val after = if (f.isFile && f.length() < 300_000 && !looksBinary(f)) f.readText() else return@runCatching null
                val before = pendingBefore.remove(p)
                withContext(Dispatchers.Main) {
                    changes += FileChange(p, before, after, System.currentTimeMillis())
                    while (changes.size > 200) changes.removeAt(0)
                }
                val rel = workspace.rel(f)
                val sb = StringBuilder()
                for (line in (settings.fileHooks + "\n" + runCatching { File(workspace.root, ".pocketcode/hooks.txt").readText() }.getOrDefault("")).lines()) {
                    val i = line.indexOf("=>")
                    if (i <= 0) continue
                    val glob = line.substring(0, i).trim()
                    val cmd = line.substring(i + 2).trim()
                    if (cmd.isEmpty()) continue
                    val re = globToRegex(glob)
                    if (!(re.matches(rel) || re.matches(f.name))) continue
                    val quoted = "'" + rel.replace("'", "'\\''") + "'"
                    val out = shell.run(cmd.replace("{file}", quoted), workspace.root, 120)
                    sb.append("\n\n[Хук ").append(glob).append(" => ").append(cmd).append("]\n").append(out.take(3000))
                }
                sb.toString().ifEmpty { null }
            }.getOrNull()
        }
    }

    var schedules by mutableStateOf(settingsStore.loadSchedules())
        private set

    fun addSchedule(prompt: String, intervalMin: Int) {
        val p = prompt.trim().ifEmpty { return }
        schedules = schedules + com.pocketcode.Schedule(UUID.randomUUID().toString(), p, currentProject, intervalMin)
        settingsStore.saveSchedules(schedules)
        com.pocketcode.Scheduler.apply(app, schedules)
    }

    fun toggleSchedule(id: String) {
        schedules = schedules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        settingsStore.saveSchedules(schedules)
        com.pocketcode.Scheduler.apply(app, schedules)
    }

    fun removeSchedule(id: String) {
        schedules = schedules.filter { it.id != id }
        settingsStore.saveSchedules(schedules)
        com.pocketcode.Scheduler.apply(app, schedules, listOf(id))
    }

    /** Запуск задачи расписания: свежий чат в её проекте, без подтверждений. false — занято или нечего запускать. */
    fun runScheduled(id: String, manual: Boolean = false): Boolean {
        val s = schedules.firstOrNull { it.id == id } ?: return false
        if (running || settings.apiKey.isBlank()) return false
        if (!projectDir(s.project).isDirectory) return false
        schedules = schedules.map { if (it.id == id) it.copy(lastRun = System.currentTimeMillis()) else it }
        settingsStore.saveSchedules(schedules)
        switchTo(s.project)
        unattended = true
        scope.launch {
            send("[Запуск по расписанию, пользователя рядом нет: подтверждений не будет, небезопасные действия отклоняются. Не пытайся их обойти.]\n\n" + s.prompt)
            delay(1000)
            while (running) delay(1000)
            unattended = false
        }
        return true
    }

    val bgProcs = BgProcesses()
    private val workers = Workers(scope)
    var previewUrl by mutableStateOf<String?>(null)
    var lastPreview by mutableStateOf<String?>(null)

    // ---------- самопроверка, отчёт, статистика, резервная копия ----------

    class CheckResult(val name: String, val ok: Boolean, val detail: String)

    var checks by mutableStateOf<List<CheckResult>>(emptyList())
        private set
    var checking by mutableStateOf(false)
        private set

    private fun makeLlm(s: Settings, model: String = s.model): LlmClient = when (s.provider) {
        Provider.ANTHROPIC -> AnthropicClient(s.baseUrl, s.apiKey, model, s.promptCaching, s.maxOutputTokens)
        Provider.OPENAI -> OpenAiClient(s.baseUrl, s.apiKey, model, s.promptCaching, s.maxOutputTokens)
    }

    private val editorDiag by lazy { DiagnosticsTool(workspace, python, shell) { emptyList() } }

    suspend fun checkText(fileName: String, text: String): String = editorDiag.checkText(fileName, text)

    private fun makeFallback(s: Settings): LlmClient? {
        val p = profiles.firstOrNull { it.id == s.fallbackProfileId && it.id != activeProfileId } ?: return null
        return makeLlm(s.copy(provider = p.provider, baseUrl = p.baseUrl, apiKey = p.apiKey), p.model)
    }

    fun runSelfCheck() {
        if (checking) return
        checking = true
        checks = emptyList()
        scope.launch {
            val out = mutableListOf<CheckResult>()
            suspend fun step(name: String, block: suspend () -> String) {
                val r = try {
                    CheckResult(name, true, kotlinx.coroutines.withTimeout(60_000) { block() })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    CheckResult(name, false, e.message ?: e.toString())
                }
                out += r
                checks = out.toList()
                if (!r.ok) AppLog.log("CHECK", "${r.name}: ${r.detail}")
            }
            val s = settings
            step("API-ключ и профиль") {
                require(s.apiKey.isNotBlank()) { "ключ не указан" }
                "${s.provider.label}, ${s.model}"
            }
            step("Связь с моделью") {
                require(s.apiKey.isNotBlank()) { "нужен ключ" }
                val r = makeLlm(s).complete("Reply with the single word: ok", listOf(Message("user", listOf(Block.Text("ping")))), emptyList()) { }
                val t = r.blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text }.trim()
                "ответ: ${t.take(40).ifEmpty { "(пусто)" }}" + if (r.inputTokens > 0) ", токенов вход/выход ${r.inputTokens}/${r.outputTokens}" else ""
            }
            step("Файлы проекта") {
                withContext(Dispatchers.IO) {
                    val f = File(workspace.root, ".pocketcode_selfcheck.tmp")
                    f.writeText("ok")
                    val back = f.readText()
                    f.delete()
                    require(back == "ok") { "запись не совпала с чтением" }
                    workspace.root.absolutePath
                }
            }
            step("Android sh") { androidShell.run("echo ok; uname -m", workspace.root, 20).lines().filter { it.isNotBlank() }.take(2).joinToString(", ") }
            step("Python") {
                python.run("import sys; print(sys.version.split()[0])", null, emptyList(), workspace.root.canonicalPath, 30).lines().first { it.isNotBlank() }
            }
            step("Git (JGit)") {
                withContext(Dispatchers.IO) {
                    val d = File(app.cacheDir, "selfcheck-git")
                    d.deleteRecursively()
                    org.eclipse.jgit.api.Git.init().setDirectory(d).call().close()
                    val ok = File(d, ".git").isDirectory
                    d.deleteRecursively()
                    require(ok) { "репозиторий не создался" }
                    "init работает"
                }
            }
            step("Интернет и web_fetch") { WebFetchTool().run(buildJsonObject { put("url", "https://example.com") }).lines().first { it.isNotBlank() }.take(60) }
            if (linuxEnv.isInstalled()) {
                step("Linux (proot)") {
                    linuxShell.run("uname -m; which python3 git node adb 2>&1 | tr '\\n' ' '", workspace.root, 40).lines().filter { it.isNotBlank() && !it.startsWith("[") }.joinToString(" | ")
                }
            } else {
                out += CheckResult("Linux (proot)", true, "не установлен (необязательно)")
                checks = out.toList()
            }
            if (linuxEnv.isInstalled()) {
                step("OpenCode в Linux") {
                    val v = runFull("opencode --version 2>&1", 40).lines().firstOrNull { it.isNotBlank() && !it.startsWith("[") }.orEmpty()
                    require(v.isNotBlank() && !v.contains("not found", true)) { "не установлен (☰ → Терминал → «Установить OpenCode»)" }
                    "версия $v"
                }
                step("Вход OpenCode") {
                    refreshOcProviders(); refreshOcCustom(); refreshOcAccounts()
                    require(ocProviders.isNotEmpty() || ocCustomProviders.isNotEmpty()) { "нет ключей: только бесплатные модели со своими лимитами (☰ → Диалоги OpenCode → «Ключ OpenCode»)" }
                    "ключи: ${(ocProviders + ocCustomProviders).joinToString()}" + (if (ocActiveAccount.isNotEmpty()) ", аккаунт «$ocActiveAccount»" else "")
                }
                if (ocCustomProviders.isNotEmpty()) step("Свой провайдер OpenCode") {
                    val r = ocCustomTest(ocCustomProviders.first())
                    require(r.startsWith("HTTP 2")) { r.take(150) }
                    r.lines().first()
                }
            }
            step("Свободное место") {
                val mb = app.filesDir.usableSpace / 1_048_576
                require(mb > 200) { "осталось только $mb МБ, очисти кэш в «Диагностике»" }
                "$mb МБ свободно"
            }
            if (syncRepo.isNotBlank()) step("Синхронизация") {
                require(syncStatus.startsWith("✓") || syncStatus.isBlank()) { syncStatus.take(150) }
                syncStatus.ifBlank { "ещё не запускалась" }
            }
            if (rootEnabled) step("Root (su)") { rootShell.check().lines().first { it.isNotBlank() }.take(80) }
            checking = false
            AppLog.log("CHECK", "самопроверка: ${out.count { it.ok }}/${out.size} успешно")
        }
    }

    fun buildReport(): String = Secrets.mask(buildReportRaw())

    private fun buildReportRaw(): String {
        val s = settings
        val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        return buildString {
            appendLine("PocketCode $ver")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("Провайдер: ${s.provider}, модель: ${s.model}, URL: ${s.baseUrl}")
            appendLine("Контекст: ${s.contextTokens}, шагов: ${s.maxSteps}, автопродолжение: ${s.autoContinue}, кэш: ${s.promptCaching}, без подтверждений: ${s.autoApprove}")
            appendLine("Linux: установлен=${linuxEnv.isInstalled()}, включён=$linuxEnabled; root=$rootEnabled; MCP=${mcpServers.size}; проект: $currentProject")
            appendLine("Расход сегодня: ${usageToday().let { "вход ${it[0]}, выход ${it[1]}, кэш ${it[2]}, запросов ${it[3]}" }}")
            if (checks.isNotEmpty()) {
                appendLine()
                appendLine("Самопроверка:")
                checks.forEach { appendLine("${if (it.ok) "OK " else "ОШИБКА "}${it.name}: ${it.detail}") }
            }
            appendLine()
            appendLine("Журнал (последние события):")
            append(AppLog.tail(60))
        }
    }



    // ---------- терминал и git-экран (ручное управление) ----------

    class TermEntry(val cmd: String) {
        var out by mutableStateOf("")
        var done by mutableStateOf(false)
    }

    val term = mutableStateListOf<TermEntry>()
    var termJob: kotlinx.coroutines.Job? = null
    val termBusy: Boolean get() = termJob?.isActive == true
    private var termProc: Process? = null

    /** Мини-эмулятор экрана: понимает \r, \n и основные управляющие последовательности (курсор вверх, очистка строки). */
    private class Screen {
        val lines = mutableListOf(StringBuilder())
        var row = 0
        var col = 0

        private fun line(): StringBuilder {
            while (lines.size <= row) lines += StringBuilder()
            return lines[row]
        }

        fun put(ch: Char) {
            val l = line()
            while (l.length < col) l.append(' ')
            if (col < l.length) l.setCharAt(col, ch) else l.append(ch)
            col++
        }

        fun feed(s: String) {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '\u001B' && i + 1 < s.length && s[i + 1] == '[' -> {
                        var k = i + 2
                        while (k < s.length && (s[k] in '0'..'9' || s[k] == ';' || s[k] == '?')) k++
                        if (k >= s.length) { i = s.length; continue }
                        val params = s.substring(i + 2, k).removePrefix("?")
                        val n = params.split(';').firstOrNull()?.toIntOrNull()
                        when (s[k]) {
                            'A' -> row = (row - (n ?: 1)).coerceAtLeast(0)
                            'B' -> row += n ?: 1
                            'C' -> col += n ?: 1
                            'D' -> col = (col - (n ?: 1)).coerceAtLeast(0)
                            'G' -> col = ((n ?: 1) - 1).coerceAtLeast(0)
                            'K' -> { val l = line(); if (col < l.length) l.setLength(if ((n ?: 0) == 0) col else 0) }
                            'J' -> { if ((n ?: 0) == 0) { line().let { if (col < it.length) it.setLength(col) }; while (lines.size > row + 1) lines.removeAt(lines.size - 1) } }
                        }
                        i = k + 1
                    }
                    ch == '\u001B' -> i += if (i + 1 < s.length && s[i + 1] == ']') {
                        // OSC: до BEL или ESC \
                        var k = i + 2
                        while (k < s.length && s[k] != '\u0007') k++
                        k - i + 1
                    } else 2
                    ch == '\r' -> { col = 0; i++ }
                    ch == '\n' -> { row++; col = 0; line(); i++ }
                    ch == '\b' -> { col = (col - 1).coerceAtLeast(0); i++ }
                    ch == '\u0007' || ch < ' ' && ch != '\t' -> i++
                    else -> { put(ch); i++ }
                }
            }
        }

        fun text(): String {
            val t = lines.joinToString("\n") { it.trimEnd() }
            return if (t.length > 200_000) t.takeLast(200_000) else t
        }
    }

    fun termRun(cmd: String, timeoutSec: Long = 600, tty: Boolean = false) {
        val c = cmd.trim().ifEmpty { return }
        if (termBusy) return
        val e = TermEntry(c)
        term += e
        while (term.size > 60) term.removeAt(0)
        val q = c.replace("'", "'\\''")
        val real = if (tty && linuxActive) {
            // В Alpine нет утилиты script, поэтому псевдотерминал создаём через Python (модуль pty)
            "export TERM=xterm; python3 -c 'import pty,sys; pty.spawn([\"sh\",\"-c\",\"stty cols 80 rows 24 2>/dev/null; \"+sys.argv[1]])' '$q'"
        } else c
        termJob = scope.launch {
            var proc: Process? = null
            val screen = Screen()
            try {
                proc = shell.builder(real, workspace.root).redirectErrorStream(true).start()
                termProc = proc
                val p = proc
                if (!tty) p.outputStream.close()
                kotlinx.coroutines.withTimeout(timeoutSec * 1000) {
                    withContext(Dispatchers.IO) {
                        val reader = p.inputStream.reader(Charsets.UTF_8)
                        val buf = CharArray(4096)
                        while (true) {
                            val n = runInterruptible { reader.read(buf) }
                            if (n < 0) break
                            screen.feed(String(buf, 0, n))
                            val text = Secrets.mask(screen.text())
                            withContext(Dispatchers.Main) { e.out = text }
                        }
                    }
                }
                val code = withContext(Dispatchers.IO) { p.waitFor() }
                e.out = Secrets.mask(screen.text()) + "\n[код выхода: $code]"
            } catch (x: kotlinx.coroutines.TimeoutCancellationException) {
                e.out = Secrets.mask(screen.text()) + "\n[таймаут ${timeoutSec} с, процесс остановлен]"
            } catch (x: kotlinx.coroutines.CancellationException) {
                e.out = Secrets.mask(screen.text()) + "\n[остановлено]"
            } catch (x: Throwable) {
                e.out = "Ошибка: ${x.message ?: x}"
            } finally {
                proc?.destroyForcibly()
                termProc = null
                e.done = true
            }
        }
    }

    /** Отправляет текст или клавиши запущенной программе (нужен режим TTY для стрелок и Ctrl+C). */
    fun termSend(text: String) {
        val p = termProc ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { p.outputStream.write(text.toByteArray()); p.outputStream.flush() }
        }
    }

    fun termStop() { termJob?.cancel() }

    var gitOut by mutableStateOf("")
        private set
    var gitBusy by mutableStateOf(false)
        private set

    fun gitRun(action: String, vararg args: Pair<String, String>) {
        if (gitBusy) return
        gitBusy = true
        scope.launch {
            val input = kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive(action))
                for ((k, v) in args) {
                    if (k == "create") put(k, kotlinx.serialization.json.JsonPrimitive(v.toBoolean()))
                    else put(k, kotlinx.serialization.json.JsonPrimitive(v))
                }
            }
            gitOut = try {
                Secrets.mask(withContext(Dispatchers.IO) { GitTool(workspace, homeDir) { settings }.run(input) })
            } catch (x: kotlinx.coroutines.CancellationException) {
                throw x
            } catch (x: Throwable) {
                "Ошибка: ${x.message ?: x}"
            }
            gitBusy = false
        }
    }

    // ---------- инспектор контекста ----------

    var historyVersion by mutableIntStateOf(0)
        private set

    class CtxRow(val index: Int, val role: String, val label: String, val tokens: Int, val canTrim: Boolean, val canCut: Boolean)

    fun contextRows(): List<CtxRow> {
        historyVersion // подписка на изменения
        return history.mapIndexed { i, m ->
            val tokens = Compactor.estimateTokens(listOf(m))
            val tr = m.blocks.filterIsInstance<Block.ToolResult>()
            val text = m.blocks.filterIsInstance<Block.Text>().joinToString(" ") { it.text }
            val tu = m.blocks.filterIsInstance<Block.ToolUse>().joinToString(", ") { it.name }
            val label = when {
                tr.isNotEmpty() -> "Результат: " + tr.first().content.take(70).replace('\n', ' ')
                tu.isNotEmpty() -> "Вызов: $tu" + if (text.isNotBlank()) " · " + text.take(50).replace('\n', ' ') else ""
                else -> text.take(90).replace('\n', ' ').ifEmpty { "(вложение)" }
            }
            CtxRow(
                i, if (m.role == "user") "вы" else "модель", label, tokens,
                canTrim = tr.any { it.content.length > 600 },
                canCut = i > 0 && m.role == "user" && tr.isEmpty() && m.blocks.any { it is Block.Text },
            )
        }
    }

    fun trimContext(i: Int, keep: Int = 400) {
        if (running || i !in history.indices) return
        val m = history[i]
        history[i] = m.copy(blocks = m.blocks.map { b ->
            if (b is Block.ToolResult && b.content.length > keep + 200) b.copy(content = b.content.take(keep) + "\n...[сокращено пользователем]") else b
        })
        agent = null
        historyVersion++
        contextUsed = Compactor.estimateTokens(history)
        persist()
    }

    /** Все самые тяжёлые результаты инструментов разом. */
    fun trimAllHeavy() {
        if (running) return
        history.indices.forEach { if (history[it].blocks.any { b -> b is Block.ToolResult && b.content.length > 1200 }) trimContext(it, 300) }
    }

    fun forgetBefore(i: Int) {
        if (running || i <= 0 || i >= history.size) return
        repeat(i) { history.removeAt(0) }
        agent = null
        historyVersion++
        contextUsed = Compactor.estimateTokens(history)
        items += UiItem.Notice("— Модель забыла ранние сообщения (убрано: $i). На экране они остались —")
        persist()
    }

    // ---------- экспорт чата ----------

    fun exportMarkdown(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val md = StringBuilder("# Чат PocketCode — ${currentProject}\n\n")
            for (it in items.toList()) when (it) {
                is UiItem.User -> md.append("## 🧑 Вы\n\n").append(it.text).append("\n\n")
                is UiItem.Assistant -> md.append("## 🤖 Модель\n\n").append(it.text).append("\n\n")
                is UiItem.Tool -> {
                    md.append("<details><summary>🔧 ").append(it.name).append(": ").append(it.description.take(120).replace('\n', ' ')).append(if (it.isError) " (ошибка)" else "").append("</summary>\n\n```\n")
                    md.append((it.output ?: "").take(4000)).append("\n```\n</details>\n\n")
                }
                is UiItem.Error -> md.append("> ⚠️ ").append(it.text).append("\n\n")
                is UiItem.Notice -> md.append("*").append(it.text).append("*\n\n")
            }
            val text = Secrets.mask(md.toString())
            runCatching { app.contentResolver.openOutputStream(uri)?.use { o -> o.write(text.toByteArray()) } }
                .onFailure { e -> withContext(Dispatchers.Main) { items += UiItem.Error("Не удалось сохранить файл: ${e.message}") } }
                .onSuccess { withContext(Dispatchers.Main) { items += UiItem.Notice("— Чат сохранён в Markdown —") } }
        }
    }

    // ---------- шаблоны проектов, автопроверки ----------

    fun createProjectFromTemplate(rawName: String, templateId: String) {
        createProject(rawName)
        val t = PROJECT_TEMPLATES.firstOrNull { it.id == templateId } ?: return
        if (currentProject != sanitize(rawName)) return
        runCatching {
            for ((path, text) in t.files) {
                val f = File(workspace.root, path)
                if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text) }
            }
            agent = null
        }
    }

    /** Подбирает автопроверки по файлам проекта и записывает их в .pocketcode/hooks.txt. */
    fun detectHooks(): String {
        val root = workspace.root
        val names = root.walkTopDown().maxDepth(3).filter { it.isFile }.map { it.name }.toList()
        fun has(vararg n: String) = names.any { it in n }
        val lines = mutableListOf<String>()
        val stack = mutableListOf<String>()
        if (names.any { it.endsWith(".py") } || has("requirements.txt", "pyproject.toml")) { stack += "Python"; lines += "*.py => python3 -m py_compile {file}" }
        if (has("package.json")) { stack += "Node"; lines += "*.js => node --check {file}"; lines += "*.json => python3 -m json.tool {file} > /dev/null" }
        else if (names.any { it.endsWith(".json") }) lines += "*.json => python3 -m json.tool {file} > /dev/null"
        if (names.any { it.endsWith(".sh") }) { stack += "Shell"; lines += "*.sh => bash -n {file}" }
        if (has("go.mod")) { stack += "Go"; lines += "*.go => gofmt -l {file}" }
        if (has("Cargo.toml")) stack += "Rust"
        if (names.any { it.endsWith(".kt") } || has("build.gradle", "build.gradle.kts")) stack += "Kotlin/Gradle"
        if (lines.isEmpty()) return "Не узнал стек: файлов с известными расширениями нет. Напиши хуки вручную в настройках."
        val f = File(root, ".pocketcode/hooks.txt")
        f.parentFile?.mkdirs()
        f.writeText(lines.joinToString("\n") + "\n")
        return "Стек: ${stack.joinToString(", ").ifEmpty { "общий" }}. Записал ${lines.size} автопроверок в .pocketcode/hooks.txt: после каждой правки файла агент сразу видит результат."
    }

    fun installCatalogItem(item: CatalogItem, global: Boolean) {
        val base = if (global) extensions.globalDir else File(workspace.root, ".pocketcode")
        val f = if (item.isSkill) File(base, "skills/${item.name}/SKILL.md") else File(base, "commands/${item.name}.md")
        f.parentFile?.mkdirs()
        f.writeText(item.content)
        agent = null
        refreshExtensions()
    }

    fun isInstalled(item: CatalogItem): Boolean {
        return if (item.isSkill) skillList.any { it.name == item.name } else commandList.any { it.name == item.name }
    }

    // --- проверка и сравнение моделей ---
    var verifyText by mutableStateOf<String?>(null)
    var verifyingId by mutableStateOf<String?>(null)
        private set

    private fun norm(s: String) = s.lowercase().substringAfterLast('/').replace(Regex("[^a-z0-9]"), "")

    fun verifyProfile(p: Profile) {
        if (verifyingId != null) return
        verifyingId = p.id
        verifyText = "Проверяю «${p.label}»…"
        scope.launch {
            val text = try {
                require(p.apiKey.isNotBlank()) { "у профиля нет ключа" }
                val t0 = System.currentTimeMillis()
                val r = kotlinx.coroutines.withTimeout(90_000) {
                    makeLlm(profileSettings(p), p.model).complete(
                        "You are being tested. Answer exactly in the requested format, nothing else.",
                        listOf(Message("user", listOf(Block.Text(
                            "Line 1: state your exact model name and version and the company that trained you. " +
                                "Line 2: the result of 1234*5678, digits only.",
                        )))),
                        emptyList(),
                    ) { }
                }
                val ms = System.currentTimeMillis() - t0
                val answer = r.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }.trim()
                val lines = mutableListOf<String>()
                lines += "Профиль: ${p.label}, запрошена модель: ${p.model}"
                if (r.servedModel.isBlank()) lines += "⚪ Сервер не назвал модель в ответе (некоторые прокси так делают), по этому признаку проверить нельзя."
                else if (norm(r.servedModel).contains(norm(p.model)) || norm(p.model).contains(norm(r.servedModel)))
                    lines += "✅ Сервер ответил моделью: ${r.servedModel}"
                else lines += "⚠️ Сервер ответил другой моделью: ${r.servedModel} (в профиле: ${p.model}). Возможно, подменили."
                val claim = answer.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                lines += "Сама о себе: ${claim.take(160).ifEmpty { "(пусто)" }}"
                val expected = listOf(
                    "claude" to "anthropic", "gpt" to "openai", "o1" to "openai", "o3" to "openai", "deepseek" to "deepseek",
                    "gemini" to "google", "llama" to "meta", "qwen" to "qwen", "mistral" to "mistral", "minimax" to "minimax", "grok" to "xai",
                ).firstOrNull { norm(p.model).contains(it.first) }
                if (expected != null) {
                    val c = claim.lowercase()
                    if (c.contains(expected.first) || c.contains(expected.second)) lines += "✅ Самоописание согласуется с названием модели."
                    else lines += "⚠️ Самоописание не похоже на «${expected.first}». Модели иногда ошибаются в самоописании, но вместе с другими признаками это повод насторожиться."
                }
                val math = answer.contains("7006652") || answer.contains("7 006 652") || answer.contains("7,006,652")
                lines += if (math) "✅ Арифметика 1234×5678 верна." else "⚠️ Арифметика 1234×5678 не сошлась (слабые модели часто ошибаются)."
                lines += "Время ответа: ${ms / 1000.0} с" + if (r.inputTokens > 0) ", токенов вход/выход: ${r.inputTokens}/${r.outputTokens}" else ""
                lines += "Важно: поле «модель» в ответе может подделать и недобросовестный прокси. Надёжнее всего сравнить качество на своих задачах (экран «Сравнить модели»)."
                lines.joinToString("\n")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Не удалось проверить: ${e.message ?: e}"
            }
            verifyText = text
            verifyingId = null
        }
    }

    class CompareEntry(val profile: Profile) {
        var text by mutableStateOf("")
        var done by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var ms by mutableStateOf(0L)
        var inTok by mutableStateOf(0)
        var outTok by mutableStateOf(0)
        var served by mutableStateOf("")
    }

    val compare = mutableStateListOf<CompareEntry>()

    fun runCompare(prompt: String, ids: List<String>) {
        val p = prompt.trim().ifEmpty { return }
        compare.clear()
        for (pr in profiles.filter { it.id in ids }) {
            val e = CompareEntry(pr)
            compare += e
            scope.launch(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    val r = makeLlm(profileSettings(pr), pr.model).complete(
                        "Answer helpfully and concisely.", listOf(Message("user", listOf(Block.Text(p)))), emptyList(),
                    ) { delta -> e.text += delta }
                    e.inTok = r.inputTokens; e.outTok = r.outputTokens; e.served = r.servedModel
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Throwable) {
                    e.error = ex.message ?: ex.toString()
                }
                e.ms = System.currentTimeMillis() - t0
                e.done = true
            }
        }
    }

    // --- расход токенов ---
    private val usage = settingsStore.loadUsage().toMutableMap()
    var usageVersion by mutableIntStateOf(0)
        private set
    private var limitWarned = false

    private fun dayKey(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // Помесячный расход по профилям: ключ "yyyy-MM|id профиля"
    private val profileUsage: MutableMap<String, List<Long>> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, List<Long>>>(settingsStore.rawGet("profileUsage") ?: "{}").toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun monthKey(): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())

    /** [вход, выход, кэш, запросов] профиля за текущий месяц. */
    fun usageMonth(profileId: String): LongArray {
        usageVersion
        val v = profileUsage[monthKey() + "|" + profileId] ?: return LongArray(4)
        return LongArray(4) { v[it] }
    }

    /** Расход профиля за последние месяцы: пары (месяц, вход+выход). */
    fun usageMonthsOf(profileId: String): List<Pair<String, Long>> =
        profileUsage.entries.filter { it.key.endsWith("|$profileId") }
            .map { it.key.substringBefore('|') to it.value[0] + it.value[1] }.sortedByDescending { it.first }.take(6)

    private fun recordUsage(input: Int, output: Int, cached: Int, profileId: String = activeProfileId) {
        // Несколько вкладок пишут в одно хранилище: сначала подхватываем чужие записи
        usage.clear(); usage.putAll(settingsStore.loadUsage())
        runCatching {
            profileUsage.clear()
            profileUsage.putAll(kotlinx.serialization.json.Json.decodeFromString<Map<String, List<Long>>>(settingsStore.rawGet("profileUsage") ?: "{}"))
        }
        run {
            val pk = monthKey() + "|" + profileId
            val o = profileUsage[pk] ?: listOf(0L, 0L, 0L, 0L)
            profileUsage[pk] = listOf(o[0] + input, o[1] + output, o[2] + cached, o[3] + 1)
            if (profileUsage.size > 240) profileUsage.keys.sorted().take(profileUsage.size - 200).forEach { profileUsage.remove(it) }
            settingsStore.rawPut("profileUsage", kotlinx.serialization.json.Json.encodeToString<Map<String, List<Long>>>(profileUsage.toMap()))
        }
        val key = dayKey() + "|" + currentProject
        val old = usage[key] ?: listOf(0L, 0L, 0L, 0L)
        usage[key] = listOf(old[0] + input, old[1] + output, old[2] + cached, old[3] + 1)
        if (usage.size > 400) usage.keys.sorted().take(usage.size - 300).forEach { usage.remove(it) }
        settingsStore.saveUsage(usage)
        usageVersion++
        val limit = settings.dailyTokenLimit
        if (limit > 0 && !limitWarned && usageToday().let { it[0] + it[1] } > limit) {
            limitWarned = true
            items += UiItem.Notice("Суточный лимит токенов ($limit) превышен: автопродолжение и очередь приостановлены.")
        }
    }

    private fun overDailyLimit(): Boolean =
        settings.dailyTokenLimit > 0 && usageToday().let { it[0] + it[1] } > settings.dailyTokenLimit

    fun usageToday(): LongArray {
        val day = dayKey()
        val sum = LongArray(4)
        usage.forEach { (k, v) -> if (k.startsWith("$day|")) for (i in 0..3) sum[i] += v[i] }
        return sum
    }

    fun usageTotal(): LongArray {
        val sum = LongArray(4)
        usage.values.forEach { v -> for (i in 0..3) sum[i] += v[i] }
        return sum
    }

    fun usageByProject(): List<Pair<String, Long>> =
        usage.entries.groupBy({ it.key.substringAfter('|') }, { it.value[0] + it.value[1] })
            .map { (k, v) -> k to v.sum() }.sortedByDescending { it.second }

    fun costOf(input: Long, output: Long): Double =
        (input * settings.priceIn + output * settings.priceOut) / 1_000_000.0

    fun resetUsage() {
        profileUsage.clear()
        settingsStore.rawPut("profileUsage", "{}")
        usage.clear()
        settingsStore.saveUsage(usage)
        usageVersion++
        limitWarned = false
    }

    // --- резервная копия настроек ---
    var backupMessage by mutableStateOf<String?>(null)
    /** Пароль для шифрования копии (не сохраняется). Пусто — копия без шифрования. */
    var backupPassword by mutableStateOf("")

    private val backupPrefKeys = listOf(
        "syncRepo", "syncProjects", "syncInterval", "syncHost", "ocFallback", "ocAutoSwitch", "ocAutoCompact", "ocRateRetryMin",
        "ocBudget", "ocFresh", "ocModel", "ocCards", "themeMode", "templates", "stars", "ocPins", "ocTitles", "bigInput",
    )

    private fun cryptKey(password: String, salt: ByteArray): javax.crypto.spec.SecretKeySpec {
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).encoded
        return javax.crypto.spec.SecretKeySpec(k, "AES")
    }

    private fun encryptBackup(plain: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, cryptKey(password, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        return "PCBK1".toByteArray() + salt + iv + cipher.doFinal(plain)
    }

    private fun decryptBackup(data: ByteArray, password: String): ByteArray {
        val salt = data.copyOfRange(5, 21)
        val iv = data.copyOfRange(21, 33)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, cryptKey(password, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(data, 33, data.size - 33)
    }

    fun exportBackup(uri: Uri) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val j = kotlinx.serialization.json.Json { prettyPrint = true }
                    val s = settings
                    val files = kotlinx.serialization.json.buildJsonObject {
                        extensions.globalDir.walkTopDown().filter { it.isFile && it.length() < 200_000 }.forEach {
                            put(it.relativeTo(extensions.globalDir).path, it.readText())
                        }
                    }
                    val obj = kotlinx.serialization.json.buildJsonObject {
                        put("app", "pocketcode-backup"); put("version", 1)
                        put("profiles", j.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), profiles))
                        put("activeProfile", activeProfileId)
                        put("settings", kotlinx.serialization.json.buildJsonObject {
                            put("githubToken", s.githubToken); put("gitName", s.gitName); put("gitEmail", s.gitEmail)
                            put("autoApprove", s.autoApprove); put("contextTokens", s.contextTokens); put("freshOnStart", s.freshOnStart)
                            put("smartApprove", s.smartApprove); put("allowRules", s.allowRules); put("promptCaching", s.promptCaching)
                            put("maxSteps", s.maxSteps); put("permissionRules", s.permissionRules); put("fileHooks", s.fileHooks)
                            put("autoContinue", s.autoContinue); put("priceIn", s.priceIn); put("priceOut", s.priceOut)
                            put("dailyTokenLimit", s.dailyTokenLimit)
                        })
                        put("mcp", j.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(McpServer.serializer()), mcpServers))
                        put("schedules", j.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(com.pocketcode.Schedule.serializer()), schedules))
                        put("devices", j.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Device.serializer()), devices))
                        put("globalFiles", files)
                        put("ocFiles", kotlinx.serialization.json.buildJsonObject {
                            ocAccDir.listFiles { f -> f.extension == "json" }?.forEach { put("acc/" + it.name, it.readText()) }
                            if (ocAuthFile.exists()) put("auth.json", ocAuthFile.readText())
                            if (ocConfigFile.exists()) put("opencode.json", ocConfigFile.readText())
                        })
                        put("prefs", kotlinx.serialization.json.buildJsonObject {
                            backupPrefKeys.forEach { k -> settingsStore.rawGet(k)?.let { put(k, it) } }
                        })
                    }
                    var bytes = j.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj).toByteArray()
                    if (backupPassword.isNotBlank()) bytes = encryptBackup(bytes, backupPassword)
                    app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }
            }
            backupMessage = r.exceptionOrNull()?.let { "Не удалось сохранить: ${it.message}" }
                ?: if (backupPassword.isNotBlank()) "Копия сохранена и зашифрована паролем. Не потеряй пароль: без него файл не открыть."
                else "Копия сохранена БЕЗ шифрования. В ней лежат API-ключи и токены, храни файл в надёжном месте (или задай пароль и сохрани заново)."
        }
    }

    fun importBackup(uri: Uri) {
        if (running) { backupMessage = "Дождись окончания работы агента."; return }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val text = if (raw.size > 33 && String(raw, 0, 5) == "PCBK1") {
                        require(backupPassword.isNotBlank()) { "файл защищён паролем: введи пароль выше и выбери файл снова" }
                        try { decryptBackup(raw, backupPassword).decodeToString() } catch (e: Exception) { throw IllegalStateException("неверный пароль") }
                    } else raw.decodeToString()
                    val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val o = j.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                    require((o["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "pocketcode-backup") { "это не копия PocketCode" }
                    o
                }
            }
            val o = r.getOrElse { backupMessage = "Не удалось прочитать: ${it.message}"; return@launch }
            runCatching {
                val j = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                fun str(m: kotlinx.serialization.json.JsonObject, k: String) = (m[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val st = o["settings"] as? kotlinx.serialization.json.JsonObject
                val newProfiles = o["profiles"]?.let { j.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Profile.serializer()), it) } ?: profiles
                val activeId = str(o, "activeProfile")?.takeIf { id -> newProfiles.any { it.id == id } } ?: newProfiles.first().id
                val a = newProfiles.first { it.id == activeId }
                var ns = settings.copy(provider = a.provider, baseUrl = a.baseUrl, model = a.model, apiKey = a.apiKey)
                if (st != null) {
                    ns = ns.copy(
                        githubToken = str(st, "githubToken") ?: ns.githubToken, gitName = str(st, "gitName") ?: ns.gitName,
                        gitEmail = str(st, "gitEmail") ?: ns.gitEmail, autoApprove = str(st, "autoApprove")?.toBoolean() ?: ns.autoApprove,
                        contextTokens = str(st, "contextTokens")?.toIntOrNull() ?: ns.contextTokens,
                        freshOnStart = str(st, "freshOnStart")?.toBoolean() ?: ns.freshOnStart,
                        smartApprove = str(st, "smartApprove")?.toBoolean() ?: ns.smartApprove,
                        allowRules = str(st, "allowRules") ?: ns.allowRules, promptCaching = str(st, "promptCaching")?.toBoolean() ?: ns.promptCaching,
                        maxSteps = str(st, "maxSteps")?.toIntOrNull() ?: ns.maxSteps, permissionRules = str(st, "permissionRules") ?: ns.permissionRules,
                        fileHooks = str(st, "fileHooks") ?: ns.fileHooks, autoContinue = str(st, "autoContinue")?.toBoolean() ?: ns.autoContinue,
                        priceIn = str(st, "priceIn")?.toFloatOrNull() ?: ns.priceIn, priceOut = str(st, "priceOut")?.toFloatOrNull() ?: ns.priceOut,
                        dailyTokenLimit = str(st, "dailyTokenLimit")?.toIntOrNull() ?: ns.dailyTokenLimit,
                    )
                }
                saveSettings(ns, newProfiles, activeId)
                o["mcp"]?.let { mcpServers = j.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(McpServer.serializer()), it); settingsStore.saveMcp(mcpServers) }
                o["schedules"]?.let {
                    schedules = j.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(com.pocketcode.Schedule.serializer()), it)
                    settingsStore.saveSchedules(schedules); com.pocketcode.Scheduler.apply(app, schedules)
                }
                o["devices"]?.let { devices = j.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Device.serializer()), it); settingsStore.saveDevices(devices) }
                (o["globalFiles"] as? kotlinx.serialization.json.JsonObject)?.forEach { (rel, v) ->
                    val f = File(extensions.globalDir, rel)
                    if (f.canonicalPath.startsWith(extensions.globalDir.canonicalPath)) {
                        f.parentFile?.mkdirs(); f.writeText((v as kotlinx.serialization.json.JsonPrimitive).content)
                    }
                }
                (o["ocFiles"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, v) ->
                    val t = (v as kotlinx.serialization.json.JsonPrimitive).content
                    when {
                        name.startsWith("acc/") -> { ocAccDir.mkdirs(); File(ocAccDir, name.removePrefix("acc/").replace("/", "")).writeText(t) }
                        name == "auth.json" -> { ocAuthFile.parentFile?.mkdirs(); ocAuthFile.writeText(t) }
                        name == "opencode.json" -> { ocConfigFile.parentFile?.mkdirs(); ocConfigFile.writeText(t) }
                    }
                }
                (o["prefs"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) -> if (k in backupPrefKeys) settingsStore.rawPut(k, (v as kotlinx.serialization.json.JsonPrimitive).content) }
                agent = null
                refreshExtensions()
                refreshOcAccounts(); refreshOcCustom()
            }.onSuccess { backupMessage = "Настройки восстановлены. Перезапусти приложение, чтобы применились все параметры." }
                .onFailure { backupMessage = "Не удалось применить: ${it.message}" }
        }
    }

    /** Текст, пришедший из «Поделиться» в другом приложении: попадёт в поле ввода. */
    var incomingText by mutableStateOf<String?>(null)

    private var limitHit = false
    private var autoChain = 0

    var pendingImages by mutableStateOf<List<Block.Image>>(emptyList())
        private set

    fun attachImage(uri: android.net.Uri) {
        scope.launch {
            val img = withContext(Dispatchers.IO) { runCatching { loadImage(uri) } }
            img.getOrNull()?.let { pendingImages = pendingImages + it }
            img.exceptionOrNull()?.let { items += UiItem.Error("Не удалось прочитать картинку: ${it.message}") }
        }
    }

    fun removeImage(i: Int) { pendingImages = pendingImages.filterIndexed { idx, _ -> idx != i } }

    private fun loadImage(uri: android.net.Uri): Block.Image {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("файл не открылся")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("не картинка")
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos)
        return Block.Image("image/jpeg", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP))
    }

    // ---------- диалоги OpenCode ----------

    class OcSession(val id: String, val title: String, val updated: String)

    var ocSessions by mutableStateOf<List<OcSession>>(emptyList())
        private set
    /** Последняя реплика беседы: id → текст (подгружается по очереди). */
    val ocPreviews = mutableStateMapOf<String, String>()
    private var ocPreviewJob: kotlinx.coroutines.Job? = null

    private fun loadOcPreviews() {
        ocPreviewJob?.cancel()
        val list = ocSessions.take(8).filter { it.id !in ocPreviews }
        ocPreviewJob = scope.launch {
            for (s in list) {
                val raw = try { ocExport(s.id, 60) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { continue }
                val last = withContext(Dispatchers.Default) {
                    val t = scanTexts(raw.takeLast(300_000))
                    (t.lastOrNull { it.first != "user" } ?: t.lastOrNull())
                }
                val texts = listOfNotNull(last)
                val lastText = last?.second
                ocPreviews[s.id] = Secrets.mask(last?.second.orEmpty()).replace(Regex("\\s+"), " ").trim().takeLast(160).ifEmpty { "(пусто)" }
            }
        }
    }

    var ocSessionsMsg by mutableStateOf<String?>(null)
        private set
    var ocSessionsBusy by mutableStateOf(false)
        private set
    var ocSessionId by mutableStateOf<String?>(null)
        private set
    var ocSessionTitle by mutableStateOf("")
        private set

    /** Вырезает JSON из вывода команды: отбрасывает всё до первой скобки и после последней (например, «[код выхода: 0]»). */
    private fun jsonStart(raw: String): String {
        // Терминал дописывает служебные строки вида «[код выхода: 0]»: убираем их до разбора
        val text = raw.lines().filterNot { it.trim().startsWith("[код выхода") || it.trim().startsWith("[таймаут") }.joinToString("\n")
        val i = text.indexOfFirst { it == '[' || it == '{' }
        if (i < 0) return text
        val end = if (text[i] == '[') text.lastIndexOf(']') else text.lastIndexOf('}')
        return if (end > i) text.substring(i, end + 1) else text.substring(i)
    }

    /** Запускает команду в Linux и читает весь вывод (до 8 МБ), без обрезки, как у обычного терминала. */
    private suspend fun runFull(cmd: String, timeoutSec: Long): String = withContext(Dispatchers.IO) {
        val p = linuxShell.builder(cmd, workspace.root).redirectErrorStream(false).start()
        p.outputStream.close()
        val errDrain = Thread { runCatching { p.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }.also { it.isDaemon = true; it.start() }
        try {
            kotlinx.coroutines.withTimeout(timeoutSec * 1000) {
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                val ins = p.inputStream
                while (true) {
                    val n = runInterruptible { ins.read(buf) }
                    if (n < 0) break
                    if (out.size() < 8_000_000) out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
        } finally {
            p.destroyForcibly()
            errDrain.interrupt()
        }
    }

    /** Экспорт беседы через файл: при выводе в pipe opencode (Bun) обрывает длинный JSON на ~64 КБ, файл пишется целиком. */
    private suspend fun ocViaFile(cmd: String, tag: String, timeoutSec: Long): String {
        val rel = "tmp/pc_${tag}_${System.nanoTime()}.json"
        val f = File(linuxEnv.rootfs, rel)
        return try {
            runFull("$cmd > /$rel 2>/dev/null", timeoutSec)
            if (f.exists() && f.length() > 0) withContext(Dispatchers.IO) { f.readText() } else runFull(cmd, timeoutSec)
        } finally {
            runCatching { f.delete() }
        }
    }

    private suspend fun ocExport(id: String, timeoutSec: Long): String {
        val safe = id.replace("'", "").replace(Regex("[^A-Za-z0-9_-]"), "")
        val rel = "tmp/pc_export_$safe.json"
        val f = File(linuxEnv.rootfs, rel)
        return try {
            runFull("opencode export '$safe' > /$rel 2>/dev/null", timeoutSec)
            if (f.exists() && f.length() > 0) withContext(Dispatchers.IO) { f.readText() } else runFull("opencode export '$safe'", timeoutSec)
        } finally {
            runCatching { f.delete() }
        }
    }

    private fun collectTexts(el: kotlinx.serialization.json.JsonElement?, role: String?, out: MutableList<Pair<String?, String>>) {
        when (el) {
            is kotlinx.serialization.json.JsonObject -> {
                val info = el["info"] as? kotlinx.serialization.json.JsonObject
                val r = ((info?.get("role") ?: el["role"]) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: role
                val type = (el["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val text = (el["text"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (type == "text" && !text.isNullOrBlank()) {
                    out += r to text
                    return
                }
                if (type == "tool") {
                    out += r to "🔧 " + ((el["tool"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "инструмент")
                    return
                }
                for ((k, v) in el) if (k != "info") collectTexts(v, r, out)
            }
            is kotlinx.serialization.json.JsonArray -> el.forEach { collectTexts(it, role, out) }
            else -> {}
        }
    }

    private val tokenRe = Regex("\"(role|type)\"\\s*:\\s*\"([^\"]*)\"|\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Запасной разбор: идёт по тексту подряд и берёт строки "text" у частей с "type": "text". */
    private fun scanTexts(raw: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        var role: String? = null
        var type: String? = null
        for (m in tokenRe.findAll(raw)) {
            val key = m.groupValues[1]
            if (key == "role") { role = m.groupValues[2]; continue }
            if (key == "type") { type = m.groupValues[2]; continue }
            if (type == "text") {
                val t = runCatching { kotlinx.serialization.json.Json.decodeFromString<String>("\"" + m.groupValues[3] + "\"") }.getOrNull()
                if (!t.isNullOrBlank()) out += role to t
            }
        }
        return out
    }

    private fun fmtTime(e: kotlinx.serialization.json.JsonElement?): String {
        val p = e as? kotlinx.serialization.json.JsonPrimitive ?: return ""
        val n = p.content.toLongOrNull() ?: return p.content
        val ms = if (n < 100_000_000_000L) n * 1000 else n
        return java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    fun refreshOcSessions() {
        if (ocSessionsBusy) return
        if (!linuxActive) { ocSessionsMsg = "Нужен включённый Linux с установленным OpenCode."; return }
        ocSessionsBusy = true
        ocSessionsMsg = "Загружаю список…"
        scope.launch {
            val raw = try {
                ocViaFile("opencode session list --format json -n 100", "list", 120)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Ошибка: ${e.message}"
            }
            try {
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(jsonStart(raw)) as kotlinx.serialization.json.JsonArray
                ocSessions = arr.mapNotNull { el ->
                    val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val id = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                    val title = (o["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty().ifBlank { "(без названия)" }
                    val t = (o["time"] as? kotlinx.serialization.json.JsonObject)?.get("updated") ?: o["updated"] ?: o["time"]
                    OcSession(id, title, fmtTime(t))
                }
                ocSessionsMsg = if (ocSessions.isEmpty()) "Бесед пока нет (OpenCode показывает беседы текущего проекта: «${currentProject}»). Ответ: " + Secrets.mask(raw.take(200)) else null
                loadOcPreviews()
            } catch (e: Throwable) {
                ocSessions = emptyList()
                ocSessionsMsg = "Не удалось разобрать ответ OpenCode:\n" + Secrets.mask(raw.take(600))
            }
            ocSessionsBusy = false
        }
    }

    fun deleteOcSession(id: String) {
        if (ocSessionsBusy) return
        ocSessionsBusy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { shell.run("opencode session delete '${id.replace("'", "")}' 2>&1", workspace.root, 60) } }
            if (ocSessionId == id) { ocSessionId = null; ocSessionTitle = "" }
            ocSessionsBusy = false
            refreshOcSessions()
        }
    }

    fun newOcSession() {
        ocSessionId = null
        ocSessionTitle = ""
        setOcContinue(false)
        items += UiItem.Notice("— Следующее сообщение начнёт новую беседу OpenCode —")
    }

    /** Выбирает беседу: следующие сообщения продолжат её, а переписка показывается в чате. */
    fun useOcSession(s: OcSession) {
        if (running) return
        ocSessionId = s.id
        ocSessionTitle = s.title
        ocSessionsBusy = true
        ocSessionsMsg = "Открываю беседу…"
        scope.launch {
            val raw = try {
                ocExport(s.id, 180)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ""
            }
            val shown = mutableListOf<UiItem>()
            var parseNote = ""
            // Разбор тяжёлый, а беседа может быть огромной: делаем это вне главного потока и берём только хвост
            val found = withContext(Dispatchers.Default) {
                try {
                    if (raw.length > 2_000_000) throw IllegalStateException("big")
                    val texts = mutableListOf<Pair<String?, String>>()
                    collectTexts(kotlinx.serialization.json.Json.parseToJsonElement(jsonStart(raw)), null, texts)
                    texts
                } catch (e: Throwable) {
                    val t = scanTexts(if (raw.length > 1_500_000) raw.takeLast(1_500_000) else raw)
                    if (t.isEmpty()) parseNote = "Не разобрал вывод opencode export (${e.javaClass.simpleName}). Размер ${raw.length} симв."
                    t
                }
            }
            for ((role, text) in found.takeLast(60)) {
                val tx = Secrets.mask(if (text.length > 6000) text.take(6000) + "\n…(обрезано)" else text)
                shown += if (role == "user") UiItem.User(tx, 0) else UiItem.Assistant(tx)
            }
            ocCtxTokens = raw.length / 4
            items.clear()
            items += UiItem.Notice("— Беседа OpenCode: ${s.title}. Новые сообщения продолжат её —")
            if (shown.isEmpty()) items += UiItem.Notice("(переписку показать не удалось, но беседа выбрана и продолжится)" + if (parseNote.isNotEmpty()) "\n$parseNote" else "\nОтвет opencode export (${raw.length} симв.): " + Secrets.mask(raw.take(300)))
            else items.addAll(shown)
            if (currentId == null) currentId = UUID.randomUUID().toString()
            openCodeMode = true
            persist()
            ocSessionsBusy = false
            ocSessionsMsg = null
        }
    }

    // ---------- очистка ----------

    var cleanupDays by mutableIntStateOf(settingsStore.rawGet("cleanupDays")?.toIntOrNull() ?: 0)
        private set
    fun setCleanupDaysValue(d: Int) { cleanupDays = d; settingsStore.rawPut("cleanupDays", d.toString()) }

    /** Чистит кэш npm и apk, временные файлы; возвращает сообщение с освобождённым местом. */
    suspend fun cleanCaches(): String {
        if (!linuxActive) return "Linux не включён: чистить нечего."
        val before = withContext(Dispatchers.IO) { dirSize(linuxEnv.rootfs) }
        runCatching { runFull("npm cache clean --force; rm -rf /root/.npm/_cacache /root/.npm/_logs; apk cache clean 2>/dev/null; rm -rf /tmp/* 2>/dev/null; echo ok", 300) }
        val after = withContext(Dispatchers.IO) { dirSize(linuxEnv.rootfs) }
        val freed = (before - after).coerceAtLeast(0)
        return "Освобождено ≈ ${freed / (1024 * 1024)} МБ."
    }

    /** Удаляет старые чаты (кроме закреплённых и текущего) и старые снимки (оставляя по одному на проект). */
    suspend fun cleanupOld(days: Int): String {
        if (days <= 0) return "Автоочистка выключена."
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var chats = 0
        for (m in sessions.list()) {
            if (m.pinned || m.id == currentId || m.updatedAt >= cutoff) continue
            sessions.delete(m.id); chats++
        }
        sessionList = sessions.list()
        var snaps = 0
        withContext(Dispatchers.IO) {
            File(app.filesDir, "snapshots").listFiles()?.filter { it.isDirectory }?.forEach { pdir ->
                val kids = pdir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                kids.drop(1).filter { it.lastModified() < cutoff }.forEach { if (it.deleteRecursively()) snaps++ }
            }
        }
        return "Удалено чатов: $chats, снимков: $snaps (старше $days дн.)."
    }

    // ---------- заметки проекта, шаблоны, модель ----------

    private val notesFile: File get() = File(workspace.root, ".pocketcode/NOTES.md")
    fun notesText(): String = runCatching { notesFile.readText() }.getOrDefault("")
    fun saveNotes(text: String) {
        runCatching {
            notesFile.parentFile?.mkdirs()
            notesFile.writeText(text.trim() + "\n")
            if (!running) agent = null
        }
    }

    var promptTemplates by mutableStateOf(
        runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(settingsStore.rawGet("templates") ?: "[]") as kotlinx.serialization.json.JsonArray)
                .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        }.getOrDefault(emptyList()),
    )
        private set

    private fun saveTemplates(l: List<String>) {
        promptTemplates = l
        settingsStore.rawPut("templates", kotlinx.serialization.json.JsonArray(l.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
    }
    fun addTemplate(t: String) { val s = t.trim(); if (s.isNotEmpty() && s !in promptTemplates) saveTemplates(promptTemplates + s) }
    fun removeTemplate(i: Int) { if (i in promptTemplates.indices) saveTemplates(promptTemplates.filterIndexed { k, _ -> k != i }) }

    /** Быстрая смена модели активного профиля. */
    fun setModelNow(model: String) {
        val m = model.trim().ifEmpty { return }
        profiles = profiles.map { if (it.id == activeProfileId) it.copy(model = m) else it }
        settings = settings.copy(model = m)
        if (!isTab) {
            settingsStore.saveProfiles(profiles, activeProfileId)
            settingsStore.save(settings)
        }
        if (!running) agent = null
    }

    // ---------- защита приложения ----------

    var appLock by mutableStateOf(settingsStore.rawGet("appLock") == "1")
        private set
    fun setAppLockValue(v: Boolean) { appLock = v; settingsStore.rawPut("appLock", if (v) "1" else "0") }

    // ---------- занимаемое место ----------

    private fun dirSize(f: File): Long {
        if (!f.exists()) return 0
        var total = 0L
        try {
            java.nio.file.Files.walkFileTree(f.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: java.nio.file.Path, exc: java.io.IOException): java.nio.file.FileVisitResult =
                    java.nio.file.FileVisitResult.CONTINUE
            })
        } catch (e: Exception) {
        }
        return total
    }

    /** Список (название, байт); последний элемент «Свободно на телефоне». */
    suspend fun storageUsage(): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val root = linuxEnv.rootfs
        val oc = File(root, "root/.local/share/opencode")
        val npm = File(root, "root/.npm")
        val rootTotal = dirSize(root)
        val list = mutableListOf<Pair<String, Long>>()
        list += "Linux целиком (система, пакеты, OpenCode)" to rootTotal
        list += "  из них данные и беседы OpenCode" to dirSize(oc)
        list += "  из них кэш npm" to dirSize(npm)
        list += "Проекты (папка приложения)" to dirSize(privateDir)
        if (sharedDir.exists()) list += "Проекты в общей папке PocketCode" to dirSize(sharedDir)
        list += "Снимки для отката" to dirSize(File(app.filesDir, "snapshots"))
        list += "Изолированные рабочие копии" to dirSize(File(app.filesDir, "worktrees"))
        list += "Сессии чатов" to dirSize(File(app.filesDir, "sessions"))
        list += "Временные файлы и кэш" to dirSize(app.cacheDir)
        list += "Всё приложение (папка данных)" to dirSize(app.filesDir)
        val free = runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrDefault(0L)
        list += "Свободно на телефоне" to free
        list
    }

    // ---------- движок OpenCode (официальный клиент внутри Linux) ----------

    var openCodeMode by mutableStateOf(false)
    var ocModel by mutableStateOf(settingsStore.rawGet("ocModel") ?: "")
    private var ocContinue = !isTab && settingsStore.rawGet("ocContinue") == "1"

    private fun setOcContinue(v: Boolean) {
        ocContinue = v
        if (!isTab) settingsStore.rawPut("ocContinue", if (v) "1" else "0")
    }

    fun setOcModelValue(v: String) { ocModel = v; settingsStore.rawPut("ocModel", v); if (!isTab) settingsStore.rawPut("ocModel|" + currentProject, v) }

    /** Модель и беседа OpenCode помнятся отдельно для каждого проекта. */
    private fun loadOcProject() {
        if (isTab) return
        settingsStore.rawGet("ocModel|" + currentProject)?.let { ocModel = it }
        val s = settingsStore.rawGet("ocSess|" + currentProject).orEmpty().split('\u001f')
        ocSessionId = s.getOrNull(0)?.takeIf { it.isNotBlank() }
        ocSessionTitle = s.getOrNull(1).orEmpty()
        setOcContinue(ocSessionId != null)
    }

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    // ---------- упоминания файлов, поиск, правки по блокам, откат к сообщению ----------
    private val noWalk = setOf(".git", "node_modules", "__pycache__", ".gradle", "build", "dist", ".venv", "venv", ".idea", ".cache")

    /** Файлы проекта для подсказок после «@». */
    suspend fun projectFiles(): List<String> = withContext(Dispatchers.IO) {
        val root = workspace.root
        val out = ArrayList<String>()
        root.walkTopDown().onEnter { it == root || it.name !in noWalk }.forEach { f ->
            if (f.isFile && out.size < 4000) out += f.relativeTo(root).invariantSeparatorsPath
        }
        out.sorted()
    }

    /** Подставляет в запрос содержимое файлов, упомянутых как @путь. */
    private fun mentionExpand(text: String): String {
        val root = workspace.root.canonicalFile
        val seen = LinkedHashSet<String>()
        Regex("""(?<![\w@])@([\w./\-]+)""").findAll(text).forEach { seen += it.groupValues[1].trimEnd('.') }
        val sb = StringBuilder(text)
        for (p in seen.take(6)) {
            val f = runCatching { File(root, p).canonicalFile }.getOrNull() ?: continue
            if (!f.path.startsWith(root.path) || !f.isFile || f.length() > 60_000) continue
            val body = runCatching { f.readText() }.getOrNull() ?: continue
            sb.append("\n\n[Файл ").append(p).append("]\n```\n").append(body).append("\n```")
        }
        return sb.toString()
    }

    class SearchHit(val path: String, val line: Int, val text: String)
    class SearchResult(val hits: List<SearchHit>, val message: String)

    suspend fun searchProject(query: String, regex: Boolean, matchCase: Boolean): SearchResult = withContext(Dispatchers.IO) {
        val root = workspace.root
        val re = try {
            Regex(if (regex) query else Regex.escape(query), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) { return@withContext SearchResult(emptyList(), "Неверное выражение: ${e.message}") }
        val hits = ArrayList<SearchHit>()
        var files = 0
        root.walkTopDown().onEnter { it == root || it.name !in noWalk }.forEach { f ->
            if (!f.isFile || f.length() > 600_000 || hits.size >= 300) return@forEach
            files++
            val lines = runCatching { f.readLines() }.getOrNull() ?: return@forEach
            val rel = f.relativeTo(root).invariantSeparatorsPath
            for ((i, l) in lines.withIndex()) if (re.containsMatchIn(l)) { hits += SearchHit(rel, i + 1, l); if (hits.size >= 300) break }
        }
        SearchResult(hits, "Найдено ${hits.size}${if (hits.size >= 300) "+" else ""} в файлах (просмотрено $files)")
    }

    suspend fun snapshotFileText(id: Long, path: String): String? = snapshots.readFile(currentProject, id, path)

    fun readProjectFile(path: String): String? = runCatching { workspace.resolve(path).takeIf { it.isFile }?.readText() }.getOrNull()
    fun writeProjectFile(path: String, text: String) {
        runCatching { workspace.resolve(path).writeText(text) }
        items += UiItem.Notice("Блок правки откатан в $path")
    }

    /** Возвращает проект и чат к моменту перед выбранным сообщением пользователя. */
    fun rewindTo(index: Int) {
        if (running || projectBusy) return
        val msgItem = items.getOrNull(index) as? UiItem.User ?: return
        val allUsers = items.count { it is UiItem.User }
        val k = items.take(index).count { it is UiItem.User }
        projectBusy = true
        scope.launch {
            var note = ""
            try {
                val label = msgItem.text.replace('\n', ' ').take(100)
                val snap = snapshots.list(currentProject).filter { it.label == label }.maxByOrNull { it.id }
                if (snap != null) { snapshots.restore(currentProject, workspace.root, snap.id); note = "Файлы возвращены к состоянию перед этим сообщением. " }
                else note = "Снимка для этого сообщения нет, файлы не менялись. "
                while (items.size > index) items.removeAt(items.lastIndex)
                val userIdx = history.withIndex().filter { (_, m) -> m.role == "user" && m.blocks.none { it is com.pocketcode.llm.Block.ToolResult } }.map { it.index }
                history = if (userIdx.size == allUsers && k < userIdx.size) history.take(userIdx[k]).toMutableList() else { note += "Память модели сброшена."; mutableListOf() }
                agent = null
                items += UiItem.Notice("— Откат к выбранному сообщению. $note—")
                persist()
            } catch (e: Exception) {
                items += UiItem.Error("Откат не удался: ${e.message}")
            } finally {
                projectBusy = false
                snapshotList = snapshots.list(currentProject)
            }
        }
    }

    fun githubRun(action: String, vararg args: Pair<String, String>) {
        if (gitBusy) return
        gitBusy = true
        scope.launch {
            val input = kotlinx.serialization.json.buildJsonObject {
                put("action", kotlinx.serialization.json.JsonPrimitive(action))
                for ((k, v) in args) if (k == "number") put(k, kotlinx.serialization.json.JsonPrimitive(v.toIntOrNull() ?: 0)) else put(k, kotlinx.serialization.json.JsonPrimitive(v))
            }
            gitOut = try {
                Secrets.mask(com.pocketcode.tools.GitHubTool(workspace) { settings.githubToken }.run(input))
            } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Throwable) { "Ошибка: ${x.message ?: x}" }
            gitBusy = false
        }
    }

    // ---------- помощь при ошибках, бюджет, отметки, запуск проекта, черновик ----------
    var ocDailyBudget by mutableIntStateOf(settingsStore.rawGet("ocBudget")?.toIntOrNull() ?: 0)
        private set
    fun setOcDailyBudgetValue(v: Int) { ocDailyBudget = v; settingsStore.rawPut("ocBudget", v.toString()) }
    var ocFreshEach by mutableStateOf(settingsStore.rawGet("ocFresh") == "1")
        private set
    fun setOcFreshEachValue(v: Boolean) { ocFreshEach = v; settingsStore.rawPut("ocFresh", if (v) "1" else "0") }
    var bigInput by mutableStateOf(settingsStore.rawGet("bigInput") == "1")
        private set
    fun setBigInputValue(v: Boolean) { bigInput = v; settingsStore.rawPut("bigInput", if (v) "1" else "0") }

    private fun ocRunsToday(): Int {
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        return ocLongs("ocRuns").count { it >= c.timeInMillis }
    }

    /** Подсказки действий после ошибки: id действий, которые приложение умеет выполнить. */
    fun adviceFor(text: String): List<String> {
        val l = text.lowercase()
        val out = mutableListOf<String>()
        val limit = "429" in l || "limit" in l || "лимит" in l || "rate" in l
        if (limit) {
            out += "later"
            if (ocAccounts.size > 1) out += "switch"
            out += "model"
            if (ocCtxTokens > 60_000) out += "compact"
        }
        if (netErr.containsMatchIn(text) || "сеть" in l) out += "retry"
        if ("контекст" in l || "context" in l || "too long" in l || "token" in l && "exceed" in l) { out += "compact"; out += "reset" }
        if (out.isEmpty()) { out += "retry"; out += "reset" }
        return out.distinct()
    }

    fun ocSwitchNext(): String {
        refreshOcAccounts()
        val list = ocAccounts
        if (list.size < 2) return "Сохранённых аккаунтов меньше двух."
        val next = list[(list.indexOf(ocActiveAccount) + 1).mod(list.size)]
        return doAccountSwitch(next)
    }

    fun lastUserText(): String? = (items.lastOrNull { it is UiItem.User } as? UiItem.User)?.text

    // отмеченные ответы
    var stars by mutableStateOf((settingsStore.rawGet("stars") ?: "").split('¦').filter { it.isNotBlank() })
        private set
    fun isStarred(text: String) = text.take(600) in stars
    fun toggleStar(text: String) {
        val t = text.take(600)
        stars = if (t in stars) stars - t else (stars + t).takeLast(200)
        settingsStore.rawPut("stars", stars.joinToString("¦") { it.replace('¦', ' ') })
    }

    /** Команды запуска, подходящие проекту. */
    fun detectRunCommands(): List<Pair<String, String>> {
        val r = workspace.root
        val out = mutableListOf<Pair<String, String>>()
        fun has(n: String) = File(r, n).exists()
        if (has("package.json")) {
            val scripts = runCatching {
                ((kotlinx.serialization.json.Json.parseToJsonElement(File(r, "package.json").readText()) as kotlinx.serialization.json.JsonObject)["scripts"] as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty()
            }.getOrDefault(emptySet())
            out += "npm install" to "npm install"
            for (s in listOf("start", "dev", "build", "test")) if (s in scripts) out += "npm run $s" to "npm run $s"
        }
        if (has("requirements.txt")) out += "pip install -r requirements.txt" to "pip install -r requirements.txt"
        for (m in listOf("main.py", "app.py", "bot.py", "run.py")) if (has(m)) { out += "python $m" to "python3 $m"; break }
        if (has("tests") || has("pytest.ini") || r.listFiles()?.any { it.name.startsWith("test_") && it.extension == "py" } == true) out += "pytest" to "python3 -m pytest -q"
        if (has("gradlew")) { out += "gradle assembleDebug" to "./gradlew assembleDebug"; out += "gradle test" to "./gradlew test" }
        if (has("Makefile")) out += "make" to "make"
        if (has("Cargo.toml")) { out += "cargo run" to "cargo run"; out += "cargo test" to "cargo test" }
        if (has("go.mod")) { out += "go run ." to "go run ."; out += "go test ./..." to "go test ./..." }
        return out
    }

    // черновик сообщения переживает закрытие приложения
    val draft: String get() = settingsStore.rawGet("draft$tabNo").orEmpty()
    fun saveDraft(t: String) { settingsStore.rawPut("draft$tabNo", t) }

    // ---------- отложенная отправка и статистика по времени суток ----------
    class DelayedTask(val id: String, val at: Long, val project: String, val text: String)

    var delayedTasks by mutableStateOf(loadDelayed())
        private set

    private fun loadDelayed(): List<DelayedTask> = (settingsStore.rawGet("delayed") ?: "").split('¦').filter { it.isNotBlank() }.mapNotNull {
        val p = it.split('\u001f')
        if (p.size < 4) null else DelayedTask(p[0], p[1].toLongOrNull() ?: 0, p[2], p[3])
    }

    private fun saveDelayed(l: List<DelayedTask>) {
        delayedTasks = l
        settingsStore.rawPut("delayed", l.joinToString("¦") { "${it.id}\u001f${it.at}\u001f${it.project}\u001f${it.text.replace('¦', ' ')}" })
    }

    fun scheduleDelayed(text: String, atMs: Long) {
        if (text.isBlank()) return
        val id = System.currentTimeMillis().toString()
        saveDelayed(delayedTasks + DelayedTask(id, atMs, currentProject, text.trim()))
        DelayedScheduler.schedule(app, id, atMs)
    }

    fun cancelDelayed(id: String) {
        DelayedScheduler.cancel(app, id)
        saveDelayed(delayedTasks.filter { it.id != id })
    }

    /** Запускает отложенную задачу (вызывается из фоновой работы). null — нечего запускать или занято. */
    fun runDelayed(id: String): kotlinx.coroutines.Job? {
        val t = delayedTasks.firstOrNull { it.id == id } ?: return null
        if (running) {
            // занято: пробуем ещё раз через 10 минут
            DelayedScheduler.schedule(app, id, System.currentTimeMillis() + 10 * 60_000)
            return null
        }
        saveDelayed(delayedTasks.filter { it.id != id })
        if (!projectDir(t.project).isDirectory) return null
        return scope.launch {
            val answer = runCatching { runRemoteTask(t.project, t.text) }.getOrElse { "Ошибка: ${it.message}" }
            Notifier.alert(app, "Отложенная задача выполнена", answer.take(200))
        }
    }

    /** Доля неудач (лимит, 429, ошибка сервера) запросов OpenCode по окнам времени суток; сеть не считается. */
    private fun ocHourAdd(ok: Boolean) {
        val old = (settingsStore.rawGet("ocHours") ?: "").split(',').filter { it.isNotBlank() }
        settingsStore.rawPut("ocHours", (old + "${System.currentTimeMillis()}:${if (ok) 1 else 0}").takeLast(800).joinToString(","))
    }

    fun ocBestHoursText(): String {
        ocStatsVersion
        val rows = (settingsStore.rawGet("ocHours") ?: "").split(',').mapNotNull {
            val p = it.split(':'); val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val h = java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
            h to (p.getOrNull(1) == "1")
        }
        if (rows.size < 10) return "Пока мало данных (${rows.size} запросов). Нужно хотя бы 10–20 запросов в разное время суток, тогда здесь появится сравнение по часам."
        val sb = StringBuilder()
        val stat = (0 until 8).map { w ->
            val r = rows.filter { it.first / 3 == w }
            Triple(w, r.size, r.count { it.second })
        }
        for ((w, n, ok) in stat) {
            val label = "%02d–%02d".format(w * 3, w * 3 + 3)
            sb.append(label).append(": ").append(if (n == 0) "нет запросов" else "успешно $ok из $n (${ok * 100 / n}%)").append('\n')
        }
        val best = stat.filter { it.second >= 3 }.maxByOrNull { it.third.toDouble() / it.second }
        if (best != null) sb.append("\nЛучшее окно по твоим данным: %02d–%02d (время телефона).".format(best.first * 3, best.first * 3 + 3))
        return sb.toString()
    }

    fun ocBestWindowStart(): Int? {
        val rows = (settingsStore.rawGet("ocHours") ?: "").split(',').mapNotNull {
            val p = it.split(':'); val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY) to (p.getOrNull(1) == "1")
        }
        if (rows.size < 10) return null
        return (0 until 8).map { w -> val r = rows.filter { it.first / 3 == w }; Triple(w, r.size, r.count { it.second }) }
            .filter { it.second >= 3 }.maxByOrNull { it.third.toDouble() / it.second }?.first?.times(3)
    }

    private fun resetMs(m: String): Long? {
        val v = Regex("X-RateLimit-Reset\"?\\s*[:=]\\s*\"?(\\d{10,13})", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (v < 100_000_000_000L) v * 1000 else v
    }

    /** Если сервер сообщил время сброса лимита, ставит повтор последней задачи на это время. */
    private fun scheduleOnReset(text: String, limited: Boolean) {
        if (!limited) return
        val rs = resetMs(text) ?: return
        val left = rs - System.currentTimeMillis()
        if (left < 0 || left > 26 * 3_600_000L) return
        val prompt = lastUserText() ?: return
        scheduleDelayed(prompt, rs + 60_000)
        items += UiItem.Notice("Запрос поставлен на повтор после сброса лимита: " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(rs + 60_000)) + ". Приложение можно закрыть.")
    }

    /** Время сброса лимита из ответа сервера (заголовок X-RateLimit-Reset). */
    private fun resetNote(m: String): String {
        val v = Regex("X-RateLimit-Reset\"?\\s*[:=]\\s*\"?(\\d{10,13})", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)?.toLongOrNull() ?: return ""
        val ms = if (v < 100_000_000_000L) v * 1000 else v
        if (ms < System.currentTimeMillis() - 60_000) return ""
        return "\nЛимит сбросится примерно " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms)) + " (время телефона)."
    }

    // ---------- синхронизация проектов и удалённое управление (через приватный репозиторий GitHub) ----------
    private val syncMgr = SyncManager(app) { projectDir(it) }
    var syncRepo by mutableStateOf(settingsStore.rawGet("syncRepo").orEmpty())
        private set
    var syncProjects by mutableStateOf(settingsStore.rawGet("syncProjects")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet<String>())
        private set
    var syncInterval by mutableIntStateOf(settingsStore.rawGet("syncInterval")?.toIntOrNull() ?: 0)
        private set
    var syncHost by mutableStateOf(settingsStore.rawGet("syncHost") == "1")
        private set
    var syncLive by mutableStateOf(settingsStore.rawGet("syncLive") == "1")
        private set
    var syncStatus by mutableStateOf("")
        private set
    var syncBusy by mutableStateOf(false)
        private set
    var remoteRows by mutableStateOf<List<RemoteRow>>(emptyList())
        private set
    var remoteProjectNames by mutableStateOf<List<String>>(emptyList())
        private set
    private var syncLiveJob: kotlinx.coroutines.Job? = null
    val deviceName: String get() = android.os.Build.MODEL ?: "телефон"

    fun setSyncRepoValue(v: String) { syncRepo = v.trim(); settingsStore.rawPut("syncRepo", syncRepo) }
    fun toggleSyncProject(name: String) {
        syncProjects = if (name in syncProjects) syncProjects - name else syncProjects + name
        settingsStore.rawPut("syncProjects", syncProjects.joinToString(","))
    }
    fun setSyncIntervalValue(min: Int) {
        syncInterval = min
        settingsStore.rawPut("syncInterval", min.toString())
        SyncScheduler.apply(app, if (syncRepo.isBlank()) 0 else min)
    }
    fun setSyncHostValue(v: Boolean) { syncHost = v; settingsStore.rawPut("syncHost", if (v) "1" else "0") }

    private suspend fun runSync(): SyncResult {
        syncBusy = true
        syncStatus = "Синхронизирую…"
        val r = syncMgr.sync(syncRepo, settings.githubToken, syncProjects.toList(), deviceName)
        syncStatus = (if (r.ok) "✓ " else "✗ ") + r.message + "  (" + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) + ")"
        remoteProjectNames = syncMgr.remoteProjects()
        remoteRows = syncMgr.rows()
        syncBusy = false
        return r
    }

    fun syncNow() {
        if (syncBusy) return
        scope.launch { runSync() }
    }

    /** Один цикл: синхронизация и, если телефон исполнитель, выполнение полученных задач. */
    fun syncTick(): kotlinx.coroutines.Job = scope.launch {
        if (syncBusy || syncRepo.isBlank()) return@launch
        val r = runSync()
        if (r.ok && syncHost) processRemote()
    }

    private suspend fun processRemote() {
        while (true) {
            if (running) return
            val t = syncMgr.pending().firstOrNull() ?: return
            if (!projectDir(t.project).isDirectory) {
                syncMgr.writeState(RemoteState(t.id, "error", "На телефоне-исполнителе нет проекта «${t.project}».", deviceName))
                runSync()
                continue
            }
            syncMgr.writeState(RemoteState(t.id, "running", "", deviceName))
            runSync()
            val answer = runCatching { runRemoteTask(t.project, t.prompt) }.getOrElse { "Ошибка: ${it.message}" }
            syncMgr.writeState(RemoteState(t.id, "done", Secrets.mask(answer.take(4000)), deviceName))
            runSync()
        }
    }

    private suspend fun runRemoteTask(project: String, prompt: String): String {
        switchTo(project)
        unattended = true
        try {
            send("[Задача пришла с другого телефона, пользователя рядом нет: подтверждений не будет, небезопасные действия отклоняются. Не пытайся их обойти.]\n\n" + prompt)
            delay(1500)
            val deadline = System.currentTimeMillis() + 25 * 60_000
            while (running && System.currentTimeMillis() < deadline) delay(1500)
        } finally {
            unattended = false
        }
        return (items.lastOrNull { it is UiItem.Assistant } as? UiItem.Assistant)?.text
            ?: (items.lastOrNull { it is UiItem.Error } as? UiItem.Error)?.text ?: "(ответа нет)"
    }

    fun refreshRemote() {
        if (syncBusy) return
        scope.launch { runSync() }
    }

    fun sendRemoteTask(project: String, text: String) {
        if (text.isBlank() || project.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) { syncMgr.addTask(project, text.trim(), deviceName) }
            remoteRows = syncMgr.rows()
            runSync()
        }
    }

    fun clearRemoteFinished() {
        scope.launch {
            withContext(Dispatchers.IO) { syncMgr.clearFinished() }
            remoteRows = syncMgr.rows()
            runSync()
        }
    }

    /** Постоянная проверка очереди раз в 2 минуты (держит приложение в фоне, расходует больше батареи). */
    fun setSyncLiveValue(v: Boolean) {
        syncLive = v
        settingsStore.rawPut("syncLive", if (v) "1" else "0")
        syncLiveJob?.cancel(); syncLiveJob = null
        if (v && !isTab) {
            startService()
            syncLiveJob = scope.launch {
                while (true) {
                    runCatching { syncTick().join() }
                    delay(120_000)
                }
            }
        } else stopService()
    }

    fun resumeSyncLive() { if (!isTab && syncLive && syncLiveJob == null) setSyncLiveValue(true) }

    /** Создаёт приватный репозиторий на GitHub (нужен токен с правом repo) и подставляет его адрес. */
    fun createSyncRepo(name: String) {
        val n = name.trim().ifEmpty { "pocketcode-sync" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        scope.launch {
            syncStatus = "Создаю репозиторий…"
            val msg = withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL("https://api.github.com/user/repos").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer ${settings.githubToken}")
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.use { it.write("""{"name":"$n","private":true,"description":"PocketCode sync"}""".toByteArray()) }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code in 200..299) {
                        val url = (kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["clone_url"]
                            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                        withContext(Dispatchers.Main) { setSyncRepoValue(url) }
                        "✓ Приватный репозиторий создан: $url"
                    } else if (code == 422) "✗ Такой репозиторий уже есть: впиши его адрес вручную (https://github.com/ты/$n.git)."
                    else "✗ GitHub ответил $code. Проверь токен (нужно право repo): " + Secrets.mask(body.take(200))
                } catch (e: Throwable) { "✗ Ошибка: ${e.message}" }
            }
            syncStatus = msg
        }
    }

    // --- тема, журнал запросов, закрепления, запасная модель, автосжатие ---
    var themeMode by mutableStateOf(settingsStore.rawGet("themeMode") ?: "system")
        private set
    fun setThemeModeValue(v: String) { themeMode = v; settingsStore.rawPut("themeMode", v) }

    var ocFallbackModel by mutableStateOf(settingsStore.rawGet("ocFallback").orEmpty())
        private set
    fun setOcFallbackValue(v: String) { ocFallbackModel = v.trim(); settingsStore.rawPut("ocFallback", v.trim()) }
    private var ocFbUsed = false

    /** Пауза перед повтором при 429 (слишком частые запросы), минуты. */
    var ocRateRetryMin by mutableIntStateOf(settingsStore.rawGet("ocRateRetryMin")?.toIntOrNull() ?: 15)
        private set
    fun setOcRateRetryValue(v: Int) { ocRateRetryMin = v; settingsStore.rawPut("ocRateRetryMin", v.toString()) }

    var ocAutoCompact by mutableIntStateOf(settingsStore.rawGet("ocAutoCompact")?.toIntOrNull() ?: 0)
        private set
    fun setOcAutoCompactValue(v: Int) { ocAutoCompact = v; settingsStore.rawPut("ocAutoCompact", v.toString()) }
    private var ocCompactSoon = false

    var ocPins by mutableStateOf(settingsStore.rawGet("ocPins")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet<String>())
        private set
    fun toggleOcPin(id: String) {
        ocPins = if (id in ocPins) ocPins - id else ocPins + id
        settingsStore.rawPut("ocPins", ocPins.joinToString(","))
    }
    var ocTitles by mutableStateOf<Map<String, String>>(
        runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(settingsStore.rawGet("ocTitles") ?: "{}") }.getOrDefault(emptyMap()),
    )
        private set
    fun renameOc(id: String, title: String) {
        val m = ocTitles.toMutableMap()
        if (title.isBlank()) m.remove(id) else m[id] = title.trim().take(80)
        ocTitles = m
        settingsStore.rawPut("ocTitles", kotlinx.serialization.json.Json.encodeToString<Map<String, String>>(m))
    }
    fun ocTitleOf(s: OcSession) = ocTitles[s.id] ?: s.title

    /** Журнал запросов к OpenCode: время, аккаунт, модель, исход, размер контекста, длительность. */
    private fun ocLogAdd(outcome: String, code: Int, startMs: Long) {
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
        val line = listOf(
            fmt.format(java.util.Date()), ocActiveAccount.ifBlank { "—" }, ocModel.ifBlank { "по умолчанию" },
            outcome + (if (code != 0) " (код $code)" else ""), if (ocCtxTokens > 0) "ctx ≈ ${ocCtxTokens / 1000}к" else "ctx ?", "${(System.currentTimeMillis() - startMs) / 1000} с",
        ).joinToString(" | ")
        val old = (settingsStore.rawGet("ocLog") ?: "").split('¦').filter { it.isNotBlank() }
        settingsStore.rawPut("ocLog", (old + line).takeLast(120).joinToString("¦"))
        ocStatsVersion++
    }
    fun ocLogLines(): List<String> { ocStatsVersion; return (settingsStore.rawGet("ocLog") ?: "").split('¦').filter { it.isNotBlank() }.reversed() }
    fun ocLogClear() { settingsStore.rawPut("ocLog", ""); ocStatsVersion++ }

    /** Проверка своего провайдера прямым запросом (без OpenCode): показывает код ответа и текст. */
    suspend fun ocCustomTest(id: String): String = withContext(Dispatchers.IO) {
        try {
            val prov = (ocConfigRead()["provider"] as? kotlinx.serialization.json.JsonObject)?.get(id) as? kotlinx.serialization.json.JsonObject ?: return@withContext "Провайдер не найден."
            val opts = prov["options"] as? kotlinx.serialization.json.JsonObject
            val url = (opts?.get("baseURL") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@withContext "Нет адреса."
            val key = (opts["apiKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val model = (prov["models"] as? kotlinx.serialization.json.JsonObject)?.keys?.firstOrNull() ?: return@withContext "Нет модели."
            val conn = java.net.URL(url.trimEnd('/') + "/chat/completions").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000; conn.readTimeout = 40_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.doOutput = true
            val body = kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive(model))
                put("max_tokens", kotlinx.serialization.json.JsonPrimitive(8))
                put("messages", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive("user")); put("content", kotlinx.serialization.json.JsonPrimitive("Ответь одним словом: ок"))
                    })
                })
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Secrets.mask("HTTP $code" + (if (code in 200..299) ": работает.\n" else ": не работает.\n") + text.take(300))
        } catch (e: Throwable) {
            "Ошибка соединения: ${e.message}"
        }
    }

    // --- размер контекста, сжатие, автопереключение аккаунтов ---
    /** Примерный размер контекста беседы OpenCode в токенах (точный из событий или оценка по экспорту). */
    var ocCtxTokens by mutableIntStateOf(0)
    private var ocStepCtx = 0
    private var ocCompact = false
    var ocAutoSwitch by mutableStateOf(settingsStore.rawGet("ocAutoSwitch") == "1")
        private set
    fun setOcAutoSwitchValue(v: Boolean) { ocAutoSwitch = v; settingsStore.rawPut("ocAutoSwitch", if (v) "1" else "0") }
    private var ocSwitchTries = 0
    private var ocRetryDelay = 15_000L

    /** Просит OpenCode кратко пересказать беседу, затем начинает новую беседу с этим пересказом. */
    fun compactOcSession() {
        if (running || !linuxActive) return
        ocCompact = true
        sendViaOpenCode("Кратко и по делу перескажи нашу беседу для переноса в новую сессию (не больше 300 слов): цель, что уже сделано, текущее состояние, что осталось. Инструменты не используй.")
    }

    private fun accStats(): MutableMap<String, List<Long>> = runCatching {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, List<Long>>>(settingsStore.rawGet("ocAccStats") ?: "{}").toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun accBump(hit: Boolean) {
        val k = ocActiveAccount.ifBlank { "—" }
        val m = accStats()
        val o = m[k] ?: listOf(0L, 0L)
        m[k] = if (hit) listOf(o[0], o[1] + 1) else listOf(o[0] + 1, o[1])
        settingsStore.rawPut("ocAccStats", kotlinx.serialization.json.Json.encodeToString<Map<String, List<Long>>>(m))
        ocStatsVersion++
    }

    fun accStatsText(name: String): String {
        ocStatsVersion
        val o = accStats()[name] ?: return "запросов 0, лимитов 0"
        return "запросов ${o[0]}, лимитов ${o[1]}"
    }

    /** Повтор последнего сообщения после ошибки. */
    fun retryLast() {
        if (running) return
        val i = items.indexOfLast { it is UiItem.User }
        if (i < 0) return
        val text = (items[i] as UiItem.User).text
        while (items.size > i) items.removeAt(items.lastIndex)
        lastRunFailed = false
        send(text)
    }

    /** Экспорт беседы OpenCode в Markdown-файл. */
    fun exportOcSessionTo(s: OcSession, uri: Uri) {
        if (ocSessionsBusy) return
        ocSessionsBusy = true
        ocSessionsMsg = "Готовлю файл…"
        scope.launch {
            val raw = runCatching { ocExport(s.id, 180) }.getOrDefault("")
            val md = withContext(Dispatchers.Default) {
                val texts = try {
                    val l = mutableListOf<Pair<String?, String>>()
                    collectTexts(kotlinx.serialization.json.Json.parseToJsonElement(jsonStart(raw)), null, l); l
                } catch (e: Throwable) { scanTexts(raw) }
                val sb = StringBuilder("# Беседа OpenCode: ${s.title}\n\n")
                for ((role, t) in texts) sb.append(if (role == "user") "## 🧑 Вы\n\n" else "## 🤖 Модель\n\n").append(t).append("\n\n")
                Secrets.mask(sb.toString())
            }
            val ok = withContext(Dispatchers.IO) { runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) } }.isSuccess }
            ocSessionsMsg = if (ok) "Беседа сохранена в Markdown." else "Не удалось сохранить файл."
            ocSessionsBusy = false
        }
    }

    // --- состояние и статистика режима OpenCode ---
    var ocCards by mutableStateOf(settingsStore.rawGet("ocCards") == "1")
    fun setOcCardsValue(v: Boolean) { ocCards = v; settingsStore.rawPut("ocCards", if (v) "1" else "0") }

    private fun ocLongs(key: String): List<Long> = (settingsStore.rawGet(key) ?: "").split(',').mapNotNull { it.toLongOrNull() }
    private fun ocAddLong(key: String, v: Long, keep: Int) = settingsStore.rawPut(key, (ocLongs(key) + v).takeLast(keep).joinToString(","))

    var ocStatsVersion by mutableIntStateOf(0)
        private set

    /** Сводка по запросам к OpenCode: помогает понять, где у бесплатного лимита порог. */
    fun ocStatsText(): String {
        ocStatsVersion
        val now = System.currentTimeMillis()
        val runs = ocLongs("ocRuns")
        val hits = ocLongs("ocHits")
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.append("Запросов за 24 часа: ").append(runs.count { now - it < 86_400_000L })
            .append(", за 7 дней: ").append(runs.count { now - it < 7 * 86_400_000L }).append('\n')
        if (hits.isEmpty()) sb.append("Лимит пока не срабатывал.")
        else {
            sb.append("Лимит срабатывал: ").append(hits.takeLast(3).joinToString { fmt.format(java.util.Date(it)) }).append('\n')
            val last = hits.last()
            val prev = hits.dropLast(1).lastOrNull() ?: 0L
            sb.append("Запросов между предыдущим и последним срабатыванием: ").append(runs.count { it > prev && it <= last })
        }
        return sb.toString()
    }

    // --- ключи OpenCode (auth.json внутри Linux) ---
    private val ocAuthFile: File get() = File(linuxEnv.rootfs, "root/.local/share/opencode/auth.json")
    var ocProviders by mutableStateOf<List<String>>(emptyList())
        private set

    private fun ocAuthRead(): MutableMap<String, kotlinx.serialization.json.JsonElement> = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(ocAuthFile.readText()) as kotlinx.serialization.json.JsonObject).toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun refreshOcProviders() { ocProviders = ocAuthRead().keys.toList() }

    fun ocAuthSet(provider: String, key: String): String {
        val p = provider.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "")
        if (p.isEmpty() || key.isBlank()) return "Укажи провайдера и ключ."
        if (!linuxEnv.isInstalled()) return "Сначала установи Linux."
        return try {
            val m = ocAuthRead()
            m[p] = kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("api"))
                put("key", kotlinx.serialization.json.JsonPrimitive(key.trim()))
            }
            ocAuthFile.parentFile?.mkdirs()
            ocAuthFile.writeText(kotlinx.serialization.json.JsonObject(m).toString())
            runCatching { ocAuthFile.setReadable(false, false); ocAuthFile.setReadable(true, true); ocAuthFile.setWritable(true, true) }
            Secrets.known = Secrets.known + key.trim()
            refreshOcProviders()
            "Ключ для «$p» сохранён."
        } catch (e: Throwable) {
            "Не удалось записать: ${e.message}"
        }
    }

    fun ocAuthRemove(provider: String) {
        val m = ocAuthRead()
        m.remove(provider)
        runCatching { ocAuthFile.writeText(kotlinx.serialization.json.JsonObject(m).toString()) }
        refreshOcProviders()
    }

    // --- свой провайдер OpenCode (OpenAI-совместимый адрес): ~/.config/opencode/opencode.json ---
    private val ocConfigFile: File get() = File(linuxEnv.rootfs, "root/.config/opencode/opencode.json")
    var ocCustomProviders by mutableStateOf<List<String>>(emptyList())
        private set

    private fun ocConfigRead(): MutableMap<String, kotlinx.serialization.json.JsonElement> = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(ocConfigFile.readText()) as kotlinx.serialization.json.JsonObject).toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun refreshOcCustom() {
        val prov = ocConfigRead()["provider"] as? kotlinx.serialization.json.JsonObject
        ocCustomProviders = prov?.entries?.filter { ((it.value as? kotlinx.serialization.json.JsonObject)?.get("options") as? kotlinx.serialization.json.JsonObject)?.get("baseURL") != null }?.map { it.key }.orEmpty()
    }

    fun ocCustomSet(idIn: String, baseUrl: String, model: String, key: String): String {
        val id = idIn.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")
        val url = baseUrl.trim().trimEnd('/')
        val m = model.trim()
        if (id.isEmpty() || !url.startsWith("http") || m.isEmpty()) return "Нужны название (латиницей), адрес https://… и модель."
        if (!linuxEnv.isInstalled()) return "Сначала установи Linux."
        return try {
            val cfg = ocConfigRead()
            val prov = ((cfg["provider"] as? kotlinx.serialization.json.JsonObject)?.toMutableMap()) ?: mutableMapOf()
            val old = prov[id] as? kotlinx.serialization.json.JsonObject
            val oldModels = (old?.get("models") as? kotlinx.serialization.json.JsonObject)?.toMutableMap() ?: mutableMapOf()
            oldModels[m] = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(m)) }
            val oldKey = ((old?.get("options") as? kotlinx.serialization.json.JsonObject)?.get("apiKey") as? kotlinx.serialization.json.JsonPrimitive)?.content
            val useKey = key.trim().ifEmpty { oldKey.orEmpty() }
            prov[id] = kotlinx.serialization.json.buildJsonObject {
                put("npm", kotlinx.serialization.json.JsonPrimitive("@ai-sdk/openai-compatible"))
                put("name", kotlinx.serialization.json.JsonPrimitive(id))
                put("options", kotlinx.serialization.json.buildJsonObject {
                    put("baseURL", kotlinx.serialization.json.JsonPrimitive(url))
                    if (useKey.isNotEmpty()) put("apiKey", kotlinx.serialization.json.JsonPrimitive(useKey))
                })
                put("models", kotlinx.serialization.json.JsonObject(oldModels))
            }
            cfg["provider"] = kotlinx.serialization.json.JsonObject(prov)
            if (!cfg.containsKey("\$schema")) cfg["\$schema"] = kotlinx.serialization.json.JsonPrimitive("https://opencode.ai/config.json")
            ocConfigFile.parentFile?.mkdirs()
            ocConfigFile.writeText(kotlinx.serialization.json.JsonObject(cfg).toString())
            runCatching { ocConfigFile.setReadable(false, false); ocConfigFile.setReadable(true, true); ocConfigFile.setWritable(true, true) }
            if (useKey.isNotEmpty()) Secrets.known = Secrets.known + useKey
            setOcModelValue("$id/$m")
            refreshOcCustom()
            "Провайдер «$id» сохранён, модель выбрана: $id/$m. Включи режим OpenCode и пиши."
        } catch (e: Throwable) { "Не удалось записать: ${e.message}" }
    }

    fun ocCustomRemove(id: String) {
        val cfg = ocConfigRead()
        val prov = ((cfg["provider"] as? kotlinx.serialization.json.JsonObject)?.toMutableMap()) ?: return
        prov.remove(id)
        cfg["provider"] = kotlinx.serialization.json.JsonObject(prov)
        runCatching { ocConfigFile.writeText(kotlinx.serialization.json.JsonObject(cfg).toString()) }
        if (ocModel.startsWith("$id/")) setOcModelValue("")
        refreshOcCustom()
    }

    // --- аккаунты OpenCode: копии auth.json. Беседы лежат отдельно (хранилище OpenCode), смена аккаунта их не трогает ---
    private val ocAccDir: File get() = File(app.filesDir, "ocAccounts")
    var ocAccounts by mutableStateOf<List<String>>(emptyList())
        private set
    var ocActiveAccount by mutableStateOf(settingsStore.rawGet("ocAccount").orEmpty())
        private set

    private fun accName(n: String) = n.trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "").take(40)

    fun refreshOcAccounts() {
        ocAccounts = ocAccDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted().orEmpty()
        refreshOcProviders()
    }

    private fun setActiveAcc(n: String) { ocActiveAccount = n; settingsStore.rawPut("ocAccount", n) }

    /** Сохраняет текущий вход (auth.json) под именем. */
    fun ocAccountSave(name: String): String {
        val n = accName(name)
        if (n.isEmpty()) return "Введи название аккаунта."
        if (!ocAuthFile.exists()) return "Сейчас в OpenCode нет входа: добавь ключ или войди (☰ → Терминал → «Войти»)."
        return try {
            ocAccDir.mkdirs()
            ocAuthFile.copyTo(File(ocAccDir, "$n.json"), overwrite = true)
            setActiveAcc(n)
            refreshOcAccounts()
            "Аккаунт «$n» сохранён."
        } catch (e: Throwable) { "Не удалось сохранить: ${e.message}" }
    }

    private fun syncActiveAcc() {
        // Текущий вход мог обновиться (токены): сохраняем его обратно в активный аккаунт
        if (ocActiveAccount.isNotEmpty() && ocAuthFile.exists() && File(ocAccDir, "$ocActiveAccount.json").exists())
            runCatching { ocAuthFile.copyTo(File(ocAccDir, "$ocActiveAccount.json"), overwrite = true) }
    }

    fun ocAccountSwitch(name: String): String {
        if (running) return "Дождись конца задачи."
        return doAccountSwitch(name)
    }

    private fun doAccountSwitch(name: String): String {
        val src = File(ocAccDir, "$name.json")
        if (!src.exists()) return "Аккаунт не найден."
        return try {
            syncActiveAcc()
            ocAuthFile.parentFile?.mkdirs()
            src.copyTo(ocAuthFile, overwrite = true)
            runCatching { ocAuthFile.setReadable(false, false); ocAuthFile.setReadable(true, true); ocAuthFile.setWritable(true, true) }
            setActiveAcc(name)
            refreshOcAccounts()
            "Выбран аккаунт «$name». Беседы OpenCode остались на месте."
        } catch (e: Throwable) { "Не удалось переключить: ${e.message}" }
    }

    /** Выход: вход убирается из OpenCode, но сохранённый аккаунт остаётся в списке. */
    fun ocLogout(): String {
        if (running) return "Дождись конца задачи."
        syncActiveAcc()
        runCatching { ocAuthFile.delete() }
        setActiveAcc("")
        refreshOcAccounts()
        return "Вышел из OpenCode. Беседы сохранены; можно выбрать другой аккаунт или работать без входа."
    }

    fun ocAccountDelete(name: String) {
        runCatching { File(ocAccDir, "$name.json").delete() }
        if (ocActiveAccount == name) setActiveAcc("")
        refreshOcAccounts()
    }

    // --- список моделей OpenCode ---
    var ocModelList by mutableStateOf<List<String>>(emptyList())
        private set
    var ocModelsBusy by mutableStateOf(false)
        private set

    fun loadOcModels() {
        if (ocModelsBusy || !linuxActive) return
        ocModelsBusy = true
        scope.launch {
            val raw = runCatching { runFull("opencode models", 90) }.getOrDefault("")
            ocModelList = raw.lines().map { it.trim() }.filter { it.contains('/') && !it.contains(' ') }.sorted()
            ocModelsBusy = false
        }
    }

    private val netErr = Regex("EAI_AGAIN|ENOTFOUND|ETIMEDOUT|ECONNRESET|ECONNREFUSED|fetch failed|socket hang up|Unable to connect|network is unreachable", RegexOption.IGNORE_CASE)
    private val rate429 = Regex("\\b429\\b|rate.?limit|too many requests", RegexOption.IGNORE_CASE)
    private var ocRetries = 0

    /** Разбор одной строки `opencode run --format json`: возвращает true, если строка обработана как событие. */
    // токены текущего запуска OpenCode (из событий step-finish, если OpenCode их прислал)
    private var ocTokIn = 0; private var ocTokOut = 0; private var ocTokCache = 0; private var ocTokSeen = false

    private fun handleOcEvent(line: String, parts: MutableMap<String, Int>): Boolean {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        } ?: run {
            // Строка JSON оборвана или повреждена: достаём что можно, сырой текст в чат не выводим
            val tool = Regex("\"tool\":\"(\\w+)\"").find(line)?.groupValues?.get(1)
            if (tool != null) {
                val cmd = Regex("\"(?:command|filePath|path|pattern|url)\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.replace("\\n", " ")?.replace("\\\"", "\"").orEmpty()
                val failed = Regex("\"status\":\"error\"").containsMatchIn(line)
                items += UiItem.Tool(tool, Secrets.mask(cmd.take(300)), "(вывод слишком длинный или оборван, показать нельзя)", failed)
            }
            return true
        }
        fun str(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val type = str(obj, "type")
        val part = obj["part"] as? kotlinx.serialization.json.JsonObject
        if (part == null) {
            if (type == "error") {
                val msg = ((obj["error"] as? kotlinx.serialization.json.JsonObject)?.let { str(it, "message") ?: it.toString() }) ?: line
                items += UiItem.Error(Secrets.mask(msg.take(800)))
                lastRunFailed = true
            }
            return true
        }
        val id = str(part, "id") ?: (str(part, "type") + items.size)
        when (str(part, "type")) {
            "step-finish", "step_finish" -> {
                val tk = part["tokens"] as? kotlinx.serialization.json.JsonObject
                fun n(o: kotlinx.serialization.json.JsonObject?, k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
                if (tk != null) {
                    ocTokIn += n(tk, "input"); ocTokOut += n(tk, "output") + n(tk, "reasoning")
                    ocTokCache += n(tk["cache"] as? kotlinx.serialization.json.JsonObject, "read")
                    ocTokSeen = true
                    ocStepCtx = n(tk, "input") + n(tk["cache"] as? kotlinx.serialization.json.JsonObject, "read")
                }
            }
            "reasoning" -> {
                val text = str(part, "text").orEmpty()
                if (text.isNotBlank()) ocLastReasoning = Secrets.mask(text).takeLast(2000)
            }
            "text" -> {
                val text = str(part, "text").orEmpty()
                if (text.isBlank()) return true
                val at = parts[id]
                if (at != null && at < items.size) items[at] = UiItem.Assistant(Secrets.mask(text))
                else { items += UiItem.Assistant(Secrets.mask(text)); parts[id] = items.lastIndex }
            }
            "tool" -> {
                val state = part["state"] as? kotlinx.serialization.json.JsonObject
                val status = str(state, "status")
                val input = state?.get("input") as? kotlinx.serialization.json.JsonObject
                val desc = str(input, "command") ?: str(input, "filePath") ?: str(input, "path") ?: str(input, "pattern") ?: str(input, "url") ?: (input?.toString() ?: "")
                val done = status == "completed" || status == "error"
                val out = if (done) Secrets.mask(str(state, "output") ?: str(state, "error") ?: "") else null
                val card = UiItem.Tool(str(part, "tool") ?: "tool", Secrets.mask(desc.take(300)), out, status == "error")
                val at = parts[id]
                if (at != null && at < items.size) items[at] = card else { items += card; parts[id] = items.lastIndex }
            }
        }
        return true
    }

    private fun sendViaOpenCode(prompt: String, isRetry: Boolean = false, shown: String = prompt) {
        if (!linuxActive) {
            items += UiItem.Error("Режим OpenCode работает только в Linux: включи его и установи в разделе «Проекты», затем «Терминал» → «Установить OpenCode».")
            return
        }
        if (!isRetry && ocDailyBudget > 0) {
            val n = ocRunsToday()
            if (n >= ocDailyBudget) {
                items += UiItem.Notice("Дневной бюджет запросов OpenCode исчерпан ($n из $ocDailyBudget). Измени его в «Диалоги OpenCode» или отправь позже («Отправить позже» в меню ⋮).")
                return
            }
            if (n + 1 >= ocDailyBudget * 8 / 10) items += UiItem.Notice("Осталось запросов на сегодня: ${ocDailyBudget - n - 1}.")
        }
        if (!isRetry && ocFreshEach) { ocSessionId = null; ocSessionTitle = ""; setOcContinue(false) }
        if (currentId == null) currentId = UUID.randomUUID().toString()
        if (!isRetry) { items += UiItem.User(shown, 0); ocRetries = 0; ocSwitchTries = 0; ocFbUsed = false; ocAddLong("ocRuns", System.currentTimeMillis(), 300); accBump(false) }
        val reply = UiItem.Assistant("⏳ OpenCode запускается и думает… (в Linux на телефоне первый ответ может идти минуту)")
        items += reply
        val idx = items.lastIndex
        var placeholder = true
        streamingIndex = null
        lastRunFailed = false
        running = true
        persist()
        startService()
        Notifier.clearAlert(app)
        val sentPrompt = ocCarry?.let { it + "\n---\n" + prompt }?.also { ocCarry = null } ?: prompt
        val q = "'" + sentPrompt.replace("'", "'\\''") + "'"
        val model = ocModel.trim().takeIf { it.isNotEmpty() }?.let { " -m '" + it.replace("'", "") + "'" } ?: ""
        // /tmp разрешён всегда (временные файлы), остальное вне проекта OpenCode по-прежнему отклоняет
        val perm = "export OPENCODE_PERMISSION='{\"external_directory\":{\"/tmp\":\"allow\",\"/tmp/**\":\"allow\"}}'; "
        val resume = when {
            ocSessionId != null -> " --session '" + ocSessionId!!.replace("'", "") + "'"
            ocContinue || isRetry -> " --continue"
            else -> ""
        }
        val cards = ocCards
        val cmd = perm + "opencode run" + (if (cards) " --format json" else "") + resume + model + " " + q
        val myRun = ++runId
        val runStart = System.currentTimeMillis()
        var retryScheduled = false
        ocTokIn = 0; ocTokOut = 0; ocTokCache = 0; ocTokSeen = false; ocStepCtx = 0; ocLastReasoning = ""
        job = scope.launch {
            var proc: Process? = null
            try {
                if (!isRetry) runCatching { snapshots.create(currentProject, workspace.root, prompt) }
                // Зависшие процессы opencode от прошлых запусков держат блокировку базы и новые запуски молчат: убираем их (если не идёт другая вкладка)
                if ((app as? com.pocketcode.PocketCodeApp)?.tabs?.any { it !== this@ChatController && it.running } != true) {
                    runCatching { withContext(Dispatchers.IO) { linuxShell.run("pkill -9 -f '[o]pencode' ; true", workspace.root, 8) } }
                }
                val pb = linuxShell.builder(cmd, workspace.root).redirectErrorStream(true)
                proc = pb.start()
                proc.outputStream.close() // иначе opencode ждёт ввода из stdin и не стартует
                val p = proc
                val buf = StringBuilder()
                val parts = mutableMapOf<String, Int>()
                var badJson = false
                var lastOut = System.currentTimeMillis()
                var idleItem: UiItem.Notice? = null
                // Если OpenCode молчит (например, сервер ответил 429 и он ждёт повтора), показываем, что приложение живо
                val watchdog = launch {
                    while (true) {
                        kotlinx.coroutines.delay(5_000)
                        val idle = (System.currentTimeMillis() - lastOut) / 1000
                        if (idle >= 20) {
                            val n = UiItem.Notice("OpenCode молчит уже $idle с. Возможно, сервер перегружен или вернул 429 (слишком много запросов), и он ждёт повтора. Если долго ничего нет, нажми «Остановить» и повтори.")
                            val old = idleItem
                            val at = if (old == null) -1 else items.indexOfFirst { it === old }
                            if (at >= 0) items[at] = n else items += n
                            idleItem = n
                        }
                    }
                }
                val reader = launch(Dispatchers.IO) {
                  try {
                    val br = p.inputStream.bufferedReader()
                    while (true) {
                        val line = br.readLine() ?: break
                        val clean = Secrets.mask(ansi.replace(line, "").substringAfterLast('\r'))
                        lastOut = System.currentTimeMillis()
                        idleItem?.let { old -> withContext(Dispatchers.Main) { items.removeAll { it === old } }; idleItem = null }
                        if (clean.isBlank() && buf.isEmpty()) continue
                        buf.append(clean).append('\n')
                        if (cards && !clean.trimStart().startsWith("{") && badJson) continue
                        badJson = false
                        if (cards && clean.trimStart().startsWith("{")) {
                            val handled = withContext(Dispatchers.Main) {
                                if (placeholder && idx < items.size) { items.removeAt(idx); placeholder = false }
                                handleOcEvent(clean.trim(), parts)
                            }
                            if (!clean.trimEnd().endsWith("}")) badJson = true
                            if (handled) continue
                        }
                        val snapshot = buf.toString().takeLast(60_000)
                        withContext(Dispatchers.Main) { if (placeholder && idx < items.size) items[idx] = UiItem.Assistant(snapshot) else if (!cards) {} else items += UiItem.Notice(clean.take(300)) }
                    }
                  } catch (e: java.io.IOException) {
                    // Процесс остановлен: поток закрыт, это не ошибка
                  }
                }
                try {
                    reader.join()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    p.destroyForcibly()
                    throw e
                } finally {
                    watchdog.cancel()
                    idleItem?.let { old -> items.removeAll { it === old } }
                }
                val code = withContext(Dispatchers.IO) { p.waitFor() }
                if (placeholder && cards && idx < items.size && (items[idx] as? UiItem.Assistant)?.text?.startsWith("⏳") == true) items.removeAt(idx)
                setOcContinue(true)
                val text = buf.toString()
                // Токены: точные из событий OpenCode, иначе оценка по длине текста (≈3 символа на токен)
                if (ocTokSeen) recordUsage(ocTokIn, ocTokOut, ocTokCache)
                else if (code == 0 && text.isNotBlank()) recordUsage(prompt.length / 3 + 1, text.length / 3 + 1, 0)
                if (ocTokSeen && ocStepCtx > 0) ocCtxTokens = ocStepCtx
                val freeLimit = text.contains("Free usage limit", ignoreCase = true)
                val rate = !freeLimit && rate429.containsMatchIn(text)
                val limited = freeLimit || rate
                if (code == 0 && ocCompact) {
                    ocCompact = false
                    val summary = (items.lastOrNull { it is UiItem.Assistant } as? UiItem.Assistant)?.text.orEmpty()
                    if (summary.isNotBlank()) {
                        ocSessionId = null; ocSessionTitle = ""; setOcContinue(false)
                        ocCarry = "[Сводка предыдущей беседы, сессия сжата]\n" + summary.take(6000)
                        ocCtxTokens = 0
                        items += UiItem.Notice("— Беседа сжата: следующий запрос начнёт новую сессию с этой сводкой —")
                    }
                } else if (code == 0 && ocCtxTokens > 100_000) {
                    items += UiItem.Notice("Контекст беседы уже ≈ ${ocCtxTokens / 1000} тыс. токенов: запросы тяжёлые и быстрее упираются в лимиты. Можно «Сжать» беседу или сбросить сессию.")
                }
                if (code != 0) ocCompact = false
                if (code == 0 && ocAutoCompact > 0 && ocCtxTokens > ocAutoCompact && !ocCompact) ocCompactSoon = true
                if (code == 0 || !netErr.containsMatchIn(text)) ocHourAdd(code == 0)
                ocLogAdd(when { code == 0 -> "ок"; freeLimit -> "лимит"; rate -> "429"; netErr.containsMatchIn(text) -> "сеть"; else -> "ошибка" }, code, runStart)
                if (code != 0 && limited) accBump(true)
                if (code != 0 && ocAutoSwitch && limited && (freeLimit || ocRetries >= 4)) {
                    refreshOcAccounts()
                    val list = ocAccounts
                    if (list.size > 1 && ocSwitchTries < list.size - 1) {
                        val cur = list.indexOf(ocActiveAccount)
                        val next = list[(cur + 1).mod(list.size)]
                        ocSwitchTries++
                        doAccountSwitch(next)
                        retryScheduled = true
                        ocRetryDelay = 3_000L
                        items += UiItem.Notice("Лимит на предыдущем аккаунте. Переключился на «$next» и повторяю запрос.")
                    }
                }
                if (!retryScheduled && code != 0 && limited && (freeLimit || ocRetries >= 4) && ocFallbackModel.isNotBlank() && ocModel != ocFallbackModel && !ocFbUsed) {
                    ocFbUsed = true
                    setOcModelValue(ocFallbackModel)
                    retryScheduled = true
                    ocRetryDelay = 3_000L
                    items += UiItem.Notice("Лимит на выбранной модели. Переключился на запасную «$ocFallbackModel» и повторяю запрос.")
                }
                if (retryScheduled) {
                } else if (code != 0 && (netErr.containsMatchIn(text) || rate) && ocRetries < 4) {
                    ocRetries++
                    retryScheduled = true
                    ocRetryDelay = if (rate) ocRateRetryMin * 60_000L else 15_000L
                    items += UiItem.Notice((if (rate) "Сервер просит подождать (429, слишком частые запросы)." else "Сеть недоступна (${netErr.find(text)?.value}).") + " Повтор через ${if (ocRetryDelay >= 60_000) "${ocRetryDelay / 60_000} мин" else "${ocRetryDelay / 1000} с"}, попытка $ocRetries из 4.")
                } else if (code != 0) {
                    lastRunFailed = true
                    if (freeLimit) { ocAddLong("ocHits", System.currentTimeMillis(), 50); ocStatsVersion++ }
                    scheduleOnReset(text, limited)
                    items += UiItem.Error(
                        "OpenCode завершился с кодом $code." + resetNote(text) +
                            (if (text.contains("Free usage limit", ignoreCase = true)) "\n\n💡 Это лимит бесплатных моделей на сервере OpenCode, а не ошибка приложения. Размер лимита и время сброса OpenCode не сообщает. Варианты: подождать и попробовать позже, оформить их платный тариф (opencode.ai/go) или переключиться на другого провайдера: Gemini, OpenRouter :free, Groq, NVIDIA (☰ → Настройки → Шаблоны провайдеров), выключив режим OpenCode." else "") +
                            (if (text.isBlank() || text.contains("not found")) "\n\n💡 Похоже, OpenCode не установлен: ☰ → Терминал → «Установить OpenCode». Для его бесплатных моделей может понадобиться вход: ☰ → Диалоги OpenCode → «Ключ OpenCode»." else ""),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastRunFailed = true
                items += UiItem.Error("Не удалось запустить OpenCode: ${e.message ?: e}")
            } finally {
                proc?.destroyForcibly()
                if (myRun == runId) {
                    finishRun()
                    if (retryScheduled) {
                        val retryId = ++runId
                        running = true
                        startService()
                        job = scope.launch {
                            kotlinx.coroutines.delay(ocRetryDelay)
                            if (retryId == runId) { running = false; sendViaOpenCode("Продолжай с того места, где остановился.", isRetry = true) }
                        }
                    } else {
                        val next = if (!lastRunFailed && queue.isNotEmpty()) queue.removeAt(0).also { saveQueue() } else null
                        if (ocCompactSoon && next == null) { ocCompactSoon = false; compactOcSession() }
                        else if (next != null) send(next)
                        else if (!appVisible) Notifier.alert(app, "OpenCode закончил", (items.lastOrNull { it is UiItem.Assistant } as? UiItem.Assistant)?.text?.takeLast(200) ?: "Готово", continueTab = tabNo)
                    }
                }
            }
        }
    }

    fun send(text: String, auto: Boolean = false) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && pendingImages.isEmpty()) || running) return
        if (openCodeMode && trimmed.isNotEmpty() && !trimmed.startsWith("/")) { sendViaOpenCode(mentionExpand(extensions.expand(trimmed) ?: trimmed), shown = trimmed); return }
        if (settings.apiKey.isBlank()) {
            items += UiItem.Error("Сначала укажи API-ключ в настройках (шестерёнка сверху).")
            return
        }
        when (trimmed.lowercase()) {
            "/clear", "/reset" -> { resetContext(); return }
            "/new" -> { newChat(); return }
        }
        val expanded = extensions.expand(trimmed)
        if (currentId == null) currentId = UUID.randomUUID().toString()
        val current = ensureAgent()
        if (auto) autoChain++ else autoChain = 0
        val planNote = if (planMode) "[РЕЖИМ ПЛАНА: пока ничего не меняй и не запускай. Изучи код (чтение, поиск), затем вызови present_plan с планом и жди одобрения.]\n\n" else ""
        limitHit = false
        val images = pendingImages
        pendingImages = emptyList()
        items += UiItem.User(trimmed, images.size)
        streamingIndex = null
        lastRunFailed = false
        running = true
        persist()
        startService()
        Notifier.clearAlert(app)

        val myRun = ++runId
        job = scope.launch {
            try {
                val snap = try {
                    snapshots.create(currentProject, workspace.root, trimmed)
                } catch (e: Exception) {
                    null
                }
                if (snap == null) {
                    items += UiItem.Notice("Снимок проекта не создан (проект слишком большой), откат этой задачи недоступен.")
                }
                current.execute(planNote + mentionExpand(expanded ?: trimmed), images).collect { handle(it) }
            } finally {
                if (myRun == runId) {
                    // Нормальное завершение; при отмене всё уже убрал cancelRun()
                    finishRun()
                    if (limitHit && !lastRunFailed && settings.autoContinue && autoChain < 50 && !overDailyLimit()) {
                        send("Продолжай работу над задачей, не останавливайся, пока она не выполнена.", auto = true)
                        return@launch
                    }
                    val next = if (!lastRunFailed && !overDailyLimit() && queue.isNotEmpty()) queue.removeAt(0).also { saveQueue() } else null
                    if (lastRunFailed && queue.isNotEmpty()) {
                        items += UiItem.Notice("Очередь (${queue.size}) приостановлена из-за ошибки. Открой её кнопкой «Очередь» и запусти.")
                    }
                    if (next != null) {
                        send(next)
                    } else if (!appVisible) {
                        val last = (items.lastOrNull { it is UiItem.Assistant } as? UiItem.Assistant)?.text
                        Notifier.alert(app, "Агент закончил", last?.take(200) ?: "Задача выполнена", continueTab = tabNo)
                    }
                }
            }
        }
    }

    /** Кнопка «Стоп» и действие в уведомлении. */
    fun stop() {
        if (!running) return
        notes.clear()
        markPendingInterrupted()
        cancelRun()
        persist()
    }

    private fun finishRun() {
        editsAllowedThisRun = false
        running = false
        streamingIndex = null
        approval = null
        job = null
        stopService()
        persist()
    }

    private fun cancelRun() {
        workers.cancelAll()
        runId++
        job?.cancel()
        job = null
        running = false
        streamingIndex = null
        approval?.result?.complete(false)
        approval = null
        stopService()
    }

    private fun markPendingInterrupted() {
        for (i in items.indices) {
            val it = items[i]
            if (it is UiItem.Tool && it.output == null) {
                items[i] = it.copy(output = "Прервано", isError = true)
            }
        }
    }

    fun answerApproval(allow: Boolean) {
        approval?.result?.complete(allow)
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                val idx = streamingIndex
                if (idx == null) {
                    items += UiItem.Assistant(event.text)
                    streamingIndex = items.lastIndex
                } else {
                    val old = items[idx] as UiItem.Assistant
                    items[idx] = old.copy(text = old.text + event.text)
                }
            }
            AgentEvent.TextEnd -> {
                streamingIndex = null
            }
            is AgentEvent.ToolCall -> {
                streamingIndex = null
                items += UiItem.Tool(event.name, event.description, null, false)
                Notifier.updateWorking(app, "Выполняю: ${event.name}")
            }
            is AgentEvent.ToolResult -> {
                val idx = items.indexOfLast { it is UiItem.Tool && it.output == null }
                if (idx >= 0) {
                    val old = items[idx] as UiItem.Tool
                    items[idx] = old.copy(output = event.output, isError = event.isError)
                }
                if (event.isError) AppLog.log("TOOLERR", "${event.name}: ${event.output.take(300)}")
                Notifier.updateWorking(app, "Агент работает…")
                persist()
            }
            is AgentEvent.Usage -> recordUsage(event.input, event.output, event.cached, if (event.viaFallback) settings.fallbackProfileId.ifBlank { activeProfileId } else activeProfileId)
            is AgentEvent.StepLimit -> {
                streamingIndex = null
                limitHit = true
                items += UiItem.Notice("Лимит шагов за запуск достигнут" + if (settings.autoContinue) ", продолжаю автоматически." else ". Напиши «продолжай».")
                persist()
            }
            is AgentEvent.Error -> {
                AppLog.log("ERROR", event.message)
                if (!appVisible) Notifier.alert(app, "Агент остановился: ошибка", event.message.take(200))
                lastRunFailed = true
                streamingIndex = null
                items += UiItem.Error(event.message + errorHint(event.message))
            }
            is AgentEvent.Notice -> {
                streamingIndex = null
                items += UiItem.Notice(event.text)
                persist()
            }
            AgentEvent.Done -> {
                streamingIndex = null
            }
        }
    }

    private val approvalMutex = kotlinx.coroutines.sync.Mutex()

    /** true — запуск без пользователя (по расписанию): подтверждений не будет, опасное отклоняется. */
    @Volatile var unattended = false

    /** «Принимать правки файлов до конца этой задачи». */
    var editsAllowedThisRun by mutableStateOf(false)
        private set

    fun allowEditsThisRun() {
        editsAllowedThisRun = true
        answerApproval(true)
    }

    private suspend fun requestApproval(tool: String, description: String): Boolean {
        if (tool == "bash" &&
            ApprovalRules.isSafeBash(
                description.removePrefix("$ "), settings.allowRules.lines(), settings.smartApprove,
                allowedRoots = if (linuxActive) null else listOf(workspace.root.absolutePath, workspace.root.canonicalPath),
            )
        ) return true
        if (unattended) return false
        if (editsAllowedThisRun && (tool == "write_file" || tool == "edit_file")) return true
        return approvalMutex.withLock { askApproval(tool, description) }
    }

    private suspend fun askApproval(tool: String, description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approval = ApprovalRequest(tool, description, deferred)
        Notifier.updateWorking(app, "Ждёт подтверждения: $tool")
        if (!appVisible) Notifier.alert(app, "Нужно подтверждение: $tool", description.take(300), tab = tabNo)
        try {
            return deferred.await()
        } finally {
            approval = null
            Notifier.clearAlert(app)
        }
    }

    /** Понятная подсказка к частым ошибкам API. */
    private fun errorHint(m: String): String {
        val l = m.lowercase()
        return when {
            "freetiererror" in l || "free tier can only" in l ->
                "\n\n💡 Бесплатный тариф OpenCode работает только внутри самого приложения OpenCode, по API его не отдают. Возьми ключ другого провайдера: в настройках → «Шаблоны провайдеров» есть бесплатные варианты (Gemini, OpenRouter с моделями :free, Groq, NVIDIA)."
            l.startsWith("http 404") -> "\n\n💡 404: проверь базовый URL (для OpenAI-совместимых обычно заканчивается на /v1) и имя модели."
            l.startsWith("http 401") || "invalid api key" in l -> "\n\n💡 Ключ не принят: проверь, что он вставлен целиком и относится к этому провайдеру."
            l.startsWith("http 429") -> "\n\n💡 Лимит запросов или квоты. Подожди или настрой резервный профиль." + resetNote(m)
            else -> ""
        }
    }

    private fun ensureAgent(): Agent {
        agent?.let { return it }
        val s = settings
        val ctxLimit = effectiveContext
        val llm: LlmClient = when (s.provider) {
            Provider.ANTHROPIC -> AnthropicClient(s.baseUrl, s.apiKey, s.model, s.promptCaching, s.maxOutputTokens)
            Provider.OPENAI -> OpenAiClient(s.baseUrl, s.apiKey, s.model, s.promptCaching, s.maxOutputTokens)
        }
        val baseTools = listOf(
            ReadFileTool(workspace),
            WriteFileTool(workspace),
            EditFileTool(workspace),
            ListDirTool(workspace),
            GrepTool(workspace),
            BashTool(workspace, shell),
            PythonTool(workspace, python),
            if (linuxActive) LinuxGitTool(workspace, shell) else GitTool(workspace, homeDir) { settings },
        )
        val skills = runCatching { extensions.skills() }.getOrDefault(emptyList())
        val mcpTools: List<Tool> = mcpServers.filter { it.enabled }.flatMap { s -> s.tools.map { McpTool(s, it) } }
        val planTools: List<Tool> = listOf(
            PresentPlanTool { planMode = false },
            TodoTool { list ->
                todos = list
                if (list.isNotEmpty() && list.all { it.startsWith("[x]", true) } && !appVisible) {
                    Notifier.alert(app, "Список задач выполнен", "Готово пунктов: ${list.size}")
                }
            },
        )
        val helper: LlmClient? = s.helperModel.trim().takeIf { it.isNotEmpty() }?.let { makeLlm(s, it) }
        val explorer = SubagentTool(
            helper ?: llm,
            listOf(ReadFileTool(workspace), ListDirTool(workspace), GrepTool(workspace), WebSearchTool(), WebFetchTool()),
            ctxLimit,
        )
        val devTools: List<Tool> = listOf(
            BgTool(workspace, shell, bgProcs),
            PreviewTool { url -> lastPreview = url; previewUrl = url },
            CodeSearchTool(workspace),
            DiagnosticsTool(workspace, python, shell) { changes.map { it.path } },
            TestTool(workspace, shell, python),
            NotesTool(extensions),
            GitHubTool(workspace) { settings.githubToken },
        )
        val tools = baseTools + mcpTools + planTools + devTools + explorer + listOfNotNull(
            if (skills.isNotEmpty()) SkillTool(extensions) else null,
            WebSearchTool(),
            WebFetchTool(),
            if (linuxActive) AdbTool(workspace, linuxShell) { devices } else null,
            if (rootEnabled) RootTool(workspace, rootShell) else null,
        )
        val sysPrompt = systemPrompt(
            workspace.root.absolutePath, shell.info, linuxActive,
            adbDevices = if (linuxActive) devices.map { "${it.label} (${it.address})" } else null,
            root = rootEnabled,
            instructions = listOfNotNull(
                runCatching { extensions.projectInstructions() }.getOrNull(),
                runCatching { extensions.projectNotes() }.getOrNull()?.let { "[Заметки проекта, записаны агентом ранее]\n$it" },
            ).joinToString("\n\n").ifEmpty { null },
            skills = skills,
        )
        val workerTools: List<Tool> = baseTools + devTools.filter { it.name != "preview" }
        val workerFactory = { name: String, _: String, isolated: Boolean ->
            var tools2 = workerTools
            var finish: (() -> String)? = null
            var promptNote = ""
            if (isolated) {
                val wt = com.pocketcode.tools.WorkTree.create(workspace.root, File(app.filesDir, "worktrees/${sanitize(name)}-${System.currentTimeMillis()}"))
                val ws2 = Workspace(wt.copy)
                val shell2: Shell = if (linuxActive) LinuxShell(linuxEnv, ws2) else androidShell
                tools2 = listOf(
                    ReadFileTool(ws2), WriteFileTool(ws2), EditFileTool(ws2), ListDirTool(ws2), GrepTool(ws2),
                    BashTool(ws2, shell2), PythonTool(ws2, python), CodeSearchTool(ws2),
                    DiagnosticsTool(ws2, python, shell2) { emptyList() },
                )
                finish = { wt.merge() }
                promptNote = "\nYour workspace is an isolated copy of the project: change files freely; your changes are merged back when you finish."
            }
            Agent(
                llm = llm,
                tools = tools2,
                system = sysPrompt + "\nYou are a parallel worker started by the main agent. Do only the task you were given, touch only the files it names, " +
                    "and finish with a short report of what you changed." + promptNote,
                autoApprove = s.autoApprove,
                contextLimit = ctxLimit,
                maxSteps = if (s.maxSteps == 0) 0 else s.maxSteps * 2,
                approve = { t, d -> requestApproval(t, d) },
            ) to finish
        }
        val created = Agent(
            llm = llm,
            tools = tools + listOf(SpawnTool(workers, workerFactory), WorkerResultTool(workers)),
            system = sysPrompt,
            autoApprove = s.autoApprove,
            contextLimit = ctxLimit,
            maxSteps = s.maxSteps,
            hooks = agentHooks,
            fallbackLlm = makeFallback(s),
            helperLlm = helper,
            eco = s.ecoMode,
            approve = ::requestApproval,
            history = history,
            pollNotes = { generateSequence { notes.poll() }.toList() },
        )
        agent = created
        return created
    }

    // ---------- сервис ----------

    private var serviceHeld = false

    private fun startService() {
        if (!serviceHeld) { serviceHeld = true; serviceUsers.incrementAndGet() }
        try {
            ContextCompat.startForegroundService(app, Intent(app, AgentService::class.java))
        } catch (e: Exception) {
            // Без сервиса агент всё равно работает, пока приложение на экране
        }
    }

    private fun stopService() {
        if (!serviceHeld) return
        serviceHeld = false
        if (serviceUsers.decrementAndGet() > 0) return
        try {
            app.stopService(Intent(app, AgentService::class.java))
        } catch (e: Exception) {
        }
    }

    companion object {
        private val serviceUsers = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
