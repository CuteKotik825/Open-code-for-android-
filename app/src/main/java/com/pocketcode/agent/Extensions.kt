package com.pocketcode.agent

import java.io.File

data class Skill(val name: String, val description: String, val dir: File, val source: String)
data class Command(val name: String, val description: String, val file: File, val source: String)

private data class Parsed(val meta: Map<String, String>, val body: String)

private fun parseFrontmatter(text: String): Parsed {
    val t = text.replace("\r\n", "\n")
    if (!t.startsWith("---\n")) return Parsed(emptyMap(), t)
    val end = t.indexOf("\n---", 4)
    if (end < 0) return Parsed(emptyMap(), t)
    val meta = t.substring(4, end).lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim().trim('"', '\'')
    }.toMap()
    val body = t.substring(end + 4).removePrefix("\n").trimStart('\n')
    return Parsed(meta, body)
}

/**
 * Инструкции проекта, навыки и пользовательские команды.
 * Источники: папки .pocketcode и .claude проекта, общая папка приложения и установленные плагины.
 */
class Extensions(
    private val projectRoot: () -> File,
    val globalDir: File,
    val pluginsDir: File,
) {
    /** Имена выключенных навыков: агент их не видит, но в списке они остаются. */
    var disabled: () -> Set<String> = { emptySet() }

    private fun roots(): List<Pair<String, File>> {
        val p = projectRoot()
        val list = mutableListOf(
            "проект" to File(p, ".pocketcode"),
            "проект" to File(p, ".claude"),
            "общие" to globalDir,
        )
        pluginsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { list += "плагин ${it.name}" to it }
        return list
    }

    fun skills(all: Boolean = false): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        for ((src, root) in roots()) {
            val candidates = mutableListOf<File>()
            File(root, "skills").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.let { candidates += it }
            if (File(root, "SKILL.md").isFile) candidates += root
            for (dir in candidates) {
                val f = File(dir, "SKILL.md")
                if (!f.isFile) continue
                val parsed = runCatching { parseFrontmatter(f.readText()) }.getOrNull() ?: continue
                val name = (parsed.meta["name"] ?: dir.name).trim().replace(' ', '-')
                if (name.isEmpty() || name in out) continue
                if (!all && name in disabled()) continue
                val desc = parsed.meta["description"]?.takeIf { it.isNotBlank() }
                    ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(160).orEmpty()
                out[name] = Skill(name, desc, dir, src)
            }
        }
        return out.values.toList()
    }

    fun commands(): List<Command> {
        val out = LinkedHashMap<String, Command>()
        for ((src, root) in roots()) {
            File(root, "commands").listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.sortedBy { it.name }?.forEach { f ->
                val name = f.name.removeSuffix(".md").lowercase().replace(' ', '-')
                if (name in out) return@forEach
                val parsed = runCatching { parseFrontmatter(f.readText()) }.getOrNull() ?: return@forEach
                val desc = parsed.meta["description"]?.takeIf { it.isNotBlank() }
                    ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(100).orEmpty()
                out[name] = Command(name, desc, f, src)
            }
        }
        return out.values.toList()
    }

    /** Текст навыка: SKILL.md и небольшие текстовые файлы рядом. */
    fun readSkill(skill: Skill): String {
        val sb = StringBuilder()
        sb.append(parseFrontmatter(File(skill.dir, "SKILL.md").readText()).body.trim())
        val extra = skill.dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name != "SKILL.md" && it.extension.lowercase() in setOf("md", "txt", "json", "yaml", "yml", "py", "sh", "js") }
            .sortedBy { it.path }.take(15).toList()
        var budget = 20_000
        for (f in extra) {
            if (budget <= 0) break
            val t = runCatching { f.readText().take(budget) }.getOrNull() ?: continue
            sb.append("\n\n--- файл навыка: ").append(f.relativeTo(skill.dir).path).append(" ---\n").append(t)
            budget -= t.length
        }
        return sb.toString()
    }

    /** Раскрывает «/имя аргументы» в текст команды или возвращает null, если такой команды нет. */
    fun expand(input: String): String? {
        if (!input.startsWith("/")) return null
        val name = input.substring(1).substringBefore(' ').substringBefore('\n').lowercase()
        val args = input.substring(1 + name.length).trim()
        val cmd = commands().firstOrNull { it.name == name } ?: return null
        val body = parseFrontmatter(cmd.file.readText()).body.trim()
        return if (body.contains("\$ARGUMENTS")) body.replace("\$ARGUMENTS", args)
        else if (args.isEmpty()) body else "$body\n\n$args"
    }

    /** Заметки агента о проекте (.pocketcode/NOTES.md): решения, договорённости, подводные камни. */
    fun projectNotes(): String? {
        val f = File(projectRoot(), ".pocketcode/NOTES.md")
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.takeLast(4000)
    }

    fun addNote(note: String) {
        val f = File(projectRoot(), ".pocketcode/NOTES.md")
        f.parentFile?.mkdirs()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        f.appendText("- ($date) ${note.trim().replace('\n', ' ')}\n")
    }

    /** AGENTS.md (или CLAUDE.md) из корня проекта. */
    fun projectInstructions(): String? {
        val root = projectRoot()
        val parts = listOf("AGENTS.md", "CLAUDE.md", ".pocketcode/AGENTS.md").mapNotNull { n ->
            File(root, n).takeIf { it.isFile }?.let { f -> runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { n to it } }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n") { (n, t) -> "[$n]\n" + t.take(10_000) }
    }
}
