package com.pocketcode.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class Block {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Block()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : Block()

    /** Картинка пользователя (base64, JPEG/PNG). */
    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val data: String) : Block()
}

/** role: "user" или "assistant" */
@Serializable
data class Message(val role: String, val blocks: List<Block>)

data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

data class LlmResponse(
    val blocks: List<Block>,
    val stopReason: String?,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
)

interface LlmClient {
    /** onTextDelta вызывается из фонового потока по мере поступления текста. */
    suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): LlmResponse
}
