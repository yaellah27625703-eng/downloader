package com.vadila.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val cards = HashMap<String, View>()

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Ui.apply(this)
        render()
        intent.getStringExtra("provider")?.let { pid ->
            ui.postDelayed({ cards[pid]?.let { v -> scroll.smoothScrollTo(0, v.top - dp(8)) } }, 250)
        }
    }

    private fun render() {
        val p = Ui.p
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(6), dp(8), dp(12), dp(8))
        }
        top.addView(Ui.iconButton(this, R.drawable.ic_forward, p.text, "رجوع") { finish() })
        top.addView(Ui.text(this, 19f, p.text, true).apply { text = "الإعدادات والمفاتيح" }, Ui.lp(0, Ui.WRAP, 1f))
        root.addView(top)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(32))
        }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        setContentView(root)

        smartSection()
        deviceSection()

        section("المفاتيح")
        note("كل خدمة تحتاج مفتاحًا (API key) خاصًا بها. ابدأ بالمجانية: Gemini ثم Groq ثم GitHub. عندما تنتهي حصة خدمة ينتقل التطبيق تلقائيًا إلى التالية، فلا يتوقف عن العمل.")
        val order = listOf("gemini", "groq", "github", "mistral", "openrouter", "cerebras", "pollinations", "anthropic", "openai", "deepseek", "xai", "custom", "free")
        // (the offline models have their own section above)
        for (id in order) Catalog.byId(id)?.let { providerCard(it) }

        section("الصوت والمكالمات")
        choice("محرك القراءة بصوت", "voice_engine", "gemini", linkedMapOf("gemini" to "صوت طبيعي (Gemini)", "phone" to "صوت الهاتف"))
        picker("صوت القراءة", "voice_name", "Kore", Speaker.VOICES)
        picker("صوت المكالمة", "call_voice", Prefs.get("voice_name", "Kore"), Speaker.VOICES)
        picker("لغة الإملاء الصوتي", "speech_lang", "", linkedMapOf(
            "" to "لغة الهاتف", "ar-SA" to "العربية", "ar-EG" to "العربية (مصر)", "ar-MA" to "العربية (المغرب)", "fr-FR" to "Français",
            "en-US" to "English", "es-ES" to "Español", "de-DE" to "Deutsch", "tr-TR" to "Türkçe", "ur-PK" to "اردو", "fa-IR" to "فارسی",
            "hi-IN" to "हिन्दी", "zh-CN" to "中文", "ja-JP" to "日本語", "ru-RU" to "Русский", "pt-BR" to "Português", "it-IT" to "Italiano",
            "id-ID" to "Indonesia", "sw-KE" to "Kiswahili", "wo-SN" to "Wolof",
        ))
        toggle("قراءة كل رد بصوت تلقائيًا", "auto_speak", false)
        toggle("إرسال الإملاء الصوتي تلقائيًا عند التوقف عن الكلام", "dictation_autosend", false)
        toggle("مكالمة بلا صدى: لا يسمعك أثناء كلامه (فعّله إن ردّ على نفسه)", "call_half_duplex", false)

        section("الصور")
        choice("مقاس الصور المرسومة", "image_aspect", "1:1", linkedMapOf("1:1" to "مربع", "16:9" to "عريض", "9:16" to "طولي", "4:3" to "4:3"))
        textPref("نموذج صور Gemini (اختياري)", "model_gemini_image", "gemini-3.1-flash-image")
        textPref("نموذج صور OpenAI (اختياري)", "model_openai_image", "gpt-image-1")

        section("شخصية المساعد")
        val sys = Ui.text(this, 13.5f, p.sub).apply {
            text = Prefs.system().take(220) + "…"
            background = Ui.pressable(this@SettingsActivity, p.surface, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                Ui.ask(this@SettingsActivity, "تعليمات المساعد", "اكتب كيف تريد أن يتصرف…", Prefs.system(), multiLine = true) { v ->
                    Prefs.set("system_prompt", if (v.trim() == Prefs.DEFAULT_SYSTEM) "" else v.trim())
                    text = Prefs.system().take(220) + "…"
                }
            }
        }
        content.addView(sys, Ui.lp(Ui.MATCH, Ui.WRAP))
        content.addView(Ui.button(this, "استعادة التعليمات الأصلية", false) {
            Prefs.set("system_prompt", "")
            sys.text = Prefs.system().take(220) + "…"
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })

        section("المظهر")
        choice("السمة", "theme", Ui.THEME_SYSTEM, Ui.themeLabels) { recreate() }

        section("حول التطبيق")
        note(
            "Vadila AI — الإصدار ${packageManager.getPackageInfo(packageName, 0).versionName}\n" +
                "مفتوح المصدر: الكود كاملًا في مستودعك على GitHub.\n\n" +
                "بصراحة: الخدمات السحابية المجانية لها حصص يومية، والتطبيق يطيل العمل بالتنقل بينها. " +
                "أما نموذج «بلا إنترنت» فيعمل داخل هاتفك بلا حدود ولا مفتاح، لكنه أضعف من النماذج السحابية الكبيرة. " +
                "ومفاتيح Claude وOpenAI تُدفع حسب الاستعمال ولا تعمل باشتراك claude.ai أو ChatGPT Plus. " +
                "تُحفظ المفاتيح داخل هاتفك فقط وتُرسل مباشرة إلى كل خدمة.",
        )
        content.addView(Ui.button(this, "فتح المستودع (الكود المصدري)", false, R.drawable.ic_open) {
            Ui.open(this, "https://github.com/yaellah27625703-eng/downloader/tree/main/assistant")
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
    }

    // ---- intelligence -----------------------------------------------------------------------------
    private fun smartSection() {
        val p = Ui.p
        section("الذكاء")
        toggle("يبحث في الويب ويقرأ الروابط ويحسب بنفسه عند الحاجة", "auto_tools", true)
        toggle("تفكير عميق تلقائي للأسئلة الصعبة (رياضيات، برمجة، تحليل)", "smart_think", true)
        toggle("يعطي الأسئلة الصعبة لأقوى نموذج عندك (مع المفاتيح المدفوعة قد يكلّف أكثر)", "smart_max", true)
        toggle("اقتراح أسئلة متابعة بعد كل إجابة", "suggestions", true)
        toggle("يتذكّر ما تخبره به عن نفسك (يُحفظ في هاتفك فقط)", "memory_on", true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            box.removeAllViews()
            val facts = Memory.all()
            box.addView(Ui.text(this, 14f, p.text, true).apply {
                text = if (facts.isEmpty()) "الذاكرة فارغة — قل له مثلًا: «تذكّر أن اسمي …»" else "ما يتذكّره عنك (${facts.size}) — اضغط على معلومة لحذفها:"
                setPadding(dp(4), dp(6), dp(4), dp(4))
            })
            for ((k, f) in facts.withIndex()) {
                box.addView(Ui.text(this, 13.5f, p.text).apply {
                    text = "• $f"
                    background = Ui.pressable(this@SettingsActivity, p.surface, 12)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        Ui.confirm(this@SettingsActivity, "حذف من الذاكرة", f, "حذف") {
                            Memory.remove(k)
                            fill()
                        }
                    }
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(4) })
            }
            if (facts.isNotEmpty()) box.addView(Ui.button(this, "مسح الذاكرة كلها", false, R.drawable.ic_delete) {
                Ui.confirm(this, "مسح الذاكرة", "سينسى المساعد كل ما تعلّمه عنك.", "مسح") {
                    Memory.clear()
                    fill()
                }
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
        }
        fill()
        content.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
    }

    // ---- offline models ---------------------------------------------------------------------------
    private val refreshers = ArrayList<() -> Unit>()
    private val ticker = object : Runnable {
        override fun run() {
            refreshers.forEach { it() }
            ui.postDelayed(this, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun deviceSection() {
        val p = Ui.p
        section("بلا إنترنت — داخل الهاتف")
        val head = Ui.card(this)
        cards["device"] = head
        head.addView(Ui.text(this, 13.5f, p.text).apply {
            text = "نموذج مفتوح المصدر من Google (Gemma 4، رخصة Apache-2.0) يعمل داخل هاتفك: بلا إنترنت، بلا مفتاح، بلا حساب، وبلا حدود. " +
                "يُنزَّل مرة واحدة، ثم يعمل حتى في وضع الطيران. أضعف من Claude وGemini السحابيين، لكنه يفهم الصور والصوت."
        })
        head.addView(Ui.text(this, 12.5f, p.sub).apply {
            text = "ذاكرة هاتفك: %.1f غيغابايت • المساحة الفارغة: %.1f غيغابايت".format(OnDevice.ramGb(), OnDevice.freeBytes() / 1e9)
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
        for (m in OnDevice.MODELS) deviceCard(m)
        toggle("استعمله أولًا حتى مع وجود الإنترنت (خصوصية تامة)", "device_first", false)
        content.addView(Ui.button(this, "عندي ملف النموذج (.litertlm): استيراده", false, R.drawable.ic_file) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 41)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        note("مفيد إذا كان الإنترنت ضعيفًا: نزّل الملف على الحاسوب من صفحة النموذج في Hugging Face، ثم انقله إلى الهاتف واستورده.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = Media.nameOf(this, uri)
        val size = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        val byName = OnDevice.MODELS.firstOrNull { it.file.equals(name, ignoreCase = true) }
        val bySize = OnDevice.MODELS.firstOrNull { size > 0 && Math.abs(size - it.bytes) < it.bytes * 0.06 }
        val guess = byName ?: bySize
        if (!name.endsWith(".litertlm", ignoreCase = true)) {
            Ui.toast(this, "اختر ملفًا بامتداد .litertlm")
            return
        }
        if (guess != null) importModel(uri, guess, size)
        else Ui.pick(this, "أي نموذج هذا؟", OnDevice.MODELS.map { it.name }) { k -> importModel(uri, OnDevice.MODELS[k], size) }
    }

    private fun importModel(uri: android.net.Uri, m: OnDevice.Model, size: Long) {
        val bar = Ui.progress(this)
        val tv = Ui.text(this, 14f, Ui.p.text).apply { text = "ينسخ ${m.name}…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(6))
            addView(tv)
            addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        }
        val d = android.app.AlertDialog.Builder(this).setTitle("استيراد النموذج").setView(box).setCancelable(false).show()
        exec.execute {
            val part = File(OnDevice.dir, m.file + ".part")
            val target = OnDevice.file(m)
            val ok = try {
                if (OnDevice.freeBytes() < size + 100_000_000L) throw java.io.IOException("المساحة غير كافية")
                contentResolver.openInputStream(uri)!!.use { input ->
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 300 && size > 0) {
                                last = now
                                val pct = (done * 1000 / size).toInt()
                                ui.post { bar.progress = pct; tv.text = "ينسخ ${m.name}… %.1f%%".format(pct / 10.0) }
                            }
                        }
                    }
                }
                if (part.length() < 1_000_000) throw java.io.IOException("الملف صغير جدًا")
                target.delete()
                part.renameTo(target)
                if (Prefs.get("model_device").isBlank()) Prefs.set("model_device", m.id)
                null
            } catch (e: Exception) {
                part.delete()
                e.message ?: e.toString()
            }
            ui.post {
                d.dismiss()
                Ui.toast(this, if (ok == null) "✓ استُورد ${m.name} — يعمل الآن بلا إنترنت" else "تعذّر الاستيراد: $ok")
            }
        }
    }

    private fun deviceCard(m: OnDevice.Model) {
        val p = Ui.p
        val card = Ui.card(this)
        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(Ui.text(this, 15.5f, p.text, true).apply { text = m.name + "  —  %.1f غ.ب".format(m.bytes / 1e9) }, Ui.lp(0, Ui.WRAP, 1f))
        if (m.id == "gemma-4-e2b") title.addView(Ui.text(this, 11.5f, p.success, true).apply {
            text = "موصى به"
            background = Ui.rounded(this@SettingsActivity, p.field, 8)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(title)
        val ram = OnDevice.ramGb()
        card.addView(Ui.text(this, 12.5f, if (ram + 0.5 < m.ramGb) p.danger else p.sub).apply {
            text = m.note + " • يحتاج ذاكرة ${m.ramGb} غيغابايت أو أكثر" + if (ram + 0.5 < m.ramGb) " (ذاكرة هاتفك أقل، قد لا يعمل)" else ""
            setPadding(0, dp(3), 0, dp(6))
        })
        val status = Ui.text(this, 13f, p.sub)
        val bar = Ui.progress(this).apply { visibility = View.GONE }
        val btns = LinearLayout(this)
        card.addView(status)
        card.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(4) })
        card.addView(btns, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        var lastState = ""
        fun refresh() {
            val downloading = ModelService.running && ModelService.modelId == m.id
            val state = when {
                downloading -> "dl"
                OnDevice.installed(m) -> if (OnDevice.preferred()?.id == m.id) "active" else "installed"
                File(OnDevice.dir, m.file + ".part").exists() -> "partial"
                else -> "none"
            }
            if (downloading) {
                val pct = if (ModelService.total > 0) (ModelService.done * 1000 / ModelService.total).toInt() else 0
                bar.progress = pct
                status.text = "يُنزَّل… %.1f%% (%.2f من %.2f غ.ب)".format(pct / 10.0, ModelService.done / 1e9, ModelService.total / 1e9)
            }
            if (state == lastState) return
            lastState = state
            bar.visibility = if (downloading) View.VISIBLE else View.GONE
            btns.removeAllViews()
            when (state) {
                "dl" -> btns.addView(Ui.button(this, "إيقاف التنزيل مؤقتًا", false, R.drawable.ic_stop) { ModelService.cancel() }, Ui.lp(Ui.MATCH, Ui.WRAP))
                "active", "installed" -> {
                    status.text = if (state == "active") "✓ مثبّت ومستعمل" else "✓ مثبّت"
                    status.setTextColor(p.success)
                    if (state == "installed") btns.addView(Ui.button(this, "استعماله", true, R.drawable.ic_check) {
                        Prefs.set("model_device", m.id)
                        OnDevice.release()
                        lastState = ""
                    }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(6) })
                    btns.addView(Ui.button(this, "حذف", false, R.drawable.ic_delete) {
                        Ui.confirm(this, "حذف ${m.name}", "سيُحذف النموذج من الهاتف ويُحرَّر %.1f غيغابايت.".format(m.bytes / 1e9), "حذف") {
                            OnDevice.delete(m)
                            lastState = ""
                        }
                    }, Ui.lp(0, Ui.WRAP, 1f))
                }
                else -> {
                    status.text = ModelService.error?.takeIf { ModelService.modelId == m.id }?.let { "✗ $it" }
                        ?: if (state == "partial") "نُزِّل جزء منه — اضغط للمتابعة" else "غير مثبّت"
                    status.setTextColor(if (ModelService.error != null && ModelService.modelId == m.id) p.danger else p.sub)
                    btns.addView(Ui.button(this, if (state == "partial") "متابعة التنزيل" else "تنزيل (%.1f غ.ب)".format(m.bytes / 1e9), true, R.drawable.ic_download) {
                        if (ModelService.running) return@button Ui.toast(this, "ينتظر انتهاء التنزيل الحالي")
                        if (OnDevice.freeBytes() < m.bytes + 300_000_000L) return@button Ui.toast(this, "المساحة الفارغة غير كافية")
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                        }
                        if (Prefs.get("model_device").isBlank() || !OnDevice.installed(OnDevice.byId(Prefs.get("model_device")) ?: m)) Prefs.set("model_device", m.id)
                        ModelService.start(this, m.id)
                        Ui.toast(this, "بدأ التنزيل في الخلفية؛ يمكنك متابعة استعمال التطبيق")
                        lastState = ""
                    }, Ui.lp(Ui.MATCH, Ui.WRAP))
                }
            }
        }
        refresh()
        refreshers.add { refresh() }
        content.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
    }

    // ---- building blocks -------------------------------------------------------------------------
    private fun section(title: String) {
        content.addView(Ui.text(this, 15f, Ui.p.accent, true).apply {
            text = title
            setPadding(dp(4), dp(18), dp(4), dp(6))
        })
    }

    private fun note(text: String) {
        content.addView(Ui.text(this, 13.5f, Ui.p.sub).apply {
            this.text = text
            setPadding(dp(4), 0, dp(4), dp(8))
        })
    }

    private fun row(label: String, value: String, onClick: () -> Unit): TextView {
        val p = Ui.p
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pressable(this@SettingsActivity, p.surface, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }
        box.addView(Ui.text(this, 14.5f, p.text).apply { text = label })
        val v = Ui.text(this, 13f, p.accent).apply { text = value }
        box.addView(v)
        content.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        return v
    }

    private fun picker(label: String, key: String, def: String, options: Map<String, String>) {
        val keys = options.keys.toList()
        var tv: TextView? = null
        tv = row(label, options[Prefs.get(key, def)] ?: Prefs.get(key, def).ifBlank { options[def] ?: "" }) {
            Ui.pick(this, label, options.values.toList(), keys.indexOf(Prefs.get(key, def))) { k ->
                Prefs.set(key, keys[k])
                tv?.text = options[keys[k]]
            }
        }
    }

    private fun choice(label: String, key: String, def: String, options: Map<String, String>, after: (() -> Unit)? = null) {
        val p = Ui.p
        content.addView(Ui.text(this, 14.5f, p.text).apply { text = label; setPadding(dp(4), dp(4), dp(4), dp(4)) })
        val chips = LinearLayout(this)
        fun fill() {
            chips.removeAllViews()
            for ((k, v) in options) {
                chips.addView(Ui.chip(this, v, Prefs.get(key, def) == k) {
                    Prefs.set(key, k)
                    fill()
                    after?.invoke()
                }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(6) })
            }
        }
        fill()
        content.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(8) })
    }

    private fun toggle(label: String, key: String, def: Boolean) {
        var tv: TextView? = null
        tv = row(label, if (Prefs.bool(key, def)) "مفعّل ✓" else "متوقف") {
            Prefs.setBool(key, !Prefs.bool(key, def))
            tv?.text = if (Prefs.bool(key, def)) "مفعّل ✓" else "متوقف"
        }
    }

    private fun textPref(label: String, key: String, hint: String) {
        var tv: TextView? = null
        tv = row(label, Prefs.get(key).ifBlank { "$hint (افتراضي)" }) {
            Ui.ask(this, label, hint, Prefs.get(key)) { v ->
                Prefs.set(key, v.trim())
                tv?.text = v.trim().ifBlank { "$hint (افتراضي)" }
            }
        }
    }

    // ---- one card per service ---------------------------------------------------------------------
    private fun providerCard(pd: ProviderDef) {
        val p = Ui.p
        val card = Ui.card(this)
        cards[pd.id] = card
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, 16f, p.text, true).apply { text = pd.name }, Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.text(this, 11.5f, if (pd.free) p.success else p.sub, true).apply {
            text = if (pd.free) "مجاني" else "مدفوع"
            background = Ui.rounded(this@SettingsActivity, p.field, 8)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        card.addView(head)
        card.addView(Ui.text(this, 13f, p.sub).apply { text = pd.hint; setPadding(0, dp(4), 0, dp(8)) })
        val status = Ui.text(this, 13f, p.sub)
        fun showStatus() {
            status.text = when {
                pd.id == "free" -> if (Prefs.hasKey("free")) "✓ مفعّل (احتياطي أخير)" else "متوقف"
                !Prefs.hasKey(pd.id) -> "— غير مضبوط"
                else -> {
                    val n = Prefs.models(pd.id).size
                    "✓ مضبوط" + (if (n > 0) " — $n نموذجًا متاحًا" else "") + " • النموذج المفضّل: " + Catalog.pretty(Catalog.modelsFor(pd, 1).firstOrNull() ?: "")
                }
            }
            status.setTextColor(if (Prefs.hasKey(pd.id)) p.success else p.sub)
        }
        showStatus()

        if (pd.id == "free") {
            card.addView(status)
            card.addView(Ui.button(this, "تفعيل / إيقاف", false) {
                Prefs.setBool("free_off", !Prefs.bool("free_off", false))
                showStatus()
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(8) })
            content.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) })
            return
        }

        var baseField: EditText? = null
        if (pd.id == "custom") {
            baseField = Ui.field(this, "http://192.168.1.5:11434/v1").apply {
                setText(Prefs.get("base_custom"))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                textDirection = View.TEXT_DIRECTION_LTR
            }
            card.addView(baseField, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6) })
        }
        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val keyField = Ui.field(this, pd.keyHint).apply {
            setText(Prefs.key(pd.id))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
            typeface = android.graphics.Typeface.MONOSPACE
        }
        keyRow.addView(keyField, Ui.lp(0, Ui.WRAP, 1f))
        var shown = false
        keyRow.addView(Ui.iconButton(this, R.drawable.ic_search, p.sub, "إظهار المفتاح") {
            shown = !shown
            keyField.inputType = InputType.TYPE_CLASS_TEXT or if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyField.typeface = android.graphics.Typeface.MONOSPACE
            keyField.setSelection(keyField.length())
        })
        card.addView(keyRow)
        val btns = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(6)) }
        if (pd.keyUrl.isNotBlank()) {
            btns.addView(Ui.button(this, if (pd.id == "custom") "تنزيل Ollama" else "احصل على مفتاح", false, R.drawable.ic_open) {
                Ui.open(this, pd.keyUrl)
                if (pd.id != "custom") Ui.toast(this, keyHelp(pd.id))
            }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = dp(6) })
        }
        btns.addView(Ui.button(this, "حفظ واختبار", true, R.drawable.ic_check) {
            val key = keyField.text.toString().trim()
            Prefs.set("key_${pd.id}", key)
            baseField?.let { Prefs.set("base_custom", it.text.toString().trim().trimEnd('/')) }
            Router.reset(pd.id)
            if (!Prefs.hasKey(pd.id)) {
                Prefs.setModels(pd.id, emptyList())
                showStatus()
                return@button
            }
            status.text = "يختبر…"
            status.setTextColor(p.sub)
            exec.execute {
                val msg = test(pd)
                ui.post {
                    showStatus()
                    if (msg != null) {
                        status.text = msg
                        status.setTextColor(p.danger)
                    }
                }
            }
        }, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(btns)
        card.addView(status)
        card.addView(Ui.text(this, 13.5f, p.accent).apply {
            text = "تغيير النموذج المفضّل…"
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { chooseModel(pd) { showStatus() } }
        })
        if (pd.id == "custom") {
            card.addView(Ui.text(this, 13.5f, p.accent).apply {
                text = if (Prefs.bool("custom_first")) "يُستعمل أولًا ✓ (اضغط للتغيير)" else "يُستعمل بعد الخدمات السحابية (اضغط ليكون أولًا)"
                setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    Prefs.setBool("custom_first", !Prefs.bool("custom_first"))
                    text = if (Prefs.bool("custom_first")) "يُستعمل أولًا ✓ (اضغط للتغيير)" else "يُستعمل بعد الخدمات السحابية (اضغط ليكون أولًا)"
                }
            })
        }
        content.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) })
    }

    private fun keyHelp(id: String) = when (id) {
        "gemini" -> "سجّل بحساب Google ← Create API key ← انسخ المفتاح والصقه هنا."
        "groq" -> "سجّل الدخول ← Create API Key ← انسخه."
        "github" -> "انزل إلى الأسفل ← Generate token ← انسخ الرمز (يبدأ بـ ghp_)."
        "anthropic" -> "Create Key ← انسخه. يحتاج رصيدًا مدفوعًا في Anthropic Console."
        "openai" -> "Create new secret key ← انسخه. يحتاج رصيدًا مدفوعًا."
        "mistral" -> "اختر خطة Experiment المجانية ثم أنشئ مفتاحًا."
        "openrouter" -> "Create Key ← انسخه. النماذج المجانية تنتهي بـ :free"
        else -> "أنشئ مفتاحًا وانسخه هنا."
    }

    /** Checks a key by listing the service's models; returns an error message or null. */
    private fun test(pd: ProviderDef): String? {
        return try {
            val models = Api.listModels(pd)
            Prefs.setModels(pd.id, models)
            val chosen = Prefs.get("model_${pd.id}")
            if (chosen.isBlank() || chosen !in models) {
                val best = pd.defaults.firstOrNull { it in models } ?: models.firstOrNull()
                if (best != null) Prefs.set("model_${pd.id}", best)
            }
            if (pd.id == "github") {
                try {
                    val u = J.parse(Net.text(Net.get("https://api.github.com/user", Builder.ghHeaders()), "github"))
                    u.str("login")?.let { Prefs.set("gh_login", it) }
                } catch (e: Exception) {
                }
            }
            null
        } catch (e: ApiError) {
            if (pd.id == "github" && e.kind != Kind.NETWORK) {
                // the token may still be fine for building apps
                try {
                    val u = J.parse(Net.text(Net.get("https://api.github.com/user", Builder.ghHeaders()), "github"))
                    val login = u.str("login")
                    if (login != null) {
                        Prefs.set("gh_login", login)
                        return "✓ الرمز صالح لبناء التطبيقات (@$login)، لكن نماذج GitHub تحتاج صلاحية models — استعمل رمزًا كلاسيكيًا."
                    }
                } catch (e2: Exception) {
                }
            }
            when (e.kind) {
                Kind.AUTH -> "✗ المفتاح غير صالح — تأكد من نسخه كاملًا."
                Kind.NETWORK -> "✗ تعذّر الاتصال: " + (e.message ?: "")
                else -> "✗ " + Errors.friendly(e)
            }
        } catch (e: Exception) {
            "✗ " + (e.message ?: e.toString())
        }
    }

    private fun chooseModel(pd: ProviderDef, after: () -> Unit) {
        val list = Prefs.models(pd.id).ifEmpty { pd.defaults }
        val items = list.map { Catalog.pretty(it) + if (Catalog.pretty(it) != it) "  ($it)" else "" } + "اكتب اسم نموذج آخر…"
        Ui.pick(this, "النموذج المفضّل — ${pd.name}", items, list.indexOf(Prefs.get("model_${pd.id}"))) { k ->
            if (k == list.size) {
                Ui.ask(this, "اسم النموذج", "مثال: ${pd.defaults.firstOrNull() ?: "model-id"}", Prefs.get("model_${pd.id}")) { v ->
                    Prefs.set("model_${pd.id}", v.trim())
                    Router.reset(pd.id)
                    after()
                }
            } else {
                Prefs.set("model_${pd.id}", list[k])
                Router.reset(pd.id)
                after()
            }
        }
    }
}
