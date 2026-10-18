package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject
import java.io.File

private const val MAX_READ_BYTES = 2_000_000L

class ReadFileTool(private val ws: Workspace) : Tool {
    override val name = "read_file"
    override val description =
        "Читает текстовый файл и возвращает строки с номерами. offset — номер первой строки (с 1), " +
            "limit — сколько строк (по умолчанию 2000)."
    override val schema = objSchema(
        Prop("path", "string", "Путь к файлу относительно рабочей папки"),
        Prop("offset", "integer", "Номер первой строки, с 1", required = false),
        Prop("limit", "integer", "Сколько строк вернуть", required = false),
    )

    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = input.str("path").orEmpty()

    override suspend fun run(input: JsonObject): String {
        val f = ws.resolve(input.need("path"))
        require(f.exists()) { "Файл не найден: ${ws.rel(f)}" }
        require(f.isFile) { "Это не файл: ${ws.rel(f)}" }
        require(f.length() <= MAX_READ_BYTES) { "Файл слишком большой (${f.length()} байт)" }
        require(!looksBinary(f)) { "Бинарный файл: ${ws.rel(f)}" }
        val offset = (input.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (input.int("limit") ?: 2000).coerceIn(1, 5000)
        val lines = f.readLines()
        if (lines.isEmpty()) return "(пустой файл)"
        if (offset > lines.size) return "(в файле всего ${lines.size} строк)"
        val slice = lines.drop(offset - 1).take(limit)
        val sb = StringBuilder()
        slice.forEachIndexed { i, line ->
            sb.append(String.format("%6d\t", offset + i)).append(line.take(2000)).append('\n')
        }
        val end = offset + slice.size - 1
        if (end < lines.size) sb.append("... (показано $offset–$end из ${lines.size} строк)")
        return sb.toString()
    }
}

class WriteFileTool(private val ws: Workspace) : Tool {
    override val name = "write_file"
    override val description =
        "Создаёт файл или полностью перезаписывает существующий. Недостающие папки создаются сами. " +
            "Для небольших правок существующих файлов используй edit_file."
    override val schema = objSchema(
        Prop("path", "string", "Путь к файлу относительно рабочей папки"),
        Prop("content", "string", "Полное содержимое файла"),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject): String {
        val content = input.str("content").orEmpty()
        return "Запись файла ${input.str("path")} (${content.length} символов, ${content.lines().size} строк)"
    }

    override suspend fun run(input: JsonObject): String {
        val f = ws.resolve(input.need("path"))
        val content = input.str("content") ?: ""
        require(!f.isDirectory) { "Это папка: ${ws.rel(f)}" }
        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content)
        return (if (existed) "Файл перезаписан: " else "Файл создан: ") +
            "${ws.rel(f)} (${content.length} символов)"
    }
}

class EditFileTool(private val ws: Workspace) : Tool {
    override val name = "edit_file"
    override val description =
        "Заменяет точный фрагмент old_string на new_string в существующем файле. " +
            "old_string должен встречаться ровно один раз (или укажи replace_all=true). " +
            "Сначала прочитай файл, чтобы взять фрагмент с точными пробелами и отступами."
    override val schema = objSchema(
        Prop("path", "string", "Путь к файлу"),
        Prop("old_string", "string", "Что заменить (точное совпадение)"),
        Prop("new_string", "string", "На что заменить"),
        Prop("replace_all", "boolean", "Заменить все вхождения", required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject): String {
        val old = input.str("old_string").orEmpty().take(300)
        val new = input.str("new_string").orEmpty().take(300)
        return "Правка ${input.str("path")}\n--- было\n$old\n+++ стало\n$new"
    }

    override suspend fun run(input: JsonObject): String {
        val f = ws.resolve(input.need("path"))
        val old = input.need("old_string")
        val new = input.str("new_string") ?: ""
        val all = input.bool("replace_all") ?: false
        require(f.isFile) { "Файл не найден: ${ws.rel(f)}" }
        require(old.isNotEmpty()) { "old_string пустой" }
        require(old != new) { "old_string и new_string совпадают" }
        val text = f.readText()
        var count = 0
        var idx = text.indexOf(old)
        while (idx >= 0) {
            count++
            idx = text.indexOf(old, idx + old.length)
        }
        require(count > 0) { "Фрагмент не найден в ${ws.rel(f)}. Перечитай файл и проверь пробелы." }
        require(count == 1 || all) {
            "Фрагмент встречается $count раз. Добавь контекста или укажи replace_all=true."
        }
        f.writeText(if (all) text.replace(old, new) else text.replaceFirst(old, new))
        return "Готово: ${ws.rel(f)} (замен: ${if (all) count else 1})"
    }
}

class ListDirTool(private val ws: Workspace) : Tool {
    override val name = "list_dir"
    override val description =
        "Показывает содержимое папки. depth — глубина (1–4, по умолчанию 1). Папка .git пропускается."
    override val schema = objSchema(
        Prop("path", "string", "Папка, по умолчанию корень рабочей папки", required = false),
        Prop("depth", "integer", "Глубина обхода", required = false),
    )

    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = input.str("path") ?: "."

