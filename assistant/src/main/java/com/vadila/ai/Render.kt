package com.vadila.ai

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/** Turns markdown answers into Android text and views. */
object Render {
    private const val EX = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun inline(s: String, out: SpannableStringBuilder) {
        val p = Ui.p
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "*_`[]()#~\\|>-+.!") {
                out.append(s[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val e = s.indexOf('`', i + 1)
                if (e > i + 1) {
                    val st = out.length
                    out.append(s, i + 1, e)
                    out.setSpan(TypefaceSpan("monospace"), st, out.length, EX)
                    out.setSpan(BackgroundColorSpan(p.field), st, out.length, EX)
                    out.setSpan(ForegroundColorSpan(p.accent), st, out.length, EX)
                    i = e + 1
                    continue
                }
            }
            if ((s.startsWith("**", i) || s.startsWith("__", i)) && i + 2 < s.length) {
                val m = s.substring(i, i + 2)
                val e = s.indexOf(m, i + 2)
                if (e > i + 2) {
                    val st = out.length
                    inline(s.substring(i + 2, e), out)
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, EX)
                    i = e + 2
                    continue
                }
            }
            if (s.startsWith("~~", i)) {
                val e = s.indexOf("~~", i + 2)
                if (e > i + 2) {
                    val st = out.length
                    inline(s.substring(i + 2, e), out)
                    out.setSpan(StrikethroughSpan(), st, out.length, EX)
                    i = e + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != c &&
                (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
            ) {
                var e = s.indexOf(c, i + 1)
                while (e > 0 && (s[e - 1] == ' ' || (c == '_' && e + 1 < s.length && s[e + 1].isLetterOrDigit()))) e = s.indexOf(c, e + 1)
                if (e > i + 1) {
                    val st = out.length
                    inline(s.substring(i + 1, e), out)
                    out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, EX)
                    i = e + 1
                    continue
                }
            }
            if (c == '!' && i + 1 < s.length && s[i + 1] == '[') {
                val close = s.indexOf("](", i)
                val end = if (close > 0) s.indexOf(')', close) else -1
                if (close > i && end > close) {
                    val url = s.substring(close + 2, end)
                    val st = out.length
                    out.append("🖼 ").append(s.substring(i + 2, close).ifBlank { "صورة" })
                    out.setSpan(URLSpan(url), st, out.length, EX)
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val close = s.indexOf("](", i)
                val end = if (close > 0) s.indexOf(')', close) else -1
                if (close > i && end > close && !s.substring(i + 1, close).contains('\n')) {
                    val url = s.substring(close + 2, end).trim().substringBefore(' ')
                    val st = out.length
                    inline(s.substring(i + 1, close), out)
                    out.setSpan(URLSpan(url), st, out.length, EX)
                    i = end + 1
                    continue
                }
            }
            if ((c == 'h') && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                var e = i
                while (e < s.length && !s[e].isWhitespace() && s[e] != ')' && s[e] != '>' && s[e] != '"' && s[e] != '،') e++
                while (e > i && s[e - 1] in ".,;:!?") e--
                val url = s.substring(i, e)
                val st = out.length
                out.append(url)
                out.setSpan(URLSpan(url), st, out.length, EX)
                i = e
                continue
            }
            out.append(c)
            i++
        }
    }

    /** Block-level markdown (headings, lists, quotes) of a text block. */
    fun text(ctx: Context, md: String): CharSequence {
        val p = Ui.p
        val out = SpannableStringBuilder()
        val lines = md.split('\n')
        for ((k, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            val t = line.trimStart()
            val indent = (line.length - t.length) / 2
            val st = out.length
            val h = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(t)
            when {
                h != null -> {
                    inline(h.groupValues[2].trimEnd('#', ' '), out)
                    val lvl = h.groupValues[1].length
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, EX)
                    out.setSpan(RelativeSizeSpan(when (lvl) { 1 -> 1.32f; 2 -> 1.22f; 3 -> 1.12f; else -> 1.04f }), st, out.length, EX)
                }
                Regex("""^(-{3,}|\*{3,}|_{3,})$""").matches(t) -> {
                    out.append("────────────")
                    out.setSpan(ForegroundColorSpan(p.divider), st, out.length, EX)
                }
                t.startsWith(">") -> {
                    inline(t.trimStart('>', ' '), out)
                    out.setSpan(QuoteSpan(p.accent, Ui.dp(ctx, 3), Ui.dp(ctx, 10)), st, out.length, EX)
                    out.setSpan(ForegroundColorSpan(p.sub), st, out.length, EX)
                }
                Regex("""^[-*+]\s+\[([ xX])]\s+(.*)$""").matchEntire(t) != null -> {
                    val m = Regex("""^[-*+]\s+\[([ xX])]\s+(.*)$""").matchEntire(t)!!
                    out.append(if (m.groupValues[1].isBlank()) "☐ " else "☑ ")
                    inline(m.groupValues[2], out)
                    out.setSpan(LeadingMarginSpan.Standard(Ui.dp(ctx, 8 + indent * 16), Ui.dp(ctx, 26 + indent * 16)), st, out.length, EX)
                }
                Regex("""^[-*+]\s+(.*)$""").matchEntire(t) != null -> {
                    val m = Regex("""^[-*+]\s+(.*)$""").matchEntire(t)!!
                    out.append(if (indent > 0) "◦  " else "•  ")
                    out.setSpan(ForegroundColorSpan(p.accent), st, st + 1, EX)
                    inline(m.groupValues[1], out)
                    out.setSpan(LeadingMarginSpan.Standard(Ui.dp(ctx, 4 + indent * 16), Ui.dp(ctx, 18 + indent * 16)), st, out.length, EX)
                }
                Regex("""^(\d+)[.)]\s+(.*)$""").matchEntire(t) != null -> {
                    val m = Regex("""^(\d+)[.)]\s+(.*)$""").matchEntire(t)!!
                    out.append(m.groupValues[1]).append(". ")
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, EX)
                    inline(m.groupValues[2], out)
                    out.setSpan(LeadingMarginSpan.Standard(Ui.dp(ctx, 4 + indent * 16), Ui.dp(ctx, 22 + indent * 16)), st, out.length, EX)
                }
                else -> inline(line, out)
            }
            if (k < lines.size - 1) out.append('\n')
        }
        return out
    }

