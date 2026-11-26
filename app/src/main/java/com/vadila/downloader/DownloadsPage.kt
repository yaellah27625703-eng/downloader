package com.vadila.downloader

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

class DownloadsPage(private val a: MainActivity) {

    val view: LinearLayout
    private val input: EditText
    private val list: ListView
    private val empty: LinearLayout
    private val chips = ArrayList<TextView>()
    private var filter = 0
    private val adapter = TaskAdapter()

    private fun dp(v: Int) = a.dp(v)

    init {
        val p = Ui.p
        view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        val card = Ui.card(a)
        input = Ui.field(a, "الصق رابط فيديو أو صفحة أو ملف…").apply {
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
        }
        card.addView(input, Ui.lp(Ui.MATCH, Ui.WRAP))
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(Ui.button(a, "لصق", false, R.drawable.ic_paste) { paste() }, Ui.lp(0, dp(48), 1f))
        row.addView(View(a), Ui.lp(dp(10), 1))
        row.addView(Ui.button(a, "تنزيل", true, R.drawable.ic_download) { submit() }, Ui.lp(0, dp(48), 1.5f))
        card.addView(row)
        view.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))

        val chipRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(6))
        }
        for (i in 0..3) {
            val c = Ui.chip(a, "", false) { filter = i; refresh(Engine.snapshot()) }
            chips.add(c)
            chipRow.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = dp(8) })
        }
        val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(chipRow)
        view.addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        list = ListView(a).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, 0, 0, dp(12))
            adapter = this@DownloadsPage.adapter
        }
        empty = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_download)
                imageTintList = ColorStateList.valueOf(p.accent)
                background = Ui.rounded(a, p.accentSoft, 40)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }, Ui.lp(dp(80), dp(80)))
            addView(Ui.text(a, 17f, p.text, bold = true).apply {
                text = "ابدأ أول تنزيل"
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(Ui.text(a, 14f, p.sub).apply {
                text = "الصق رابط فيديو من يوتيوب أو تيك توك أو فيسبوك أو إنستغرام أو X، أو رابط أي ملف.\nأو افتح المتصفح ونزّل مباشرة من أي موقع."
                gravity = Gravity.CENTER
            })
        }
        val frame = FrameLayout(a)
        frame.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(empty, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        view.addView(frame, Ui.lp(Ui.MATCH, 0, 1f))
    }

    fun setInput(s: String) = input.setText(s)

    private fun submit() {
        val urls = a.extractUrls(input.text.toString())
        if (urls.isEmpty()) { a.toast("لم أجد رابطًا صالحًا يبدأ بـ http أو https"); return }
        input.setText("")
        a.hideKeyboard()
        a.requestDownload(urls)
    }

    private fun paste() {
        val cm = a.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString()
        if (text.isNullOrBlank()) { a.toast("الحافظة فارغة"); return }
        val cur = input.text.toString()
        input.setText(if (cur.isBlank()) text else cur.trimEnd() + "\n" + text)
        input.setSelection(input.text.length)
    }

    private fun matches(t: Task, f: Int) = when (f) {
        1 -> t.status == Status.RUNNING || t.status == Status.QUEUED
        2 -> t.status == Status.DONE
        3 -> t.status == Status.PAUSED || t.status == Status.FAILED
        else -> true
    }

    fun refresh(all: List<Task>) {
        val p = Ui.p
        val labels = listOf("الكل", "الجارية", "المكتملة", "المتوقفة")
        for (i in chips.indices) {
            val n = all.count { matches(it, i) }
            val sel = i == filter
            chips[i].text = "${labels[i]} ($n)"
            chips[i].setTextColor(if (sel) p.onAccent else p.text)
            chips[i].background = Ui.pressable(a, if (sel) p.accent else p.field, 18)
        }
        adapter.items = all.filter { matches(it, filter) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ list

    private class Holder(
        val iconBox: FrameLayout,
        val icon: ImageView,
        val name: TextView,
        val info: TextView,
        val bar: ProgressBar,
        val badge: TextView,
        val action: ImageButton,
        val open: ImageButton,
        val delete: ImageButton,
        val card: LinearLayout,
    )

    inner class TaskAdapter : BaseAdapter() {
        var items: List<Task> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h: Holder
            val v: View
            if (convertView == null) {
                h = build()
                v = h.card.parent as View
                v.tag = h
            } else {
                v = convertView
                h = convertView.tag as Holder
            }
            bind(h, items[position])
            return v
        }
    }

    private fun build(): Holder {
        val p = Ui.p
        val wrap = FrameLayout(a).apply { setPadding(0, dp(5), 0, dp(5)) }
        val card = Ui.card(a)
        wrap.addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconBox = FrameLayout(a).apply { background = Ui.rounded(a, p.accentSoft, 14) }
        val icon = ImageView(a).apply { imageTintList = ColorStateList.valueOf(p.accent) }
        iconBox.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        top.addView(iconBox, Ui.lp(dp(46), dp(46)))

        val texts = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val name = Ui.text(a, 15f, p.text, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val info = Ui.text(a, 12.5f, p.sub).apply { setPadding(0, dp(3), 0, 0) }
        texts.addView(name)
        texts.addView(info)
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
        val action = Ui.iconButton(a, R.drawable.ic_pause, p.accent, "إيقاف/استئناف")
        val open = Ui.iconButton(a, R.drawable.ic_open, p.accent, "فتح")
        val delete = Ui.iconButton(a, R.drawable.ic_delete, p.danger, "حذف")
        bottom.addView(action)
        bottom.addView(open)
        bottom.addView(delete)
        card.addView(bottom)
        return Holder(iconBox, icon, name, info, bar, badge, action, open, delete, card)
    }

    private fun bind(h: Holder, t: Task) {
        val p = Ui.p
        h.name.text = t.fileName.ifBlank { t.url }
        h.icon.setImageResource(Ui.iconFor(t))

        // progress
        val pct: Float = when {
            t.status == Status.DONE -> 100f
            t.media -> t.progressPct
            t.totalBytes > 0 -> t.downloaded() * 100f / t.totalBytes
            else -> -1f
        }
        val barColor = when (t.status) {
            Status.DONE -> p.success
            Status.FAILED -> p.danger
            Status.PAUSED -> p.sub
            else -> p.accent
        }
        h.bar.progressTintList = ColorStateList.valueOf(barColor)
        if (t.status == Status.RUNNING && pct < 0) h.bar.isIndeterminate = true
        else {
            h.bar.isIndeterminate = false
            h.bar.progress = (pct.coerceIn(0f, 100f) * 10).toInt()
        }

        // info line
        val pctText = if (pct >= 0) "${pct.toInt()}%" else ""
        val sizeText = when {
            t.media -> if (t.totalBytes > 0) Engine.fmtBytes(t.totalBytes) else ""
            t.totalBytes > 0 -> "${Engine.fmtBytes(t.downloaded())} / ${Engine.fmtBytes(t.totalBytes)}"
            else -> Engine.fmtBytes(t.downloaded())
        }
        val eta = if (t.media && t.eta > 0) "متبقٍّ ${t.eta / 60}:${"%02d".format(t.eta % 60)}" else ""
        h.info.setTextColor(if (t.status == Status.FAILED) p.danger else p.sub)
        h.info.text = when (t.status) {
            Status.RUNNING -> if (t.pauseRequested) "جارٍ الإيقاف…"
            else listOf(t.statusLine, pctText, sizeText, "${Engine.fmtBytes(t.speed)}/ث", eta)
                .filter { it.isNotBlank() }.joinToString(" • ")
            Status.QUEUED -> "في الانتظار"
            Status.PAUSED -> listOf("متوقف مؤقتًا", pctText).filter { it.isNotBlank() }.joinToString(" • ")
            Status.DONE -> "اكتمل • ${Engine.fmtBytes(t.totalBytes)}"
            Status.FAILED -> "فشل: ${t.error ?: "خطأ غير معروف"}"
        }

        h.badge.text = when {
            t.media -> Quality.shortLabel(t.quality)
            !t.supportsRanges && t.status == Status.RUNNING -> "اتصال واحد"
            t.status == Status.RUNNING -> "${t.segments.count { !it.complete() }} اتصالات"
            else -> t.fileName.substringAfterLast('.', "ملف").uppercase().take(5)
        }

        // buttons
        h.action.visibility = if (t.status == Status.DONE) View.GONE else View.VISIBLE
        h.action.setImageResource(
            when (t.status) {
                Status.RUNNING, Status.QUEUED -> R.drawable.ic_pause
                Status.FAILED -> R.drawable.ic_refresh
                else -> R.drawable.ic_play
            }
        )
        h.action.setOnClickListener {
            if (t.status == Status.RUNNING || t.status == Status.QUEUED) Engine.pause(t.id) else Engine.resume(t.id)
            refresh(Engine.snapshot())
        }
        val canOpen = t.status == Status.DONE && t.savedUri != null
        h.open.visibility = if (canOpen) View.VISIBLE else View.GONE
        h.open.setOnClickListener { openFile(t) }
        h.card.setOnClickListener { if (canOpen) openFile(t) }
        h.delete.setOnClickListener { confirmDelete(t) }
    }

    private fun openFile(t: Task) {
        val uri = t.savedUri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), t.mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(Intent.createChooser(i, "فتح باستخدام"))
        } catch (e: Exception) {
            a.toast("لا يوجد تطبيق لفتح هذا الملف")
        }
    }

    private fun confirmDelete(t: Task) {
        val msg = if (t.status == Status.DONE)
            "إزالة \"${t.fileName}\" من القائمة؟\nيبقى الملف في مجلد Download/Downloader."
        else "إلغاء تنزيل \"${t.fileName}\" وحذف الجزء المنزَّل؟"
        AlertDialog.Builder(a)
            .setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> Engine.remove(t.id); refresh(Engine.snapshot()) }
            .setNegativeButton("لا", null)
            .show()
    }
}
