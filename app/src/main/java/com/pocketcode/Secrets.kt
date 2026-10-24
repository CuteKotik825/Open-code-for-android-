package com.pocketcode

/** Маскирует ключи и токены в тексте, который уходит в журнал и отчёты. */
object Secrets {
    private val patterns = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{16,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("AIza[0-9A-Za-z_\\-]{30,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("xox[baprs]-[A-Za-z0-9\\-]{10,}"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{16,}"),
        Regex("(?i)((?:api[_-]?key|token|secret|password|passwd)\\s*[:=]\\s*)[\"']?[^\\s\"',;]{6,}"),
    )

    @Volatile var known: List<String> = emptyList()

    fun mask(text: String): String {
        var t = text
        for (k in known) if (k.length >= 8) t = t.replace(k, "***")
        for (p in patterns) {
            t = p.replace(t) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "***" else "***" }
        }
        return t
    }
}
