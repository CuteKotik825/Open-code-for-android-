package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject

private val READ_ONLY_GIT = setOf(
    "status", "log", "diff", "show", "branch", "remote", "rev-parse", "ls-files", "blame", "describe", "shortlog",
)

/** Настоящий git внутри Linux (proot). Имя, e-mail и токен GitHub прописаны в ~/.gitconfig при установке/сохранении настроек. */
class LinuxGitTool(private val ws: Workspace, private val shell: Shell) : Tool {
    override val name = "git"
    override val description =
        "Запускает настоящий git (в Linux) в папке проекта. Передай args — всё, что идёт после слова git, " +
            "например: status | log --oneline -5 | add -A | commit -m \"текст\" | clone https://github.com/user/repo | push | pull. " +
            "Имя автора и токен GitHub уже настроены. Если git не установлен, поставь его: apk add git."
    override val schema = objSchema(
        Prop("args", "string", "Аргументы git"),
        Prop("timeout_sec", "integer", "Таймаут в секундах (по умолчанию 120, максимум 600)", required = false),
    )

    private fun args(input: JsonObject) = input.str("args").orEmpty().trim().removePrefix("git ").trim()

    override fun needsApproval(input: JsonObject): Boolean {
        val a = args(input)
        val first = a.split(Regex("\\s+")).firstOrNull().orEmpty()
        val chained = listOf(";", "&", "|", ">", "<", "`", "$(").any { a.contains(it) }
        val branchSafe = first != "branch" || a.split(Regex("\\s+")).drop(1).none { it in setOf("-d", "-D", "-m", "-M", "-c", "-C", "--delete") }
        return !(first in READ_ONLY_GIT && !chained && branchSafe)
    }

    override fun describe(input: JsonObject) = "git " + args(input)

    override suspend fun run(input: JsonObject): String {
        val a = args(input)
        require(a.isNotEmpty()) { "Не указаны аргументы git" }
        val timeout = (input.int("timeout_sec") ?: 120).coerceIn(1, 600).toLong()
        val out = shell.run("git $a", ws.root, timeout)
        return if (out.contains("git: not found") || out.contains("git: command not found")) {
            "$out\nGit не установлен в Linux. Выполни в bash: apk add git"
        } else out
    }
}
