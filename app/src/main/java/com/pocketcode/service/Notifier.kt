package com.pocketcode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pocketcode.MainActivity

object Notifier {
    const val ACTION_STOP = "com.pocketcode.action.STOP"
    const val ACTION_APPROVAL = "com.pocketcode.action.APPROVAL"
    const val FOREGROUND_ID = 1
    private const val ALERT_ID = 2
    private const val CHANNEL_WORK = "agent_work"
    private const val CHANNEL_ALERT = "agent_alert"

    private fun manager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val nm = manager(ctx)
        if (nm.getNotificationChannel(CHANNEL_WORK) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WORK, "Работа агента", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "Нужно внимание", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Постоянное уведомление на время работы агента. */
    fun working(ctx: Context, text: String): Notification {
        ensureChannels(ctx)
        val stop = PendingIntent.getService(
            ctx,
            1,
            Intent(ctx, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_WORK)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PocketCode")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Стоп", stop)
            .build()
    }

    fun updateWorking(ctx: Context, text: String) {
        runCatching { manager(ctx).notify(FOREGROUND_ID, working(ctx, text)) }
    }

    /** Одноразовое уведомление: нужно подтверждение или агент закончил. */
    fun alert(ctx: Context, title: String, text: String, tab: Int = -1) {
        runCatching {
            ensureChannels(ctx)
            fun act(allow: Boolean) = PendingIntent.getBroadcast(
                ctx, if (allow) 10 + tab else 100 + tab,
                Intent(ctx, ApprovalReceiver::class.java).setAction(ACTION_APPROVAL).putExtra("tab", tab).putExtra("allow", allow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
            if (tab >= 0) {
                b.addAction(0, "Разрешить", act(true))
                b.addAction(0, "Отклонить", act(false))
            }
            manager(ctx).notify(ALERT_ID, b.build())
        }
    }

    fun clearAlert(ctx: Context) {
        runCatching { manager(ctx).cancel(ALERT_ID) }
    }
}
