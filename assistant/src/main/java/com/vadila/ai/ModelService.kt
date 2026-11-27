package com.vadila.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Downloads an offline model in the background (resumable), with a progress notification. */
class ModelService : Service() {
    companion object {
        private const val CHANNEL = "models"
        private const val NOTE_ID = 7

        @Volatile
        var modelId: String? = null
            private set
        @Volatile
        var done = 0L
            private set
        @Volatile
        var total = 0L
            private set
        @Volatile
        var error: String? = null
            private set
        @Volatile
        var running = false
            private set
        @Volatile
        private var stop = false

        fun start(ctx: Context, id: String) {
            if (running) return
            error = null
            stop = false
            modelId = id
            ctx.startForegroundService(Intent(ctx, ModelService::class.java).putExtra("id", id))
        }

        fun cancel() {
            stop = true
        }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = OnDevice.byId(intent?.getStringExtra("id") ?: modelId ?: "")
        if (m == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "تنزيل النماذج", NotificationManager.IMPORTANCE_LOW))
        val n = note(m.name, 0, 0, "يبدأ التنزيل…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTE_ID, n)
        running = true
        modelId = m.id
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vadila:model").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
        Thread {
            try {
                download(m, nm)
            } catch (e: Exception) {
                error = if (stop) null else (if (e is ApiError) Errors.friendly(e) else (e.message ?: e.toString()))
            } finally {
                running = false
                try {
                    wake?.release()
                } catch (e: Exception) {
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                val ok = OnDevice.installed(m)
                if (!stop) nm.notify(NOTE_ID + 1, note(m.name, 0, 0, if (ok) "✓ اكتمل التنزيل — يعمل الآن بلا إنترنت" else "تعذّر التنزيل: ${error ?: ""}", finished = true))
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun download(m: OnDevice.Model, nm: NotificationManager) {
        val target = OnDevice.file(m)
        if (OnDevice.installed(m)) return
        val part = File(OnDevice.dir, m.file + ".part")
        var attempt = 0
        while (true) {
            if (stop) return
            val have = if (part.exists()) part.length() else 0L
            if (OnDevice.freeBytes() < (m.bytes - have) + 300_000_000L) {
                throw IOException("المساحة غير كافية: يحتاج نحو ${"%.1f".format((m.bytes - have) / 1e9)} غيغابايت فارغة.")
            }
            try {
                val req = Request.Builder().url(m.url).header("User-Agent", "VadilaAI/1.1")
                if (have > 0) req.header("Range", "bytes=$have-")
                Net.exec(req.build(), "hf").use { r ->
                    val append = have > 0 && r.code == 206
                    val len = r.body?.contentLength() ?: -1L
                    total = if (len > 0) (if (append) have + len else len) else m.bytes
                    done = if (append) have else 0L
                    val src = r.body?.byteStream() ?: throw IOException("رد فارغ")
                    FileOutputStream(part, append).use { o ->
                        val buf = ByteArray(1 shl 17)
                        var lastNote = 0L
                        while (true) {
                            if (stop) return
                            val n = src.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastNote > 1000) {
                                lastNote = now
                                nm.notify(NOTE_ID, note(m.name, done, total, null))
                            }
                        }
                    }
                }
                if (part.length() >= total * 0.98 || total <= 0) {
                    part.renameTo(target)
                    return
                }
                throw IOException("انقطع التنزيل")
            } catch (e: Exception) {
                if (stop) return
                if (e is ApiError && (e.kind == Kind.GONE || e.kind == Kind.AUTH)) throw e
                attempt++
                if (attempt > 30) throw e
                Thread.sleep(minOf(30_000L, 2000L * attempt))
            }
        }
    }

    private fun note(name: String, cur: Long, max: Long, text: String?, finished: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java).putExtra("provider", "device"), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("نموذج بلا إنترنت: $name")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (text != null) b.setContentText(text) else {
            val pct = if (max > 0) (cur * 100 / max).toInt() else 0
            b.setContentText("$pct%  —  ${"%.2f".format(cur / 1e9)} / ${"%.2f".format(max / 1e9)} غيغابايت")
            b.setProgress(100, pct, max <= 0)
        }
        if (finished) b.setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
        return b.build()
    }
}
