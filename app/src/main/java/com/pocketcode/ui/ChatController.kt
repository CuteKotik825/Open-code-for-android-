package com.pocketcode.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
import com.pocketcode.service.AgentService
import com.pocketcode.service.Notifier
import com.pocketcode.session.SessionData
import com.pocketcode.session.SessionMeta
import com.pocketcode.session.SessionStore
import com.pocketcode.session.UiItem
import com.pocketcode.tools.AndroidShell
import com.pocketcode.tools.BashTool
import com.pocketcode.tools.EditFileTool
import com.pocketcode.tools.GitTool
import com.pocketcode.tools.GrepTool
import com.pocketcode.tools.ListDirTool
import com.pocketcode.tools.ReadFileTool
import com.pocketcode.tools.Workspace
import com.pocketcode.tools.WriteFileTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

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
    private val homeDir = File(app.filesDir, "home")
    private val workspace = Workspace(File(app.filesDir, "workspace"))
    private val shell = AndroidShell(homeDir)
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

    /** Выставляется MainActivity: нужно ли слать уведомления, когда экран не виден. */
    @Volatile
    var appVisible = false

    val workspacePath: String get() = workspace.root.absolutePath

    private var history = mutableListOf<Message>()
    private var agent: Agent? = null
    private var job: Job? = null
    private var runId = 0
    private var streamingIndex: Int? = null

    init {
        sessionList = sessions.list()
        sessionList.firstOrNull()?.let { loadInto(it.id) }
    }

    // ---------- настройки ----------

    fun saveSettings(s: Settings) {
        settings = s
        settingsStore.save(s)
        agent = null // пересоберём с новыми настройками, история лежит в controller
    }

    // ---------- сессии ----------

    fun newChat() {
        cancelRun()
        items.clear()
        history = mutableListOf()
        agent = null
        currentId = null
    }

    fun openSession(id: String) {
        cancelRun()
        loadInto(id)
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
        streamingIndex = null
    }

    private fun persist() {
        val id = currentId ?: return
        val title = (items.firstOrNull { it is UiItem.User } as? UiItem.User)?.text
            ?.replace('\n', ' ')?.take(60) ?: "Новый чат"
        val data = SessionData(id, title, System.currentTimeMillis(), items.toList(), history.toList())
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
        if (currentId == null) currentId = UUID.randomUUID().toString()
        val current = ensureAgent()
        items += UiItem.User(trimmed)
        streamingIndex = null
        running = true
        persist()
        startService()
        Notifier.clearAlert(app)

        val myRun = ++runId
        job = scope.launch {
            try {
                current.execute(trimmed).collect { handle(it) }
            } finally {
                if (myRun == runId) {
                    // Нормальное завершение; при отмене всё уже убрал cancelRun()
                    finishRun()
                    if (!appVisible) {
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
            GitTool(workspace, homeDir) { settings },
        )
        val created = Agent(
            llm = llm,
            tools = tools,
            system = systemPrompt(workspace.root.absolutePath, shell.info),
            autoApprove = s.autoApprove,
            contextLimit = s.contextTokens,
            approve = ::requestApproval,
            history = history,
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
