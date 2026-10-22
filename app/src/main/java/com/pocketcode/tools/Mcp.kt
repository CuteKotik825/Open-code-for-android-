package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class McpToolDef(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean = false)

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    /** По строке «Имя: значение», например «Authorization: Bearer …». */
    val headers: String = "",
    val enabled: Boolean = true,
    val tools: List<McpToolDef> = emptyList(),
)

/** Клиент MCP по HTTP (Streamable HTTP): JSON-RPC через POST, ответ JSON или SSE. */
class McpClient(private val server: McpServer) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private var sessionId: String? = null
    private var nextId = 1

    private fun post(body: JsonObject, expectResponse: Boolean): JsonObject? {
        val rb = Request.Builder().url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
        sessionId?.let { rb.header("Mcp-Session-Id", it) }
        server.headers.lines().forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) rb.header(l.substring(0, i).trim(), l.substring(i + 1).trim())
        }
        rb.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(rb.build()).execute().use { r ->
            r.header("Mcp-Session-Id")?.let { sessionId = it }
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "HTTP ${r.code}: ${text.take(300)}" }
            if (!expectResponse) return null
            val id = (body["id"] as? JsonPrimitive)?.contentOrNull
            val ct = r.header("Content-Type") ?: ""
            val candidates: List<String> = if (ct.contains("event-stream")) {
                text.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            } else listOf(text)
            for (c in candidates) {
                val el = runCatching { Json.parseToJsonElement(c) }.getOrNull() ?: continue
                val obj = el as? JsonObject ?: continue
                if ((obj["id"] as? JsonPrimitive)?.contentOrNull == id) return obj
            }
            error("Пустой или непонятный ответ сервера")
        }
    }

    private fun rpc(method: String, params: JsonObject): JsonElement {
        val id = nextId++
        val resp = post(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }, true)!!
        (resp["error"] as? JsonObject)?.let { error(it["message"]?.let { m -> (m as? JsonPrimitive)?.contentOrNull } ?: it.toString()) }
        return resp["result"] ?: JsonObject(emptyMap())
    }

    private fun init() {
        rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "PocketCode"); put("version", "1.0") }
        })
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, false)
    }

    suspend fun listTools(): List<McpToolDef> = withContext(Dispatchers.IO) {
        init()
        val res = rpc("tools/list", JsonObject(emptyMap())).jsonObject
        (res["tools"] as? JsonArray ?: JsonArray(emptyList())).map { t ->
            val o = t.jsonObject
            val schema = (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") }
            val ro = ((o["annotations"] as? JsonObject)?.get("readOnlyHint") as? JsonPrimitive)?.contentOrNull == "true"
            McpToolDef(
                (o["name"] as JsonPrimitive).content,
                (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                schema, ro,
            )
        }
    }

    suspend fun call(tool: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        init()
        val res = rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) }).jsonObject
        val parts = (res["content"] as? JsonArray ?: JsonArray(emptyList())).map { c ->
            val o = c.jsonObject
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                else -> "[${(o["type"] as? JsonPrimitive)?.contentOrNull ?: "данные"} пропущены]"
            }
        }
        val out = parts.joinToString("\n").ifEmpty { res.toString() }
        if ((res["isError"] as? JsonPrimitive)?.contentOrNull == "true") throw IllegalStateException(out)
        out
    }
}

/** Инструмент, который предоставляет MCP-сервер. */
class McpTool(private val server: McpServer, private val def: McpToolDef) : Tool {
    override val name = "mcp_" + server.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') + "_" + def.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
    override val description = "[MCP ${server.name}] " + def.description
    override val schema = def.schema
    override fun needsApproval(input: JsonObject) = !def.readOnly
    override fun describe(input: JsonObject) = "${server.name}: ${def.name} ${input.toString().take(200)}"
    override suspend fun run(input: JsonObject): String = McpClient(server).call(def.name, input)
}
