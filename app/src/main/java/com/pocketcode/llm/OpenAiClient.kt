package com.pocketcode.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.TreeMap

/** Любой OpenAI-совместимый API (OpenAI, OpenRouter, DeepSeek, локальный сервер и т.п.). */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** Для OpenRouter помечает системное сообщение точкой кэша (OpenAI, DeepSeek и др. кэшируют префикс сами). */
    private val caching: Boolean = false,
    /** Максимум токенов в ответе; 0 — не передавать, пусть решает провайдер. */
    private val maxTokens: Int = 0,
) : LlmClient {

    private class CallAcc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (maxTokens > 0) put("max_tokens", maxTokens)
            if (baseUrl.contains("api.openai.com")) putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    if (caching && baseUrl.contains("openrouter", ignoreCase = true)) {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", system)
                                putJsonObject("cache_control") { put("type", "ephemeral") }
                            }
                        }
                    } else {
                        put("content", system)
                    }
                }
                for (m in messages) {
                    if (m.role == "assistant") {
                        val text = m.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                        val calls = m.blocks.filterIsInstance<Block.ToolUse>()
                        if (text.isBlank() && calls.isEmpty()) continue
                        addJsonObject {
                            put("role", "assistant")
                            if (text.isBlank()) put("content", JsonNull) else put("content", text)
                            if (calls.isNotEmpty()) {
                                putJsonArray("tool_calls") {
                                    for (c in calls) {
                                        addJsonObject {
                                            put("id", c.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", c.name)
                                                put("arguments", c.input.toString())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // tool-сообщения должны идти сразу после assistant с tool_calls
                        for (r in m.blocks.filterIsInstance<Block.ToolResult>()) {
                            addJsonObject {
                                put("role", "tool")
                                put("tool_call_id", r.toolUseId)
                                put("content", r.content)
                            }
                        }
                        val text = m.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                        val imgs = m.blocks.filterIsInstance<Block.Image>()
                        if (imgs.isNotEmpty()) {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    if (text.isNotBlank()) addJsonObject { put("type", "text"); put("text", text) }
                                    for (im in imgs) addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:${im.mediaType};base64,${im.data}") }
                                    }
                                }
                            }
                        } else if (text.isNotBlank()) {
                            addJsonObject {
                                put("role", "user")
                                put("content", text)
                            }
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (t in tools) {
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.schema)
                            }
                        }
                    }
                }
            }
        }

        val text = StringBuilder()
        val calls = TreeMap<Int, CallAcc>()
        var finishReason: String? = null
        var inTok = 0
        var outTok = 0
        var cacheTok = 0

        Http.postSse(
            baseUrl.trimEnd('/') + "/chat/completions",
            mapOf("Authorization" to "Bearer $apiKey"),
            body.toString(),
        ) { data ->
            val ev = Json.parseToJsonElement(data).jsonObject
            (ev["error"] as? JsonObject)?.let { err ->
                throw IOException((err["message"] as? JsonPrimitive)?.contentOrNull ?: data.take(400))
            }
            (ev["usage"] as? JsonObject)?.let { u ->
                inTok = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: inTok
                outTok = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: outTok
                cacheTok = ((u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull ?: cacheTok
            }
            val choice = (ev["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@postSse
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return@postSse

            (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { piece ->
                if (piece.isNotEmpty()) {
                    text.append(piece)
                    onTextDelta(piece)
                }
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                val tc = el as? JsonObject ?: return@forEach
                val index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val acc = calls.getOrPut(index) { CallAcc() }
                (tc["id"] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) acc.id = it }
                val fn = tc["function"] as? JsonObject
                (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) acc.name = it }
                (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { acc.args.append(it) }
            }
        }

        val blocks = mutableListOf<Block>()
        if (text.isNotBlank()) blocks += Block.Text(text.toString())
        for ((index, c) in calls) {
            if (c.name.isEmpty()) continue
            val input = try {
                Json.parseToJsonElement(c.args.toString().ifBlank { "{}" }) as? JsonObject
                    ?: JsonObject(emptyMap())
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            blocks += Block.ToolUse(c.id.ifEmpty { "call_$index" }, c.name, input)
        }
        return LlmResponse(blocks, finishReason, inTok, outTok, cacheTok)
    }
}
