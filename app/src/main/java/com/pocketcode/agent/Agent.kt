package com.pocketcode.agent

import com.pocketcode.llm.Block
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message
import com.pocketcode.llm.ToolSpec
import com.pocketcode.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed class AgentEvent {
    data class Text(val text: String) : AgentEvent()
    data class ToolCall(val name: String, val description: String) : AgentEvent()
    data class ToolResult(val name: String, val output: String, val isError: Boolean) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object Done : AgentEvent()
}

private const val MAX_STEPS = 40
private const val MAX_TOOL_OUTPUT = 30_000

class Agent(
    private val llm: LlmClient,
    private val tools: List<Tool>,
    private val system: String,
    private val autoApprove: Boolean,
    /** Спрашивает пользователя: (имя инструмента, описание) -> разрешено ли. */
    private val approve: suspend (String, String) -> Boolean,
) {
    val history = mutableListOf<Message>()

    private val byName = tools.associateBy { it.name }
    private val specs = tools.map { ToolSpec(it.name, it.description, it.schema) }

    /** Если прошлый запуск остановили посреди работы, закрываем висящие вызовы инструментов. */
    private fun repairHistory() {
        val last = history.lastOrNull() ?: return
        if (last.role != "assistant") return
        val pending = last.blocks.filterIsInstance<Block.ToolUse>()
        if (pending.isEmpty()) return
        history += Message(
            "user",
            pending.map { Block.ToolResult(it.id, "Прервано пользователем", true) },
        )
    }

    fun send(text: String): Flow<AgentEvent> = flow {
        repairHistory()
        history += Message("user", listOf(Block.Text(text)))
        var steps = 0
        while (true) {
            if (++steps > MAX_STEPS) {
                emit(AgentEvent.Error("Достигнут лимит шагов ($MAX_STEPS). Напиши «продолжай», чтобы идти дальше."))
                break
            }
            val response = try {
                llm.complete(system, history, specs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AgentEvent.Error(e.message ?: e.toString()))
                break
            }
            history += Message("assistant", response.blocks)

            for (b in response.blocks) {
                if (b is Block.Text && b.text.isNotBlank()) emit(AgentEvent.Text(b.text))
            }
            val calls = response.blocks.filterIsInstance<Block.ToolUse>()
            if (calls.isEmpty()) break

            val results = mutableListOf<Block>()
            for (call in calls) {
                val tool = byName[call.name]
                val description = tool?.describe(call.input) ?: call.input.toString().take(200)
                emit(AgentEvent.ToolCall(call.name, description))

                var output = ""
                var isError = false
                if (tool == null) {
                    output = "Неизвестный инструмент: ${call.name}"
                    isError = true
                } else if (tool.needsApproval(call.input) && !autoApprove && !approve(call.name, description)) {
                    output = "Пользователь отклонил это действие"
                    isError = true
                } else {
                    try {
                        output = withContext(Dispatchers.IO) { tool.run(call.input) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        output = "Ошибка: ${e.message ?: e.toString()}"
                        isError = true
                    }
                }
                if (output.length > MAX_TOOL_OUTPUT) {
                    output = output.take(MAX_TOOL_OUTPUT) + "\n... (вывод обрезан)"
                }
                emit(AgentEvent.ToolResult(call.name, output, isError))
                results += Block.ToolResult(call.id, output, isError)
            }
            history += Message("user", results)
        }
        emit(AgentEvent.Done)
    }
}

fun systemPrompt(workspacePath: String, shellInfo: String): String = """
You are PocketCode, a coding agent running inside an Android app on the user's phone. There is no PC:
everything happens on the device and through the network.

Workspace: all file tools work inside "$workspacePath" (shown to you as the project root, paths are relative to it).
Tools: read_file, write_file, edit_file, list_dir, grep, bash, git.
- Read a file before editing it. Prefer edit_file for small changes; use write_file for new files.
- bash runs in: $shellInfo Use the git tool for git, not bash.
- Some actions need the user's approval; if the user declines, do not retry the same thing, adapt or ask.
- Explore first (list_dir, grep) when the task touches unfamiliar code. Keep changes minimal and focused,
  do not refactor or rename things that were not asked.
- After changes, say briefly what you changed. The screen is small: keep answers short, no long preambles.

Reply in the same language the user writes in.
""".trimIndent()
