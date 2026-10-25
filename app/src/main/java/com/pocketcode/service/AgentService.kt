package com.pocketcode.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.pocketcode.PocketCodeApp

/**
 * Foreground-сервис: пока агент работает, Android не убивает процесс,
 * а wake lock не даёт процессору уснуть при выключенном экране.
 * Сама работа идёт в ChatController, сервис только держит приложение живым.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketcode:agent").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        // Не даём Wi-Fi уснуть при выключенном экране (частая причина обрывов связи в долгих задачах)
        runCatching {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketcode:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Notifier.ACTION_STOP) {
            (application as PocketCodeApp).tabs.forEach { it.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notifier.working(this, "Агент работает…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.FOREGROUND_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }
}
