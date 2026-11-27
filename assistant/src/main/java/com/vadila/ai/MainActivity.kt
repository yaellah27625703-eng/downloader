package com.vadila.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        const val REQ_IMAGES = 11
        const val REQ_FILES = 12
        const val REQ_CAMERA = 13
        const val REQ_VIDEO = 14
        const val REQ_CALL = 15
        const val REQ_SETTINGS = 16
        const val REQ_STUDIO = 17
        const val PERM_MIC = 21
        const val PERM_MIC_CALL = 22
        const val PERM_MIC_NOTE = 23
    }

    private lateinit var store: Store
    private var chat = Chat()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var attachRow: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var statusTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var brainRow: LinearLayout
    private lateinit var toolRow: LinearLayout
    private val pending = ArrayList<Att>()
    private var brain = "auto"
    private var searchOn = false
    private var imageMode = false
    private var running: Cancel? = null
    private var dictation: Dictation? = null
    private var recorder: Recorder? = null
    private var cameraUri: Uri? = null
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newCachedThreadPool()
    private val holders = HashMap<String, Holder>()
    private var autoScroll = true

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Media.init(this)
        Ui.apply(this)
        store = Store(File(filesDir, "chats"))
        brain = Prefs.get("brain", "auto")
        Speaker.init(this)
        Speaker.onChange = { holders.values.forEach { it.updateSpeak() } }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        build()
        val last = Prefs.get("last_chat")
        val c = if (last.isNotBlank()) store.load(last) else null
        if (c != null && c.msgs.isNotEmpty()) openChat(c) else newChat()
        handleShare(intent)
        if (!Prefs.bool("welcomed")) {
            Prefs.setBool("welcomed", true)
            ui.postDelayed({ showSetupHint() }, 400)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        dictation?.stop()
        Speaker.stop()
        running?.stop()
    }

    // =============================================================================================
    // layout
    // =============================================================================================
    private fun build() {
        val p = Ui.p
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(6), dp(6), dp(6), dp(4))
        }
        top.addView(Ui.iconButton(this, R.drawable.ic_chats, p.text, "المحادثات") { showHistory() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { renameChat() }
        }
        titleTv = Ui.text(this, 17f, p.text, bold = true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = Ui.text(this, 12f, p.sub).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        titles.addView(titleTv)
        titles.addView(subTv)
        top.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_add, p.text, "محادثة جديدة") { newChat() })
        top.addView(Ui.iconButton(this, R.drawable.ic_call, p.accent, "مكالمة صوتية") { startCall() })
        top.addView(Ui.iconButton(this, R.drawable.ic_more, p.text, "المزيد") { showMenu() })
        root.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        // brains
        brainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        val brainScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(p.surface)
            addView(brainRow)
        }
        root.addView(brainScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(View(this).apply { setBackgroundColor(p.divider) }, Ui.lp(Ui.MATCH, 1))

        // messages
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
            setOnScrollChangeListener { v, _, y, _, _ ->
                val child = (v as ScrollView).getChildAt(0)
                autoScroll = child == null || y + v.height >= child.height - dp(80)
            }
        }
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        statusTv = Ui.text(this, 12.5f, p.accent).apply {
            setPadding(dp(16), dp(4), dp(16), dp(2))
            visibility = View.GONE
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(statusTv, Ui.lp(Ui.MATCH, Ui.WRAP))

        // attachments preview
        attachRow = LinearLayout(this).apply { setPadding(dp(10), dp(4), dp(10), dp(2)) }
        attachScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(attachRow)
            visibility = View.GONE
        }
        root.addView(attachScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        // tools
        toolRow = LinearLayout(this).apply { setPadding(dp(10), dp(4), dp(10), dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toolRow) }, Ui.lp(Ui.MATCH, Ui.WRAP))

        // composer
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        composer.addView(Ui.iconButton(this, R.drawable.ic_attach, p.sub, "إرفاق", 46) { showAttachMenu() })
        input = Ui.field(this, "اكتب أو تكلّم…", multiLine = true).apply {
            maxLines = 7
            minHeight = dp(46)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            background = Ui.rounded(this@MainActivity, p.field, 22)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        composer.addView(input, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(6) })
        sendBtn = ImageButton(this).apply {
            background = Ui.pressable(this@MainActivity, p.accent, 23)
            imageTintList = android.content.res.ColorStateList.valueOf(p.onAccent)
            val pad = dp(11)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { onSendButton() }
            setOnLongClickListener { startVoiceNote(); true }
        }
        composer.addView(sendBtn, Ui.lp(dp(46), dp(46)))
        root.addView(composer, Ui.lp(Ui.MATCH, Ui.WRAP))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSendIcon()
        })
        setContentView(root)
        rebuildBrains()
        rebuildTools()
        updateSendIcon()
    }

    private fun rebuildBrains() {
        brainRow.removeAllViews()
        val items = listOf(
            Triple("auto", "تلقائي", R.drawable.ic_sparkle),
            Triple("claude", "Claude", null),
            Triple("gpt", "ChatGPT", null),
            Triple("gemini", "Gemini", null),
            Triple("council", "مجلس الثلاثة", R.drawable.ic_check),
            Triple("device", "بلا إنترنت", R.drawable.ic_phone),
        )
        for ((id, label, icon) in items) {
            val chip = Ui.chip(this, label, brain == id, icon) { selectBrain(id) }
            brainRow.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        }
        val custom = brain.contains(':')
        brainRow.addView(Ui.chip(this, if (custom) Catalog.pretty(brain.substringAfter(':')) else "نموذج محدد…", custom, R.drawable.ic_tune) { pickModel() },
            Ui.lp(Ui.WRAP, Ui.WRAP))
        updateSubtitle()
    }

    private fun selectBrain(id: String) {
        if (id == "claude" && Router.familyChain("claude").isEmpty()) {
            needKey("Claude", "anthropic", "Claude يحتاج مفتاح API من Anthropic (مدفوع حسب الاستعمال، ومنفصل عن اشتراك claude.ai).")
            return
        }
        if (id == "gpt" && Router.familyChain("gpt").isEmpty()) {
            needKey("ChatGPT", "github", "للوصول إلى نماذج ChatGPT مجانًا أضف رمز GitHub (مجاني)، أو مفتاح OpenAI المدفوع.")
            return
        }
        if (id == "device" && !Prefs.hasKey("device")) {
            AlertDialog.Builder(this).setTitle("بلا إنترنت")
                .setMessage("نزّل أولًا نموذج Gemma 4 (نحو 2.6 غيغابايت مرة واحدة). بعدها يعمل التطبيق داخل هاتفك دون إنترنت ودون مفتاح أو حساب.")
                .setPositiveButton("تنزيل النموذج") { _, _ -> openSettings("device") }
                .setNegativeButton("لاحقًا", null).show()
            return
        }
        if (id == "gemini" && Router.familyChain("gemini").isEmpty()) {
            needKey("Gemini", "gemini", "أضف مفتاح Gemini المجاني من Google AI Studio (دقيقة واحدة).")
            return
        }
        brain = id
        Prefs.set("brain", id)
        rebuildBrains()
    }

    private fun needKey(name: String, provider: String, msg: String) {
        AlertDialog.Builder(this).setTitle(name).setMessage(msg)
            .setPositiveButton("أضف المفتاح") { _, _ -> openSettings(provider) }
            .setNegativeButton("لاحقًا", null).show()
    }

    private fun pickModel() {
        val options = ArrayList<Pair<String, String>>()
        for (p in Catalog.providers) {
            if (!Prefs.hasKey(p.id)) continue
            val models = (Prefs.models(p.id).ifEmpty { p.defaults }).take(25)
            for (m in models) options.add("${p.id}:$m" to "${p.name} — ${Catalog.pretty(m)}")
        }
        if (options.isEmpty()) {
            openSettings(null)
            return
        }
        Ui.pick(this, "اختر نموذجًا", options.map { it.second }, options.indexOfFirst { it.first == brain }) { k ->
            brain = options[k].first
            Prefs.set("brain", brain)
            rebuildBrains()
        }
    }

    private fun rebuildTools() {
        toolRow.removeAllViews()
        toolRow.addView(Ui.chip(this, "بحث في الويب", searchOn, R.drawable.ic_globe) {
            searchOn = !searchOn
            if (searchOn) imageMode = false
            rebuildTools()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        toolRow.addView(Ui.chip(this, "رسم صورة", imageMode, R.drawable.ic_brush) {
            imageMode = !imageMode
            if (imageMode) searchOn = false
            rebuildTools()
            if (imageMode) input.hint = "صِف الصورة التي تريدها، أو أرفق صورة واكتب التعديل…" else input.hint = "اكتب أو تكلّم…"
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        toolRow.addView(Ui.chip(this, "استوديو الصور", false, R.drawable.ic_image) { openStudio(null) },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
        toolRow.addView(Ui.chip(this, "صنع تطبيق", false, R.drawable.ic_code) { appWizard() }, Ui.lp(Ui.WRAP, Ui.WRAP))
        updateSubtitle()
    }

    private fun updateSubtitle() {
        if (!::subTv.isInitialized) return
        val mode = when (brain) {
            "auto" -> "الأفضل المتاح تلقائيًا"
            "claude" -> "Claude"
            "gpt" -> "ChatGPT"
            "gemini" -> "Gemini"
            "council" -> "Claude + ChatGPT + Gemini"
            "device" -> "داخل الهاتف — بلا إنترنت"
            else -> Catalog.pretty(brain.substringAfter(':'))
        }
        subTv.text = mode + (if (searchOn) " • بحث في الويب" else "") + (if (imageMode) " • رسم" else "")
    }

    private fun updateSendIcon() {
        if (!::sendBtn.isInitialized) return
        val icon = when {
            running != null -> R.drawable.ic_stop
            dictation?.active == true -> R.drawable.ic_stop
            input.text.isNullOrBlank() && pending.isEmpty() -> R.drawable.ic_mic
            else -> R.drawable.ic_send
        }
        sendBtn.setImageResource(icon)
        sendBtn.contentDescription = when (icon) {
            R.drawable.ic_mic -> "إملاء صوتي (اضغط مطولًا لتسجيل رسالة صوتية)"
            R.drawable.ic_stop -> "إيقاف"
            else -> "إرسال"
        }
    }

    private fun onSendButton() {
        running?.let {
            it.stop()
            return
        }
        if (dictation?.active == true) {
            dictation?.stop()
            return
        }
        if (input.text.isNullOrBlank() && pending.isEmpty()) startDictation() else send()
    }

    // =============================================================================================
    // chats
    // =============================================================================================
    private fun newChat() {
        running?.stop()
        if (chat.msgs.isNotEmpty()) store.save(chat)
        chat = Chat()
        holders.clear()
        list.removeAllViews()
        showWelcome()
        titleTv.text = "Vadila AI"
        Prefs.set("last_chat", "")
    }

    private fun openChat(c: Chat) {
        running?.stop()
        chat = c
        holders.clear()
        list.removeAllViews()
        for (m in c.msgs) addView(m)
        titleTv.text = c.title.ifBlank { "Vadila AI" }
        Prefs.set("last_chat", c.id)
        ui.post { scroll.fullScroll(View.FOCUS_DOWN) }
        if (c.msgs.isEmpty()) showWelcome()
    }

    private fun saveChat() {
        chat.updated = System.currentTimeMillis()
        if (chat.msgs.isNotEmpty()) {
            store.save(chat)
            Prefs.set("last_chat", chat.id)
        }
    }

    private fun renameChat() {
        if (chat.msgs.isEmpty()) return
        Ui.ask(this, "اسم المحادثة", "العنوان", chat.title) {
            chat.title = it.trim()
            titleTv.text = chat.title.ifBlank { "Vadila AI" }
            saveChat()
        }
    }

    private fun showHistory() {
        val p = Ui.p
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, 20f, p.text, true).apply { text = "المحادثات" }, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.iconButton(this, R.drawable.ic_close, p.text, "إغلاق") { d.dismiss() })
        box.addView(head)
        val search = Ui.field(this, "ابحث في العناوين…")
        box.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6); bottomMargin = dp(8) })
        box.addView(Ui.button(this, "محادثة جديدة", true, R.drawable.ic_add) { d.dismiss(); newChat() }, Ui.lp(Ui.MATCH, Ui.WRAP))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ScrollView(this).apply { addView(rows) }, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = dp(8) })
        val fmt = SimpleDateFormat("d MMM، HH:mm", Locale("ar"))
        fun fill(q: String) {
            rows.removeAllViews()
            val entries = store.list().filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
            if (entries.isEmpty()) rows.addView(Ui.text(this, 14f, p.sub).apply { text = "لا توجد محادثات بعد."; setPadding(dp(8), dp(16), dp(8), dp(8)) })
            for (e in entries) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.pressable(this@MainActivity, if (e.id == chat.id) p.accentSoft else p.surface, 14)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener {
                        d.dismiss()
                        if (chat.msgs.isNotEmpty() && e.id != chat.id) saveChat()
                        store.load(e.id)?.let { openChat(it) }
                    }
                    setOnLongClickListener {
                        Ui.confirm(this@MainActivity, "حذف المحادثة", "«${e.title.ifBlank { "بلا عنوان" }}» ستُحذف نهائيًا مع مرفقاتها.", "حذف") {
                            store.delete(e.id)
                            if (e.id == chat.id) newChat()
                            fill(search.text.toString())
                        }
                        true
                    }
                }
                row.addView(Ui.text(this, 15f, p.text, true).apply { text = e.title.ifBlank { "بلا عنوان" }; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
                row.addView(Ui.text(this, 12f, p.sub).apply { text = fmt.format(Date(e.updated)) })
                rows.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = fill(s.toString())
        })
        fill("")
        box.addView(Ui.text(this, 12f, p.sub).apply { text = "اضغط مطولًا على محادثة لحذفها."; setPadding(dp(4), dp(6), dp(4), 0) })
        d.setContentView(box)
        d.show()
    }

    // =============================================================================================
    // welcome
    // =============================================================================================
    private fun showWelcome() {
        val p = Ui.p
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
            tag = "welcome"
        }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_sparkle)
            imageTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }, Ui.lp(dp(56), dp(56)))
        box.addView(Ui.text(this, 22f, p.text, true).apply { text = "أهلًا! أنا Vadila AI"; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) })
        box.addView(Ui.text(this, 14.5f, p.sub).apply {
            text = "أجمع قوة Claude وChatGPT وGemini ونماذج مفتوحة المصدر. أرسل سؤالًا أو صورة أو فيديو أو ملفًا، أو اتصل بي صوتيًا."
            gravity = Gravity.CENTER
        })
        val ideas = listOf(
            "📷 حلّل صورة" to "حلّل هذه الصورة بدقة: ",
            "📄 لخّص مستندًا" to "لخّص هذا المستند في نقاط: ",
            "📱 اصنع تطبيق أندرويد" to "اصنع لي تطبيق أندرويد كاملًا: ",
            "🌐 ابحث عن آخر الأخبار" to "ابحث في الويب عن: ",
            "🎨 ارسم صورة" to "",
            "🎙 مكالمة صوتية" to "",
        )
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(18), 0, 0) }
        var row: LinearLayout? = null
        for ((k, idea) in ideas.withIndex()) {
            if (k % 2 == 0) {
                row = LinearLayout(this)
                grid.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
            }
            val tv = Ui.text(this, 14f, p.text).apply {
                text = idea.first
                gravity = Gravity.CENTER
                background = Ui.pressable(this@MainActivity, p.surface, 14, p.divider)
                setPadding(dp(8), dp(14), dp(8), dp(14))
                setOnClickListener {
                    when (k) {
                        3 -> { searchOn = true; imageMode = false; rebuildTools(); input.setText(idea.second); input.setSelection(input.length()); input.requestFocus() }
                        4 -> { imageMode = true; searchOn = false; rebuildTools(); input.requestFocus() }
                        5 -> startCall()
                        2 -> appWizard()
                        else -> { input.setText(idea.second); input.setSelection(input.length()); input.requestFocus(); if (k == 0) pickImages() else pickFiles() }
                    }
                }
            }
            row!!.addView(tv, Ui.lp(0, Ui.WRAP, 1f).apply { if (k % 2 == 0) marginEnd = dp(8) })
        }
        box.addView(grid, Ui.lp(Ui.MATCH, Ui.WRAP))
        val configured = Catalog.providers.count { it.id != "free" && Prefs.hasKey(it.id) }
        if (configured == 0) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, 15f, p.text, true).apply { text = "يعمل الآن بنموذج مجاني بسيط" })
            card.addView(Ui.text(this, 13.5f, p.sub).apply {
                text = "لتحصل على القوة الكاملة (تحليل الصور والفيديو، المكالمات الطبيعية، البحث، Claude وChatGPT) أضف مفتاحًا مجانيًا واحدًا على الأقل — Gemini أولًا."
                setPadding(0, dp(4), 0, dp(10))
            })
            card.addView(Ui.button(this, "إعداد المفاتيح المجانية", true, R.drawable.ic_key) { openSettings(null) }, Ui.lp(Ui.MATCH, Ui.WRAP))
            card.addView(Ui.button(this, "أو: تنزيل نموذج يعمل بلا إنترنت ولا مفتاح", false, R.drawable.ic_phone) { openSettings("device") },
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
            box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14) })
        }
        list.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun removeWelcome() {
        for (i in list.childCount - 1 downTo 0) if (list.getChildAt(i).tag == "welcome") list.removeViewAt(i)
    }

    private fun showSetupHint() {
        if (Catalog.providers.any { it.id != "free" && Prefs.hasKey(it.id) }) return
        AlertDialog.Builder(this).setTitle("مرحبًا بك في Vadila AI")
            .setMessage(
                "التطبيق يعمل فورًا بنموذج مجاني بسيط.\n\n" +
                    "وبلا أي مفتاح أو إنترنت: نزّل من الإعدادات نموذج Gemma 4 ليعمل داخل هاتفك.\n\n" +
                    "لتشغيل القدرات المتقدمة أضف مفاتيح مجانية (كل مفتاح يأخذ دقيقة):\n" +
                    "• Gemini: الصور والفيديو والصوت والمكالمات.\n" +
                    "• Groq: سرعة عالية وبحث في الويب.\n" +
                    "• GitHub: نماذج ChatGPT مجانًا وبناء تطبيقاتك.\n\n" +
                    "مفاتيح Claude وOpenAI مدفوعة حسب الاستعمال، وهي منفصلة عن الاشتراكات الشهرية.",
            )
            .setPositiveButton("الإعداد الآن") { _, _ -> openSettings(null) }
            .setNegativeButton("لاحقًا", null).show()
    }

    // =============================================================================================
    // menu, attachments
    // =============================================================================================
    private fun showMenu() {
        val items = listOf("المحادثات السابقة", "استوديو الصور", "صنع تطبيق أو برنامج", "مشاركة المحادثة", "حذف هذه المحادثة", "الإعدادات والمفاتيح")
        Ui.pick(this, "القائمة", items) { k ->
            when (k) {
                0 -> showHistory()
                1 -> openStudio(null)
                2 -> appWizard()
                3 -> shareChat()
                4 -> if (chat.msgs.isNotEmpty()) Ui.confirm(this, "حذف المحادثة", "ستُحذف هذه المحادثة نهائيًا.", "حذف") {
                    store.delete(chat.id)
                    chat = Chat()
                    newChat()
                }
                5 -> openSettings(null)
            }
        }
    }

    private fun shareChat() {
        if (chat.msgs.isEmpty()) return
        val sb = StringBuilder("# ").append(chat.title).append("\n\n")
        for (m in chat.msgs) {
            sb.append(if (m.role == "user") "**أنا:** " else "**Vadila AI:** ").append(m.text).append("\n\n")
        }
        Ui.shareText(this, sb.toString())
    }

    private fun showAttachMenu() {
        val items = listOf("صور من المعرض", "التقاط صورة بالكاميرا", "ملف أو مستند (PDF، Word، Excel…)", "فيديو", "تسجيل رسالة صوتية")
        Ui.pick(this, "إرفاق", items) { k ->
            when (k) {
                0 -> pickImages()
                1 -> takePhoto()
                2 -> pickFiles()
                3 -> pickVideo()
                4 -> startVoiceNote()
            }
        }
    }

    private fun pickImages() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_IMAGES)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_IMAGES)
        }
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), REQ_VIDEO)
    }

    private fun takePhoto() {
        val uri = Media.newCameraUri(this) ?: return Ui.toast(this, "تعذّر فتح الكاميرا")
        cameraUri = uri
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), REQ_CAMERA)
        } catch (e: Exception) {
            Ui.toast(this, "لا يوجد تطبيق كاميرا")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_SETTINGS -> {
                rebuildBrains()
                if (chat.msgs.isEmpty()) {
                    list.removeAllViews()
                    showWelcome()
                }
            }
            REQ_CALL -> if (resultCode == RESULT_OK) data?.getStringExtra("transcript")?.let { addTranscript(it) }
            REQ_STUDIO -> if (resultCode == RESULT_OK) {
                data?.getStringExtra("path")?.let { path ->
                    val f = File(path)
                    if (f.exists()) {
                        addPending(Media.fromFile(f, f.name, if (path.endsWith(".png")) "image/png" else "image/jpeg"))
                        data.getStringExtra("prompt")?.let { input.setText(it) }
                    }
                }
            }
        }
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAMERA) cameraUri?.let { try { contentResolver.delete(it, null, null) } catch (e: Exception) {} }
            return
        }
        val uris = ArrayList<Uri>()
        when (requestCode) {
            REQ_IMAGES, REQ_FILES, REQ_VIDEO -> {
                data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
            }
            REQ_CAMERA -> cameraUri?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        status("يجهّز المرفقات…")
        exec.execute {
            for (u in uris) {
                try {
                    val a = Media.import(this, u)
                    ui.post { addPending(a) }
                } catch (e: Exception) {
                    ui.post { Ui.toast(this, "تعذّر إرفاق ملف: " + (e.message ?: "")) }
                }
            }
            ui.post { status(null) }
        }
    }

    private fun addPending(a: Att) {
        pending.add(a)
        refreshAttachRow()
    }

    private fun refreshAttachRow() {
        val p = Ui.p
        attachRow.removeAllViews()
        attachScroll.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        for (a in pending.toList()) {
            val item = FrameLayout(this)
            if (a.isImage) {
                item.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(Media.decodeFile(a.path, 200))
                    background = Ui.rounded(this@MainActivity, p.field, 12)
                    clipToOutline = true
                }, FrameLayout.LayoutParams(dp(64), dp(64)))
            } else {
                item.addView(Ui.text(this, 11.5f, p.text).apply {
                    text = (when {
                        a.isVideo -> "🎬 "
                        a.isAudio -> "🎙 "
                        a.isPdf -> "📕 "
                        else -> "📄 "
                    }) + a.name
                    maxLines = 3
                    gravity = Gravity.CENTER
                    background = Ui.rounded(this@MainActivity, p.field, 12)
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                }, FrameLayout.LayoutParams(dp(104), dp(64)))
            }
            item.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                background = Ui.rounded(this@MainActivity, 0xAA000000.toInt(), 10)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    pending.remove(a)
                    refreshAttachRow()
                }
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END))
            attachRow.addView(item, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
        updateSendIcon()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { input.setText(it) }
                val u: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) importUris(listOf(u))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (l != null) importUris(l)
            }
        }
    }

    // =============================================================================================
    // voice
    // =============================================================================================
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startDictation() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_MIC)
            return
        }
        Speaker.stop()
        val before = input.text.toString().let { if (it.isBlank()) "" else "$it " }
        val d = Dictation(this, { text, final ->
            input.setText(before + text)
            input.setSelection(input.length())
            if (final && Prefs.bool("dictation_autosend", false) && text.isNotBlank()) send()
        }, { active ->
            status(if (active) "أسمعك… تكلّم الآن" else null)
            updateSendIcon()
        }, { err -> if (err.isNotEmpty()) Ui.toast(this, err) })
        dictation = d
        d.start()
    }

    private fun startVoiceNote() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_MIC_NOTE)
            return
        }
        val f = File(Media.dir, "voice_${System.currentTimeMillis()}.wav")
        val rec = Recorder(f)
        if (!rec.start()) return Ui.toast(this, "تعذّر فتح الميكروفون")
        recorder = rec
        val p = Ui.p
        val tv = Ui.text(this, 28f, p.text, true).apply { gravity = Gravity.CENTER; text = "0:00" }
        val bar = Ui.progress(this).apply { max = 100 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(tv)
            addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        }
        val d = AlertDialog.Builder(this).setTitle("🎙 تسجيل رسالة صوتية").setView(box)
            .setPositiveButton("إرسال") { _, _ ->
                rec.stop()
                recorder = null
                addPending(Media.fromFile(f, "رسالة صوتية.wav", "audio/wav"))
                send()
            }
            .setNegativeButton("إلغاء") { _, _ ->
                rec.stop()
                recorder = null
                f.delete()
            }
            .setCancelable(false).show()
        val tick = object : Runnable {
            override fun run() {
                if (recorder !== rec || !d.isShowing) return
                val s = rec.seconds.toInt()
                tv.text = "%d:%02d".format(s / 60, s % 60)
                bar.progress = (rec.level * 100).toInt()
                ui.postDelayed(this, 120)
            }
        }
        ui.post(tick)
    }

    private fun startCall() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_MIC_CALL)
            return
        }
        Speaker.stop()
        startActivityForResult(Intent(this, CallActivity::class.java), REQ_CALL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Ui.toast(this, "يحتاج التطبيق إذن الميكروفون لهذه الميزة.")
            return
        }
        when (requestCode) {
            PERM_MIC -> startDictation()
            PERM_MIC_CALL -> startCall()
            PERM_MIC_NOTE -> startVoiceNote()
        }
    }

    private fun addTranscript(json: String) {
        val turns = J.parse(json).list()
        if (turns.isEmpty()) return
        removeWelcome()
        val sb = StringBuilder()
        for (t in turns) {
            val who = if (t.str("r") == "user") "**أنت:** " else "**Vadila AI:** "
            sb.append(who).append(t.str("t") ?: "").append("\n\n")
        }
        val m = Msg(role = "assistant", text = "📞 **نص المكالمة الصوتية**\n\n" + sb.toString().trim(), model = "مكالمة صوتية")
        chat.msgs.add(m)
        if (chat.title.isBlank()) chat.title = "مكالمة صوتية"
        titleTv.text = chat.title
        addView(m)
        saveChat()
        scrollDown(true)
    }

    // =============================================================================================
    // sending
    // =============================================================================================
    private fun status(s: String?) {
        if (s == null) {
            statusTv.visibility = View.GONE
        } else {
            statusTv.text = s
            statusTv.visibility = View.VISIBLE
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy، HH:mm", Locale("ar")).format(Date())
        return Prefs.system() + "\n\nالتاريخ والوقت الآن (حسب هاتف المستخدم): $now."
    }

    private fun buildReq(search: Boolean, image: Boolean): Req {
        val msgs = chat.msgs.filter { it.error == null && (it.text.isNotBlank() || it.atts.isNotEmpty() || it.images.isNotEmpty()) }
        val userIdx = msgs.indices.filter { msgs[it].role == "user" }
        val mediaFrom = userIdx.takeLast(3).firstOrNull() ?: 0
        val turns = msgs.mapIndexed { i, m ->
            if (m.role == "user") {
                val atts = if (i >= mediaFrom) m.atts else emptyList()
                val note = if (i < mediaFrom && m.atts.isNotEmpty()) "\n[أرفق المستخدم سابقًا: " + m.atts.joinToString("، ") { it.name } + "]" else ""
                Turn("user", m.text + note, atts)
            } else {
                Turn("assistant", m.text.ifBlank { if (m.images.isNotEmpty()) "[صورة أنشأتُها]" else "…" }, emptyList())
            }
        }
        return Req(turns, systemPrompt(), search, image)
    }

    fun send(textIn: String? = null) {
        if (running != null) return
        dictation?.stop()
        val text = (textIn ?: input.text.toString()).trim()
        if (text.isEmpty() && pending.isEmpty()) return
        removeWelcome()
        val user = Msg(role = "user", text = text, atts = ArrayList(pending))
        pending.clear()
        refreshAttachRow()
        input.setText("")
        chat.msgs.add(user)
        if (chat.title.isBlank()) {
            chat.title = Md.firstLineTitle(text).ifBlank { user.atts.firstOrNull()?.name ?: "محادثة" }
            titleTv.text = chat.title
        }
        addView(user)
        scrollDown(true)
        respond()
    }

    /** A clear message when no configured service can handle the attachments. */
    private fun missingCapability(req: Req): String? {
        val atts = req.turns.lastOrNull()?.atts ?: return null
        if (atts.isEmpty()) return null
        val chain = Router.autoChain()
        val vision = chain.any { it.has(Catalog.VISION) }
        return when {
            atts.any { it.isImage } && !vision ->
                "لتحليل الصور أضف مفتاح Gemini المجاني من الإعدادات (أو مفتاح Claude أو OpenAI أو رمز GitHub)."
            atts.any { it.isVideo } && !Prefs.hasKey("gemini") && !vision ->
                "لتحليل الفيديو أضف مفتاح Gemini المجاني من الإعدادات."
            atts.any { it.isAudio } && !Prefs.hasKey("gemini") && !Prefs.hasKey("groq") && OnDevice.installedModels().none { it.multimodal } ->
                "لفهم الرسائل والملفات الصوتية أضف مفتاح Gemini أو Groq (كلاهما مجاني) من الإعدادات."
            else -> null
        }
    }

    private fun respond() {
        val search = searchOn
        val image = imageMode
        val req = buildReq(search, image)
        val reply = Msg(role = "assistant")
        if (!image) missingCapability(req)?.let { msg ->
            reply.error = msg
            chat.msgs.add(reply)
            addView(reply)
            saveChat()
            scrollDown(true)
            return
        }
        chat.msgs.add(reply)
        val h = addView(reply)
        h.setStreaming(true)
        val cancel = Cancel()
        running = cancel
        updateSendIcon()
        val choice = brain
        val acc = StringBuilder()
        var lastPush = 0L
        var pushScheduled = false
        val pushUi = Runnable {
            pushScheduled = false
            lastPush = System.currentTimeMillis()
            reply.text = synchronized(acc) { acc.toString() }
            h.render(streaming = true)
            scrollDown(false)
        }
        val st: (String) -> Unit = { s -> ui.post { if (running === cancel) status(s) } }
        val onDelta: (Brain, Delta) -> Unit = { b, d ->
            if (d.text.isNotEmpty()) {
                synchronized(acc) { acc.append(d.text) }
                ui.post {
                    if (!pushScheduled) {
                        pushScheduled = true
                        val wait = (90 - (System.currentTimeMillis() - lastPush)).coerceAtLeast(0) + if (acc.length > 12_000) 120 else 0
                        ui.postDelayed(pushUi, wait)
                    }
                }
            }
            if (reply.model.isEmpty() || d.model != null) ui.post { reply.model = b.label; h.updateHeader() }
        }
        exec.execute {
            try {
                if (image) {
                    makeImage(req, reply, cancel, st)
                } else {
                    val last = req.turns.last()
                    val links = Web.readLinks(last.text, Prefs.hasKey("gemini"), cancel, st)
                    val req2 = if (links.isEmpty()) req else Req(req.turns.dropLast(1) + Turn(last.role, last.text + links, last.atts), req.system, req.search, req.wantImage)
                    val res = when {
                        choice == "council" -> Router.runCouncil(req2, cancel, st, onDelta)
                        search -> Router.runSearch(Router.chainFor(choice), req2, cancel, st, onDelta)
                        else -> Router.run(Router.chainFor(choice), req2, cancel, st, onDelta)
                    }
                    val imgs = res.images.mapNotNull { (mime, b64) -> saveImage(Net.unb64(b64), mime) }
                    ui.post {
                        ui.removeCallbacks(pushUi)
                        reply.text = res.text
                        reply.images.addAll(imgs)
                        reply.sources.clear()
                        reply.sources.addAll(res.sources)
                        reply.opinions.clear()
                        reply.opinions.addAll(res.opinions)
                        reply.model = (res.brain?.label ?: "") + if (res.brain != null) " · " + res.brain.p.name.substringBefore(" (") else ""
                        if (choice == "council") reply.model = "مجلس الثلاثة · " + reply.model
                        finishReply(reply, h)
                    }
                }
            } catch (e: Exception) {
                val msg = if (e is ApiError) {
                    if (e.kind == Kind.CANCELLED) null else Errors.friendly(e) + if (e.kind == Kind.QUOTA || e.kind == Kind.RATE || e.kind == Kind.GONE) "\nأضف مفاتيح مجانية أخرى من الإعدادات ليستمر العمل دون توقف." else ""
                } else Errors.friendly(e)
                ui.post {
                    ui.removeCallbacks(pushUi)
                    val partial = synchronized(acc) { acc.toString() }
                    if (msg == null) {
                        reply.text = partial.ifBlank { "_(أُوقف)_" }
                    } else if (partial.isNotBlank()) {
                        reply.text = partial + "\n\n_(" + msg + ")_"
                    } else {
                        reply.error = msg
                    }
                    finishReply(reply, h)
                }
            }
        }
    }

    private fun finishReply(reply: Msg, h: Holder) {
        running = null
        status(null)
        updateSendIcon()
        h.setStreaming(false)
        h.render(streaming = false)
        saveChat()
        scrollDown(false)
        if (reply.error == null && Prefs.bool("auto_speak", false) && reply.text.isNotBlank()) Speaker.speak(this, reply.id, reply.text)
    }

    private fun saveImage(bytes: ByteArray, mime: String): String? {
        val dir = File(filesDir, "img").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}." + if (mime.contains("png")) "png" else "jpg")
        return try {
            FileOutputStream(f).use { it.write(bytes) }
            f.path
        } catch (e: Exception) {
            null
        }
    }

    private val EDIT_WORDS = Regex("""^(اجعل|غيّر|غير|أضف|اضف|احذف|أزل|ازل|عدّل|عدل|بدّل|بدل|حوّل|حول|لوّن|لون|كبّر|صغّر|make|change|add|remove|turn|edit|replace|put|give)""", RegexOption.IGNORE_CASE)

    private fun makeImage(req: Req, reply: Msg, cancel: Cancel, st: (String) -> Unit) {
        val last = req.turns.last()
        var input: Att? = last.atts.firstOrNull { it.isImage }
        if (input == null && EDIT_WORDS.containsMatchIn(last.text.trim())) {
            val prev = chat.msgs.dropLast(1).lastOrNull { it.role == "assistant" && it.images.isNotEmpty() }
            prev?.images?.lastOrNull()?.let { path -> input = Media.fromFile(File(path), File(path).name, if (path.endsWith(".png")) "image/png" else "image/jpeg") }
        }
        val prompt = last.text.ifBlank { "حسّن هذه الصورة" }
        val img = Imaging.create(prompt, Prefs.get("image_aspect", "1:1"), input?.file, input?.mime, cancel, st)
        val path = saveImage(img.bytes, img.mime)
        ui.post {
            reply.text = img.note.ifBlank { if (input != null) "عدّلتُ الصورة كما طلبت." else "تفضّل الصورة:" }
            path?.let { reply.images.add(it) }
            reply.model = img.engine
            finishReply(reply, holders[reply.id]!!)
        }
    }

    private fun scrollDown(force: Boolean) {
        if (!force && !autoScroll) return
        ui.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun regenerate(m: Msg) {
        if (running != null) return
        val idx = chat.msgs.indexOf(m)
        if (idx < 0) return
        while (chat.msgs.size > idx) {
            val r = chat.msgs.removeAt(chat.msgs.size - 1)
            holders.remove(r.id)?.let { list.removeView(it.root) }
        }
        respond()
    }

    private fun editUser(m: Msg) {
        if (running != null) return
        val idx = chat.msgs.indexOf(m)
        if (idx < 0) return
        while (chat.msgs.size > idx) {
            val r = chat.msgs.removeAt(chat.msgs.size - 1)
            holders.remove(r.id)?.let { list.removeView(it.root) }
        }
        input.setText(m.text)
        input.setSelection(input.length())
        pending.clear()
        pending.addAll(m.atts)
        refreshAttachRow()
        saveChat()
    }

    // =============================================================================================
    // message views
    // =============================================================================================
    private fun addView(m: Msg): Holder {
        val h = Holder(m)
        holders[m.id] = h
        list.addView(h.root, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(14) })
        h.render(false)
        return h
    }

    inner class Holder(val msg: Msg) {
        val root = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val header = Ui.text(this@MainActivity, 12f, Ui.p.sub)
        private val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val extras = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        private var speakBtn: ImageButton? = null
        private var streaming = false
        private val hooks = Render.Hooks(
            onCopyCode = { Ui.copy(this@MainActivity, it, "كود") },
            onSaveCode = { code, file -> saveCode(code, file) },
            onLongPress = { messageMenu(msg) },
        )

        init {
            val p = Ui.p
            if (msg.role == "user") {
                root.gravity = Gravity.END
                val bubble = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.rounded(this@MainActivity, p.userBubble, 18)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnLongClickListener { messageMenu(msg); true }
                }
                bubble.addView(extras)
                bubble.addView(body)
                root.addView(bubble, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = dp(48) })
            } else {
                val headRow = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                headRow.addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_sparkle)
                    imageTintList = android.content.res.ColorStateList.valueOf(p.accent)
                }, Ui.lp(dp(16), dp(16)).apply { marginEnd = dp(6) })
                headRow.addView(header)
                root.addView(headRow)
                root.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
                root.addView(extras, Ui.lp(Ui.MATCH, Ui.WRAP))
                root.addView(actions, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
        }

        fun setStreaming(s: Boolean) {
            streaming = s
        }

        fun updateHeader() {
            header.text = msg.model.ifBlank { "Vadila AI" }
        }

        fun updateSpeak() {
            speakBtn?.setImageResource(if (Speaker.speakingId == msg.id) R.drawable.ic_stop else R.drawable.ic_speaker)
        }

        fun render(streaming: Boolean) {
            val p = Ui.p
            if (msg.role == "user") {
                extras.removeAllViews()
                for (a in msg.atts) extras.addView(attachmentView(a), Ui.lp(Ui.WRAP, Ui.WRAP).apply { bottomMargin = dp(6) })
                body.removeAllViews()
                if (msg.text.isNotBlank()) body.addView(TextView(this@MainActivity).apply {
                    text = Render.text(this@MainActivity, msg.text)
                    setTextColor(p.text)
                    textSize = 16f
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    setOnLongClickListener { messageMenu(msg); true }
                })
                return
            }
            updateHeader()
            if (msg.error != null) {
                body.removeAllViews()
                body.addView(Ui.text(this@MainActivity, 15f, p.danger).apply { text = "⚠ " + msg.error })
                body.addView(Ui.button(this@MainActivity, "إعادة المحاولة", false, R.drawable.ic_refresh) { regenerate(msg) },
                    Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = dp(8) })
                actions.removeAllViews()
                extras.removeAllViews()
                return
            }
            if (msg.text.isBlank() && streaming) {
                body.removeAllViews()
                body.addView(Ui.progress(this@MainActivity, horizontal = false), Ui.lp(dp(28), dp(28)))
            } else {
                Render.fill(this@MainActivity, body, msg.text, hooks, streaming)
            }
            extras.removeAllViews()
            actions.removeAllViews()
            if (streaming) return
            for (path in msg.images) extras.addView(imageView(path), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
            if (msg.opinions.isNotEmpty()) extras.addView(opinionsView(msg))
            if (msg.sources.isNotEmpty()) extras.addView(sourcesView(msg))
            val files = Md.files(msg.text)
            if (files.isNotEmpty()) {
                extras.addView(Ui.button(this@MainActivity, "📦 المشروع: ${files.size} ملف — تنزيل أو بناء", true, R.drawable.ic_build) {
                    Builder.projectMenu(this@MainActivity, msg, chat) { prompt -> sendFixRequest(prompt) }
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
            }
            val tint = p.sub
            actions.addView(Ui.iconButton(this@MainActivity, R.drawable.ic_copy, tint, "نسخ", 38) { Ui.copy(this@MainActivity, msg.text) })
            speakBtn = Ui.iconButton(this@MainActivity, R.drawable.ic_speaker, tint, "قراءة بصوت", 38) {
                if (Speaker.speakingId == msg.id) Speaker.stop() else Speaker.speak(this@MainActivity, msg.id, msg.text)
            }
            actions.addView(speakBtn)
            updateSpeak()
            actions.addView(Ui.iconButton(this@MainActivity, R.drawable.ic_share, tint, "مشاركة", 38) { Ui.shareText(this@MainActivity, msg.text) })
            actions.addView(Ui.iconButton(this@MainActivity, R.drawable.ic_refresh, tint, "إعادة التوليد", 38) { regenerate(msg) })
            actions.addView(Ui.iconButton(this@MainActivity, R.drawable.ic_more, tint, "المزيد", 38) { messageMenu(msg) })
        }

        private fun attachmentView(a: Att): View {
            val p = Ui.p
            if (a.isImage && File(a.path).exists()) {
                return ImageView(this@MainActivity).apply {
                    adjustViewBounds = true
                    maxHeight = dp(220)
                    maxWidth = dp(240)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(Media.decodeFile(a.path, 720))
                    clipToOutline = true
                    background = Ui.rounded(this@MainActivity, p.field, 12)
                    setOnClickListener { viewImage(a.path) }
                }
            }
            return Ui.text(this@MainActivity, 13.5f, p.text).apply {
                text = (when {
                    a.isVideo -> "🎬 "
                    a.isAudio -> "🎙 "
                    a.isPdf -> "📕 "
                    else -> "📄 "
                }) + a.name + "  (" + human(a.size) + ")"
                background = Ui.rounded(this@MainActivity, p.surface, 10)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    if (a.text != null && a.text!!.isNotBlank()) Ui.showText(this@MainActivity, a.name, a.text!!.take(60_000))
                }
            }
        }

        private fun imageView(path: String): View = ImageView(this@MainActivity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(Media.decodeFile(path, 1280))
            clipToOutline = true
            background = Ui.rounded(this@MainActivity, Ui.p.field, 14)
            setOnClickListener { viewImage(path) }
        }
    }

    private fun opinionsView(m: Msg): View {
        val p = Ui.p
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, p.surface, 14, p.divider)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        box.addView(Ui.text(this, 13.5f, p.accent, true).apply { text = "▸ آراء النماذج (${m.opinions.size}) — اضغط للعرض" })
        box.setOnClickListener {
            val sb = StringBuilder()
            for (o in m.opinions) sb.append("━━━ ").append(o.label).append(if (o.ok) "" else " ✗").append(" ━━━\n").append(Md.speakable(o.text)).append("\n\n")
            Ui.showText(this, "آراء النماذج", sb.toString())
        }
        return box.also { it.layoutParams = Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) } }
    }

    private fun sourcesView(m: Msg): View {
        val p = Ui.p
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(Ui.text(this, 13f, p.sub, true).apply { text = "المصادر" })
        for ((k, s) in m.sources.take(12).withIndex()) {
            box.addView(Ui.text(this, 13.5f, p.accent).apply {
                text = "${k + 1}. " + s.title.ifBlank { s.url }.take(90)
                setPadding(0, dp(3), 0, dp(3))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { Ui.open(this@MainActivity, s.url) }
            })
        }
        return box
    }

    private fun messageMenu(m: Msg) {
        val items = ArrayList<String>()
        items += "نسخ النص"
        items += "تحديد جزء من النص"
        items += "مشاركة"
        if (m.role == "assistant") items += "قراءة بصوت"
        if (m.role == "user") items += "تعديل وإعادة الإرسال"
        if (m.role == "assistant") items += "إعادة التوليد بنموذج آخر"
        items += "حذف الرسالة"
        Ui.pick(this, "الرسالة", items) { k ->
            when (items[k]) {
                "نسخ النص" -> Ui.copy(this, m.text)
                "تحديد جزء من النص" -> Ui.showText(this, "النص", m.text)
                "مشاركة" -> Ui.shareText(this, m.text)
                "قراءة بصوت" -> Speaker.speak(this, m.id, m.text)
                "تعديل وإعادة الإرسال" -> editUser(m)
                "إعادة التوليد بنموذج آخر" -> {
                    pickModel()
                }
                "حذف الرسالة" -> {
                    chat.msgs.remove(m)
                    holders.remove(m.id)?.let { list.removeView(it.root) }
                    saveChat()
                }
            }
        }
    }

    private fun viewImage(path: String) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        box.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(Media.decodeFile(path, 2048))
            setOnClickListener { d.dismiss() }
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(Ui.iconButton(this, R.drawable.ic_download, Color.WHITE, "حفظ في المعرض", 48) {
            val f = File(path)
            val ok = Media.saveToGallery(this, f.readBytes(), if (path.endsWith(".png")) "image/png" else "image/jpeg", "VadilaAI_${System.currentTimeMillis()}" + (if (path.endsWith(".png")) ".png" else ".jpg"))
            Ui.toast(this, if (ok != null) "حُفظت في المعرض (Pictures/Vadila AI)" else "تعذّر الحفظ")
        })
        bar.addView(Ui.iconButton(this, R.drawable.ic_share, Color.WHITE, "مشاركة", 48) { shareImage(path) })
        bar.addView(Ui.iconButton(this, R.drawable.ic_edit, Color.WHITE, "تعديل في الاستوديو", 48) { d.dismiss(); openStudio(path) })
        bar.addView(Ui.iconButton(this, R.drawable.ic_close, Color.WHITE, "إغلاق", 48) { d.dismiss() })
        box.addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        d.setContentView(box)
        d.show()
    }

    private fun shareImage(path: String) {
        val f = File(path)
        val uri = Media.saveToGallery(this, f.readBytes(), if (path.endsWith(".png")) "image/png" else "image/jpeg", "VadilaAI_share_${System.currentTimeMillis()}" + (if (path.endsWith(".png")) ".png" else ".jpg"))
            ?: return Ui.toast(this, "تعذّرت المشاركة")
        val i = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "مشاركة الصورة"))
    }

    private fun saveCode(code: String, file: String?) {
        val name = (file ?: "code.txt").substringAfterLast('/')
        val tmp = File(cacheDir, name)
        tmp.writeText(code)
        val uri = Media.saveToDownloads(this, tmp, "text/plain", name)
        Ui.toast(this, if (uri != null) "حُفظ في التنزيلات/Vadila AI/$name" else "تعذّر الحفظ")
    }

    private fun sendFixRequest(prompt: String) {
        input.setText(prompt)
        send()
    }

    private fun appWizard() {
        val kinds = listOf(
            "تطبيق أندرويد (APK)" to "تطبيق أندرويد بلغة Kotlin",
            "تطبيق لكل الأنظمة: أندرويد وآيفون وويندوز ولينكس (Flutter)" to "تطبيق Flutter يعمل على أندرويد وآيفون وويندوز ولينكس",
            "برنامج لويندوز ولينكس وماك (Python)" to "برنامج سطح مكتب بلغة Python بواجهة رسومية",
            "موقع ويب" to "موقع ويب (HTML وCSS وJavaScript) يعمل مباشرة",
        )
        Ui.pick(this, "ماذا تريد أن تصنع؟", kinds.map { it.first }) { k ->
            Ui.ask(this, kinds[k].first, "صِف فكرتك بالتفصيل: ما الذي يفعله، الشاشات، الألوان…", multiLine = true) { idea ->
                if (idea.isBlank()) return@ask
                val prompt = "اصنع لي ${kinds[k].second} كاملًا وجاهزًا للبناء.\nالفكرة: $idea\n\n" +
                    "اكتب كل ملفات المشروع كاملة دون اختصار، وقبل كل ملف سطر `FILE: المسار`. " +
                    "اجعل الواجهة جميلة وعصرية وتدعم العربية. في النهاية اشرح باختصار كيف يعمل."
                if (brain == "auto" && Router.familyChain("claude").isNotEmpty()) Ui.toast(this, "سيكتبه Claude (الأقوى في البرمجة)")
                send(prompt)
            }
        }
    }

    // =============================================================================================
    // navigation
    // =============================================================================================
    private fun openSettings(provider: String?) {
        val i = Intent(this, SettingsActivity::class.java)
        if (provider != null) i.putExtra("provider", provider)
        startActivityForResult(i, REQ_SETTINGS)
    }

    private fun openStudio(path: String?) {
        val i = Intent(this, ImageActivity::class.java)
        if (path != null) i.putExtra("path", path)
        startActivityForResult(i, REQ_STUDIO)
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f غ.ب".format(b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.1f م.ب".format(b / (1024.0 * 1024))
        b >= 1L shl 10 -> "%.0f ك.ب".format(b / 1024.0)
        else -> "$b بايت"
    }

}