    // ---- code -------------------------------------------------------------------------------------
    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "private", "public", "protected", "internal", "override", "open", "abstract", "data", "sealed", "enum",
        "companion", "const", "lateinit", "suspend", "null", "true", "false", "this", "super", "new", "static", "final", "void", "int",
        "long", "float", "double", "boolean", "char", "byte", "short", "String", "def", "lambda", "from", "as", "in", "is", "not", "and",
        "or", "None", "True", "False", "self", "pass", "with", "try", "catch", "except", "finally", "raise", "throw", "throws", "async",
        "await", "yield", "function", "let", "const", "typeof", "instanceof", "export", "default", "extends", "implements", "switch",
        "case", "struct", "impl", "pub", "fn", "mut", "use", "mod", "match", "func", "go", "defer", "type", "namespace", "using",
        "include", "define", "auto", "template", "typename", "virtual", "echo", "then", "fi", "elif", "done", "esac", "select",
        "where", "insert", "update", "delete", "create", "table", "values", "into", "join", "on", "group", "by", "order", "Widget",
        "required", "late", "final", "extension", "mixin", "dynamic", "print", "println", "elif", "global", "nonlocal", "assert",
    )

    fun code(code: String, lang: String): CharSequence {
        val sb = SpannableStringBuilder(code)
        if (code.length > 60_000) return sb
        val hashComments = lang in setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "toml", "ruby", "rb", "r", "perl",
            "dockerfile", "makefile", "conf", "ini", "properties", "powershell", "ps1", "cmake", "text", "")
        val cStr = 0xFFA5D6A7.toInt()
        val cCom = 0xFF7F8796.toInt()
        val cNum = 0xFFFFCC80.toInt()
        val cKey = 0xFFB39DDB.toInt()
        val cFn = 0xFF81D4FA.toInt()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (c == '/' && i + 1 < n && code[i + 1] == '/' || hashComments && c == '#' || c == '-' && i + 1 < n && code[i + 1] == '-' && lang == "sql") {
                val e = code.indexOf('\n', i).let { if (it < 0) n else it }
                sb.setSpan(ForegroundColorSpan(cCom), i, e, EX)
                i = e
                continue
            }
            if (c == '/' && i + 1 < n && code[i + 1] == '*') {
                val e = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                sb.setSpan(ForegroundColorSpan(cCom), i, e, EX)
                i = e
                continue
            }
            if (c == '<' && code.startsWith("<!--", i)) {
                val e = code.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                sb.setSpan(ForegroundColorSpan(cCom), i, e, EX)
                i = e
                continue
            }
            if (c == '"' || c == '\'' || c == '`') {
                val triple = i + 2 < n && code[i + 1] == c && code[i + 2] == c
                var e = i + if (triple) 3 else 1
                while (e < n) {
                    if (code[e] == '\\') {
                        e += 2
                        continue
                    }
                    if (triple) {
                        if (code[e] == c && e + 2 < n && code[e + 1] == c && code[e + 2] == c) {
                            e += 3
                            break
                        }
                    } else {
                        if (code[e] == c) {
                            e++
                            break
                        }
                        if (code[e] == '\n' && c != '`') break
                    }
                    e++
                }
                e = e.coerceAtMost(n)
                sb.setSpan(ForegroundColorSpan(cStr), i, e, EX)
                i = e
                continue
            }
            if (c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_')) {
                var e = i
                while (e < n && (code[e].isLetterOrDigit() || code[e] == '.' || code[e] == '_')) e++
                sb.setSpan(ForegroundColorSpan(cNum), i, e, EX)
                i = e
                continue
            }
            if (c.isLetter() || c == '_' || c == '@') {
                var e = i + 1
                while (e < n && (code[e].isLetterOrDigit() || code[e] == '_')) e++
                val word = code.substring(i, e)
                if (word in KEYWORDS || c == '@') sb.setSpan(ForegroundColorSpan(cKey), i, e, EX)
                else if (e < n && code[e] == '(') sb.setSpan(ForegroundColorSpan(cFn), i, e, EX)
                i = e
                continue
            }
            i++
        }
        return sb
    }

    // ---- views ------------------------------------------------------------------------------------
    class Hooks(
        val onCopyCode: (String) -> Unit,
        val onSaveCode: (String, String?) -> Unit,
        val onLongPress: () -> Unit,
    )

    fun body(ctx: Context, md: String, hooks: Hooks, streaming: Boolean = false): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fill(ctx, box, md, hooks, streaming)
        return box
    }

    fun fill(ctx: Context, box: LinearLayout, md: String, hooks: Hooks, streaming: Boolean) {
        box.removeAllViews()
        val p = Ui.p
        for (b in Md.blocks(md)) {
            when (b) {
                is Blk.Text -> {
                    if (b.md.isBlank()) continue
                    val tv = TextView(ctx).apply {
                        text = text(ctx, b.md)
                        setTextColor(p.text)
                        textSize = 16f
                        setLineSpacing(0f, 1.18f)
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        movementMethod = LinkMovementMethod.getInstance()
                        linksClickable = true
                        setLinkTextColor(p.accent)
                        setOnLongClickListener { hooks.onLongPress(); true }
                    }
                    box.addView(tv, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 2); bottomMargin = Ui.dp(ctx, 4) })
                }
                is Blk.Code -> box.addView(codeView(ctx, b, hooks, streaming), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 6); bottomMargin = Ui.dp(ctx, 8) })
                is Blk.Table -> box.addView(tableView(ctx, b), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 6); bottomMargin = Ui.dp(ctx, 8) })
            }
        }
    }

    private fun codeView(ctx: Context, b: Blk.Code, hooks: Hooks, streaming: Boolean): View {
        val p = Ui.p
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(ctx, p.codeBg, 12)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 2), Ui.dp(ctx, 4), 0)
        }
        val title = TextView(ctx).apply {
            text = b.file ?: b.lang.ifBlank { "code" }
            setTextColor(0xFFB8BCC8.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        head.addView(title, Ui.lp(0, Ui.WRAP, 1f))
        if (!streaming || b.closed) {
            head.addView(Ui.iconButton(ctx, R.drawable.ic_download, 0xFFB8BCC8.toInt(), "حفظ", 36) { hooks.onSaveCode(b.code, b.file) })
            head.addView(Ui.iconButton(ctx, R.drawable.ic_copy, 0xFFB8BCC8.toInt(), "نسخ", 36) { hooks.onCopyCode(b.code) })
        }
        card.addView(head)
        val tv = TextView(ctx).apply {
            text = if (streaming && !b.closed) b.code else code(b.code, b.lang)
            setTextColor(0xFFE6E8EF.toInt())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
            setTextIsSelectable(!streaming)
        }
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tv)
        }
        card.addView(hs)
        return card
    }

    private fun tableView(ctx: Context, b: Blk.Table): View {
        val p = Ui.p
        val table = TableLayout(ctx).apply {
            background = Ui.rounded(ctx, p.surface, 10, p.divider)
            setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2))
        }
        for ((r, row) in b.rows.withIndex()) {
            val tr = TableRow(ctx)
            if (r == 0) tr.setBackgroundColor(p.accentSoft)
            for (cell in row) {
                val sb = SpannableStringBuilder()
                inline(cell, sb)
                if (r == 0) sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, EX)
                tr.addView(TextView(ctx).apply {
                    text = sb
                    setTextColor(p.text)
                    textSize = 14f
                    maxWidth = Ui.dp(ctx, 260)
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 7), Ui.dp(ctx, 10), Ui.dp(ctx, 7))
                })
            }
            table.addView(tr)
            if (r < b.rows.size - 1) table.addView(View(ctx).apply { setBackgroundColor(p.divider) }, TableLayout.LayoutParams(Ui.MATCH, 1))
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(table)
        }
    }
}
