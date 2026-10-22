package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** GitHub API: pull request, issues, статус проверок. Токен берётся из настроек. */
class GitHubTool(private val ws: Workspace, private val token: () -> String) : Tool {
    override val name = "github"
    override val description =
        "GitHub API для репозитория текущего проекта (определяется по remote origin). action: " +
            "pr_create (title, body, head?, base?), pr_list, pr_get (number), issue_list, issue_get (number), " +
            "issue_create (title, body), issue_comment (number, body), checks (ref? — ветка или коммит, по умолчанию текущая ветка). " +
            "Перед pr_create ветку нужно отправить (git push). Токен GitHub указывается в настройках."
    override val schema = objSchema(
        Prop("action", "string", "Действие"),
        Prop("title", "string", "Заголовок", required = false),
        Prop("body", "string", "Текст", required = false),
        Prop("head", "string", "Ветка с изменениями (по умолчанию текущая)", required = false),
        Prop("base", "string", "Целевая ветка (по умолчанию основная)", required = false),
        Prop("number", "integer", "Номер PR или issue", required = false),
        Prop("ref", "string", "Ветка или коммит для checks", required = false),
    )

    private val writes = setOf("pr_create", "issue_create", "issue_comment")
    override fun needsApproval(input: JsonObject) = input.str("action") in writes
    override fun describe(input: JsonObject) = "github ${input.str("action")} ${input.str("title") ?: input.int("number")?.let { "#$it" } ?: ""}".trim()

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun repo(): String {
        val cfg = File(ws.root, ".git/config")
        require(cfg.isFile) { "В проекте нет git-репозитория" }
        val url = Regex("""\[remote "origin"][^\[]*?url\s*=\s*(\S+)""").find(cfg.readText())?.groupValues?.get(1)
            ?: throw IllegalStateException("У репозитория нет remote origin")
        return Regex("""github\.com[:/]+([^/]+)/([^/\s]+?)(\.git)?$""").find(url)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
            ?: throw IllegalStateException("origin ($url) — не GitHub")
    }

    private fun branch(): String {
        val head = File(ws.root, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return head.removePrefix("ref: refs/heads/").takeIf { head.startsWith("ref:") } ?: throw IllegalStateException("Не на ветке (detached HEAD)")
    }

    private fun call(method: String, path: String, body: JsonObject? = null): kotlinx.serialization.json.JsonElement {
        val t = token()
        require(t.isNotBlank()) { "Нужен токен GitHub: добавь его в настройках" }
        val rb = Request.Builder().url("https://api.github.com$path")
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        rb.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "GitHub ${r.code}: " + (runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.let { (it as JsonPrimitive).content } }.getOrNull() ?: text.take(300)) }
            return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val repo = repo()
        when (val action = input.need("action")) {
            "pr_create" -> {
                val head = input.str("head") ?: branch()
                val base = input.str("base") ?: call("GET", "/repos/$repo").jsonObject.s("default_branch")
                val r = call("POST", "/repos/$repo/pulls", buildJsonObject {
                    put("title", input.need("title")); put("body", input.str("body") ?: "")
                    put("head", head); put("base", base)
                }).jsonObject
                "PR создан: ${r.s("html_url")}"
            }
            "pr_list" -> (call("GET", "/repos/$repo/pulls?state=open&per_page=15") as JsonArray).joinToString("\n") {
                val o = it.jsonObject; "#${(o["number"] as JsonPrimitive).content} ${o.s("title")} (${o.s("html_url")})"
            }.ifEmpty { "Открытых PR нет." }
            "pr_get" -> call("GET", "/repos/$repo/pulls/${input.int("number") ?: error("Нужен number")}").jsonObject.let {
                "#${(it["number"] as JsonPrimitive).content} ${it.s("title")} [${it.s("state")}]\n${it.s("html_url")}\n\n${it.s("body").take(3000)}"
            }
            "issue_list" -> (call("GET", "/repos/$repo/issues?state=open&per_page=20") as JsonArray)
                .filter { (it.jsonObject["pull_request"]) == null }.joinToString("\n") {
                    val o = it.jsonObject; "#${(o["number"] as JsonPrimitive).content} ${o.s("title")}"
                }.ifEmpty { "Открытых issues нет." }
            "issue_get" -> {
                val n = input.int("number") ?: error("Нужен number")
                val o = call("GET", "/repos/$repo/issues/$n").jsonObject
                val comments = (call("GET", "/repos/$repo/issues/$n/comments?per_page=10") as JsonArray)
                    .joinToString("\n---\n") { c -> c.jsonObject["user"]!!.jsonObject.s("login") + ": " + c.jsonObject.s("body").take(800) }
                "#$n ${o.s("title")} [${o.s("state")}]\n${o.s("body").take(3000)}\n\nКомментарии:\n$comments"
            }
            "issue_create" -> call("POST", "/repos/$repo/issues", buildJsonObject {
                put("title", input.need("title")); put("body", input.str("body") ?: "")
            }).jsonObject.let { "Issue создан: ${it.s("html_url")}" }
            "issue_comment" -> call("POST", "/repos/$repo/issues/${input.int("number") ?: error("Нужен number")}/comments",
                buildJsonObject { put("body", input.need("body")) }).jsonObject.let { "Комментарий добавлен: ${it.s("html_url")}" }
            "checks" -> {
                val ref = input.str("ref") ?: branch()
                val runs = call("GET", "/repos/$repo/commits/$ref/check-runs?per_page=30").jsonObject["check_runs"]?.jsonArray ?: JsonArray(emptyList())
                if (runs.isEmpty()) "Проверок нет."
                else runs.joinToString("\n") { val o = it.jsonObject; "${o.s("name")}: ${o.s("status")} ${o.s("conclusion")}" }
            }
            else -> throw IllegalArgumentException("Неизвестное действие: $action")
        }
    }
}
