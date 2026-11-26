package com.vadila.downloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: TaskAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            adapter.refresh()
            val list = adapter.items
            val running = list.count { it.status == Status.RUNNING }
            val queued = list.count { it.status == Status.QUEUED }
            summary.text = "السرعة الكلية: ${Engine.fmtBytes(Engine.totalSpeed())}/ث • يعمل: $running • في الانتظار: $queued"
            empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            handler.postDelayed(this, 800)
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        input = EditText(this).apply {
            hint = "ضع رابطًا مباشرًا أو أكثر (كل رابط في سطر)"
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("تنزيل") { addFromInput() }, weight())
        row.addView(button("لصق") { paste() }, weight())
        row.addView(button("الإعدادات") { showSettings() }, weight())
        root.addView(row)

        summary = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(summary)

        empty = TextView(this).apply {
            text = "لا توجد تنزيلات بعد.\nالصق رابط ملف مباشر (فيديو، صورة، صوت، ملف مضغوط...) ثم اضغط تنزيل،\nأو شارك الرابط إلى هذا التطبيق من أي تطبيق آخر."
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(32), dp(8), dp(8))
        }
        root.addView(empty)

        val listView = ListView(this).apply { dividerHeight = dp(1) }
        adapter = TaskAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "استئناف الكل")
        menu.add(0, 2, 1, "إيقاف الكل")
        menu.add(0, 3, 2, "مسح المكتملة من القائمة")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> Engine.resumeAll()
            2 -> Engine.pauseAll()
            3 -> Engine.clearFinished()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ input

    private fun extractUrls(text: String): List<String> =
        Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '؛', '،') }
            .distinct().toList()

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urls = extractUrls(text)
            if (urls.isNotEmpty()) {
                input.setText(urls.joinToString("\n"))
                toast("اضغط تنزيل لبدء التنزيل")
            }
            i.action = null
        }
    }

    private fun addFromInput() {
        val urls = extractUrls(input.text.toString())
        if (urls.isEmpty()) { toast("لم أجد رابطًا صالحًا يبدأ بـ http أو https"); return }
        Engine.add(urls)
        input.setText("")
        toast("أُضيف ${urls.size} تنزيل")
        adapter.refresh()
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) { toast("الحافظة فارغة"); return }
        val cur = input.text.toString()
        input.setText(if (cur.isBlank()) text else cur.trimEnd() + "\n" + text)
        input.setSelection(input.text.length)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun field(label: String, value: Int): EditText {
            box.addView(TextView(this).apply { text = label; setPadding(0, dp(10), 0, 0) })
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            box.addView(e)
            return e
        }
        val conn = field("عدد الاتصالات لكل ملف (1 – 16)", Engine.connections)
        val conc = field("عدد الملفات التي تُنزَّل معًا (1 – 5)", Engine.maxConcurrent)
        val limit = field("حد السرعة بالكيلوبايت/ث (0 = بلا حد)", Engine.speedLimitKb)
        AlertDialog.Builder(this)
            .setTitle("الإعدادات")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                conn.text.toString().toIntOrNull()?.let { Engine.connections = it }
                conc.text.toString().toIntOrNull()?.let { Engine.maxConcurrent = it }
                limit.text.toString().toIntOrNull()?.let { Engine.speedLimitKb = it }
                toast("حُفظت الإعدادات (عدد الاتصالات يطبَّق على التنزيلات الجديدة)")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ------------------------------------------------------------------ list

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private class Holder(
        val name: TextView,
        val info: TextView,
        val bar: ProgressBar,
        val action: Button,
        val open: Button,
        val delete: Button,
    )

    inner class TaskAdapter : BaseAdapter() {
        var items: List<Task> = emptyList()

        fun refresh() {
            items = Engine.snapshot()
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: View
            val h: Holder
            if (convertView == null) {
                val ctx = this@MainActivity
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                val name = TextView(ctx).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }
                val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
                val info = TextView(ctx).apply { textSize = 13f }
                val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val action = Button(ctx).apply { isAllCaps = false }
                val open = Button(ctx).apply { isAllCaps = false; text = "فتح" }
                val delete = Button(ctx).apply { isAllCaps = false; text = "حذف" }
                buttons.addView(action, weight())
                buttons.addView(open, weight())
                buttons.addView(delete, weight())
                box.addView(name)
                box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                box.addView(info)
                box.addView(buttons)
                h = Holder(name, info, bar, action, open, delete)
                box.tag = h
                view = box
            } else {
                view = convertView
                h = convertView.tag as Holder
            }
            bind(h, items[position])
            return view
        }

        private fun bind(h: Holder, t: Task) {
            h.name.text = t.fileName.ifBlank { t.url }
            val done = t.downloaded()
            val total = t.totalBytes
            when {
                t.status == Status.DONE -> { h.bar.isIndeterminate = false; h.bar.progress = 1000 }
                total > 0 -> { h.bar.isIndeterminate = false; h.bar.progress = (done * 1000 / total).toInt().coerceIn(0, 1000) }
                t.status == Status.RUNNING -> h.bar.isIndeterminate = true
                else -> { h.bar.isIndeterminate = false; h.bar.progress = 0 }
            }
            val size = if (total > 0) "${Engine.fmtBytes(done)} / ${Engine.fmtBytes(total)}" else Engine.fmtBytes(done)
            val pct = if (total > 0) " (${done * 100 / total}%)" else ""
            h.info.text = when (t.status) {
                Status.RUNNING -> if (t.pauseRequested) "جارٍ الإيقاف…" else
                    "يُنزَّل • $size$pct • ${Engine.fmtBytes(t.speed)}/ث • ${t.segments.count { !it.complete() }} اتصال"
                Status.QUEUED -> "في الانتظار"
                Status.PAUSED -> "متوقف مؤقتًا • $size$pct"
                Status.DONE -> "اكتمل • ${Engine.fmtBytes(total)} • في مجلد Download/Downloader"
                Status.FAILED -> "فشل: ${t.error ?: "خطأ غير معروف"}"
            }

            h.action.visibility = if (t.status == Status.DONE) View.GONE else View.VISIBLE
            h.action.text = when (t.status) {
                Status.RUNNING, Status.QUEUED -> "إيقاف"
                Status.FAILED -> "إعادة المحاولة"
                else -> "استئناف"
            }
            h.action.setOnClickListener {
                if (t.status == Status.RUNNING || t.status == Status.QUEUED) Engine.pause(t.id) else Engine.resume(t.id)
                refresh()
            }
            h.open.visibility = if (t.status == Status.DONE && t.savedUri != null) View.VISIBLE else View.GONE
            h.open.setOnClickListener { openFile(t) }
            h.delete.setOnClickListener { confirmDelete(t) }
        }
    }

    private fun openFile(t: Task) {
        val uri = t.savedUri ?: return
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), t.mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "فتح باستخدام"))
        } catch (e: Exception) {
            toast("لا يوجد تطبيق لفتح هذا الملف")
        }
    }

    private fun confirmDelete(t: Task) {
        val msg = if (t.status == Status.DONE)
            "إزالة \"${t.fileName}\" من القائمة؟ (يبقى الملف في مجلد التنزيلات)"
        else "إلغاء تنزيل \"${t.fileName}\" وحذف الجزء المنزَّل؟"
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> Engine.remove(t.id); adapter.refresh() }
            .setNegativeButton("لا", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