    override suspend fun run(input: JsonObject): String {
        val dir = ws.resolve(input.str("path") ?: ".")
        require(dir.isDirectory) { "Не папка: ${ws.rel(dir)}" }
        val depth = (input.int("depth") ?: 1).coerceIn(1, 4)
        val sb = StringBuilder()
        var count = 0
        fun walk(d: File, level: Int) {
            val children = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            for (c in children) {
                if (c.name == ".git") continue
                if (count >= 500) return
                count++
                sb.append("  ".repeat(level - 1)).append(c.name)
                if (c.isDirectory) sb.append('/') else sb.append("  (${c.length()} Б)")
                sb.append('\n')
                if (c.isDirectory && level < depth) walk(c, level + 1)
            }
        }
        walk(dir, 1)
        if (count == 0) return "(пусто)"
        if (count >= 500) sb.append("... список обрезан на 500 записях")
        return sb.toString()
    }
}

class GrepTool(private val ws: Workspace) : Tool {
    override val name = "grep"
    override val description =
        "Ищет регулярное выражение по текстовым файлам рабочей папки. Возвращает путь:строка: текст (до 200 совпадений)."
    override val schema = objSchema(
        Prop("pattern", "string", "Регулярное выражение (Java regex)"),
        Prop("path", "string", "Папка или файл, по умолчанию весь корень", required = false),
        Prop("ignore_case", "boolean", "Игнорировать регистр", required = false),
    )

    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = input.str("pattern").orEmpty()

    override suspend fun run(input: JsonObject): String {
        val options = if (input.bool("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = try {
            Regex(input.need("pattern"), options)
        } catch (e: Exception) {
            throw IllegalArgumentException("Неверное регулярное выражение: ${e.message}")
        }
        val start = ws.resolve(input.str("path") ?: ".")
        require(start.exists()) { "Не найдено: ${ws.rel(start)}" }
        val out = StringBuilder()
        var matches = 0

        fun searchFile(f: File) {
            if (f.length() > 1_000_000 || looksBinary(f)) return
            var n = 0
            f.useLines { lines ->
                for (line in lines) {
                    n++
                    if (regex.containsMatchIn(line)) {
                        out.append(ws.rel(f)).append(':').append(n).append(": ").append(line.take(300)).append('\n')
                        if (++matches >= 200) return
                    }
                }
            }
        }

        fun walk(d: File) {
            val children = d.listFiles()?.sortedBy { it.name } ?: return
            for (c in children) {
                if (matches >= 200) return
                if (c.name == ".git") continue
                if (c.isDirectory) walk(c) else searchFile(c)
            }
        }

        if (start.isFile) searchFile(start) else walk(start)
        if (matches == 0) return "Совпадений нет"
        if (matches >= 200) out.append("... остановлено на 200 совпадениях")
        return out.toString()
    }
}
