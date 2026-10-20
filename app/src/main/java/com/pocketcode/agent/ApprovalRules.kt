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

    /** command — текст команды без начального «$ ». */
    fun isSafeBash(command: String, extraPrefixes: List<String>, useBuiltin: Boolean = true): Boolean {
        if (command.isBlank()) return false
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
