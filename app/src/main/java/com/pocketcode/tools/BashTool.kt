package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val MAX_OUTPUT = 30_000

/**
 * Место, где выполняются команды: системный sh Android или Linux в proot.
 */
interface Shell {
    val info: String
    suspend fun run(command: String, cwd: File, timeoutSec: Long): String
}

class AndroidShell(private val homeDir: File) : Shell {
    override val info = "Android sh (toybox): ls, cat, grep, sed, find, tar, wget, ping и т.п. Нет python, node, git."

    override suspend fun run(command: String, cwd: File, timeoutSec: Long): String {
        homeDir.mkdirs()
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", homeDir.absolutePath)
            put("PATH", "/system/bin:/system/xbin:" + (get("PATH") ?: ""))
            put("TERM", "dumb")
        }
        return runProcess(pb, timeoutSec)
    }
}

/** Запускает процесс, собирает вывод (с ограничением) и ждёт до таймаута; «Стоп» прерывает ожидание сразу. */
suspend fun runProcess(pb: ProcessBuilder, timeoutSec: Long): String =
    withContext(Dispatchers.IO) {
        val process = pb.start()
        process.outputStream.close()
        val out = StringBuilder()
        var finished = false
        coroutineScope {
            val reader = launch(Dispatchers.IO) {
                // При остановке процесса Android закрывает поток, и чтение падает: это не ошибка команды
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (out.length < MAX_OUTPUT) out.append(buf, 0, n)
                        }
                    }
                } catch (e: java.io.IOException) {
                }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
                while (process.isAlive && System.nanoTime() < deadline) delay(100)
                finished = !process.isAlive
            } finally {
                if (process.isAlive) {
                    // Сначала мягко (proot успевает завершить дочерние процессы), затем принудительно
                    process.destroy()
                    var waited = 0
                    while (process.isAlive && waited < 20) {
                        delay(100)
                        waited++
                    }
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            reader.join()
        }
        val text = out.toString().take(MAX_OUTPUT)
        val code = if (finished) process.exitValue() else null
        buildString {
            append(text.trimEnd())
            if (isNotEmpty()) append('\n')
            append(if (code == null) "[таймаут ${timeoutSec} с, процесс остановлен]" else "[код выхода: $code]")
        }
    }

class BashTool(private val ws: Workspace, private val shell: Shell) : Tool {
    override val name = "bash"
    override val description get() =
        "Выполняет shell-команду в рабочей папке (по умолчанию корень). Среда: ${shell.info} " +
            "Для git используй отдельный инструмент git. Интерактивные команды не поддерживаются."
    override val schema = objSchema(
        Prop("command", "string", "Команда для sh -c"),
        Prop("cwd", "string", "Папка запуска относительно рабочей, по умолчанию корень", required = false),
        Prop("timeout_sec", "integer", "Таймаут в секундах (по умолчанию 60, максимум 600)", required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "$ " + input.str("command").orEmpty()

    override suspend fun run(input: JsonObject): String {
        val cwd = ws.resolve(input.str("cwd") ?: ".")
        require(cwd.isDirectory) { "Нет такой папки: ${ws.rel(cwd)}" }
        val timeout = (input.int("timeout_sec") ?: 60).coerceIn(1, 600).toLong()
        return shell.run(input.need("command"), cwd, timeout)
    }
}
