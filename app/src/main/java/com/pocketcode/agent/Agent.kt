package com.pocketcode.agent

import com.pocketcode.llm.Block
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message
import com.pocketcode.llm.ToolSpec
import com.pocketcode.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed class AgentEvent {
    /** Очередной кусок текста ответа (потоковый вывод). */
    data class TextDelta(val text: String) : AgentEvent()

    /** Текущий ответ модели закончился, следующий текст начнётся в новом блоке. */
    object TextEnd : AgentEvent()

    data class ToolCall(val name: String, val description: String) : AgentEvent()
    data class ToolResult(val name: String, val output: String, val isError: Boolean) : AgentEvent()
    data class Error(val message: String) : AgentEvent()

    /** Служебное сообщение в ленте, например «контекст сжат». */
    data class Notice(val text: String) : AgentEvent()
    object Done : AgentEvent()

    /** Достигнут лимит шагов за один запуск. */
    object StepLimit : AgentEvent()

    /** Расход токенов за один ответ модели. */
    data class Usage(val input: Int, val output: Int, val cached: Int) : AgentEvent()
}

private const val MAX_TOOL_OUTPUT = 30_000

class Agent(
    llm: LlmClient,
    private val tools: List<Tool>,
    private val system: String,
    private val autoApprove: Boolean,
    /** Размер контекста модели в токенах: при заполнении ~70% старая часть истории сжимается. */
    contextLimit: Int,
    /** Спрашивает пользователя: (имя инструмента, описание) -> разрешено ли. */
    private val approve: suspend (String, String) -> Boolean,
    /** История диалога для модели; общая с хранилищем сессий. */
    val history: MutableList<Message> = mutableListOf(),
    /** Забирает уточнения, которые пользователь написал, пока агент работает. */
    private val pollNotes: () -> List<String> = { emptyList() },
    /** Шагов за запуск; 0 — без лимита. */
    private val maxSteps: Int = 0,
    private val hooks: AgentHooks = AgentHooks.NONE,
    /** Резервная модель: включается, если у основной кончилась квота, лимит запросов или ключ не принят. */
    private val fallbackLlm: LlmClient? = null,
    /** Дешёвая модель для сводок контекста. */
    helperLlm: LlmClient? = null,
) {
    private var llm: LlmClient = llm
    private var usedFallback = false

    private fun looksLikeQuota(msg: String?): Boolean {
        val m = msg?.lowercase() ?: return false
        return listOf("429", "402", "401", "403", "529", "rate limit", "rate_limit", "quota", "insufficient", "too many requests",
            "overloaded", "credit", "billing", "invalid api key", "incorrect api key", "unauthorized").any { m.contains(it) }
    }

    private val compactor = Compactor(helperLlm ?: llm, contextLimit)
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

    /** Добавляет уточнения пользователя к последнему сообщению пользователя (или отдельным сообщением). */
    private fun injectNotes(notes: List<String>) {
        if (notes.isEmpty()) return
        val extra = notes.map { Block.Text("[Уточнение от пользователя, пока ты работал]\n$it") }
        val last = history.lastOrNull()
        if (last != null && last.role == "user") history[history.lastIndex] = last.copy(blocks = last.blocks + extra)
        else history += Message("user", extra)
    }

    private fun mustAsk(tool: Tool, input: kotlinx.serialization.json.JsonObject, d: Decision): Boolean = when (d) {
        Decision.ASK -> true
        Decision.ALLOW -> tool.alwaysAsk
        else -> tool.needsApproval(input) && (tool.alwaysAsk || !autoApprove)
    }

    fun execute(text: String, images: List<Block.Image> = emptyList()): Flow<AgentEvent> = channelFlow {
        repairHistory()
        history += Message("user", images + Block.Text(text.ifBlank { "(см. изображение)" }))
        var steps = 0
        var overflowRetried = false
        while (true) {
            if (maxSteps > 0 && ++steps > maxSteps) {
                send(AgentEvent.StepLimit)
                break
            }
            injectNotes(pollNotes())
            compactor.maskOld(history)
            if (compactor.needsCompaction(history)) {
                try {
                    compactor.compact(history, force = false)?.let { send(AgentEvent.Notice(it)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    send(AgentEvent.Notice("Не удалось сжать контекст: ${e.message ?: e}"))
                }
            }
            val response = try {
                llm.complete(system, history, specs) { delta -> trySend(AgentEvent.TextDelta(delta)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(AgentEvent.TextEnd)
                if (!usedFallback && fallbackLlm != null && looksLikeQuota(e.message)) {
                    usedFallback = true
                    llm = fallbackLlm
                    send(AgentEvent.Notice("Основной профиль не сработал (${(e.message ?: "").take(100)}). Перехожу на резервный."))
                    continue
                }
                if (!overflowRetried && Compactor.looksLikeOverflow(e.message)) {
                    // Модель отказалась из-за размера контекста: сжимаем принудительно и повторяем
                    overflowRetried = true
                    try {
                        val note = compactor.compact(history, force = true)
                        if (note != null) {
                            send(AgentEvent.Notice(note))
                            continue
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ce: Exception) {
                        send(AgentEvent.Notice("Не удалось сжать контекст: ${ce.message ?: ce}"))
                    }
                }
                send(AgentEvent.Error(e.message ?: e.toString()))
                break
            }
            overflowRetried = false
            if (response.inputTokens + response.outputTokens > 0) {
                send(AgentEvent.Usage(response.inputTokens, response.outputTokens, response.cachedTokens))
            }
            history += Message("assistant", response.blocks)
            send(AgentEvent.TextEnd)

            val calls = response.blocks.filterIsInstance<Block.ToolUse>()
            if (calls.isEmpty()) {
                val late = pollNotes()
                if (late.isEmpty()) break
                injectNotes(late)
                continue
            }

            val results = mutableListOf<Block>()
            for (call in calls) {
                val tool = byName[call.name]
                val description = tool?.describe(call.input) ?: call.input.toString().take(200)
                val decision = hooks.decide(call.name, call.input, description)
                send(AgentEvent.ToolCall(call.name, description))

                var output = ""
                var isError = false
                if (tool == null) {
                    output = "Неизвестный инструмент: ${call.name}"
                    isError = true
                } else if (hooks.planMode() && call.name !in PLAN_ALLOWED) {
                    output = "Режим плана: изменять и запускать пока нельзя. Исследуй (чтение, поиск), затем вызови present_plan и дождись одобрения."
                    isError = true
                } else if (decision == Decision.DENY) {
                    output = "Запрещено правилом пользователя в настройках. Не повторяй это, выбери другой путь или спроси пользователя."
                    isError = true
                } else if (mustAsk(tool, call.input, decision) && !approve(call.name, description)) {
                    output = "Пользователь отклонил это действие"
                    isError = true
                } else {
                    try {
                        hooks.before(tool, call.input)
                        output = withContext(Dispatchers.IO) { tool.run(call.input) }
                        hooks.after(tool, call.input, output, false)?.let { output += it }
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
                send(AgentEvent.ToolResult(call.name, output, isError))
                results += Block.ToolResult(call.id, output, isError)
            }
            history += Message("user", results)
        }
        send(AgentEvent.Done)
    }.buffer(Channel.UNLIMITED)
}

fun systemPrompt(
    workspacePath: String,
    shellInfo: String,
    linux: Boolean,
    adbDevices: List<String>? = null,
    root: Boolean = false,
    instructions: String? = null,
    skills: List<Skill> = emptyList(),
): String = """
You are PocketCode, a coding agent running inside an Android app on the user's phone. There is no PC:
everything happens on the device and through the network.

Workspace: the current project folder "$workspacePath"; all file tools work inside it and paths are relative to it.
Tools: read_file, write_file, edit_file, list_dir, grep, bash, python, git.
- Read a file before editing it. Prefer edit_file for small changes; use write_file for new files.
- python runs an embedded Python 3.11 (standard library only, no pip, no stdin). ${if (linux) "Because bash is a real Linux here, prefer running `python3` through bash: it has pip and many libraries (install with `pip install --break-system-packages <pkg>`)." else "bash has no python, so use this tool for scripts, tests and calculations."}
- bash runs in: $shellInfo
${if (linux) "- Linux notes: before relying on python3/node/git run `which python3 node git`; if something is missing install it with `apk add <pkg>` (python3 py3-pip nodejs npm git build-base). The project is mounted at /work and commands start there. The git tool runs the real git CLI, credentials are already configured." else "- Use the git tool for git (pure-Java implementation), not bash."}
${if (adbDevices != null) "- adb controls other Android devices over ADB (Wi-Fi), like on a PC. Saved devices: " + (if (adbDevices.isEmpty()) "none (the user can add some on the Devices screen; you may also pass ip:port)" else adbDevices.joinToString("; ")) + ". Pass device by label or ip:port, never -s. Project files are at /work inside adb push/install paths. Read-only commands run without asking, everything else asks the user." else ""}
${if (root) "- root runs a command as root on THIS phone, outside the app sandbox. Every call needs the user's approval. Use it only when really necessary, never touch anything outside the task, and prefer the project folder tools otherwise." else ""}
- bg starts long-running processes (dev servers, watchers, long tests) in the background and lets you read their output; then use preview to show a local web page to the user. Never run a server with plain bash: it would block until timeout.
- code_search finds relevant files/definitions by meaning; diagnostics checks syntax of files you edited (run it after edits).
- github works with the repo's pull requests, issues and CI checks (needs the token from settings). spawn starts parallel workers with their own context for independent parts of a big task (different files); collect them with worker_result before you finish.
- run_tests runs the project's tests and returns a compact failure summary. spawn with isolated=true gives a worker its own copy of the project (merged back afterwards).
- remember saves a short durable note about the project (decisions, how to run things, gotchas) that you will see at the start of future chats.
- todo keeps a visible checklist; use it for work with 3+ steps and update it as you go.
- In plan mode (the user message says so) you may only read and search; finish by calling present_plan and wait for approval.
- task starts a helper agent with its own clean context that only reads (files, grep, web) and returns a short report. Use it for broad code exploration or research so your own context stays small; give it a self-contained prompt.
- web_search and web_fetch read the internet (docs, errors, libraries); use them when you need current or external information instead of guessing.
- Work in large steps: make independent tool calls together in ONE response (read several files at once, run several searches together) instead of one call per turn; write a whole file with write_file rather than many tiny edits when you rewrite most of it; chain shell commands with && in one bash call.
- Some actions need the user's approval; if the user declines, do not retry the same thing, adapt or ask.
- Explore first (list_dir, grep) when the task touches unfamiliar code. Keep changes minimal and focused,
  do not refactor or rename things that were not asked.
- After changes, say briefly what you changed. The screen is small: keep answers short, no long preambles.

${if (skills.isNotEmpty()) "Skills (load one with the skill tool when its description fits the task):\n" + skills.joinToString("\n") { "- ${it.name}: ${it.description}" } + "\n" else ""}${if (instructions != null) "Project instructions written by the user (follow them):\n$instructions\n" else ""}
Reply in the same language the user writes in.
""".trimIndent()