/** Pictures: Gemini → OpenAI → free Flux. */
object Imaging {
    class Img(val bytes: ByteArray, val mime: String, val note: String, val engine: String)

    private fun dims(aspect: String): Pair<Int, Int> = when (aspect) {
        "16:9" -> 1344 to 768
        "9:16" -> 768 to 1344
        "4:3" -> 1152 to 864
        "3:4" -> 864 to 1152
        else -> 1024 to 1024
    }

    fun create(prompt: String, aspect: String, input: File?, inputMime: String?, cancel: Cancel, status: (String) -> Unit): Img {
        val errors = ArrayList<String>()
        if (Prefs.hasKey("gemini")) {
            try {
                status(if (input != null) "Gemini يعدّل الصورة…" else "Gemini يرسم…")
                val (b, m, t) = GeminiApi.image(prompt, input, inputMime, aspect, cancel)
                return Img(b, m, t, "Gemini (Nano Banana)")
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                errors += "Gemini: " + Errors.friendly(e) + if (e.kind == Kind.QUOTA) " (نماذج الصور في Gemini تحتاج حسابًا مدفوعًا)" else ""
            }
        }
        if (Prefs.hasKey("openai")) {
            try {
                status(if (input != null) "OpenAI يعدّل الصورة…" else "OpenAI يرسم…")
                val size = when (aspect) { "16:9", "4:3" -> "1536x1024"; "9:16", "3:4" -> "1024x1536"; else -> "1024x1024" }
                var src = input
                if (input != null && inputMime != "image/png") {
                    val bmp = BitmapFactory.decodeFile(input.path)
                    val f = File(input.path + ".edit.png")
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    src = f
                }
                return Img(OaiApi.image(prompt, src, size, cancel), "image/png", "", "OpenAI")
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                errors += "OpenAI: " + Errors.friendly(e)
            }
        }
        if (input == null) {
            try {
                var p = prompt
                if (p.any { it in '؀'..'ۿ' }) {
                    status("يترجم الوصف…")
                    p = try {
                        Router.quick(prompt, "Rewrite the user's picture description as a detailed English prompt for an image generator. Output only the prompt.", cancel)
                    } catch (e: ApiError) {
                        if (e.kind == Kind.CANCELLED) throw e
                        prompt
                    }
                }
                status("يرسم مجانًا (Flux)…")
                val (w, h) = dims(aspect)
                return Img(OaiApi.freeImage(p, w, h, cancel), "image/jpeg", "", "Flux (مجاني، مفتوح المصدر)")
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                errors += "Flux: " + Errors.friendly(e)
            }
        } else {
            errors += "تعديل الصور بالوصف يحتاج مفتاح Gemini بحساب مدفوع أو مفتاح OpenAI. جرّب أدوات استوديو الصور المجانية (إزالة الخلفية، التحسين، الفلاتر)."
        }
        throw ApiError(Kind.GONE, 0, errors.joinToString("\n"))
    }
}
