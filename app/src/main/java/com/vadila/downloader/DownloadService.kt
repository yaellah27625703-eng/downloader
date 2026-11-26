package com.vadila.downloader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Keeps downloads running in the background (even after leaving or swiping away the app),
 * shows overall progress, and hosts the optional floating button over other apps.
 */
class DownloadService : Service() {

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            if (running) { ctx.startService(Intent(ctx, DownloadService::class.java)); return }
            runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        }

        fun bubbleAllowed(ctx: Context) = Engine.bubble && Settings.canDrawOverlays(ctx)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var nm: NotificationManager
    private var bubble: Bubble? = null

    private val tick = object : Runnable {
        override fun run() {
            val active = Engine.activeCount()
            syncBubble()
            if (active == 0 && bubble == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            holdLocks(active > 0)
            nm.notify(NOTIF_ID, build())
            bubble?.update()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Restarted by the system after being killed: continue the queue.
        Engine.schedule()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from recents: downloads keep going.
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun holdLocks(on: Boolean) {
        if (on) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Downloader::dl")
                    .apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 60 * 60 * 1000L)
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Downloader::wifi")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) runCatching { wifiLock?.acquire() }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun build(): Notification {
        val list = Engine.snapshot()
        val running = list.filter { it.status == Status.RUNNING }
        val queued = list.count { it.status == Status.QUEUED }
        var pctSum = 0f
        var known = 0
        for (t in running) {
            val p = when {
                t.media -> t.progressPct
                t.totalBytes > 0 -> t.downloaded() * 100f / t.totalBytes
                else -> -1f
            }
            if (p >= 0) { pctSum += p; known++ }
        }
        val text = if (running.isEmpty() && queued == 0) "الزر العائم مفعّل • انسخ رابطًا ثم اضغط الزر"
        else "يجري تنزيل ${running.size} • في الانتظار $queued • ${Engine.fmtBytes(Engine.totalSpeed())}/ث"
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
        if (running.isNotEmpty()) {
            if (known == running.size) b.setProgress(1000, (pctSum / known * 10).toInt(), false)
            else b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun syncBubble() {
        val want = bubbleAllowed(this)
        if (want && bubble == null) bubble = runCatching { Bubble().also { it.show() } }.getOrNull()
        if (!want && bubble != null) { bubble?.hide(); bubble = null }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        bubble?.hide()
        bubble = null
        holdLocks(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ floating button

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * A small draggable circle shown over other apps.
     * Tap: downloads the link you copied. Long press: opens the app.
     */
    private inner class Bubble {
        private val wm = getSystemService(WindowManager::class.java)
        private val accent = 0xFF1565C0.toInt()
        private var pendingRead = false
        private val label: TextView
        private val icon: ImageView
        private val root: FrameLayout = object : FrameLayout(this@DownloadService) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                // Android only lets a focused window read the clipboard.
                if (hasWindowFocus && pendingRead) {
                    pendingRead = false
                    readClipboard()
                    setFocusable(false)
                }
            }
        }
        private val params = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        init {
            root.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setStroke(dp(2), Color.WHITE)
            }
            root.elevation = dp(6).toFloat()
            icon = ImageView(this@DownloadService).apply {
                setImageResource(R.drawable.ic_download)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            root.addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            label = TextView(this@DownloadService).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setupTouch()
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupTouch() {
            val slop = ViewConfiguration.get(this@DownloadService).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            var downTime = 0L
            root.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        dragging = false
                        downTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(root, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            if (System.currentTimeMillis() - downTime > 600) openApp() else requestClipboard()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun setFocusable(on: Boolean) {
            params.flags = if (on) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            runCatching { wm.updateViewLayout(root, params) }
        }

        private fun requestClipboard() {
            pendingRead = true
            setFocusable(true)
            // If focus doesn't arrive (some phones), try anyway shortly after.
            handler.postDelayed({
                if (pendingRead) { pendingRead = false; readClipboard(); setFocusable(false) }
            }, 400)
        }

        private fun readClipboard() {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = runCatching {
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@DownloadService)?.toString()
            }.getOrNull().orEmpty()
            val urls = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(text)
                .map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
            if (urls.isEmpty()) {
                toast("انسخ رابطًا أولًا، ثم اضغط الزر")
                return
            }
            val busy = Engine.snapshot().filter { it.status != Status.FAILED }.map { it.url }.toSet()
            val fresh = urls.filter { it !in busy }
            if (fresh.isEmpty()) { toast("هذا الرابط موجود في التنزيلات"); return }
            Engine.add(fresh, Engine.defaultQuality)
            toast("بدأ التنزيل (${Quality.shortLabel(Engine.defaultQuality)})")
        }

        private fun openApp() {
            startActivity(Intent(this@DownloadService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        private fun toast(s: String) = Toast.makeText(this@DownloadService, s, Toast.LENGTH_SHORT).show()

        fun show() { wm.addView(root, params) }

        fun hide() { runCatching { wm.removeView(root) } }

        fun update() {
            val running = Engine.snapshot().filter { it.status == Status.RUNNING }
            if (running.isEmpty()) {
                label.visibility = View.GONE
                icon.visibility = View.VISIBLE
                return
            }
            var sum = 0f
            var n = 0
            for (t in running) {
                val p = if (t.media) t.progressPct else if (t.totalBytes > 0) t.downloaded() * 100f / t.totalBytes else -1f
                if (p >= 0) { sum += p; n++ }
            }
            label.text = if (n > 0) "${(sum / n).toInt()}%" else "…"
            label.visibility = View.VISIBLE
            icon.visibility = View.GONE
        }
    }
}
