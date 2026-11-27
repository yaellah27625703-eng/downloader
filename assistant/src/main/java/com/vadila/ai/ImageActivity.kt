package com.vadila.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Picture studio: AI creation and editing, plus free on-device tools. */
class ImageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var image: ImageView
    private lateinit var busy: ProgressBar
    private lateinit var statusTv: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private var current: Bitmap? = null
    private val history = ArrayDeque<Bitmap>()
    private var tab = 0
    private var cancel: Cancel? = null
    private var cameraUri: Uri? = null

    // live adjustments
    private var bright = 0
    private var contrast = 0
    private var sat = 100
    private var warm = 0

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Media.init(this)
        Ui.apply(this)
        build()
        intent.getStringExtra("path")?.let { path -> Media.decodeFile(path, 2048)?.let { setImage(it, false) } }
        if (current == null) tab = 3
        showTab(tab)
    }

    private fun build() {
        val p = Ui.p
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        top.addView(Ui.iconButton(this, R.drawable.ic_forward, p.text, "رجوع") { finish() })
        top.addView(Ui.text(this, 18f, p.text, true).apply { text = "استوديو الصور" }, Ui.lp(0, Ui.WRAP, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_image, p.text, "فتح صورة") { pick() })
        top.addView(Ui.iconButton(this, R.drawable.ic_camera, p.text, "التقاط صورة") { takePhoto() })
        top.addView(Ui.iconButton(this, R.drawable.ic_undo, p.text, "تراجع") { undo() })
        top.addView(Ui.iconButton(this, R.drawable.ic_download, p.text, "حفظ في المعرض") { save() })
        top.addView(Ui.iconButton(this, R.drawable.ic_share, p.text, "مشاركة") { share() })
        root.addView(top)
        val frame = FrameLayout(this).apply { setBackgroundColor(if (p.dark) 0xFF000000.toInt() else 0xFFE9EAF0.toInt()) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { if (current == null) pick() }
        }
        frame.addView(image, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        busy = Ui.progress(this, horizontal = false).apply { visibility = View.GONE }
        frame.addView(busy, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(frame, Ui.lp(Ui.MATCH, 0, 1f))
        statusTv = Ui.text(this, 13f, p.accent).apply { setPadding(dp(14), dp(6), dp(14), 0); visibility = View.GONE }
        root.addView(statusTv)
        tabs = LinearLayout(this).apply { setPadding(dp(10), dp(8), dp(10), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        root.addView(panel, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(Ui.button(this, "أرسل الصورة إلى المحادثة", false, R.drawable.ic_send) { sendToChat() },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
        setContentView(root)
        image.setImageResource(R.drawable.ic_image)
    }

    private fun showTab(t: Int) {
        tab = t
        tabs.removeAllViews()
        val names = listOf("تعديل بالذكاء", "أدوات", "فلاتر", "إنشاء صورة")
        for ((k, n) in names.withIndex()) tabs.addView(Ui.chip(this, n, k == t) { showTab(k) }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        panel.removeAllViews()
        when (t) {
            0 -> aiPanel()
            1 -> toolsPanel()
            2 -> filtersPanel()
            3 -> createPanel()
        }
    }

    private fun status(s: String?) {
        statusTv.visibility = if (s == null) View.GONE else View.VISIBLE
        statusTv.text = s ?: ""
        busy.visibility = if (s == null) View.GONE else View.VISIBLE
    }

    // ---- panels -----------------------------------------------------------------------------------
    private fun aiPanel() {
        val p = Ui.p
        val field = Ui.field(this, "اكتب التعديل: مثال «اجعل الخلفية شاطئًا عند الغروب»", multiLine = true).apply { maxLines = 3 }
        panel.addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
        val ideas = listOf(
            "حسّن جودة الصورة ووضوحها وألوانها",
            "حوّلها إلى رسم كرتوني جميل",
            "اجعلها لوحة زيتية فنية",
            "غيّر الخلفية إلى استوديو أبيض احترافي",
            "أزل الأشخاص في الخلفية",
            "لوّن الصورة القديمة بالأبيض والأسود",
        )
        val row = LinearLayout(this)
        for (i in ideas) row.addView(Ui.chip(this, i, false) { field.setText(i) }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        panel.addView(Ui.button(this, "نفّذ بالذكاء الاصطناعي", true, R.drawable.ic_sparkle) {
            val src = current ?: return@button pick()
            val prompt = field.text.toString().trim().ifBlank { return@button Ui.toast(this, "اكتب التعديل المطلوب") }
            runAi(prompt, src)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
        panel.addView(Ui.text(this, 12f, p.sub).apply {
            text = "يحتاج مفتاح Gemini بحساب مدفوع أو مفتاح OpenAI. الأدوات والفلاتر وإزالة الخلفية مجانية تمامًا وتعمل على الهاتف."
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun createPanel() {
        val field = Ui.field(this, "صِف الصورة: مثال «قطة ترتدي نظارة شمسية على شاطئ، أسلوب واقعي»", multiLine = true).apply { maxLines = 3 }
        panel.addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
        val aspects = linkedMapOf("1:1" to "مربع", "16:9" to "عريض", "9:16" to "طولي", "4:3" to "4:3")
        var aspect = Prefs.get("image_aspect", "1:1")
        val row = LinearLayout(this)
        fun fill() {
            row.removeAllViews()
            for ((k, v) in aspects) row.addView(Ui.chip(this, v, k == aspect) { aspect = k; Prefs.set("image_aspect", k); fill() }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        }
        fill()
        panel.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        panel.addView(Ui.button(this, "ارسم", true, R.drawable.ic_brush) {
            val prompt = field.text.toString().trim().ifBlank { return@button Ui.toast(this, "صِف الصورة أولًا") }
            val c = Cancel()
            cancel = c
            status("يرسم…")
            exec.execute {
                try {
                    val img = Imaging.create(prompt, aspect, null, null, c) { s -> ui.post { status(s) } }
                    val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)
                    ui.post {
                        status(null)
                        if (bmp != null) setImage(bmp, true)
                        Ui.toast(this, "رُسمت بواسطة ${img.engine}")
                        showTab(1)
                    }
                } catch (e: Exception) {
                    ui.post { status(null); Ui.showText(this, "تعذّر الرسم", if (e is ApiError) e.message ?: "" else Errors.friendly(e)) }
                }
            }
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
    }

    private fun toolsPanel() {
        val p = Ui.p
        val row = LinearLayout(this)
        fun tool(label: String, icon: Int, f: () -> Unit) {
            row.addView(Ui.chip(this, label, false, icon) { if (current == null) pick() else f() }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        }
        tool("إزالة الخلفية", R.drawable.ic_sparkle) { removeBackground() }
        tool("تحسين تلقائي", R.drawable.ic_sparkle) { process("يحسّن…") { autoEnhance(it) } }
        tool("زيادة الحدة", R.drawable.ic_tune) { process("يزيد الحدة…") { sharpen(it) } }
        tool("تدوير", R.drawable.ic_rotate) { transform { m -> m.postRotate(90f) } }
        tool("قلب", R.drawable.ic_flip) { transform { m -> m.postScale(-1f, 1f) } }
        tool("قص مربع", R.drawable.ic_crop) { crop(1f) }
        tool("قص 4:5", R.drawable.ic_crop) { crop(0.8f) }
        tool("قص 16:9", R.drawable.ic_crop) { crop(16f / 9f) }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        fun slider(label: String, min: Int, max: Int, value: Int, set: (Int) -> Unit) {
            val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            l.addView(Ui.text(this, 13.5f, p.text).apply { text = label }, Ui.lp(dp(74), Ui.WRAP))
            l.addView(SeekBar(this).apply {
                this.max = max - min
                progress = value - min
                progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(p.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        set(progress + min)
                        preview()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }, Ui.lp(0, Ui.WRAP, 1f))
            panel.addView(l, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
        }
        slider("السطوع", -100, 100, bright) { bright = it }
        slider("التباين", -60, 100, contrast) { contrast = it }
        slider("التشبع", 0, 200, sat) { sat = it }
        slider("الدفء", -100, 100, warm) { warm = it }
        panel.addView(Ui.button(this, "تطبيق التعديلات", true, R.drawable.ic_check) {
            val src = current ?: return@button
            if (bright == 0 && contrast == 0 && sat == 100 && warm == 0) return@button
            val out = applyMatrix(src, adjustMatrix())
            bright = 0; contrast = 0; sat = 100; warm = 0
            setImage(out, true)
            showTab(1)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
    }

    private fun filtersPanel() {
        val row = LinearLayout(this)
        val filters = listOf<Pair<String, () -> ColorMatrix>>(
            "أبيض وأسود" to { ColorMatrix().apply { setSaturation(0f) } },
            "سيبيا" to { ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 40f, 0f, 1f, 0f, 0f, 20f, 0f, 0f, 1f, 0f, -20f, 0f, 0f, 0f, 1f, 0f))) } },
            "حيوي" to { ColorMatrix().apply { setSaturation(1.45f); postConcat(contrastMatrix(12)) } },
            "دافئ" to { warmMatrix(45) },
            "بارد" to { warmMatrix(-45) },
            "عتيق" to { ColorMatrix().apply { setSaturation(0.55f); postConcat(warmMatrix(30)); postConcat(contrastMatrix(-12)) } },
            "درامي" to { ColorMatrix().apply { setSaturation(0.8f); postConcat(contrastMatrix(40)) } },
            "سينمائي" to { ColorMatrix(floatArrayOf(1.05f, 0f, 0f, 0f, -8f, 0f, 1.0f, 0.05f, 0f, 0f, 0f, 0.1f, 1.1f, 0f, 12f, 0f, 0f, 0f, 1f, 0f)).apply { postConcat(contrastMatrix(15)) } },
        )
        for ((name, m) in filters) {
            row.addView(Ui.chip(this, name, false) {
                val src = current ?: return@chip pick()
                setImage(applyMatrix(src, m()), true)
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        panel.addView(Ui.text(this, 12f, Ui.p.sub).apply { text = "كل فلتر يُطبَّق فوق الحالي؛ استعمل زر التراجع في الأعلى للرجوع."; setPadding(0, dp(8), 0, 0) })
    }

    // ---- image state ------------------------------------------------------------------------------
    private fun setImage(b: Bitmap, pushHistory: Boolean) {
        current?.let { if (pushHistory) { history.addLast(it); while (history.size > 10) history.removeFirst() } }
        current = b
        image.clearColorFilter()
        image.setImageBitmap(b)
    }

    private fun undo() {
        val prev = history.removeLastOrNull() ?: return Ui.toast(this, "لا يوجد ما يُتراجع عنه")
        current = prev
        image.setImageBitmap(prev)
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1)
    }

    private fun takePhoto() {
        val uri = Media.newCameraUri(this) ?: return
        cameraUri = uri
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), 2)
        } catch (e: Exception) {
            Ui.toast(this, "لا يوجد تطبيق كاميرا")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = if (requestCode == 2) cameraUri else data?.data
        uri ?: return
        exec.execute {
            val b = Media.decodeBitmap(this, uri, 2048)
            ui.post {
                if (b == null) Ui.toast(this, "تعذّرت قراءة الصورة") else {
                    setImage(b, true)
                    if (tab == 3) showTab(0)
                }
            }
        }
    }

    private fun bytesOf(b: Bitmap): Pair<ByteArray, String> {
        val bos = ByteArrayOutputStream()
        return if (b.hasAlpha()) {
            b.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray() to "image/png"
        } else {
            b.compress(Bitmap.CompressFormat.JPEG, 94, bos)
            bos.toByteArray() to "image/jpeg"
        }
    }

    private fun save() {
        val b = current ?: return
        val (bytes, mime) = bytesOf(b)
        val uri = Media.saveToGallery(this, bytes, mime, "VadilaAI_${System.currentTimeMillis()}." + if (mime == "image/png") "png" else "jpg")
        Ui.toast(this, if (uri != null) "حُفظت في المعرض (Pictures/Vadila AI)" else "تعذّر الحفظ")
    }

    private fun share() {
        val b = current ?: return
        val (bytes, mime) = bytesOf(b)
        val uri = Media.saveToGallery(this, bytes, mime, "VadilaAI_${System.currentTimeMillis()}." + if (mime == "image/png") "png" else "jpg") ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة"))
    }

    private fun sendToChat() {
        val b = current ?: return pick()
        val (bytes, mime) = bytesOf(b)
        val f = File(Media.dir, "studio_${System.currentTimeMillis()}." + if (mime == "image/png") "png" else "jpg")
        FileOutputStream(f).use { it.write(bytes) }
        setResult(RESULT_OK, Intent().putExtra("path", f.path))
        finish()
    }

    // ---- AI edit ----------------------------------------------------------------------------------
    private fun runAi(prompt: String, src: Bitmap) {
        val c = Cancel()
        cancel = c
        status("يعدّل…")
        exec.execute {
            try {
                val (bytes, mime) = bytesOf(src)
                val f = File(cacheDir, "edit_in." + if (mime == "image/png") "png" else "jpg")
                FileOutputStream(f).use { it.write(bytes) }
                val img = Imaging.create(prompt, Prefs.get("image_aspect", "1:1"), f, mime, c) { s -> ui.post { status(s) } }
                val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size)
                ui.post {
                    status(null)
                    if (bmp != null) setImage(bmp, true)
                }
            } catch (e: Exception) {
                ui.post { status(null); Ui.showText(this, "تعذّر التعديل", if (e is ApiError) e.message ?: "" else Errors.friendly(e)) }
            }
        }
    }

    // ---- free on-device tools -----------------------------------------------------------------------
    private fun process(msg: String, f: (Bitmap) -> Bitmap) {
        val src = current ?: return
        status(msg)
        exec.execute {
            val out = try {
                f(src)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                status(null)
                if (out != null) setImage(out, true) else Ui.toast(this, "تعذّر التنفيذ")
            }
        }
    }

    private fun transform(f: (Matrix) -> Unit) {
        val src = current ?: return
        val m = Matrix()
        f(m)
        setImage(Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true), true)
    }

    private fun crop(ratio: Float) {
        val src = current ?: return
        var w = src.width
        var h = (w / ratio).toInt()
        if (h > src.height) {
            h = src.height
            w = (h * ratio).toInt()
        }
        setImage(Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h), true)
    }

    private fun removeBackground() {
        val src = current ?: return
        status("يزيل الخلفية على الهاتف… (أول مرة يُنزَّل نموذج صغير من Google)")
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        seg.process(InputImage.fromBitmap(src, 0))
            .addOnSuccessListener { r ->
                status(null)
                val fg = r.foregroundBitmap
                if (fg == null) {
                    Ui.toast(this, "لم يُعثر على عنصر واضح في الصورة")
                    return@addOnSuccessListener
                }
                Ui.pick(this, "الخلفية الجديدة", listOf("شفافة (PNG)", "بيضاء", "سوداء", "ضبابية (تأثير البورتريه)", "لون التطبيق")) { k ->
                    val out = when (k) {
                        0 -> fg
                        4 -> composite(fg, null, Ui.p.accent)
                        1 -> composite(fg, null, Color.WHITE)
                        2 -> composite(fg, null, Color.BLACK)
                        else -> composite(fg, blur(src), Color.BLACK)
                    }
                    setImage(out, true)
                }
            }
            .addOnFailureListener { e ->
                status(null)
                Ui.showText(this, "تعذّرت إزالة الخلفية", "قد يكون النموذج قيد التنزيل من Google Play — حاول بعد دقيقة.\n\n" + (e.message ?: ""))
            }
    }

    private fun composite(fg: Bitmap, bg: Bitmap?, color: Int): Bitmap {
        val out = Bitmap.createBitmap(fg.width, fg.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (bg != null) c.drawBitmap(Bitmap.createScaledBitmap(bg, fg.width, fg.height, true), 0f, 0f, null) else c.drawColor(color)
        c.drawBitmap(fg, 0f, 0f, null)
        return out
    }

    private fun blur(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, maxOf(1, src.width / 24), maxOf(1, src.height / 24), true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun autoEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        for (c in px) {
            hr[(c shr 16) and 0xFF]++
            hg[(c shr 8) and 0xFF]++
            hb[c and 0xFF]++
        }
        fun bounds(hist: IntArray): Pair<Int, Int> {
            val total = px.size
            var acc = 0
            var lo = 0
            while (lo < 255 && acc + hist[lo] < total * 0.005) { acc += hist[lo]; lo++ }
            acc = 0
            var hi = 255
            while (hi > 0 && acc + hist[hi] < total * 0.005) { acc += hist[hi]; hi-- }
            return lo to maxOf(hi, lo + 1)
        }
        val (rl, rh) = bounds(hr)
        val (gl, gh) = bounds(hg)
        val (bl, bh) = bounds(hb)
        fun lut(lo: Int, hi: Int) = IntArray(256) { v -> ((v - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
        val lr = lut(rl, rh)
        val lg = lut(gl, gh)
        val lb = lut(bl, bh)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and -0x1000000) or (lr[(c shr 16) and 0xFF] shl 16) or (lg[(c shr 8) and 0xFF] shl 8) or lb[c and 0xFF]
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return applyMatrix(out, ColorMatrix().apply { setSaturation(1.12f) })
    }

    private fun sharpen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        val amount = 0.6f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = px[i]
                val n = px[i - w]
                val s = px[i + w]
                val e = px[i + 1]
                val wv = px[i - 1]
                fun ch(shift: Int): Int {
                    val center = (c shr shift) and 0xFF
                    val sum = ((n shr shift) and 0xFF) + ((s shr shift) and 0xFF) + ((e shr shift) and 0xFF) + ((wv shr shift) and 0xFF)
                    return (center + amount * (4 * center - sum)).toInt().coerceIn(0, 255)
                }
                out[i] = (c and -0x1000000) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(out, 0, w, 0, 0, w, h)
        return b
    }

    // ---- colour matrices --------------------------------------------------------------------------
    private fun contrastMatrix(c: Int): ColorMatrix {
        val s = (100f + c) / 100f
        val t = 128f * (1f - s)
        return ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, t, 0f, s, 0f, 0f, t, 0f, 0f, s, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    private fun warmMatrix(w: Int): ColorMatrix {
        val k = w / 300f
        return ColorMatrix(floatArrayOf(1f + k, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    }

    private fun adjustMatrix(): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(sat / 100f)
        m.postConcat(contrastMatrix(contrast))
        val b = bright * 1.6f
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(warmMatrix(warm))
        return m
    }

    private fun applyMatrix(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    private fun preview() {
        image.colorFilter = ColorMatrixColorFilter(adjustMatrix())
        if (bright == 0 && contrast == 0 && sat == 100 && warm == 0) image.clearColorFilter()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel?.stop()
    }
}
