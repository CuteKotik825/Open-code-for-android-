package com.pocketcode.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Клиент сервиса Python: запускает код, при таймауте или отмене убивает процесс интерпретатора. */
class PythonRunner(private val context: Context) {
    private class Result(val output: String, val code: Int)

    private val mutex = Mutex()
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var pending: CompletableDeferred<Result>? = null

    private val replyHandler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_RESULT) {
            val b = msg.data
            pending?.complete(Result(b.getString("out").orEmpty(), b.getInt("code")))
        }
        true
    }
    private val replyMessenger = Messenger(replyHandler)

    suspend fun run(code: String?, file: String?, args: List<String>, cwd: String, timeoutSec: Long): String =
        mutex.withLock {
            val svc = connect()
            val result = CompletableDeferred<Result>()
            pending = result
            val msg = Message.obtain(null, MSG_RUN).apply {
                replyTo = replyMessenger
                data = Bundle().apply {
                    putString("code", code)
                    putString("file", file)
                    putString("cwd", cwd)
                    putStringArray("args", args.toTypedArray())
                }
            }
            try {
                svc.send(msg)
            } catch (e: Exception) {
                disconnect()
                throw IllegalStateException("Не удалось отправить задачу в Python: ${e.message}")
            }
            try {
                val r = withTimeoutOrNull(timeoutSec * 1000) { result.await() }
                if (r == null) {
                    disconnect() // убивает процесс, остановит и зависший код
                    "[таймаут $timeoutSec с, Python остановлен]"
                } else {
                    r.output.trimEnd() + (if (r.output.isNotBlank()) "\n" else "") + "[код выхода: ${r.code}]"
                }
            } catch (e: CancellationException) {
                disconnect()
                throw e
            } finally {
                pending = null
            }
        }

    private suspend fun connect(): Messenger {
        service?.let { return it }
        val ready = CompletableDeferred<Messenger>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val m = Messenger(binder)
                service = m
                ready.complete(m)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                pending?.complete(Result("[процесс Python завершился неожиданно]", -1))
            }
        }
        val bound = withContext(Dispatchers.Main) {
            context.bindService(Intent(context, PythonService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
        check(bound) { "Не удалось запустить службу Python" }
        connection = conn
        try {
            return withTimeout(30_000) { ready.await() }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    private suspend fun disconnect() {
        val conn = connection
        service = null
        connection = null
        if (conn != null) {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { context.unbindService(conn) }
            }
        }
    }
}
