package com.vadila.downloader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.URLEncoder
import java.util.Collections

class BrowserPage(private val a: MainActivity) {

    companion object {
        const val HOME = "https://www.google.com"
        private val MEDIA = Regex(
            "\\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)$",
            RegexOption.IGNORE_CASE
        )
        private val STREAM = Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE)
    }

    val view: LinearLayout
    val web: WebView
    private val address: EditText
    private val bar: ProgressBar
    private val fab: TextView
    private val backBtn: ImageButton
    private val fwdBtn: ImageButton
    /** Media files the page requested while loading (filled from a WebView thread). */
    private val found: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private var started = false
    private var pendingUrl: String? = null
    private var lastUrl: String? = null

    private fun dp(v: Int) = a.dp(v)

    init {
        val p = Ui.p
        view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        backBtn = Ui.iconButton(a, R.drawable.ic_back, p.text, "رجوع").apply { setOnClickListener { if (web.canGoBack()) web.goBack() } }
        fwdBtn = Ui.iconButton(a, R.drawable.ic_forward, p.text, "تقدّم").apply { setOnClickListener { if (web.canGoForward()) web.goForward() } }
        address = Ui.field(a, "ابحث أو اكتب عنوان موقع").apply {
            setSingleLine(true)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) { go(v.text.toString()); true } else false
            }
        }
        val reload = Ui.iconButton(a, R.drawable.ic_refresh, p.text, "تحديث").apply { setOnClickListener { web.reload() } }
        val home = Ui.iconButton(a, R.drawable.ic_home, p.text, "الرئيسية").apply { setOnClickListener { web.loadUrl(HOME) } }
        toolbar.addView(backBtn)
        toolbar.addView(fwdBtn)
        toolbar.addView(address, Ui.lp(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        toolbar.addView(reload)
        toolbar.addView(home)
        view.addView(toolbar, Ui.lp(Ui.MATCH, Ui.WRAP))

        bar = Ui.progress(a).apply { visibility = View.GONE }
        view.addView(bar, Ui.lp(Ui.MATCH, dp(4)))

        val frame = FrameLayout(a)
        web = WebView(a)
        frame.addView(web, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        fab = Ui.text(a, 15f, p.onAccent, bold = true).apply {
            text = "تنزيل"
            gravity = Gravity.CENTER
            background = Ui.pressable(a, p.accent, 26)
            elevation = dp(6).toFloat()
            setPadding(dp(20), 0, dp(20), 0)
            val d = a.getDrawable(R.drawable.ic_download)!!.mutate()
            d.setTint(p.onAccent)
            d.setBounds(0, 0, dp(22), dp(22))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
            setOnClickListener { onFab() }
        }
        frame.addView(fab, FrameLayout.LayoutParams(Ui.WRAP, dp(52), Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(16); bottomMargin = dp(18)
        })
        view.addView(frame, Ui.lp(Ui.MATCH, 0, 1f))

        setupWeb()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            if (Ui.p.dark && Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") return false
                try {
                    if (scheme == "intent") {
                        val intent = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        try { a.startActivity(intent) } catch (e: Exception) {
                            if (fallback != null) web.loadUrl(fallback)
                        }
                    } else {
                        a.startActivity(Intent(Intent.ACTION_VIEW, u))
                    }
                } catch (e: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                found.clear()
                if (url != null && !address.hasFocus()) address.setText(url)
                bar.visibility = View.VISIBLE
                updateNav()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                bar.visibility = View.GONE
                updateNav()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null && url != lastUrl) { lastUrl = url; found.clear() }
                if (url != null && !address.hasFocus()) address.setText(url)
                updateNav()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (MEDIA.containsMatchIn(u.substringBefore('#').substringBefore('?')) && found.size < 60) found.add(u)
                return null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.isIndeterminate = false
                bar.progress = newProgress * 10
                bar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                a.enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                a.exitFullscreen()
            }
        }

        // Files the site itself offers for download start right away.
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                a.toast("هذا الملف يُنشأ داخل الصفحة ولا يمكن تنزيله برابط")
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            Engine.addFromBrowser(
                url, Engine.defaultQuality, name, mimetype, userAgent, web.url,
                CookieManager.getInstance().getCookie(url)
            )
            a.toast("بدأ تنزيل: $name")
        }

        // Long-press a link or image to download it.
        web.setOnLongClickListener {
            val r = web.hitTestResult
            val link = when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> r.extra
                else -> null
            }
            if (link == null || !link.startsWith("http")) return@setOnLongClickListener false
            val isImage = r.type != WebView.HitTestResult.SRC_ANCHOR_TYPE
            AlertDialog.Builder(a)
                .setTitle(link.take(120))
                .setItems(arrayOf(if (isImage) "تنزيل الصورة" else "تنزيل الرابط", "فتح", "نسخ الرابط")) { _, w ->
                    when (w) {
                        0 -> if (isImage) {
                            Engine.addFromBrowser(link, Engine.defaultQuality, null, null,
                                web.settings.userAgentString, web.url, CookieManager.getInstance().getCookie(link))
                            a.toast("بدأ التنزيل")
                        } else a.requestDownload(listOf(link), web.url, web.settings.userAgentString)
                        1 -> web.loadUrl(link)
                        2 -> {
                            a.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("link", link))
                            a.toast("نُسخ الرابط")
                        }
                    }
                }
                .show()
            true
        }
    }

    private fun updateNav() {
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    private fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(url)
        address.clearFocus()
        web.requestFocus()
        a.hideKeyboard()
    }

    fun onShown() {
        if (started) return
        started = true
        web.loadUrl(pendingUrl ?: HOME)
    }

    fun restore(url: String) { pendingUrl = url }

    fun currentUrl(): String? = if (started) web.url else pendingUrl

    fun tick() {
        val n = found.size
        fab.text = if (n > 0) "تنزيل ($n)" else "تنزيل"
    }

    private fun shortName(u: String): String {
        val path = Uri.parse(u).lastPathSegment ?: u
        return path.take(60)
    }

    private fun onFab() {
        val page = web.url
        if (page.isNullOrBlank() || page == "about:blank") { a.toast("افتح صفحة أولًا"); return }
        val media = synchronized(found) { found.toList() }
        val ua = web.settings.userAgentString
        if (media.isEmpty()) {
            a.requestDownload(listOf(page), null, null)
            return
        }
        val labels = ArrayList<String>()
        labels.add("فيديو هذه الصفحة (اختيار الجودة)")
        for (m in media) {
            val kind = if (STREAM.containsMatchIn(m.substringBefore('?'))) "بث" else "ملف"
            labels.add("$kind: ${shortName(m)}")
        }
        AlertDialog.Builder(a)
            .setTitle("ما الذي تريد تنزيله؟")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == 0) { a.requestDownload(listOf(page), null, null); return@setItems }
                val m = media[w - 1]
                val stream = STREAM.containsMatchIn(m.substringBefore('?'))
                Engine.addFromBrowser(
                    m, Engine.defaultQuality, null, null, ua, page,
                    CookieManager.getInstance().getCookie(m), forceMedia = stream
                )
                a.toast("بدأ التنزيل")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
