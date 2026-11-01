package com.pocketcode.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Маленький локальный HTTP-прокси (только 127.0.0.1). OpenCode и Claude Code, работающие в Linux,
 * ходят на http://127.0.0.1:PORT/t/ТОКЕН/<адрес сервиса в base64>/путь, а прокси пересылает запрос
 * на настоящий адрес, предварительно подождав своей очереди в [RateGate]. Считаются только POST
 * (запросы к модели), остальное проходит без задержки. Ответ передаётся потоком.
 */
class RateProxy(private val token: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()

    val running get() = server?.isClosed == false

    fun start(): Boolean {
        if (running) return true
        return try {
            server = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
            scope.launch {
                val s = server ?: return@launch
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Throwable) { break }
                    launch { runCatching { handle(c) }; runCatching { c.close() } }
                }
            }
            true
        } catch (_: Throwable) { false }
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
        }
    }

    private fun reply(c: Socket, code: Int, msg: String) {
        val body = msg.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 $code X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body); flush()
        }
    }

    private fun handle(c: Socket) {
        val i = BufferedInputStream(c.getInputStream())
        val first = readLine(i) ?: return
        val parts = first.split(" ")
        if (parts.size < 2) return reply(c, 400, "bad request")
        val method = parts[0]; val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val l = readLine(i) ?: break
            if (l.isEmpty()) break
            val k = l.indexOf(':'); if (k > 0) headers[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim()
        }
        val m = Regex("^/t/([^/]+)/([A-Za-z0-9_-]+)(/.*)?$").find(target) ?: return reply(c, 404, "PocketCode rate proxy: неверный путь")
        if (m.groupValues[1] != token) return reply(c, 403, "bad token")
        val base = runCatching { String(Base64.getUrlDecoder().decode(m.groupValues[2])) }.getOrNull()
        if (base == null || !(base.startsWith("https://") || base.startsWith("http://"))) return reply(c, 400, "bad upstream")
        val url = base.trimEnd('/') + m.groupValues[3]
        // тело запроса
        val body: ByteArray = if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val n = readLine(i)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
                if (n == 0) { readLine(i); break }
                val buf = ByteArray(n); var off = 0
                while (off < n) { val r = i.read(buf, off, n - off); if (r < 0) break; off += r }
                out.write(buf, 0, off); readLine(i)
            }
            out.toByteArray()
        } else {
            val n = headers["content-length"]?.toIntOrNull() ?: 0
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = i.read(buf, off, n - off); if (r < 0) break; off += r }
            buf
        }
        if (method == "POST") runBlocking { RateGate.acquire() }
        val rb = Request.Builder().url(url)
        val skip = setOf("host", "connection", "content-length", "accept-encoding", "transfer-encoding", "proxy-connection", "keep-alive")
        headers.forEach { (k, v) -> if (k !in skip) rb.header(k, v) }
        val ct = headers["content-type"]?.toMediaTypeOrNull()
        if (method == "GET" || method == "HEAD") rb.method(method, null) else rb.method(method, body.toRequestBody(ct))
        client.newCall(rb.build()).execute().use { r ->
            val o = c.getOutputStream()
            val sb = StringBuilder("HTTP/1.1 ${r.code} ${r.message.ifEmpty { "X" }}\r\n")
            r.headers.forEach { (k, v) ->
                if (k.lowercase() !in setOf("transfer-encoding", "content-length", "connection", "content-encoding")) sb.append("$k: $v\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            o.write(sb.toString().toByteArray()); o.flush()
            val src = r.body?.source()
            if (src != null) {
                val buf = ByteArray(4096)
                while (true) {
                    val n = src.read(buf); if (n < 0) break
                    o.write(buf, 0, n); o.flush()
                }
            }
        }
    }

    companion object {
        const val PORT = 18765
        fun wrap(token: String, upstream: String): String =
            "http://127.0.0.1:$PORT/t/$token/" + Base64.getUrlEncoder().withoutPadding().encodeToString(upstream.trimEnd('/').toByteArray())
    }
}
