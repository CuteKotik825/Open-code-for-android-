package com.pocketcode.agent

import com.pocketcode.llm.Block
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message

private const val SUMMARY_SYSTEM = """You compress the earlier part of a coding-agent conversation into a summary that the agent will use to continue the work.
Write the summary in the language the user used. Be concrete and compact (up to ~800 words). Include:
- the user's goals and any standing instructions or preferences;
- what has been done so far: files created/changed/read (exact paths), commands run and their important results, git state (branch, commits, pushes);
- decisions made and why;
- the current state of the task, unresolved problems and the next steps.
Do not invent facts. Do not address the user. Output only the summary."""

/** Следит за размером истории и сжимает старую часть, чтобы диалог не упёрся в контекст модели. */
class Compactor(private val llm: LlmClient, private val limitTokens: Int) {

    fun needsCompaction(history: List<Message>): Boolean =
        estimateTokens(history) + OVERHEAD_TOKENS > (limitTokens * TRIGGER_RATIO).toInt()

    /**
     * Сжимает [history] на месте. Возвращает текст для чата или null, если сжимать нечего.
     * force=true — после ошибки переполнения: сразу делаем сводку.
     */
    suspend fun compact(history: MutableList<Message>, force: Boolean): String? {
        val before = estimateTokens(history)

        // Этап 1: старые длинные выводы инструментов нужны модели редко — сокращаем их
        val keepFrom = (history.size - KEEP_RECENT).coerceAtLeast(0)
        val pruned = history.mapIndexed { i, m ->
            if (i >= keepFrom) m else m.copy(blocks = m.blocks.map { b ->
                if (b is Block.ToolResult && b.content.length > PRUNE_OVER) {
                    b.copy(content = b.content.take(PRUNE_KEEP) + "\n...[вывод сокращён для экономии контекста]")
                } else b
            })
        }
        history.clear()
        history.addAll(pruned)
        val afterPrune = estimateTokens(history)
        if (!force && afterPrune + OVERHEAD_TOKENS <= (limitTokens * TRIGGER_RATIO).toInt()) {
            return "Контекст облегчён: старые выводы инструментов сокращены (~$before → ~$afterPrune токенов)."
        }

        // Этап 2: старую часть заменяем сводкой
        val cut = findCut(history, (limitTokens * TAIL_RATIO).toInt())
        if (cut <= 0) return if (afterPrune < before) "Контекст облегчён (~$before → ~$afterPrune токенов)." else null

        val old = history.subList(0, cut).toList()
        val tail = history.subList(cut, history.size).toList()
        val summary = summarize(old)

        val rebuilt = mutableListOf<Message>()
        rebuilt += Message(
            "user",
            listOf(Block.Text("[Сводка предыдущей части диалога, сжатой для экономии контекста]\n$summary")),
        )
        if (tail.first().role == "user") {
            rebuilt += Message("assistant", listOf(Block.Text("Принял сводку, продолжаю.")))
        }
        rebuilt += tail
        history.clear()
        history.addAll(rebuilt)
        return "Контекст сжат: ранняя часть диалога заменена сводкой (~$before → ~${estimateTokens(history)} токенов)."
    }

    private suspend fun summarize(old: List<Message>): String {
        val sb = StringBuilder()
        for (m in old) {
            for (b in m.blocks) {
                when (b) {
                    is Block.Text ->
                        sb.append(if (m.role == "user") "USER: " else "ASSISTANT: ").append(b.text.take(4000)).append('\n')
                    is Block.ToolUse ->
                        sb.append("TOOL CALL ${b.name}: ").append(b.input.toString().take(600)).append('\n')
                    is Block.ToolResult ->
                        sb.append(if (b.isError) "TOOL ERROR: " else "TOOL RESULT: ").append(b.content.take(800)).append('\n')
                }
            }
        }
        val transcript = if (sb.length > MAX_TRANSCRIPT_CHARS) {
            sb.substring(0, 30_000) + "\n[...середина пропущена...]\n" + sb.substring(sb.length - (MAX_TRANSCRIPT_CHARS - 30_000))
        } else sb.toString()

        val response = llm.complete(
            SUMMARY_SYSTEM,
            listOf(Message("user", listOf(Block.Text("Conversation to summarize:\n\n$transcript\n\nWrite the summary now.")))),
            emptyList(),
        ) { }
        val text = response.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }.trim()
        check(text.isNotEmpty()) { "модель вернула пустую сводку" }
        return text
    }

    /** Индекс первого сообщения, которое останется нетронутым; всё до него уйдёт в сводку. */
    private fun findCut(history: List<Message>, tailBudget: Int): Int {
        val sizes = history.map { estimateTokens(listOf(it)) }
        var suffix = 0
        var best = -1
        for (i in history.indices.reversed()) {
            suffix += sizes[i]
            if (!isCutPoint(history[i])) continue
            if (suffix <= tailBudget || best == -1) best = i else break
        }
        return best
    }

    /**
     * Резать можно перед сообщением ассистента или перед пользовательским текстом;
     * сообщение с tool_result резать нельзя: его tool_use остался бы в сводке.
     */
    private fun isCutPoint(m: Message): Boolean =
        m.role == "assistant" || m.blocks.any { it is Block.Text }

    companion object {
        private const val TRIGGER_RATIO = 0.7
        private const val TAIL_RATIO = 0.25
        private const val OVERHEAD_TOKENS = 3000 // системный промпт и описания инструментов
        private const val KEEP_RECENT = 8
        private const val PRUNE_OVER = 1500
        private const val PRUNE_KEEP = 700
        private const val MAX_TRANSCRIPT_CHARS = 120_000

        /** Грубая оценка: ~3 символа на токен (с запасом для кириллицы и кода). */
        fun estimateTokens(messages: List<Message>): Int {
            var chars = 0
            for (m in messages) for (b in m.blocks) {
                chars += when (b) {
                    is Block.Text -> b.text.length
                    is Block.ToolUse -> b.name.length + b.input.toString().length
                    is Block.ToolResult -> b.content.length
                }
            }
            return chars / 3
        }

        fun looksLikeOverflow(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return listOf(
                "prompt is too long", "context length", "context_length", "maximum context",
                "too many tokens", "exceeds the context", "token limit", "request too large", "http 413",
            ).any { m.contains(it) }
        }
    }
}
