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
import kotlinx.coroutines.delay
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
class ChatController(private val app: Application) {
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    var currentProject by mutableStateOf(settingsStore.loadProject())
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
        refreshProjects()
        sessionList = sessions.list()
        if (!settings.freshOnStart) {
            sessionList.firstOrNull { projectOf(it) == currentProject }?.let { loadInto(it.id) }
        }
    }

    private fun projectOf(meta: SessionMeta) = meta.project.ifBlank { DEFAULT_PROJECT }

    // ---------- настройки ----------

    var profiles by mutableStateOf(settingsStore.loadProfiles())
        private set
    var activeProfileId by mutableStateOf(settingsStore.loadActiveId())
        private set

    fun saveSettings(s: Settings, newProfiles: List<Profile>, activeId: String) {
        profiles = newProfiles
        activeProfileId = activeId
        settingsStore.saveProfiles(newProfiles, activeId)
        settings = s
        settingsStore.save(s)
        runCatching { linuxEnv.configureGit(s.gitName, s.gitEmail, s.githubToken) }
        agent = null // пересоберём с новыми настройками, история лежит в controller
    }

    /** Быстрое переключение ключа/модели из чата. */
    fun switchProfile(id: String) {
        if (running || id == activeProfileId) return
        val p = profiles.firstOrNull { it.id == id } ?: return
        settings = settings.copy(provider = p.provider, baseUrl = p.baseUrl, model = p.model, apiKey = p.apiKey)
        settingsStore.save(settings)
        settingsStore.saveProfiles(profiles, id)
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
        settingsStore.saveProject(name)
        workspace.root = projectDir(name)
        items.clear()
        history = mutableListOf()
        agent = null
        currentId = null
        streamingIndex = null
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

    fun newChat() {
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
    val queue = mutableStateListOf<String>().apply { addAll(settingsStore.loadQueue()) }

    private fun saveQueue() = settingsStore.saveQueue(queue.toList())

    private var lastRunFailed = false

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
                for (line in settings.fileHooks.lines()) {
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

    fun send(text: String, auto: Boolean = false) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && pendingImages.isEmpty()) || running) return
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
                current.execute(planNote + (expanded ?: trimmed), images).collect { handle(it) }
            } finally {
                if (myRun == runId) {
                    // Нормальное завершение; при отмене всё уже убрал cancelRun()
                    finishRun()
                    if (limitHit && !lastRunFailed && settings.autoContinue && autoChain < 50) {
                        send("Продолжай работу над задачей, не останавливайся, пока она не выполнена.", auto = true)
                        return@launch
                    }
                    val next = if (!lastRunFailed && queue.isNotEmpty()) queue.removeAt(0).also { saveQueue() } else null
                    if (lastRunFailed && queue.isNotEmpty()) {
                        items += UiItem.Notice("Очередь (${queue.size}) приостановлена из-за ошибки. Открой её кнопкой «Очередь» и запусти.")
                    }
                    if (next != null) {
                        send(next)
                    } else if (!appVisible) {
                        val last = (items.lastOrNull { it is UiItem.Assistant } as? UiItem.Assistant)?.text
                        Notifier.alert(app, "Агент закончил", last?.take(200) ?: "Задача выполнена")
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
                Notifier.updateWorking(app, "Агент работает…")
                persist()
            }
            is AgentEvent.StepLimit -> {
                streamingIndex = null
                limitHit = true
                items += UiItem.Notice("Лимит шагов за запуск достигнут" + if (settings.autoContinue) ", продолжаю автоматически." else ". Напиши «продолжай».")
                persist()
            }
            is AgentEvent.Error -> {
                lastRunFailed = true
                streamingIndex = null
                items += UiItem.Error(event.message)
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

    private suspend fun requestApproval(tool: String, description: String): Boolean {
        if (tool == "bash" &&
            ApprovalRules.isSafeBash(
                description.removePrefix("$ "), settings.allowRules.lines(), settings.smartApprove,
                allowedRoots = if (linuxActive) null else listOf(workspace.root.absolutePath, workspace.root.canonicalPath),
            )
        ) return true
        if (unattended) return false
        return approvalMutex.withLock { askApproval(tool, description) }
    }

    private suspend fun askApproval(tool: String, description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approval = ApprovalRequest(tool, description, deferred)
        Notifier.updateWorking(app, "Ждёт подтверждения: $tool")
        if (!appVisible) Notifier.alert(app, "Нужно подтверждение: $tool", description.take(300))
        try {
            return deferred.await()
        } finally {
            approval = null
            Notifier.clearAlert(app)
        }
    }

    private fun ensureAgent(): Agent {
        agent?.let { return it }
        val s = settings
        val llm: LlmClient = when (s.provider) {
            Provider.ANTHROPIC -> AnthropicClient(s.baseUrl, s.apiKey, s.model, s.promptCaching)
            Provider.OPENAI -> OpenAiClient(s.baseUrl, s.apiKey, s.model, s.promptCaching)
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
            TodoTool { todos = it },
        )
        val explorer = SubagentTool(
            llm,
            listOf(ReadFileTool(workspace), ListDirTool(workspace), GrepTool(workspace), WebSearchTool(), WebFetchTool()),
            s.contextTokens,
        )
        val devTools: List<Tool> = listOf(
            BgTool(workspace, shell, bgProcs),
            PreviewTool { url -> lastPreview = url; previewUrl = url },
            CodeSearchTool(workspace),
            DiagnosticsTool(workspace, python, shell) { changes.map { it.path } },
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
            instructions = runCatching { extensions.projectInstructions() }.getOrNull(),
            skills = skills,
        )
        val workerTools: List<Tool> = baseTools + devTools.filter { it.name != "preview" }
        val workerFactory = { _: String ->
            Agent(
                llm = llm,
                tools = workerTools,
                system = sysPrompt + "\nYou are a parallel worker started by the main agent. Do only the task you were given, touch only the files it names, " +
                    "and finish with a short report of what you changed.",
                autoApprove = s.autoApprove,
                contextLimit = s.contextTokens,
                maxSteps = if (s.maxSteps == 0) 0 else s.maxSteps * 2,
                approve = { t, d -> requestApproval(t, d) },
            )
        }
        val created = Agent(
            llm = llm,
            tools = tools + listOf(SpawnTool(workers, workerFactory), WorkerResultTool(workers)),
            system = sysPrompt,
            autoApprove = s.autoApprove,
            contextLimit = s.contextTokens,
            maxSteps = s.maxSteps,
            hooks = agentHooks,
            approve = ::requestApproval,
            history = history,
            pollNotes = { generateSequence { notes.poll() }.toList() },
        )
        agent = created
        return created
    }

    // ---------- сервис ----------

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, AgentService::class.java))
        } catch (e: Exception) {
            // Без сервиса агент всё равно работает, пока приложение на экране
        }
    }

    private fun stopService() {
        try {
            app.stopService(Intent(app, AgentService::class.java))
        } catch (e: Exception) {
        }
    }
}
