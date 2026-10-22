package com.pocketcode.agent

import com.pocketcode.tools.Tool
import kotlinx.serialization.json.JsonObject

enum class Decision { DEFAULT, ALLOW, ASK, DENY }

/** Точки, в которые приложение вмешивается в работу агента: режим плана, правила, хуки, журнал правок. */
interface AgentHooks {
    fun planMode(): Boolean = false
    fun decide(tool: String, input: JsonObject, description: String): Decision = Decision.DEFAULT
    suspend fun before(tool: Tool, input: JsonObject) {}

    /** Возвращает текст, который добавится к результату инструмента (например, вывод хука). */
    suspend fun after(tool: Tool, input: JsonObject, output: String, isError: Boolean): String? = null

    companion object {
        val NONE = object : AgentHooks {}
    }
}

/** Инструменты, разрешённые в режиме плана: только чтение и согласование плана. */
val PLAN_ALLOWED = setOf(
    "read_file", "list_dir", "grep", "web_search", "web_fetch", "task", "skill", "todo", "present_plan",
    "code_search", "diagnostics",
)

/**
 * Правила пользователя, по строке: `allow|ask|deny инструмент [часть описания]`.
 * Инструмент — имя или `*`. Побеждает первое подходящее правило.
 * Примеры: `deny git push`, `ask write_file src/`, `allow edit_file`.
 */
object PermissionRules {
    fun decide(rules: String, tool: String, description: String): Decision {
        for (raw in rules.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 3)
            if (parts.size < 2) continue
            val decision = when (parts[0].lowercase()) {
                "allow" -> Decision.ALLOW
                "ask" -> Decision.ASK
                "deny" -> Decision.DENY
                else -> continue
            }
            if (parts[1] != "*" && !parts[1].equals(tool, ignoreCase = true)) continue
            val pattern = parts.getOrNull(2)?.trim().orEmpty()
            if (pattern.isEmpty() || description.contains(pattern, ignoreCase = true)) return decision
        }
        return Decision.DEFAULT
    }
}
