package com.rm.apogee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a hosted game going while the app is out of sight: a notice of who's in and for how long,
 * and the CPU and Wi-Fi kept awake for the others playing. Started and stopped by [show].
 */
class HostingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(TITLE) ?: "Hosting"
        val text = intent?.getStringExtra(TEXT) ?: ""
        val notice = notice(title, text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTICE_ID, notice)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apogee:hosting")?.apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "apogee:hosting")?.apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release(); wake = null
        wifi?.takeIf { it.isHeld }?.release(); wifi = null
        super.onDestroy()
    }

    private fun notice(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Hosting a game", NotificationManager.IMPORTANCE_LOW))
        // A tap brings the game back.
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hosting"
        private const val NOTICE_ID = 1
        private const val TITLE = "title"
        private const val TEXT = "text"

        /** Shows or updates the notice, keeping the game going, or with a null [title], lets it go. */
        fun show(context: Context, title: String?, text: String) {
            val intent = Intent(context, HostingService::class.java)
            if (title == null) {
                context.stopService(intent)
                return
            }
            intent.putExtra(TITLE, title).putExtra(TEXT, text)
            runCatching { context.startForegroundService(intent) }
        }
    }
}
