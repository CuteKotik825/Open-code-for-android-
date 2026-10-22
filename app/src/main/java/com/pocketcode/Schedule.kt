package com.pocketcode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/** Задача по расписанию: промпт, который агент выполняет сам в выбранном проекте. */
@Serializable
data class Schedule(
    val id: String,
    val prompt: String,
    val project: String,
    val intervalMin: Int,
    val enabled: Boolean = true,
    val lastRun: Long = 0,
)

object Scheduler {
    fun apply(context: Context, list: List<Schedule>, removed: List<String> = emptyList()) {
        val wm = WorkManager.getInstance(context)
        removed.forEach { wm.cancelUniqueWork("sched-$it") }
        for (s in list) {
            if (!s.enabled) {
                wm.cancelUniqueWork("sched-${s.id}")
                continue
            }
            val req = PeriodicWorkRequestBuilder<ScheduledWorker>(maxOf(15, s.intervalMin).toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("id" to s.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("sched-${s.id}", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

class ScheduledWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val controller = (applicationContext as PocketCodeApp).controller
        val started = withContext(Dispatchers.Main) { controller.runScheduled(id) }
        if (!started) return Result.success()
        val deadline = System.currentTimeMillis() + 9 * 60_000
        while (controller.running && System.currentTimeMillis() < deadline) delay(2000)
        return Result.success()
    }
}
