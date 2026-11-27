package com.vadila.downloader

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class DubPage(private val a: MainActivity) {

    companion object {
        const val PICK = 4242
        const val KEYS_URL = "https://elevenlabs.io/app/settings/api-keys"
    }

    val view = ScrollView(a)
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val keyCard: LinearLayout
    private val fileName: TextView
    private val fileInfo: TextView
    private val srcValue: TextView
    private val targetRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val engineRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val freeBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val engineHint: TextView
    private val voiceValue: TextView
    private val asrValue: TextView
    private val targetTitle: TextView
    private val targetScroll: HorizontalScrollView
    private val jobsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val jobsTitle: TextView
    private val cards = HashMap<Long, Holder>()

    private var pickedUri: Uri? = null
    private var pickedName = ""
    private var hasVideo = true
    private var hasAudio = true
    private var durationMs = 0L
    private var sourceLang = ""
    private var targetLang = "ar"

    private fun dp(v: Int) = a.dp(v)

    init {
        val p = Ui.p
        body.setPadding(dp(12), dp(12), dp(12), dp(24))
        view.addView(body)

        // --- connect ElevenLabs
        keyCard = Ui.card(a)
        keyCard.addView(Ui.text(a, 17f, p.text, bold = true).apply { text = "اربط حساب ElevenLabs أولًا" })
        keyCard.addView(Ui.text(a, 14f, p.sub).apply {
            text = "الدبلجة بأصوات الممثلين نفسها تتم في خوادم ElevenLabs.\n" +
                "1. افتح الموقع وسجّل حسابًا واشترك في باقة مدفوعة (الباقة المجانية تضع علامة مائية).\n" +
                "2. من صفحة API Keys أنشئ مفتاحًا وفعّل له صلاحية Dubbing، ثم انسخه.\n" +
                "3. اضغط «إضافة المفتاح» والصقه."
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(6), 0, dp(10))
        })
        val kr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        kr.addView(Ui.button(a, "فتح الموقع", false, R.drawable.ic_open) {
            runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEYS_URL))) }
        }, Ui.lp(0, dp(48), 1f))
        kr.addView(View(a), Ui.lp(dp(10), 1))
        kr.addView(Ui.button(a, "إضافة المفتاح", true, R.drawable.ic_paste) { askKey() }, Ui.lp(0, dp(48), 1f))
        keyCard.addView(kr)
        body.addView(keyCard, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(12) })

        // --- new dub
        val card = Ui.card(a)
        card.addView(Ui.text(a, 17f, p.text, bold = true).apply { text = "دبلجة فيديو إلى العربية" })
        card.addView(HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
            addView(engineRow)
        })
        engineHint = Ui.text(a, 12.5f, p.sub).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }
        card.addView(engineHint)
        val fileBox = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(a, p.field, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fileBox.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_movie)
            imageTintList = ColorStateList.valueOf(p.accent)
        }, Ui.lp(dp(28), dp(28)))
        val fileTexts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        fileName = Ui.text(a, 15f, p.text, bold = true).apply {
            text = "لم تختر ملفًا بعد"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        fileInfo = Ui.text(a, 12.5f, p.sub).apply { text = "فيديو أو صوت من هاتفك أو من تنزيلاتك" }
        fileTexts.addView(fileName); fileTexts.addView(fileInfo)
        fileBox.addView(fileTexts, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(fileBox, Ui.lp(Ui.MATCH, Ui.WRAP))

        val pr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        pr.addView(Ui.button(a, "من الهاتف", false, R.drawable.ic_file) { pickFromPhone() }, Ui.lp(0, dp(46), 1f))
        pr.addView(View(a), Ui.lp(dp(10), 1))
        pr.addView(Ui.button(a, "من التنزيلات", false, R.drawable.ic_download) { pickFromDownloads() }, Ui.lp(0, dp(46), 1f))
        card.addView(pr)

        // source language
        val sr = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(6))
            background = Ui.pressable(a, Color.TRANSPARENT, 12)
            setOnClickListener { pickSource() }
        }
        sr.addView(Ui.text(a, 15f, p.text).apply { text = "لغة الفيديو الأصلية" }, Ui.lp(0, Ui.WRAP, 1f))
        srcValue = Ui.text(a, 14f, p.accent, bold = true).apply { text = Dub.sourceLanguages[""] }
        sr.addView(srcValue)
        card.addView(sr)

        fun optionRow(title: String, onClick: () -> Unit): TextView {
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                background = Ui.pressable(a, Color.TRANSPARENT, 12)
                setOnClickListener { onClick() }
            }
            r.addView(Ui.text(a, 15f, p.text).apply { text = title }, Ui.lp(0, Ui.WRAP, 1f))
            val v = Ui.text(a, 14f, p.accent, bold = true)
            r.addView(v)
            freeBox.addView(r)
            return v
        }
        voiceValue = optionRow("الصوت العربي") { pickVoice() }
        asrValue = optionRow("دقة فهم الكلام") { pickAsr() }
        card.addView(freeBox)

        targetTitle = Ui.text(a, 15f, p.text).apply { text = "الدبلجة إلى"; setPadding(dp(4), dp(8), dp(4), dp(6)) }
        card.addView(targetTitle)
        targetScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(targetRow) }
        card.addView(targetScroll)
        buildTargets()
        buildEngines()

        card.addView(Ui.button(a, "ابدأ الدبلجة", true, R.drawable.ic_dub) { confirmStart() },
            Ui.lp(Ui.MATCH, dp(52)).apply { topMargin = dp(16) })
        card.addView(Ui.text(a, 12.5f, p.sub).apply {
            text = "يستمر العمل في الخلفية حتى لو أغلقت التطبيق. يُحفظ الفيديو المدبلج في Download/Downloader، " +
                "ومعه الصوت الأصلي وترجمة عربية مكتوبة كمسارات إضافية تختارها من مشغّل الفيديو."
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))

        jobsTitle = Ui.text(a, 14f, p.accent, bold = true).apply {
            text = "الدبلجات"
            setPadding(dp(6), dp(18), dp(6), dp(8))
        }
        body.addView(jobsTitle)
        body.addView(jobsBox)
    }

    private fun buildEngines() {
        val p = Ui.p
        engineRow.removeAllViews()
        val engines = linkedMapOf("free" to "مجاني على الهاتف", "eleven" to "أصوات أصلية (مدفوع)")
        for ((code, label) in engines) {
            engineRow.addView(Ui.chip(a, label, code == Dub.engine) {
                Dub.engine = code
                if (code == "free") targetLang = "ar"
                buildEngines(); buildTargets(); refresh()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
        val free = Dub.engine == "free"
        engineHint.text = if (free)
            "مجاني بالكامل: يفهم الكلام بنموذج Whisper، ويترجمه بترجمة Google المجانية، وينطقه بصوت عربي من هاتفك، " +
                "ويخفض الكلام الأصلي مع إبقاء الموسيقى والمؤثرات. الصوت العربي آلي وليس أصوات الممثلين."
        else "بأصوات الممثلين الأصليين نفسها مع الموسيقى والمؤثرات، عبر خوادم ElevenLabs. يحتاج اشتراكًا مدفوعًا ومفتاحًا."
        engineHint.setTextColor(p.sub)
        freeBox.visibility = if (free) View.VISIBLE else View.GONE
        targetTitle.visibility = if (free) View.GONE else View.VISIBLE
        targetScroll.visibility = if (free) View.GONE else View.VISIBLE
        voiceValue.text = if (Dub.voiceName.isBlank()) "الافتراضي" else voiceLabel(Dub.voiceName)
        asrValue.text = Whisper.models[Dub.asrModel]?.third ?: "سريع"
    }

    private fun voiceLabel(name: String) = name.removePrefix("ar-").substringBefore("-local").take(24)

    private fun pickAsr() {
        val keys = Whisper.models.keys.toList()
        AlertDialog.Builder(a)
            .setTitle("دقة فهم الكلام")
            .setItems(keys.map { Whisper.models.getValue(it).third }.toTypedArray()) { _, w ->
                Dub.asrModel = keys[w]; buildEngines()
            }
            .show()
        a.toast("«دقيق» أفضل فهمًا للكلام لكنه أبطأ بنحو 3 مرات")
    }

    private fun pickVoice() {
        a.toast("جارٍ تجهيز الأصوات…")
        Thread {
            val v = ArabicVoice(a)
            val ok = v.awaitReady()
            val voices = if (ok) v.arabicVoices() else emptyList()
            a.runOnUiThread {
                if (voices.isEmpty()) {
                    v.shutdown()
                    AlertDialog.Builder(a)
                        .setTitle("لا يوجد صوت عربي")
                        .setMessage("ثبّت اللغة العربية في محرّك الأصوات (Google) ثم ارجع واختر الصوت.")
                        .setPositiveButton("تثبيت الصوت العربي") { _, _ ->
                            runCatching { a.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                                .onFailure { runCatching { a.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                    return@runOnUiThread
                }
                var chosen = voices.indexOfFirst { it.name == Dub.voiceName }.coerceAtLeast(0)
                val labels = voices.mapIndexed { i, vo ->
                    "صوت ${i + 1} – ${voiceLabel(vo.name)}" + (if (vo.isNetworkConnectionRequired) " (يحتاج إنترنت)" else "")
                }.toTypedArray()
                AlertDialog.Builder(a)
                    .setTitle("اختر الصوت العربي (اضغط للاستماع)")
                    .setSingleChoiceItems(labels, chosen) { _, w ->
                        chosen = w
                        v.use(voices[w].name)
                        v.speak("مرحبًا بكم، هذا هو الصوت الذي سيُستخدم في دبلجة الفيديو إلى العربية.")
                    }
                    .setPositiveButton("اختيار") { _, _ ->
                        Dub.voiceName = voices[chosen].name
                        buildEngines()
                    }
                    .setNegativeButton("إلغاء", null)
                    .setOnDismissListener { v.shutdown() }
                    .show()
            }
        }.start()
    }

    private fun buildTargets() {
        targetRow.removeAllViews()
        for ((code, label) in Dub.targetLanguages) {
            targetRow.addView(Ui.chip(a, label, code == targetLang) {
                if (code == "ar-EG" && Dub.model != "dubbing_v2") {
                    a.toast("اللهجة المصرية متاحة في نموذج v2 فقط (من الإعدادات)")
                    return@chip
                }
                targetLang = code; buildTargets()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
    }

    // ------------------------------------------------------------------ picking

    private fun pickFromPhone() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        runCatching { a.startActivityForResult(i, PICK) }.onFailure { a.toast("لا يوجد تطبيق لاختيار الملفات") }
    }

    private fun pickFromDownloads() {
        val list = Engine.snapshot().filter {
            it.status == Status.DONE && it.savedUri != null &&
                (Ui.iconFor(it) == R.drawable.ic_movie || Ui.iconFor(it) == R.drawable.ic_music)
        }
        if (list.isEmpty()) { a.toast("لا توجد فيديوهات مكتملة في التنزيلات"); return }
        AlertDialog.Builder(a)
            .setTitle("اختر من التنزيلات")
            .setItems(list.map { it.fileName }.toTypedArray()) { _, w -> onPicked(Uri.parse(list[w].savedUri), list[w].fileName) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun onPicked(uri: Uri, knownName: String? = null) {
        runCatching { a.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        pickedUri = uri
        pickedName = knownName ?: runCatching {
            a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "video.mp4"
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(a, uri)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "no"
        } catch (e: Exception) {
            durationMs = 0L; hasVideo = true; hasAudio = true
        } finally {
            runCatching { r.release() }
        }
        fileName.text = pickedName
        fileInfo.text = (if (hasVideo) "فيديو" else "صوت") + (if (durationMs > 0) " • ${minutesText(durationMs)}" else "")
        if (!hasAudio) a.toast("هذا الفيديو بلا صوت، فلا يوجد كلام لدبلجته")
    }

    private fun minutesText(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun pickSource() {
        val codes = Dub.sourceLanguages.keys.toList()
        AlertDialog.Builder(a)
            .setTitle("لغة الفيديو الأصلية")
            .setItems(codes.map { Dub.sourceLanguages.getValue(it) }.toTypedArray()) { _, w ->
                sourceLang = codes[w]
                srcValue.text = Dub.sourceLanguages.getValue(sourceLang)
            }
            .show()
    }

    // ------------------------------------------------------------------ starting

    private fun confirmStart() {
        val uri = pickedUri ?: run { a.toast("اختر فيديو أولًا"); return }
        if (!hasAudio) { a.toast("هذا الملف بلا صوت"); return }
        if (Dub.engine == "free") {
            val minutes = if (durationMs > 0) Math.ceil(durationMs / 60000.0).toInt() else 0
            val first = !Whisper.modelFile(a, Dub.asrModel).exists()
            val msg = buildString {
                append("الملف: ").append(pickedName).append("\n")
                if (minutes > 0) append("المدة: ").append(minutes).append(" دقيقة\n")
                append("لغة الفيديو: ").append(Dub.sourceLanguages[sourceLang]).append("\n\n")
                append("مجاني تمامًا ويعمل على هاتفك. ")
                append("يستغرق عادةً بين نصف مدة الفيديو وضعفها حسب قوة الهاتف، ويستمر في الخلفية.")
                if (first) append("\n\nأول مرة فقط: سيُنزَّل نموذج فهم الكلام وحزمة الترجمة، فاتصل بشبكة Wi-Fi.")
            }
            AlertDialog.Builder(a)
                .setTitle("بدء الدبلجة المجانية؟")
                .setMessage(msg)
                .setPositiveButton("ابدأ") { _, _ -> launchDub(uri) }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        if (Dub.apiKey.isBlank()) { askKey(); return }
        val minutes = if (durationMs > 0) Math.ceil(durationMs / 60000.0).toInt() else 0
        val v2 = Dub.model == "dubbing_v2"
        val perMin = if (v2) "نحو 2.2 دولار" else "نحو 0.5 دولار"
        val msg = buildString {
            append("الملف: ").append(pickedName).append("\n")
            if (minutes > 0) append("المدة: ").append(minutes).append(" دقيقة\n")
            append("الدبلجة إلى: ").append(Dub.targetLanguages[targetLang]).append("\n")
            append("النموذج: ").append(if (v2) "v2 (أعلى جودة)" else "v1 (أرخص)").append("\n\n")
            append("تُحسب التكلفة من رصيدك في ElevenLabs حسب باقتك؛ الدقيقة الإضافية تكلّف ").append(perMin).append(" تقريبًا")
            if (minutes > 0) append("، أي قرابة ").append(if (v2) "%.0f".format(minutes * 2.2) else "%.1f".format(minutes * 0.5)).append(" دولار لهذا الملف إن لم يكفِ رصيد باقتك")
            append(".")
        }
        AlertDialog.Builder(a)
            .setTitle("بدء الدبلجة؟")
            .setMessage(msg)
            .setPositiveButton("ابدأ") { _, _ -> launchDub(uri) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun launchDub(uri: Uri) {
        Dub.start(uri, pickedName, hasVideo, hasAudio, durationMs, sourceLang, if (Dub.engine == "free") "ar" else targetLang)
        pickedUri = null
        fileName.text = "لم تختر ملفًا بعد"
        fileInfo.text = "فيديو أو صوت من هاتفك أو من تنزيلاتك"
        a.toast("بدأت الدبلجة، تابعها في الأسفل")
        refresh()
    }

    fun askKey() {
        val field = Ui.field(a, "الصق مفتاح API هنا").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setText(Dub.apiKey)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        AlertDialog.Builder(a)
            .setTitle("مفتاح ElevenLabs")
            .setView(box)
            .setPositiveButton("حفظ وتحقق") { _, _ ->
                Dub.apiKey = field.text.toString()
                refresh()
                verifyKey()
            }
            .setNeutralButton("الحصول على مفتاح") { _, _ ->
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEYS_URL))) }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun verifyKey() {
        val key = Dub.apiKey
        if (key.isBlank()) return
        a.toast("جارٍ التحقق من المفتاح…")
        Thread {
            val msg = try {
                val c = (URL("https://api.elevenlabs.io/v1/dubbing/project?page_size=1").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("xi-api-key", key)
                    connectTimeout = 20_000; readTimeout = 20_000
                }
                when (c.responseCode) {
                    in 200..299 -> "المفتاح يعمل ✓"
                    401 -> "المفتاح غير صحيح أو لا يملك صلاحية الدبلجة"
                    403 -> "المفتاح لا يملك صلاحية Dubbing، فعّلها في ElevenLabs"
                    else -> "تعذّر التحقق (${c.responseCode})، لكن حُفظ المفتاح"
                }
            } catch (e: Exception) {
                "تعذّر الاتصال للتحقق، لكن حُفظ المفتاح"
            }
            a.runOnUiThread { a.toast(msg) }
        }.start()
    }

    // ------------------------------------------------------------------ job list

    private class Holder(
        val root: LinearLayout, val name: TextView, val info: TextView,
        val bar: ProgressBar, val badge: TextView, val retry: ImageButton, val open: ImageButton, val delete: ImageButton,
    )

    private fun build(): Holder {
        val p = Ui.p
        val card = Ui.card(a)
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconBox = FrameLayout(a).apply { background = Ui.rounded(a, p.accentSoft, 14) }
        iconBox.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_dub)
            imageTintList = ColorStateList.valueOf(p.accent)
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        top.addView(iconBox, Ui.lp(dp(46), dp(46)))
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val name = Ui.text(a, 15f, p.text, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val info = Ui.text(a, 12.5f, p.sub).apply { setPadding(0, dp(3), 0, 0) }
        texts.addView(name); texts.addView(info)
        top.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        card.addView(top)
        val bar = Ui.progress(a)
        card.addView(bar, Ui.lp(Ui.MATCH, dp(8)).apply { topMargin = dp(12) })
        val bottom = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val badge = Ui.text(a, 12f, p.sub).apply {
            background = Ui.rounded(a, p.field, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        bottom.addView(badge)
        bottom.addView(View(a), Ui.lp(0, 1, 1f))
        val retry = Ui.iconButton(a, R.drawable.ic_refresh, p.accent, "إعادة المحاولة")
        val open = Ui.iconButton(a, R.drawable.ic_open, p.accent, "فتح")
        val delete = Ui.iconButton(a, R.drawable.ic_delete, p.danger, "حذف")
        bottom.addView(retry); bottom.addView(open); bottom.addView(delete)
        card.addView(bottom)
        val wrap = LinearLayout(a).apply { setPadding(0, dp(5), 0, dp(5)) }
        wrap.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
        return Holder(wrap, name, info, bar, badge, retry, open, delete)
    }

    fun refresh() {
        val p = Ui.p
        keyCard.visibility = if (Dub.engine == "eleven" && Dub.apiKey.isBlank()) View.VISIBLE else View.GONE
        val jobs = Dub.snapshot()
        jobsTitle.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        val ids = jobs.map { it.id }.toSet()
        for (id in cards.keys.toList()) if (id !in ids) { jobsBox.removeView(cards[id]!!.root); cards.remove(id) }
        for ((index, j) in jobs.withIndex()) {
            val h = cards.getOrPut(j.id) { build().also { jobsBox.addView(it.root, index) } }
            h.name.text = j.name
            val pct = if (j.progress >= 0) " ${j.progress.toInt()}%" else ""
            h.info.setTextColor(if (j.stage == DubStage.FAILED) p.danger else p.sub)
            h.info.text = when (j.stage) {
                DubStage.PREPARING -> j.status + pct
                DubStage.TRANSCRIBING -> "تحويل الكلام إلى نص…$pct"
                DubStage.TRANSLATING -> (if (j.status.startsWith("تنزيل")) j.status else "الترجمة إلى العربية…") + pct
                DubStage.VOICING -> "تسجيل الصوت العربي…$pct"
                DubStage.UPLOADING -> "رفع الصوت إلى ElevenLabs…$pct"
                DubStage.WAITING -> j.status.ifBlank { "تجري الدبلجة في ElevenLabs…" }
                DubStage.DOWNLOADING -> "تنزيل الصوت العربي…$pct"
                DubStage.MERGING -> "دمج الصوت العربي مع الفيديو…$pct"
                DubStage.DONE -> "اكتملت الدبلجة • في مجلد Download/Downloader"
                DubStage.FAILED -> "فشل: ${j.error ?: "خطأ غير معروف"}"
            }
            val running = j.stage != DubStage.DONE && j.stage != DubStage.FAILED
            h.bar.progressTintList = ColorStateList.valueOf(
                when (j.stage) { DubStage.DONE -> p.success; DubStage.FAILED -> p.danger; else -> p.accent }
            )
            if (running && (j.progress < 0 || j.stage == DubStage.WAITING)) h.bar.isIndeterminate = true
            else {
                h.bar.isIndeterminate = false
                h.bar.progress = when (j.stage) {
                    DubStage.DONE -> 1000
                    DubStage.FAILED -> 0
                    else -> (j.progress.coerceIn(0f, 100f) * 10).toInt()
                }
            }
            h.badge.text = if (j.engine == "free") "مجاني • " + (Dub.sourceLanguages[j.sourceLang] ?: j.sourceLang) + " ← العربية"
            else (Dub.targetLanguages[j.targetLang] ?: j.targetLang) + " • ElevenLabs " + (if (j.model == "dubbing_v2") "v2" else "v1")
            h.retry.visibility = if (j.stage == DubStage.FAILED) View.VISIBLE else View.GONE
            h.retry.setOnClickListener { Dub.retry(j.id); refresh() }
            h.open.visibility = if (j.stage == DubStage.DONE && j.resultUri != null) View.VISIBLE else View.GONE
            h.open.setOnClickListener { openResult(j) }
            h.delete.setOnClickListener { confirmDelete(j) }
        }
    }

    private fun openResult(j: DubJob) {
        val uri = j.resultUri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), j.resultMime.ifBlank { "video/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(Intent.createChooser(i, "فتح باستخدام"))
        } catch (e: Exception) {
            a.toast("لا يوجد تطبيق لفتح هذا الملف")
        }
    }

    private fun confirmDelete(j: DubJob) {
        val running = j.stage != DubStage.DONE && j.stage != DubStage.FAILED
        val msg = when {
            j.stage == DubStage.DONE -> "إزالة هذه الدبلجة من القائمة؟ يبقى الفيديو المدبلج في مجلد التنزيلات."
            running -> "إيقاف هذه الدبلجة وحذفها؟ إن كانت قد رُفعت إلى ElevenLabs فقد تُحسب تكلفتها."
            else -> "حذف هذه الدبلجة من القائمة؟"
        }
        AlertDialog.Builder(a)
            .setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> Dub.remove(j.id); refresh() }
            .setNegativeButton("لا", null)
            .show()
    }
}
