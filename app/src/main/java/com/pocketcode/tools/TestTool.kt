package com.pocketcode.tools

import com.pocketcode.python.PythonRunner
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Запуск тестов проекта с разбором падений: агент получает короткую сводку, а не километры лога. */
class TestTool(private val ws: Workspace, private val shell: Shell, private val python: PythonRunner) : Tool {
    override val name = "run_tests"
    override val description =
        "Запустить тесты проекта и получить сводку: сколько прошло, какие упали и почему. Фреймворк определяется сам " +
            "(npm test, pytest/unittest, gradlew test, go test, cargo test) или передай command."
    override val schema = objSchema(
        Prop("command", "string", "Своя команда запуска тестов (необязательно)", required = false),
        Prop("timeout_sec", "integer", "Таймаут, по умолчанию 300, максимум 900", required = false),
    )
    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "тесты: " + (input.str("command") ?: "автоопределение")

    private fun detect(): String? {
        val r = ws.root
        return when {
            File(r, "package.json").isFile && Regex("\"test\"\\s*:").containsMatchIn(File(r, "package.json").readText()) -> "npm test --silent 2>&1"
            File(r, "gradlew").isFile -> "sh ./gradlew test --console=plain 2>&1"
            File(r, "go.mod").isFile -> "go test ./... 2>&1"
            File(r, "Cargo.toml").isFile -> "cargo test 2>&1"
            File(r, "pytest.ini").isFile || File(r, "tox.ini").isFile || File(r, "tests").isDirectory ||
                r.listFiles()?.any { it.name.startsWith("test_") && it.name.endsWith(".py") } == true -> "PY"
            else -> null
        }
    }

    override suspend fun run(input: JsonObject): String {
        val timeout = (input.int("timeout_sec") ?: 300).coerceIn(10, 900).toLong()
        val custom = input.str("command")
        val raw: String = if (custom != null) shell.run(custom, ws.root, timeout) else when (val d = detect()) {
            null -> throw IllegalStateException("Не нашёл тестов (package.json test, pytest, gradlew, go.mod, Cargo.toml). Передай command.")
            "PY" -> {
                val hasPy = shell.run("command -v python3", ws.root, 15).contains("python3")
                if (hasPy) shell.run("python3 -m pytest -q -x 2>&1 || python3 -m unittest discover -v 2>&1", ws.root, timeout)
                else python.run(
                    "import unittest,sys\nr=unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.discover('.'))\nsys.exit(0 if r.wasSuccessful() else 1)",
                    null, emptyList(), ws.root.canonicalPath, timeout,
                )
            }
            else -> shell.run(d, ws.root, timeout)
        }
        return summarize(raw)
    }

    private fun summarize(out: String): String {
        val lines = out.lines()
        val key = Regex("(FAILED|FAIL:|FAIL |ERROR:|ERROR |AssertionError|Traceback|●|✕|panicked|error\\[|--- FAIL|Tests? failed|BUILD FAILED|\\d+ (failed|passed|tests?))", RegexOption.IGNORE_CASE)
        val picked = LinkedHashSet<Int>()
        for ((i, l) in lines.withIndex()) if (key.containsMatchIn(l)) for (j in maxOf(0, i - 1)..minOf(lines.lastIndex, i + 2)) picked += j
        val body = picked.sorted().joinToString("\n") { lines[it].take(220) }.take(6000)
        val tail = lines.filter { it.isNotBlank() }.takeLast(8).joinToString("\n") { it.take(220) }
        return if (body.isBlank()) "Вывод тестов (конец):\n$tail" else "Ключевые строки:\n$body\n\nКонец вывода:\n$tail"
    }
}
