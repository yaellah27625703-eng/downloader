package com.vadila.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Keeps the app alive while downloads run and shows overall progress. */
class DownloadService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    private val tick = object : Runnable {
        override fun run() {
            if (Engine.activeCount() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            nm.notify(NOTIF_ID, build())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Engine.init(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "التنزيلات", NotificationManager.IMPORTANCE_LOW)
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Downloader::dl")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val list = Engine.snapshot()
        val running = list.filter { it.status == Status.RUNNING }
        val queued = list.count { it.status == Status.QUEUED }
        var done = 0L
        var total = 0L
        var known = true
        for (t in running) {
            done += t.downloaded()
            if (t.totalBytes > 0) total += t.totalBytes else known = false
        }
        val text = "يجري تنزيل ${running.size} • في الانتظار $queued • ${Engine.fmtBytes(Engine.totalSpeed())}/ث"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("مدير التنزيلات")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (known && total > 0) {
            b.setProgress(1000, (done * 1000 / total).toInt(), false)
        } else {
            b.setProgress(0, 0, running.isNotEmpty())
        }
        return b.build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
