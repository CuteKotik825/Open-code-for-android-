package com.pocketcode.tools

import com.pocketcode.python.PythonRunner
import kotlinx.serialization.json.JsonObject

class PythonTool(private val ws: Workspace, private val runner: PythonRunner) : Tool {
    override val name = "python"
    override val description =
        "Запускает Python 3.11, встроенный в приложение. Передай code (фрагмент) или file (путь к .py в проекте). " +
            "Рабочая папка запуска — корень проекта. Доступна только стандартная библиотека (urllib, json, re, " +
            "sqlite3, unittest и т.д.); pip install и сторонние пакеты недоступны. Ввод с клавиатуры не поддерживается."
    override val schema = objSchema(
        Prop("code", "string", "Код Python для выполнения", required = false),
        Prop("file", "string", "Путь к .py-файлу относительно проекта", required = false),
        Prop("args", "string", "Аргументы командной строки через пробел (только вместе с file)", required = false),
        Prop("timeout_sec", "integer", "Таймаут в секундах (по умолчанию 60, максимум 600)", required = false),
    )

    override fun needsApproval(input: JsonObject) = true

    override fun describe(input: JsonObject): String {
        val file = input.str("file")
        return if (file != null) "python $file ${input.str("args").orEmpty()}".trim()
        else "python\n" + input.str("code").orEmpty().take(600)
    }

    override suspend fun run(input: JsonObject): String {
        val code = input.str("code")
        val fileName = input.str("file")
        require(!code.isNullOrBlank() || !fileName.isNullOrBlank()) { "Нужен параметр code или file" }
        require((code?.length ?: 0) <= 200_000) { "Код слишком длинный: сохрани его в файл и передай file" }
        val file = fileName?.let {
            val f = ws.resolve(it)
            require(f.isFile) { "Файл не найден: $it" }
            f.absolutePath
        }
        val args = input.str("args").orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val timeout = (input.int("timeout_sec") ?: 60).coerceIn(1, 600).toLong()
        return runner.run(code, file, args, ws.root.canonicalPath, timeout)
    }
}
