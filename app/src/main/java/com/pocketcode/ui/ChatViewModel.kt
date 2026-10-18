package com.pocketcode.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketcode.Provider
import com.pocketcode.Settings
import com.pocketcode.SettingsStore
import com.pocketcode.agent.Agent
import com.pocketcode.agent.AgentEvent
import com.pocketcode.agent.systemPrompt
import com.pocketcode.llm.AnthropicClient
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.OpenAiClient
import com.pocketcode.tools.AndroidShell
import com.pocketcode.tools.BashTool
import com.pocketcode.tools.EditFileTool
import com.pocketcode.tools.GitTool
import com.pocketcode.tools.GrepTool
import com.pocketcode.tools.ListDirTool
import com.pocketcode.tools.ReadFileTool
import com.pocketcode.tools.WriteFileTool
import com.pocketcode.tools.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed class UiItem {
    data class User(val text: String) : UiItem()
    data class Assistant(val text: String) : UiItem()
    data class Tool(val name: String, val description: String, val output: String?, val isError: Boolean) : UiItem()
    data class Error(val text: String) : UiItem()
}

class ApprovalRequest(
    val tool: String,
    val description: String,
    val result: CompletableDeferred<Boolean>,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val homeDir = File(app.filesDir, "home")
    private val workspace = Workspace(File(app.filesDir, "workspace"))
    private val shell = AndroidShell(homeDir)

    var settings by mutableStateOf(store.load())
        private set
    val items = mutableStateListOf<UiItem>()
    var running by mutableStateOf(false)
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set

    val workspacePath: String get() = workspace.root.absolutePath

    private var agent: Agent? = null
    private var job: Job? = null

    fun saveSettings(s: Settings) {
        settings = s
        store.save(s)
        // Пересобираем агента с новыми настройками, сохраняя историю диалога
        val oldHistory = agent?.history?.toList()
        agent = null
        if (oldHistory != null) {
            ensureAgent().history.addAll(oldHistory)
        }
    }

    fun newChat() {
        stop()
        items.clear()
        agent = null
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || running) return
        if (settings.apiKey.isBlank()) {
            items += UiItem.Error("Сначала укажи API-ключ в настройках (шестерёнка сверху).")
            return
        }
        val current = ensureAgent()
        items += UiItem.User(trimmed)
        running = true
        job = viewModelScope.launch {
            try {
                current.send(trimmed).collect { handle(it) }
            } finally {
                running = false
                approval?.result?.complete(false)
                approval = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun answerApproval(allow: Boolean) {
        approval?.result?.complete(allow)
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.Text -> items += UiItem.Assistant(event.text)
            is AgentEvent.ToolCall -> items += UiItem.Tool(event.name, event.description, null, false)
            is AgentEvent.ToolResult -> {
                val idx = items.indexOfLast { it is UiItem.Tool && it.output == null }
                if (idx >= 0) {
                    val old = items[idx] as UiItem.Tool
                    items[idx] = old.copy(output = event.output, isError = event.isError)
                }
            }
            is AgentEvent.Error -> items += UiItem.Error(event.message)
            AgentEvent.Done -> Unit
        }
    }

    private suspend fun requestApproval(tool: String, description: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approval = ApprovalRequest(tool, description, deferred)
        try {
            return deferred.await()
        } finally {
            approval = null
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
            approve = ::requestApproval,
        )
        agent = created
        return created
    }
}
