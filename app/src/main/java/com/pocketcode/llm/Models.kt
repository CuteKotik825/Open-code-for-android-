package com.pocketcode.llm

import kotlinx.serialization.json.JsonObject

sealed class Block {
    data class Text(val text: String) : Block()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean) : Block()
}

/** role: "user" или "assistant" */
data class Message(val role: String, val blocks: List<Block>)

data class ToolSpec(val name: String, val description: String, val schema: JsonObject)

data class LlmResponse(val blocks: List<Block>, val stopReason: String?)

interface LlmClient {
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): LlmResponse
}
