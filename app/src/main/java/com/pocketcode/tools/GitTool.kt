package com.pocketcode.tools

import com.pocketcode.Settings
import kotlinx.serialization.json.JsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.ByteArrayOutputStream
import java.io.File

private val READ_ONLY_ACTIONS = setOf("status", "log", "branch", "diff")

/** Git на чистой Java (JGit): внешние программы не нужны. */
class GitTool(
    private val ws: Workspace,
    private val homeDir: File,
    private val settings: () -> Settings,
) : Tool {
    override val name = "git"
    override val description =
        "Работа с git-репозиториями. action: clone (url, dir?), status, diff (неподготовленные изменения), " +
            "add (path?, по умолчанию всё), commit (message), push, pull, log (count?), " +
            "branch (список), checkout (branch, create?). dir — папка репозитория относительно рабочей " +
            "(по умолчанию корень). Для https-репозиториев GitHub используется токен из настроек."
    override val schema = objSchema(
        Prop("action", "string", "clone | status | diff | add | commit | push | pull | log | branch | checkout"),
        Prop("dir", "string", "Папка репозитория (для clone — куда клонировать)", required = false),
        Prop("url", "string", "URL репозитория для clone", required = false),
        Prop("message", "string", "Сообщение коммита", required = false),
        Prop("path", "string", "Что добавить в индекс для add", required = false),
        Prop("branch", "string", "Имя ветки для checkout", required = false),
        Prop("create", "boolean", "Создать ветку при checkout", required = false),
        Prop("count", "integer", "Сколько коммитов показать в log", required = false),
    )

    override fun needsApproval(input: JsonObject) = input.str("action") !in READ_ONLY_ACTIONS

    override fun describe(input: JsonObject): String {
        val action = input.str("action").orEmpty()
        val extra = listOfNotNull(input.str("url"), input.str("message"), input.str("branch"), input.str("dir"))
            .joinToString(" ")
        return "git $action $extra".trim()
    }

    override suspend fun run(input: JsonObject): String {
        runCatching {
            homeDir.mkdirs()
            FS.DETECTED.setUserHome(homeDir)
        }
        val action = input.need("action")
        val s = settings()
        val creds = if (s.githubToken.isNotBlank()) UsernamePasswordCredentialsProvider(s.githubToken, "") else null

        if (action == "clone") {
            val url = input.need("url")
            val dirName = input.str("dir") ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            val target = ws.resolve(dirName)
            require(!target.exists() || target.list().isNullOrEmpty()) { "Папка не пуста: ${ws.rel(target)}" }
            val cmd = Git.cloneRepository().setURI(url).setDirectory(target)
            if (creds != null) cmd.setCredentialsProvider(creds)
            cmd.call().close()
            return "Склонировано в ${ws.rel(target)}"
        }

        val repoDir = ws.resolve(input.str("dir") ?: ".")
        val git = try {
            Git.open(repoDir)
        } catch (e: Exception) {
            throw IllegalArgumentException("${ws.rel(repoDir)} — не git-репозиторий (нет .git)")
        }
        git.use {
            when (action) {
                "status" -> {
                    val st = git.status().call()
                    return buildString {
                        appendLine("Ветка: ${git.repository.branch}")
                        fun section(title: String, items: Set<String>) {
                            if (items.isEmpty()) return
                            appendLine("$title:")
                            items.sorted().forEach { appendLine("  $it") }
                        }
                        section("Добавлено в индекс", st.added)
                        section("Изменено в индексе", st.changed)
                        section("Удалено в индексе", st.removed)
                        section("Изменено, не в индексе", st.modified)
                        section("Удалено, не в индексе", st.missing)
                        section("Не отслеживается", st.untracked)
                        section("Конфликты", st.conflicting)
                        if (st.isClean) append("Рабочая копия чистая")
                    }.trimEnd()
                }
                "diff" -> {
                    val out = ByteArrayOutputStream()
                    git.diff().setOutputStream(out).call()
                    val text = out.toString("UTF-8")
                    return if (text.isBlank()) "Нет неподготовленных изменений" else text.take(30_000)
                }
                "add" -> {
                    val pattern = input.str("path") ?: "."
                    git.add().addFilepattern(pattern).call()
                    // второй проход фиксирует удаления
                    git.add().setUpdate(true).addFilepattern(pattern).call()
                    return "Добавлено в индекс: $pattern"
                }
                "commit" -> {
                    val message = input.need("message")
                    val commit = git.commit()
                        .setMessage(message)
                        .setAuthor(s.gitName, s.gitEmail)
                        .setCommitter(s.gitName, s.gitEmail)
                        .call()
                    return "Коммит ${commit.name.take(7)}: ${commit.shortMessage}"
                }
                "push" -> {
                    val cmd = git.push()
                    if (creds != null) cmd.setCredentialsProvider(creds)
                    val results = cmd.call()
                    val lines = results.flatMap { r ->
                        r.remoteUpdates.map { u -> "${u.remoteName}: ${u.status}" + (u.message?.let { " ($it)" } ?: "") }
                    }
                    return if (lines.isEmpty()) "Отправлено" else lines.joinToString("\n")
                }
                "pull" -> {
                    val cmd = git.pull()
                    if (creds != null) cmd.setCredentialsProvider(creds)
                    val r = cmd.call()
                    return if (r.isSuccessful) "Готово: ${r.mergeResult?.mergeStatus ?: "обновлено"}"
                    else "Не удалось выполнить pull: $r"
                }
                "log" -> {
                    val n = (input.int("count") ?: 10).coerceIn(1, 100)
                    return git.log().setMaxCount(n).call()
                        .joinToString("\n") { "${it.name.take(7)} ${it.authorIdent.name}: ${it.shortMessage}" }
                        .ifBlank { "Коммитов нет" }
                }
                "branch" -> {
                    val current = git.repository.branch
                    return git.branchList().call().joinToString("\n") {
                        val short = Repository.shortenRefName(it.name)
                        (if (short == current) "* " else "  ") + short
                    }.ifBlank { "Веток нет (нужен первый коммит)" }
                }
                "checkout" -> {
                    val branch = input.need("branch")
                    git.checkout().setName(branch).setCreateBranch(input.bool("create") == true).call()
                    return "Ветка: ${git.repository.branch}"
                }
                else -> throw IllegalArgumentException("Неизвестное действие git: $action")
            }
        }
    }
}
