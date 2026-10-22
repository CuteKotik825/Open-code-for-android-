package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

private val SKIP_DIRS = setOf(".git", "node_modules", "build", "dist", "__pycache__", ".gradle", ".idea", "venv", ".venv", "target")
private val DEF_RE = Regex("""\b(def|class|function|fun|func|interface|struct|enum|trait|type|object|const|val|var|let|public|private|protected)\b""")

/** Поиск по проекту с ранжированием: имена файлов, определения, частота слов. Быстрее и умнее, чем grep. */
class CodeSearchTool(private val ws: Workspace) : Tool {
    override val name = "code_search"
    override val description =
        "Умный поиск по проекту по смыслу слов: находит файлы и места, где описано то, что ищешь (имена функций, классов, файлов, слова в коде). " +
            "Результаты ранжируются. Используй для «где в проекте это делается»; для точной строки/regex лучше grep."
    override val schema = objSchema(
        Prop("query", "string", "Слова или имена через пробел"),
        Prop("definitions_only", "boolean", "Только определения (функции, классы, переменные)", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "поиск по проекту: ${input.str("query") ?: ""}"

    private class Doc(val path: String, val lines: List<String>)

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val terms = input.need("query").lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 2 }.distinct()
        require(terms.isNotEmpty()) { "Пустой запрос" }
        val defsOnly = input.bool("definitions_only") == true
        val docs = mutableListOf<Doc>()
        var count = 0
        ws.root.walkTopDown().onEnter { it.name !in SKIP_DIRS }.forEach { f ->
            if (!f.isFile || f.length() > 400_000 || count >= 4000) return@forEach
            if (looksBinary(f)) return@forEach
            count++
            docs += Doc(ws.rel(f), runCatching { f.readLines() }.getOrDefault(emptyList()))
        }
        class Hit(val doc: Doc, val score: Double, val lines: List<Pair<Int, String>>)
        val hits = docs.mapNotNull { d ->
            var score = 0.0
            val name = d.path.lowercase()
            if (!defsOnly) for (t in terms) if (name.contains(t)) score += 5.0
            val matched = mutableListOf<Pair<Double, Pair<Int, String>>>()
            for ((i, line) in d.lines.withIndex()) {
                val l = line.lowercase()
                var ls = 0.0
                for (t in terms) {
                    if (!l.contains(t)) continue
                    ls += if (DEF_RE.containsMatchIn(l)) 4.0 else if (defsOnly) 0.0 else 1.0
                }
                if (ls > 0) { score += ls; matched += ls to (i + 1 to line.trim().take(160)) }
            }
            if (score <= 0) null
            else Hit(d, score / Math.log10(10.0 + d.lines.size), matched.sortedByDescending { it.first }.take(3).map { it.second }.sortedBy { it.first })
        }.sortedByDescending { it.score }.take(10)
        if (hits.isEmpty()) "Ничего не найдено по: ${terms.joinToString(" ")}"
        else hits.joinToString("\n\n") { h -> h.doc.path + "\n" + h.lines.joinToString("\n") { "  ${it.first}: ${it.second}" } }
    }
}
