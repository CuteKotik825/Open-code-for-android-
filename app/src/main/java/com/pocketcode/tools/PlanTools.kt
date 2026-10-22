package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject

/** Показывает план пользователю и ждёт одобрения; пока его нет, в режиме плана менять ничего нельзя. */
class PresentPlanTool(private val onApproved: () -> Unit) : Tool {
    override val name = "present_plan"
    override val description =
        "Показать пользователю план работы и получить одобрение. Обязательно вызывай в режиме плана, когда исследование закончено. " +
            "План: нумерованные шаги, какие файлы будут изменены, как проверить результат."
    override val schema = objSchema(Prop("plan", "string", "Текст плана"))
    override fun needsApproval(input: JsonObject) = true
    override val alwaysAsk = true
    override fun describe(input: JsonObject) = "ПЛАН:\n" + (input.str("plan") ?: "").take(4000)
    override suspend fun run(input: JsonObject): String {
        onApproved()
        return "План одобрен пользователем. Режим плана выключен, приступай к реализации."
    }
}

/** Чек-лист задач агента: пользователь видит прогресс в чате. */
class TodoTool(private val onUpdate: (List<String>) -> Unit) : Tool {
    override val name = "todo"
    override val description =
        "Вести список задач для сложной работы (3+ шагов). Передавай весь список целиком, по строке на задачу: " +
            "«[ ] текст» — не начата, «[~] текст» — в работе, «[x] текст» — готова. Обновляй по ходу работы."
    override val schema = objSchema(Prop("todos", "string", "Список задач, по строке на задачу"))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "обновляю список задач"
    override suspend fun run(input: JsonObject): String {
        val lines = input.need("todos").lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        onUpdate(lines)
        val done = lines.count { it.startsWith("[x]", true) }
        return "Список обновлён: $done из ${lines.size} готово."
    }
}
