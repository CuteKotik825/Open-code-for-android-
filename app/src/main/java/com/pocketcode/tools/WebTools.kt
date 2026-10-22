package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

private val webHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

private fun get(url: String): Pair<String, String> {
    val req = Request.Builder().url(url).header("User-Agent", UA)
        .header("Accept-Language", "ru,en;q=0.8").build()
    webHttp.newCall(req).execute().use { r ->
        val ct = r.header("Content-Type") ?: ""
        val body = r.body?.source()?.let { src ->
            src.request(3_000_000)
            src.buffer.clone().readUtf8()
        } ?: ""
        check(r.isSuccessful) { "HTTP ${r.code}" }
        return ct to body
    }
}

/** Читает веб-страницу и возвращает её текст. */
class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description =
        "Скачать веб-страницу по адресу и вернуть её текст (HTML очищается). Для документации, статей, API-ответов (JSON отдаётся как есть). " +
            "Только чтение (GET). Длинные страницы обрезаются; параметр offset позволяет читать дальше."
    override val schema = objSchema(
        Prop("url", "string", "Адрес http(s)"),
        Prop("offset", "integer", "С какого символа продолжить (по умолчанию 0)", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "открыть ${input.str("url") ?: ""}"
    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val url = input.need("url").trim()
        require(url.startsWith("http://") || url.startsWith("https://")) { "Нужен адрес http(s)" }
        val offset = (input.int("offset") ?: 0).coerceAtLeast(0)
        val (ct, body) = get(url)
        val text = if (ct.contains("html", true) || body.trimStart().startsWith("<")) {
            val doc = Jsoup.parse(body, url)
            doc.select("script,style,noscript,svg,nav,footer,header,form,iframe").remove()
            val title = doc.title()
            val main = doc.selectFirst("main, article") ?: doc.body()
            val sb = StringBuilder()
            if (title.isNotBlank()) sb.append("# ").append(title).append("\n\n")
            main.select("a[href]").forEach { a ->
                val h = a.absUrl("href")
                if (h.isNotEmpty() && a.text().isNotBlank()) a.text("${a.text()} <$h>")
            }
            main.select("br").append("\\n")
            main.select("p,div,li,h1,h2,h3,h4,h5,h6,pre,tr").prepend("\\n")
            sb.append(main.wholeText().replace("\\n", "\n"))
            sb.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
        } else body
        val limit = 12_000
        if (offset >= text.length) "Конец страницы."
        else {
            val part = text.substring(offset, minOf(text.length, offset + limit))
            if (offset + limit < text.length) part + "\n\n[...обрезано; всего ${text.length} символов, продолжение: offset=${offset + limit}]" else part
        }
    }
}

/** Поиск в интернете через DuckDuckGo (без ключа). */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Поиск в интернете. Возвращает до 8 результатов: заголовок, адрес, фрагмент. Потом открывай нужные через web_fetch."
    override val schema = objSchema(Prop("query", "string", "Поисковый запрос"))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "поиск: ${input.str("query") ?: ""}"
    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val q = input.need("query").trim()
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val (_, html) = get("https://html.duckduckgo.com/html/?q=$enc")
        val doc = Jsoup.parse(html)
        val results = doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            val m = Regex("[?&]uddg=([^&]+)").find(href)
            if (m != null) href = URLDecoder.decode(m.groupValues[1], "UTF-8")
            if (href.startsWith("//")) href = "https:$href"
            Triple(a.text(), href, r.selectFirst(".result__snippet")?.text().orEmpty())
        }.take(8)
        if (results.isEmpty()) "Ничего не найдено (или поисковик временно отказал). Попробуй другой запрос."
        else results.mapIndexed { i, (t, u, s) -> "${i + 1}. $t\n   $u\n   $s" }.joinToString("\n")
    }
}
