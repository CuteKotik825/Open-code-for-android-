package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject
import java.io.File

/** Команды от root (su) на этом телефоне, вне песочницы приложения. */
class RootShell {
    suspend fun run(command: String, cwd: File, timeoutSec: Long): String {
        val pb = ProcessBuilder("su", "-c", command)
            .directory(if (cwd.isDirectory) cwd else File("/"))
            .redirectErrorStream(true)
        return runProcess(pb, timeoutSec)
    }

    /** Проверка: есть ли su и выдал ли менеджер root доступ приложению. */
    suspend fun check(): String = try {
        run("id", File("/"), 30)
    } catch (e: Exception) {
        "su недоступен: ${e.message}"
    }
}

class RootTool(private val ws: Workspace, private val root: RootShell) : Tool {
    override val name = "root"
    override val description =
        "Выполняет команду от root (su) на ЭТОМ телефоне, вне песочницы приложения: можно читать и менять системные файлы. " +
            "Каждый вызов подтверждает пользователь. Используй только когда без root нельзя, не трогай ничего за пределами задачи, " +
            "для работы с файлами проекта root не нужен."
    override val schema = objSchema(
        Prop("command", "string", "Команда для su -c"),
        Prop("timeout_sec", "integer", "Таймаут в секундах (по умолчанию 60, максимум 300)", required = false),
    )

    override val alwaysAsk: Boolean get() = true
    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "ROOT $ " + input.str("command").orEmpty()

    override suspend fun run(input: JsonObject): String {
        val timeout = (input.int("timeout_sec") ?: 60).coerceIn(1, 300).toLong()
        return root.run(input.need("command"), ws.root, timeout)
    }
}
