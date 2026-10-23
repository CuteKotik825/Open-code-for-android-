package com.pocketcode.tools

import com.pocketcode.python.PythonRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jsoup.parser.Parser

/** Проверка синтаксиса файлов без запуска проекта. Это не полноценный LSP, но ловит самые частые ошибки. */
class DiagnosticsTool(
    private val ws: Workspace,
    private val python: PythonRunner,
    private val shell: Shell,
    private val recent: () -> List<String>,
) : Tool {
    override val name = "diagnostics"
    override val description =
        "Проверить синтаксис файла без запуска: Python, JSON, XML/HTML, JS (если есть node), sh; для остальных — баланс скобок. " +
            "Вызывай после правок. Без path проверяет файлы, изменённые в этой сессии."
    override val schema = objSchema(Prop("path", "string", "Файл для проверки", required = false))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "проверка ${input.str("path") ?: "изменённых файлов"}"

    override suspend fun run(input: JsonObject): String {
        val paths = input.str("path")?.let { listOf(it) } ?: recent().distinct().takeLast(10)
        if (paths.isEmpty()) return "Нечего проверять: укажи path."
        val lines = mutableListOf<String>()
        for (p in paths) {
            lines += try { "$p: " + check(p) } catch (e: Exception) { "$p: не удалось проверить (${e.message})" }
        }
        return lines.joinToString("\n")
    }

    private suspend fun check(path: String): String = checkFile(ws.resolve(path))

    /** Проверка произвольного текста (из редактора, ещё не сохранённого): через временный файл внутри проекта. */
    suspend fun checkText(fileName: String, text: String): String {
        val tmp = java.io.File(ws.root, ".pocketcode/tmp/check_${System.currentTimeMillis()}_" + fileName.substringAfterLast('/'))
        tmp.parentFile?.mkdirs()
        return try {
            tmp.writeText(text)
            checkFile(tmp)
        } finally {
            tmp.delete()
        }
    }

    suspend fun checkFile(f: java.io.File): String {
        if (!f.isFile) return "файла нет"
        if (f.length() > 1_000_000) return "слишком большой"
        val text = f.readText()
        return when (f.extension.lowercase()) {
            "py" -> {
                val code = "import ast\nsrc=open(${pyStr(f.absolutePath)},encoding='utf-8').read()\nast.parse(src)\nprint('OK')"
                val out = python.run(code, null, emptyList(), ws.root.canonicalPath, 30)
                if (out.contains("OK") && !out.contains("Error")) "OK" else out.lines().takeLast(6).joinToString(" | ")
            }
            "json" -> withContext(Dispatchers.Default) {
                runCatching { Json.parseToJsonElement(text); "OK" }.getOrElse { "ошибка JSON: ${it.message?.take(200)}" }
            }
            "xml", "html", "htm", "svg" -> {
                val parser = Parser.xmlParser().setTrackErrors(5)
                if (f.extension.lowercase().startsWith("ht")) "OK (HTML мягкий, ошибки не проверяются)"
                else { org.jsoup.Jsoup.parse(text, "", parser); if (parser.errors.isEmpty()) "OK" else parser.errors.joinToString("; ") { it.toString() } }
            }
            "js", "mjs", "cjs" -> viaShell("command -v node >/dev/null && node --check ${q(ws.rel(f))} && echo OK || echo 'node не найден или ошибка (см. выше)'")
            "sh" -> viaShell("sh -n ${q(ws.rel(f))} && echo OK")
            else -> balance(text)
        }
    }

    private suspend fun viaShell(cmd: String): String =
        shell.run(cmd, ws.root, 60).lines().filter { it.isNotBlank() && !it.startsWith("[код выхода") }.joinToString(" | ").ifEmpty { "OK" }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun pyStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Грубая проверка баланса скобок вне строк и комментариев «//» и «#». */
    private fun balance(text: String): String {
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1
        var i = 0
        var inStr: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') { line++; if (inStr == '\'' || inStr == '"') inStr = null }
            if (inStr != null) {
                if (c == '\\') i++ else if (c == inStr) inStr = null
            } else when (c) {
                '"', '\'', '`' -> inStr = c
                '/' -> if (text.getOrNull(i + 1) == '/') while (i < text.length && text[i] != '\n') i++
                '#' -> while (i < text.length && text[i] != '\n') i++
                '(', '[', '{' -> stack.addLast(c to line)
                ')', ']', '}' -> {
                    val open = stack.removeLastOrNull() ?: return "лишняя «$c» в строке $line"
                    val ok = (open.first == '(' && c == ')') || (open.first == '[' && c == ']') || (open.first == '{' && c == '}')
                    if (!ok) return "не совпадают «${open.first}» (строка ${open.second}) и «$c» (строка $line)"
                }
            }
            i++
        }
        return stack.lastOrNull()?.let { "не закрыта «${it.first}» из строки ${it.second}" } ?: "скобки сбалансированы (грубая проверка)"
    }
}
