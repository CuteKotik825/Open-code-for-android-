package com.pocketcode.tools

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Фоновые процессы (серверы, долгие тесты). Живут, пока работает приложение. */
class BgProcesses {
    class Entry(val name: String, val command: String, val process: Process, val startedAt: Long) {
        val buffer = StringBuilder()
        @Volatile var finishedCode: Int? = null
    }

    private val map = LinkedHashMap<String, Entry>()

    @Synchronized fun get(name: String) = map[name]
    @Synchronized fun all(): List<Entry> = map.values.toList()

    @Synchronized
    fun start(name: String, command: String, pb: ProcessBuilder): Entry {
        map[name]?.let { require(!it.process.isAlive) { "Процесс «$name» уже запущен, сначала останови его" } }
        require(map.values.count { it.process.isAlive } < 5) { "Уже запущено 5 фоновых процессов" }
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.outputStream.close()
        val e = Entry(name, command, p, System.currentTimeMillis())
        map[name] = e
        Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(e.buffer) {
                            e.buffer.append(buf, 0, n)
                            if (e.buffer.length > 100_000) e.buffer.delete(0, e.buffer.length - 80_000)
                        }
                    }
                }
            } catch (_: java.io.IOException) {
            }
            e.finishedCode = runCatching { p.waitFor() }.getOrNull()
        }.apply { isDaemon = true }.start()
        return e
    }

    fun stop(name: String): Boolean {
        val e = get(name) ?: return false
        if (e.process.isAlive) {
            e.process.destroy()
            Thread.sleep(1500)
            if (e.process.isAlive) e.process.destroyForcibly()
        }
        return true
    }

    fun stopAll() = all().forEach { runCatching { stop(it.name) } }
}

class BgTool(private val ws: Workspace, private val shell: Shell, private val procs: BgProcesses) : Tool {
    override val name = "bg"
    override val description =
        "Фоновые процессы: серверы разработки, долгие тесты, сборки. action: start (name, command) — запустить и сразу вернуть управление; " +
            "read (name, lines?) — последние строки вывода и состояние; list — все процессы; stop (name). " +
            "Среда запуска та же, что у bash. Процессы живут, пока работает приложение."
    override val schema = objSchema(
        Prop("action", "string", "start, read, list или stop"),
        Prop("name", "string", "Короткое имя процесса", required = false),
        Prop("command", "string", "Команда (для start)", required = false),
        Prop("lines", "integer", "Сколько последних строк вывода вернуть (по умолчанию 60)", required = false),
    )

    override fun needsApproval(input: JsonObject) = input.str("action") == "start"
    override fun describe(input: JsonObject) = when (input.str("action")) {
        "start" -> "фон ${input.str("name")}: $ ${input.str("command")}"
        else -> "фон ${input.str("action")} ${input.str("name").orEmpty()}".trim()
    }

    private fun state(e: BgProcesses.Entry) =
        if (e.process.isAlive) "работает" else "завершён, код ${e.finishedCode ?: "?"}"

    override suspend fun run(input: JsonObject): String {
        when (input.need("action")) {
            "start" -> {
                val n = input.need("name").trim()
                val cmd = input.need("command")
                val e = procs.start(n, cmd, shell.builder(cmd, ws.root))
                delay(1500)
                return "Запущен «$n» (${state(e)}). Вывод:\n" + tail(e, 30)
            }
            "read" -> {
                val e = procs.get(input.need("name")) ?: throw IllegalArgumentException("Нет процесса «${input.str("name")}»")
                return "[${state(e)}]\n" + tail(e, (input.int("lines") ?: 60).coerceIn(1, 400))
            }
            "list" -> {
                val all = procs.all()
                return if (all.isEmpty()) "Фоновых процессов нет." else all.joinToString("\n") { "${it.name}: ${state(it)} — ${it.command.take(80)}" }
            }
            "stop" -> return if (procs.stop(input.need("name"))) "Остановлен." else "Нет такого процесса."
            else -> throw IllegalArgumentException("action: start, read, list или stop")
        }
    }

    private fun tail(e: BgProcesses.Entry, n: Int): String {
        val text = synchronized(e.buffer) { e.buffer.toString() }
        return text.lines().takeLast(n).joinToString("\n").take(12_000)
    }
}

/** Открывает локальный сервер проекта во встроенном окне просмотра. */
class PreviewTool(private val onOpen: (String) -> Unit) : Tool {
    override val name = "preview"
    override val description =
        "Показать пользователю веб-страницу локального сервера (запущенного через bg) во встроенном окне. " +
            "Передай port или url вида http://localhost:8000/. Разрешены только localhost и 127.0.0.1."
    override val schema = objSchema(
        Prop("port", "integer", "Порт на localhost", required = false),
        Prop("url", "string", "Адрес http://localhost:порт/путь", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "превью " + (input.str("url") ?: ":" + input.int("port"))
    override suspend fun run(input: JsonObject): String {
        val url = input.str("url") ?: "http://localhost:${input.int("port") ?: throw IllegalArgumentException("Нужен port или url")}/"
        require(Regex("^http://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?$").matches(url)) { "Разрешены только http://localhost и 127.0.0.1" }
        onOpen(url)
        return "Страница открыта у пользователя: $url (кнопка «Превью» в чате вернёт её)."
    }
}
