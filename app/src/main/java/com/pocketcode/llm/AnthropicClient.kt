package com.pocketcode.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.TreeMap

class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** Кэш промпта: система и история помечаются точками кэша, повторные запросы дешевле и быстрее. */
    private val caching: Boolean = false,
    /** Максимум токенов в одном ответе; 0 — 16384. */
    private val maxTokens: Int = 0,
) : LlmClient {

    private class Acc {
        var type: String = ""
        val text = StringBuilder()
        var id: String = ""
        var name: String = ""
        val json = StringBuilder()
    }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", if (maxTokens > 0) maxTokens else 16384)
            put("stream", true)
            if (caching) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            } else {
                put("system", system)
            }
            val lastIdx = messages.indexOfLast { m -> m.blocks.any { it !is Block.Text || it.text.isNotBlank() } }
            putJsonArray("messages") {
                for ((mi, m) in messages.withIndex()) {
                    // Пустые text-блоки API отвергает
                    val blocks = m.blocks.filter { it !is Block.Text || it.text.isNotBlank() }
                    if (blocks.isEmpty()) continue
                    addJsonObject {
                        put("role", m.role)
                        putJsonArray("content") {
                            for (b in blocks) {
                                addJsonObject {
                                    if (caching && mi == lastIdx && b === blocks.last()) {
                                        putJsonObject("cache_control") { put("type", "ephemeral") }
                                    }
                                    when (b) {
                                        is Block.Text -> {
                                            put("type", "text")
                                            put("text", b.text)
                                        }
                                        is Block.ToolUse -> {
                                            put("type", "tool_use")
                                            put("id", b.id)
                                            put("name", b.name)
                                            put("input", b.input)
                                        }
                                        is Block.Image -> {
                                            put("type", "image")
                                            putJsonObject("source") {
                                                put("type", "base64")
                                                put("media_type", b.mediaType)
                                                put("data", b.data)
                                            }
                                        }
                                        is Block.ToolResult -> {
                                            put("type", "tool_result")
                                            put("tool_use_id", b.toolUseId)
                                            put("content", b.content)
                                            if (b.isError) put("is_error", true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (t in tools) {
                        addJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.schema)
                        }
                    }
                }
            }
        }

        val acc = TreeMap<Int, Acc>()
        var stopReason: String? = null
        var inTok = 0
        var outTok = 0
        var cacheTok = 0
        var served = ""

        Http.postSse(
            baseUrl.trimEnd('/') + "/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body.toString(),
        ) { data ->
            val ev = Json.parseToJsonElement(data).jsonObject
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val index = ev["index"]?.jsonPrimitive?.intOrNull ?: return@postSse
                    val block = ev["content_block"]?.jsonObject ?: return@postSse
                    val a = Acc()
                    a.type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    a.id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    a.name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val initial = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (initial.isNotEmpty()) {
                        a.text.append(initial)
                        onTextDelta(initial)
                    }
                    acc[index] = a
                }
                "content_block_delta" -> {
                    val index = ev["index"]?.jsonPrimitive?.intOrNull ?: return@postSse
                    val a = acc[index] ?: return@postSse
                    val delta = ev["delta"]?.jsonObject ?: return@postSse
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val t = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (t.isNotEmpty()) {
                                a.text.append(t)
                                onTextDelta(t)
                            }
                        }
                        "input_json_delta" ->
                            a.json.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
                "message_start" -> {
                    served = ev["message"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val u = ev["message"]?.jsonObject?.get("usage")?.jsonObject
                    inTok = (u?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0) +
                        (u?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull ?: 0)
                    cacheTok = u?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull ?: 0
                    outTok = u?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
                }
                "message_delta" -> {
                    ev["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { outTok = it }
                    stopReason = ev["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                }
                "error" -> {
                    val msg = ev["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    throw IOException(msg ?: data.take(400))
                }
            }
        }

        val blocks = mutableListOf<Block>()
        for (a in acc.values) {
            when (a.type) {
                "text" -> if (a.text.isNotBlank()) blocks += Block.Text(a.text.toString())
                "tool_use" -> {
                    val input = try {
                        Json.parseToJsonElement(a.json.toString().ifBlank { "{}" }) as? JsonObject
                            ?: JsonObject(emptyMap())
                    } catch (e: Exception) {
                        JsonObject(emptyMap())
                    }
                    blocks += Block.ToolUse(a.id, a.name, input)
                }
            }
        }
        return LlmResponse(blocks, stopReason, inTok, outTok, cacheTok, served)
    }
}
