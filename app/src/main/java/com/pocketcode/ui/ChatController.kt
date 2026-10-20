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
import com.pocketcode.agent.AgentEvent
import com.pocketcode.agent.systemPrompt
import com.pocketcode.llm.AnthropicClient
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message
import com.pocketcode.llm.OpenAiClient
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
import com.pocketcode.tools.GitTool
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

    fun saveSettings(s: Settings) {
        settings = s
        settingsStore.save(s)
        runCatching { linuxEnv.configureGit(s.gitName, s.gitEmail, s.githubToken) }
        agent = null // пересоберём с новыми настройками, история лежит в controller
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
    fun addExternalFolder(treeUri: Uri) {
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
        var name = sanitize(dir.name).ifEmpty { "folder" }
        val taken = projects.toSet()
        var i = 2
        val original = name
        while (name in taken) name = "$original-${i++}"
        externalProjects = externalProjects + (name to dir.absolutePath)
        settingsStore.saveExternalProjects(externalProjects)
        projectMessage = null
        selectProject(name)
    }

    // ---------- сессии ----------

    fun newChat() {
        cancelRun()
        queue.clear()
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
    val queue = mutableStateListOf<String>()

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
        if (!running) send(t) else queue += t
    }

    fun clearQueue() {
        queue.clear()
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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || running) return
        if (settings.apiKey.isBlank()) {
            items += UiItem.Error("Сначала укажи API-ключ в настройках (шестерёнка сверху).")
            return
        }
        when (trimmed.lowercase()) {
            "/clear", "/reset" -> { resetContext(); return }
            "/new" -> { newChat(); return }
        }
        if (currentId == null) currentId = UUID.randomUUID().toString()
        val current = ensureAgent()
        items += UiItem.User(trimmed)
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
                current.execute(trimmed).collect { handle(it) }
            } finally {
                if (myRun == runId) {
                    // Нормальное завершение; при отмене всё уже убрал cancelRun()
                    finishRun()
                    val next = if (!lastRunFailed && queue.isNotEmpty()) queue.removeAt(0) else null
                    if (lastRunFailed) queue.clear()
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
        queue.clear()
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

    private suspend fun requestApproval(tool: String, description: String): Boolean {
        if (tool == "bash" &&
            ApprovalRules.isSafeBash(description.removePrefix("$ "), settings.allowRules.lines(), settings.smartApprove)
        ) return true
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
            Provider.ANTHROPIC -> AnthropicClient(s.baseUrl, s.apiKey, s.model)
            Provider.OPENAI -> OpenAiClient(s.baseUrl, s.apiKey, s.model)
        }
        val tools = listOf(
            ReadFileTool(workspace),
            WriteFileTool(workspace),
            EditFileTool(workspace),
            ListDirTool(workspace),
            GrepTool(workspace),
            BashTool(workspace, shell),
            PythonTool(workspace, python),
            if (linuxActive) LinuxGitTool(workspace, shell) else GitTool(workspace, homeDir) { settings },
        )
        val created = Agent(
            llm = llm,
            tools = tools,
            system = systemPrompt(workspace.root.absolutePath, shell.info, linuxActive),
            autoApprove = s.autoApprove,
            contextLimit = s.contextTokens,
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
