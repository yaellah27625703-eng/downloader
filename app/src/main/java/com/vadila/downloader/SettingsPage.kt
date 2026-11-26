package com.vadila.downloader

import android.app.AlertDialog
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

    private fun divider(card: LinearLayout) {
        card.addView(View(a).apply { setBackgroundColor(Ui.p.divider) }, Ui.lp(Ui.MATCH, 1).apply {
            topMargin = dp(4); bottomMargin = dp(4)
        })
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
        slider(s, "عدد الاتصالات لكل ملف", 1, 16, Engine.connections) { Engine.connections = it }
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
            text = "مدير التنزيلات – الإصدار 3.0\nتُحفظ الملفات في مجلد Download/Downloader"
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
    }
}
