package com.pocketcode.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class AnthropicClient(
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
            put("max_tokens", 8192)
            put("system", system)
            putJsonArray("messages") {
                for (m in messages) {
                    // Пустые text-блоки API отвергает
                    val blocks = m.blocks.filter { it !is Block.Text || it.text.isNotBlank() }
                    if (blocks.isEmpty()) continue
                    addJsonObject {
                        put("role", m.role)
                        putJsonArray("content") {
                            for (b in blocks) {
                                addJsonObject {
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

        val url = baseUrl.trimEnd('/') + "/v1/messages"
        val text = Http.postJson(
            url,
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body.toString(),
        )

        val root = Json.parseToJsonElement(text).jsonObject
        val content = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val blocks = content.mapNotNull { el ->
            val o = el.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> Block.Text(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> Block.ToolUse(
                    id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    input = o["input"] as? JsonObject ?: JsonObject(emptyMap()),
                )
                else -> null
            }
        }
        return LlmResponse(blocks, (root["stop_reason"] as? JsonPrimitive)?.contentOrNull)
    }
}
