package com.vadila.downloader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL

class SettingsPage(private val a: MainActivity) {

    val view: ScrollView = ScrollView(a)
    private val body = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(24))
    }

    init {
        view.addView(body)
    }

    private fun dp(v: Int) = a.dp(v)

    private fun section(title: String): LinearLayout {
        body.addView(Ui.text(a, 14f, Ui.p.accent, bold = true).apply {
            text = title
            setPadding(dp(6), dp(14), dp(6), dp(8))
        })
        val card = Ui.card(a)
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
        return card
    }

    private fun row(card: LinearLayout, title: String, value: String, onClick: (() -> Unit)?): TextView {
        val p = Ui.p
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            if (onClick != null) {
                background = Ui.pressable(a, android.graphics.Color.TRANSPARENT, 12)
                setOnClickListener { onClick() }
            }
        }
        r.addView(Ui.text(a, 15f, p.text).apply { text = title }, Ui.lp(0, Ui.WRAP, 1f))
        val v = Ui.text(a, 14f, p.accent, bold = true).apply { text = value }
        r.addView(v)
        card.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP))
        return v
    }

    private fun slider(card: LinearLayout, title: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val p = Ui.p
        val label = Ui.text(a, 15f, p.text).apply {
            text = "$title: $value"
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        card.addView(label)
        val sb = SeekBar(a).apply {
            this.min = min
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.divider)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title: $progress"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onChange(seekBar?.progress ?: value)
                }
            })
        }
        card.addView(sb, Ui.lp(Ui.MATCH, dp(40)))
    }

    private fun switchRow(card: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val p = Ui.p
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        r.addView(Ui.text(a, 15f, p.text).apply { text = title }, Ui.lp(0, Ui.WRAP, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        r.addView(Switch(a).apply {
            isChecked = checked
            thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(states, intArrayOf(p.accentSoft, p.divider))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        card.addView(r)
    }

    private fun hint(card: LinearLayout, text: String) {
        card.addView(Ui.text(a, 12.5f, Ui.p.sub).apply {
            this.text = text
            setPadding(dp(4), 0, dp(4), dp(6))
        })
    }

    private fun divider(card: LinearLayout) {
        card.addView(View(a).apply { setBackgroundColor(Ui.p.divider) }, Ui.lp(Ui.MATCH, 1).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
    }

    fun requestBattery() {
        runCatching {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.packageName)))
        }.onFailure {
            runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    fun rebuild() {
        val p = Ui.p
        body.removeAllViews()

        // Appearance
        val look = section("المظهر")
        val chips = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val currentTheme = Ui.themeKey(a)
        for ((key, label) in Ui.themeLabels) {
            chips.addView(Ui.chip(a, label, key == currentTheme) {
                if (key != Ui.themeKey(a)) {
                    Ui.setThemeKey(a, key)
                    a.recreate()
                }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
        look.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        look.addView(Ui.text(a, 12.5f, p.sub).apply {
            text = "تلقائي يتبع وضع الهاتف • الهادئ ألوان دافئة مريحة للعين"
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        // Quality
        val q = section("جودة الفيديو")
        lateinit var qValue: TextView
        qValue = row(q, "الجودة الافتراضية", Quality.shortLabel(Engine.defaultQuality)) {
            a.pickFixedQuality("الجودة الافتراضية") { key ->
                Engine.defaultQuality = key
                qValue.text = Quality.shortLabel(key)
            }
        }
        divider(q)
        val askRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        askRow.addView(Ui.text(a, 15f, p.text).apply {
            text = "اسأل عن الجودة قبل كل تنزيل"
        }, Ui.lp(0, Ui.WRAP, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        askRow.addView(Switch(a).apply {
            isChecked = Engine.askQuality
            thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.sub))
            trackTintList = ColorStateList(states, intArrayOf(p.accentSoft, p.divider))
            setOnCheckedChangeListener { _, checked -> Engine.askQuality = checked }
        })
        q.addView(askRow)
        q.addView(Ui.text(a, 12.5f, p.sub).apply {
            text = "عند التفعيل تظهر الجودات المتوفرة فعلًا للفيديو مع حجم كل منها."
            setPadding(dp(4), 0, dp(4), dp(4))
        })

        // Speed
        val s = section("السرعة")
        slider(s, "عدد الاتصالات لكل ملف", 1, 32, Engine.connections) { Engine.connections = it }
        hint(s, "16 أو أكثر = أقصى سرعة. الاتصالات الحرة تتقاسم الأجزاء المتبقية تلقائيًا حتى آخر بايت، والاتصال البطيء يُستبدل بآخر جديد.")
        slider(s, "عدد الملفات التي تُنزَّل معًا", 1, 5, Engine.maxConcurrent) { Engine.maxConcurrent = it }
        divider(s)
        lateinit var limitValue: TextView
        fun limitText(k: Int) = if (k <= 0) "بلا حد" else "$k ك.ب/ث"
        limitValue = row(s, "حد السرعة", limitText(Engine.speedLimitKb)) {
            val field: EditText = Ui.field(a, "0 = بلا حد").apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Engine.speedLimitKb.toString())
            }
            val box = LinearLayout(a).apply {
                setPadding(dp(20), dp(12), dp(20), 0)
                addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            AlertDialog.Builder(a)
                .setTitle("حد السرعة (كيلوبايت/ث)")
                .setView(box)
                .setPositiveButton("حفظ") { _, _ ->
                    val k = field.text.toString().toIntOrNull() ?: 0
                    Engine.speedLimitKb = k
                    limitValue.text = limitText(k)
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        switchRow(s, "مسرّع الفيديو (aria2 – 16 اتصالًا)", Engine.useAria2) { Engine.useAria2 = it }
        hint(s, "يسرّع تنزيل الفيديو بعدة اتصالات. إن فشل مع موقع ما يعيد المحاولة تلقائيًا بالمحرك العادي.")

        // Background
        val bg = section("العمل في الخلفية")
        val pm = a.getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(a.packageName)
        row(bg, "التشغيل دون قيود البطارية", if (unrestricted) "مفعّل ✓" else "اضغط للتفعيل") {
            requestBattery()
        }
        hint(bg, "يمنع الهاتف من إيقاف التنزيل عند الخروج من التطبيق أو إطفاء الشاشة.")
        divider(bg)
        val overlayOk = Settings.canDrawOverlays(a)
        switchRow(bg, "زر عائم فوق التطبيقات", Engine.bubble && overlayOk) { on ->
            Engine.bubble = on
            if (on && !Settings.canDrawOverlays(a)) {
                a.toast("فعّل «السماح بالظهور فوق التطبيقات» ثم ارجع")
                runCatching {
                    a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)))
                }
            } else if (on) DownloadService.start(a)
        }
        hint(bg, "انسخ رابط فيديو من أي تطبيق (تيك توك، يوتيوب…) ثم اضغط الزر العائم فيبدأ التنزيل بالجودة الافتراضية. اسحبه لتحريكه، واضغط مطوّلًا لفتح التطبيق.")

        // Dubbing
        val d = section("الدبلجة")
        switchRow(d, "إبقاء الصوت الأصلي كمسار ثانٍ", Dub.keepOriginal) { Dub.keepOriginal = it }
        hint(d, "يمكنك التبديل بين العربية والصوت الأصلي من قائمة الصوت في مشغّل الفيديو.")
        divider(d)
        lateinit var keyValue: TextView
        keyValue = row(d, "مفتاح ElevenLabs (للأصوات الأصلية)", if (Dub.apiKey.isBlank()) "غير مضاف" else "مضاف ✓") {
            a.dub.askKey()
        }
        val models = linkedMapOf("dubbing_v2" to "v2 – أعلى جودة", "dubbing_v1" to "v1 – أرخص")
        val mrow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        for ((k, label) in models) {
            mrow.addView(Ui.chip(a, label, Dub.model == k) { Dub.model = k; rebuild() },
                Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
        d.addView(mrow)
        hint(d, "هذان الخياران للدبلجة المدفوعة فقط. الدبلجة المجانية لا تحتاج مفتاحًا.")

        // Engine
        val e = section("محرك تنزيل الفيديو")
        val ver = row(e, "الإصدار", "…", null)
        Thread {
            val v = runCatching { YoutubeDL.getInstance().versionName(a) }.getOrNull() ?: "يُجهَّز عند أول استخدام"
            a.runOnUiThread { ver.text = v }
        }.start()
        e.addView(Ui.text(a, 12.5f, p.sub).apply {
            text = "يُحدَّث تلقائيًا كل 3 أيام. إن فشل تنزيل من موقع، جرّب التحديث الآن."
            setPadding(dp(4), dp(2), dp(4), dp(10))
        })
        e.addView(Ui.button(a, "تحديث الآن", false, R.drawable.ic_refresh) {
            a.toast("جارٍ تحديث محرك التنزيل…")
            Thread {
                val msg = Engine.updateExtractor()
                a.runOnUiThread {
                    a.toast(msg)
                    if (a.current == 2) rebuild()
                }
            }.start()
        }, Ui.lp(Ui.MATCH, dp(46)))

        // About
        val about = section("حول")
        about.addView(Ui.text(a, 14f, p.text).apply {
            text = "مدير التنزيلات – الإصدار 5.2\nتُحفظ الملفات في مجلد Download/Downloader"
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
    }
}
