package com.pocketcode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Отложенная отправка: задача запускается в выбранное время даже если приложение закрыто. */
object DelayedScheduler {
    fun schedule(context: Context, id: String, atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<DelayedWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("delayed-$id", androidx.work.ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("delayed-$id")
    }
}

class DelayedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val controller = (applicationContext as PocketCodeApp).controller
        val job = withContext(Dispatchers.Main) { controller.runDelayed(id) } ?: return Result.success()
        job.join()
        return Result.success()
    }
}
