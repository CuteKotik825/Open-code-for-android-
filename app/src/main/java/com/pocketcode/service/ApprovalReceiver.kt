package com.pocketcode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketcode.PocketCodeApp

/** Кнопки «Разрешить» и «Отклонить» в уведомлении. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PocketCodeApp ?: return
        val tab = intent.getIntExtra("tab", 0)
        val allow = intent.getBooleanExtra("allow", false)
        if (intent.getBooleanExtra("cont", false)) {
            app.tabs.firstOrNull { it.tabNo == tab }?.send("Продолжай с того места, где остановился.")
            Notifier.clearAlert(context)
            return
        }
        app.tabs.firstOrNull { it.tabNo == tab }?.answerApproval(allow)
        Notifier.clearAlert(context)
    }
}
