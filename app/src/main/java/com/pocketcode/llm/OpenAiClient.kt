package com.pocketcode.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Любой OpenAI-совместимый API (OpenAI, OpenRouter, DeepSeek, локальный сервер и т.п.). */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmClient {

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
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
                        if (text.isNotBlank()) {
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

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val text = Http.postJson(url, mapOf("Authorization" to "Bearer $apiKey"), body.toString())

        val root = Json.parseToJsonElement(text).jsonObject
        val choices = root["choices"] as? JsonArray
        val choice = choices?.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("Пустой ответ модели: ${text.take(400)}")
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())

        val blocks = mutableListOf<Block>()
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
        if (!content.isNullOrBlank()) blocks += Block.Text(content)

        (message["tool_calls"] as? JsonArray)?.forEach { el ->
            val call = el as? JsonObject ?: return@forEach
            val fn = call["function"] as? JsonObject ?: return@forEach
            val args = (fn["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val input = try {
                Json.parseToJsonElement(args.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
            blocks += Block.ToolUse(
                id = (call["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                input = input,
            )
        }
        return LlmResponse(blocks, (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull)
    }
}
