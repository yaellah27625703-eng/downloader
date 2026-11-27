package com.vadila.downloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    lateinit var downloads: DownloadsPage
    lateinit var browser: BrowserPage
    lateinit var dub: DubPage
    private lateinit var settings: SettingsPage
    private lateinit var pages: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val navIndex = ArrayList<Int>()
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    var current = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 800)
        }
    }

    fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        Ui.apply(this)
        val p = Ui.p

        val outer = FrameLayout(this).apply {
            setBackgroundColor(p.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }
        title = Ui.text(this, 21f, p.text, bold = true)
        subtitle = Ui.text(this, 13f, p.sub)
        top.addView(title)
        top.addView(subtitle)
        main.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        pages = FrameLayout(this)
        main.addView(pages, Ui.lp(Ui.MATCH, 0, 1f))
        main.addView(View(this).apply { setBackgroundColor(p.divider) }, Ui.lp(Ui.MATCH, dp(1)))
        main.addView(buildNav(), Ui.lp(Ui.MATCH, dp(66)))
        outer.addView(main, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        outer.addView(fullscreen, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        setContentView(outer)

        downloads = DownloadsPage(this)
        browser = BrowserPage(this)
        settings = SettingsPage(this)
        dub = DubPage(this)
        pages.addView(downloads.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pages.addView(browser.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pages.addView(dub.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pages.addView(settings.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        savedInstanceState?.getString("browserUrl")?.let { browser.restore(it) }
        show(savedInstanceState?.getInt("page") ?: 0)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", current)
        browser.currentUrl()?.let { outState.putString("browserUrl", it) }
    }

    private fun buildNav(): View {
        val p = Ui.p
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.surface)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val items = listOf(
            Triple("التنزيلات", R.drawable.ic_download, 0),
            Triple("المتصفح", R.drawable.ic_globe, 1),
            Triple("الدبلجة", R.drawable.ic_dub, 3),
            Triple("الإعدادات", R.drawable.ic_tune, 2),
        )
        for ((label, icon, idx) in items) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = Ui.pressable(this@MainActivity, Color.TRANSPARENT, 16)
                setOnClickListener { show(idx) }
            }
            val iv = ImageView(this).apply {
                setImageResource(icon)
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }
            val tv = Ui.text(this, 12f, p.sub).apply { text = label }
            item.addView(iv, Ui.lp(Ui.WRAP, dp(32)))
            item.addView(tv)
            bar.addView(item, Ui.lp(0, Ui.MATCH, 1f))
            navIcons.add(iv)
            navLabels.add(tv)
            navIndex.add(idx)
        }
        return bar
    }

    fun show(i: Int) {
        val p = Ui.p
        current = i
        downloads.view.visibility = if (i == 0) View.VISIBLE else View.GONE
        browser.view.visibility = if (i == 1) View.VISIBLE else View.GONE
        settings.view.visibility = if (i == 2) View.VISIBLE else View.GONE
        dub.view.visibility = if (i == 3) View.VISIBLE else View.GONE
        for (k in navIcons.indices) {
            val sel = navIndex[k] == i
            navIcons[k].imageTintList = ColorStateList.valueOf(if (sel) p.accent else p.sub)
            navIcons[k].background = if (sel) Ui.rounded(this, p.accentSoft, 16) else null
            navLabels[k].setTextColor(if (sel) p.accent else p.sub)
            navLabels[k].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        title.text = when (i) {
            0 -> "مدير التنزيلات"
            1 -> "المتصفح"
            3 -> "الدبلجة إلى العربية"
            else -> "الإعدادات"
        }
        when (i) {
            1 -> browser.onShown()
            2 -> settings.rebuild()
            3 -> dub.refresh()
        }
        hideKeyboard()
        onTick()
    }

    private fun onTick() {
        val list = Engine.snapshot()
        val running = list.count { it.status == Status.RUNNING }
        val queued = list.count { it.status == Status.QUEUED }
        subtitle.text = if (running + queued == 0) "لا توجد تنزيلات جارية"
        else "يعمل $running • في الانتظار $queued • ${Engine.fmtBytes(Engine.totalSpeed())}/ث"
        if (current == 0) downloads.refresh(list)
        if (current == 3) dub.refresh()
        browser.tick()
    }

    override fun onResume() {
        super.onResume()
        browser.web.onResume()
        handler.post(tick)
        // Returning from the permission screens: show the floating button / refresh settings.
        if (DownloadService.bubbleAllowed(this)) DownloadService.start(this)
        if (current == 2) settings.rebuild()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        browser.web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        browser.web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DubPage.PICK && resultCode == RESULT_OK) data?.data?.let { dub.onPicked(it) }
    }

    /** Opens the dubbing tab with a finished download already chosen. */
    fun dubDownload(t: Task) {
        val uri = t.savedUri ?: return
        show(3)
        dub.onPicked(android.net.Uri.parse(uri), t.fileName)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            fullscreen.visibility == View.VISIBLE -> exitFullscreen()
            current == 1 && browser.web.canGoBack() -> browser.web.goBack()
            current != 0 -> show(0)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ fullscreen video

    fun enterFullscreen(v: View?, cb: WebChromeClient.CustomViewCallback?) {
        if (v == null) return
        if (fullscreen.visibility == View.VISIBLE) exitFullscreen()
        fullscreen.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        fullscreen.visibility = View.VISIBLE
        customCallback = cb
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    fun exitFullscreen() {
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        Ui.apply(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ------------------------------------------------------------ starting downloads

    fun extractUrls(text: String): List<String> =
        Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '؛', '،') }
            .distinct().toList()

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urls = extractUrls(text)
            if (urls.isNotEmpty()) {
                show(0)
                requestDownload(urls)
            }
            i.action = null
        }
    }

    private val fileExt = Regex(
        "\\.(mp4|m4v|webm|mkv|mov|3gp|avi|mp3|m4a|aac|ogg|opus|wav|flac|jpg|jpeg|png|gif|webp|zip|rar|7z|apk|pdf|docx?|xlsx?|pptx?|txt|iso|exe|tar|gz)$",
        RegexOption.IGNORE_CASE
    )

    private fun looksLikeFile(url: String): Boolean =
        fileExt.containsMatchIn(url.substringBefore('#').substringBefore('?'))

    /** Adds downloads, asking for the quality first when that's enabled. */
    fun requestDownload(urls: List<String>, referer: String? = null, userAgent: String? = null) {
        if (urls.isEmpty()) return
        val enqueue = { q: String ->
            if (referer == null && userAgent == null) Engine.add(urls, q)
            else urls.forEach { Engine.addFromBrowser(it, q, null, null, userAgent, referer, null) }
            toast(if (urls.size == 1) "بدأ التنزيل" else "أُضيف ${urls.size} تنزيلات")
            onTick()
            askBatteryOnce()
        }
        if (!Engine.askQuality || (urls.size == 1 && looksLikeFile(urls[0]))) {
            enqueue(Engine.defaultQuality); return
        }
        if (urls.size > 1) { pickFixedQuality("الجودة لكل الروابط", enqueue); return }
        pickRealQuality(urls[0], enqueue)
    }

    fun pickFixedQuality(heading: String, onPicked: (String) -> Unit) {
        val keys = Quality.fixed.keys.toList()
        AlertDialog.Builder(this)
            .setTitle(heading)
            .setItems(keys.map { Quality.label(it) }.toTypedArray()) { _, w -> onPicked(keys[w]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pickRealQuality(url: String, onPicked: (String) -> Unit) {
        var cancelled = false
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        box.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Ui.p.accent)
        }, Ui.lp(dp(36), dp(36)))
        box.addView(Ui.text(this, 15f, Ui.p.text).apply {
            text = "جارٍ جلب الجودات المتاحة…\n(أول مرة قد تستغرق بضع ثوانٍ)"
            setPadding(dp(16), 0, dp(16), 0)
        })
        val loading = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("إلغاء") { _, _ -> cancelled = true }
            .setNeutralButton("جودة يدويًا") { _, _ ->
                cancelled = true
                pickFixedQuality("اختر الجودة", onPicked)
            }
            .setOnCancelListener { cancelled = true }
            .show()

        Thread {
            val result = runCatching { Media.fetchQualities(this, url) }
            runOnUiThread {
                if (cancelled || isFinishing) return@runOnUiThread
                loading.dismiss()
                result.onSuccess { av ->
                    val opts = av.options
                    AlertDialog.Builder(this)
                        .setTitle(av.title.ifBlank { "اختر الجودة" })
                        .setItems(opts.map { it.label }.toTypedArray()) { _, w -> onPicked(opts[w].key) }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }.onFailure { e ->
                    AlertDialog.Builder(this)
                        .setTitle("تعذّر جلب الجودات")
                        .setMessage((e.message ?: "") + "\n\nإن كان رابط ملف مباشر فيمكنك تنزيله على أي حال.")
                        .setPositiveButton("تنزيل على أي حال") { _, _ -> onPicked(Engine.defaultQuality) }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
        }.start()
    }

    /** Asks once to let downloads run in the background without battery restrictions. */
    private fun askBatteryOnce() {
        if (Engine.askedBattery) return
        Engine.askedBattery = true
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("التنزيل في الخلفية")
            .setMessage("ليستمر التنزيل بأقصى سرعة حتى بعد الخروج من التطبيق أو إطفاء الشاشة، اسمح للتطبيق بالعمل دون قيود البطارية.")
            .setPositiveButton("السماح") { _, _ -> settings.requestBattery() }
            .setNegativeButton("لاحقًا", null)
            .show()
    }

    fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
