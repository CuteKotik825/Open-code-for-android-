package com.pocketcode.tools

import com.pocketcode.agent.Agent
import com.pocketcode.agent.AgentEvent
import com.pocketcode.llm.LlmClient
import kotlinx.serialization.json.JsonObject

private const val SUB_SYSTEM = """You are a read-only research helper inside a coding agent on a phone.
You get one self-contained task. Explore with your tools (read_file, list_dir, grep, web_search, web_fetch) and finish with a compact, concrete report:
exact file paths and line hints, key findings, answers to the question. You cannot change anything. Do not ask questions.
No long preambles; the report is read by another agent. Reply in the language of the task."""

/** Подагент: отдельный агент с чистым контекстом, только читает и возвращает отчёт. */
class SubagentTool(
    private val llm: LlmClient,
    private val readTools: List<Tool>,
    private val contextLimit: Int,
) : Tool {
    override val name = "task"
    override val description =
        "Запустить помощника с чистым контекстом для исследования: найти нужный код, разобраться в структуре, прочитать документацию. " +
            "Он только читает (файлы, grep, веб) и возвращает короткий отчёт; твой контекст не засоряется. Пиши в prompt самодостаточное задание."
    override val schema = objSchema(Prop("prompt", "string", "Что исследовать и что вернуть в отчёте"))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "помощник: ${(input.str("prompt") ?: "").take(80)}"

    override suspend fun run(input: JsonObject): String {
        val prompt = input.need("prompt")
        val sub = Agent(
            llm = llm, tools = readTools, system = SUB_SYSTEM, autoApprove = true,
            contextLimit = contextLimit, approve = { _, _ -> true }, maxSteps = 30,
        )
        val cur = StringBuilder()
        var error: String? = null
        sub.execute(prompt).collect { ev ->
            when (ev) {
                is AgentEvent.TextDelta -> cur.append(ev.text)
                is AgentEvent.ToolCall -> cur.clear()
                is AgentEvent.Error -> error = ev.message
                else -> {}
            }
        }
        error?.let { if (cur.isBlank()) throw IllegalStateException(it) }
        return cur.toString().trim().ifEmpty { "Помощник ничего не вернул." }.take(10_000)
    }
}
