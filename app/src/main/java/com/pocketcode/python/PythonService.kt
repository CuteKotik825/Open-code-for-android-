package com.pocketcode.python

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

const val MSG_RUN = 1
const val MSG_RESULT = 2
const val MAX_PY_OUTPUT = 60_000

/**
 * Интерпретатор Python в отдельном процессе (:python).
 * Зависшую программу останавливаем, убивая процесс при unbind (см. onDestroy).
 */
class PythonService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        messenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == MSG_RUN) handleRun(msg)
                true
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleRun(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val data = msg.data
        worker.execute {
            var output: String
            var code: Int
            try {
                val module = Python.getInstance().getModule("runner")
                val result = module.callAttr(
                    "run",
                    data.getString("code"),
                    data.getString("cwd"),
                    data.getString("file"),
                    // Java-коллекции из Python не перебираются, поэтому аргументы идут одной строкой
                    (data.getStringArray("args") ?: emptyArray()).joinToString("\n"),
                ).asList()
                output = result[0].toString()
                code = result[1].toInt()
            } catch (e: Throwable) {
                output = "Ошибка запуска Python: ${e.message ?: e}"
                code = -1
            }
            if (output.length > MAX_PY_OUTPUT) {
                output = output.take(MAX_PY_OUTPUT) + "\n... (вывод обрезан)"
            }
            val reply = Message.obtain(null, MSG_RESULT).apply {
                this.data = Bundle().apply {
                    putString("out", output)
                    putInt("code", code)
                }
            }
            try {
                replyTo.send(reply)
            } catch (e: Exception) {
                // клиент уже ушёл
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        // Процесс служит только для Python: гарантированно останавливаем зависший код
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
