package com.pocketcode.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Общий ограничитель частоты запросов к модели. 0 = без ограничений.
 * Запросы выстраиваются в очередь с равными промежутками (60 000 / N мс), поэтому
 * лимит не превышается даже если запросы приходят пачкой. Один счётчик на всё приложение:
 * собственный агент, OpenCode и Claude Code (через локальный прокси) делят одну квоту.
 */
object RateGate {
    @Volatile var perMinute: Int = 0
    private val mutex = Mutex()
    private var nextAt = 0L
    @Volatile var waitingMs: Long = 0
        private set

    suspend fun acquire() {
        val n = perMinute
        if (n <= 0) return
        val gap = 60_000L / n
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val at = maxOf(now, nextAt)
            nextAt = at + gap
            at - now
        }
        if (wait > 0) {
            waitingMs = wait
            try { delay(wait) } finally { waitingMs = 0 }
        }
    }
}
