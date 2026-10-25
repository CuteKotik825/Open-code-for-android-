package com.pocketcode

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Небольшой журнал событий и ошибок в файле: из него собирается отчёт для разработчика. */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "applog.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log("CRASH", "${t.name}: " + e.stackTraceToString().take(2500)) }
            runCatching { File(context.filesDir, "crashed.flag").writeText(System.currentTimeMillis().toString()) }
            prev?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun log(level: String, text: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${fmt.format(Date())} $level ${Secrets.mask(text.replace('\n', ' ').take(600))}\n")
            if (f.length() > 200_000) {
                val keep = f.readText().takeLast(120_000)
                f.writeText(keep.substringAfter('\n'))
            }
        }
    }

    @Synchronized
    fun tail(n: Int): String = runCatching { file?.readLines()?.takeLast(n)?.joinToString("\n") }.getOrNull().orEmpty()

    @Synchronized
    fun clear() { runCatching { file?.writeText("") } }
}
