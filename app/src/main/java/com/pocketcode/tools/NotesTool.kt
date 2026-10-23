package com.pocketcode.tools

import com.pocketcode.agent.Extensions
import kotlinx.serialization.json.JsonObject

/** Долгая память проекта: агент записывает важное в .pocketcode/NOTES.md, и это попадает в начало каждого нового чата. */
class NotesTool(private val ext: Extensions) : Tool {
    override val name = "remember"
    override val description =
        "Записать в заметки проекта важный факт, который пригодится в следующих чатах: принятое решение, договорённость с пользователем, " +
            "как запускать тесты/сборку, неочевидная ловушка. Одна короткая строка. Не записывай то, что и так видно из кода."
    override val schema = objSchema(Prop("note", "string", "Короткая заметка"))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "заметка: " + (input.str("note") ?: "").take(100)
    override suspend fun run(input: JsonObject): String {
        ext.addNote(input.need("note"))
        return "Записано в .pocketcode/NOTES.md"
    }
}
