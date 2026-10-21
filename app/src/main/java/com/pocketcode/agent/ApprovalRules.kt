package com.pocketcode.agent

/**
 * Правила автоподтверждения: безопасные команды, которые только читают, не спрашиваем.
 * Всё, что содержит перенаправление вывода, подстановку команд или опасные ключи, идёт на подтверждение.
 */
object ApprovalRules {
    private val SAFE = listOf(
        "ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "find", "which", "whoami", "uname", "df", "du",
        "stat", "file", "tree", "id", "env", "printenv", "basename", "dirname", "realpath", "sort", "uniq", "cut",
        "git status", "git log", "git diff", "git show", "git branch", "git remote", "git --version",
        "python3 --version", "python --version", "node --version", "npm --version", "pip --version", "pip3 --version",
        "apk list", "apk info", "apk search",
    )
    private val DANGEROUS_TOKENS = listOf("-exec", "-execdir", "-ok", "-delete", "-fdelete", "--output", "-o", "-fprint")
    private val FORBIDDEN_CHARS = listOf(">", "`", "$(", "\${")

    private fun segments(command: String): List<String> =
        command.split(Regex("&&|\\|\\||;|\\||\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun matchesPrefix(segment: String, prefix: String) =
        segment == prefix || segment.startsWith("$prefix ")

    private fun pathsInside(command: String, roots: List<String>): Boolean {
        // Переменные оболочки и «~» могут указывать куда угодно
        if (command.contains('$') || command.contains('~')) return false
        for (raw in command.split(Regex("\\s+"))) {
            val t = raw.trim('"', '\'')
            if (t.contains("..")) return false
            if (t.startsWith("/") && t != "/dev/null" && roots.none { t == it || t.startsWith("$it/") }) return false
        }
        return true
    }

    /** command — текст команды без начального «$ ». */
    fun isSafeBash(
        command: String,
        extraPrefixes: List<String>,
        useBuiltin: Boolean = true,
        /** Если задано (оболочка Android), команда не должна трогать пути вне этих папок. */
        allowedRoots: List<String>? = null,
    ): Boolean {
        if (command.isBlank()) return false
        if (allowedRoots != null && !pathsInside(command, allowedRoots)) return false
        if (FORBIDDEN_CHARS.any { command.contains(it) }) return false
        val segs = segments(command)
        if (segs.isEmpty()) return false
        val extras = extraPrefixes.map { it.trim() }.filter { it.isNotEmpty() }
        return segs.all { seg ->
            val tokens = seg.split(Regex("\\s+"))
            val builtin = useBuiltin && SAFE.any { matchesPrefix(seg, it) } && tokens.none { it in DANGEROUS_TOKENS } &&
                !(matchesPrefix(seg, "git branch") && tokens.drop(2).any { it in setOf("-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move") })
            builtin || extras.any { matchesPrefix(seg, it) }
        }
    }
}
