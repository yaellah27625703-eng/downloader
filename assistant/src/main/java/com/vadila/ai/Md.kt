package com.vadila.ai

/** A piece of a markdown answer. */
sealed class Blk {
    class Text(val md: String) : Blk()
    class Code(val lang: String, val code: String, val file: String?, val closed: Boolean) : Blk()
    class Table(val rows: List<List<String>>) : Blk()
}

/** Markdown structure helpers (pure Kotlin; the Android rendering lives in Render.kt). */
object Md {
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})\s*(.*)$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val PATH = Regex("""^[\w\-. /\[\]()+@~]*[\w\-.]+$""")
    private val NO_EXT_FILES = setOf(
        "dockerfile", "makefile", "procfile", "gradlew", "license", "readme", "cmakelists.txt", "podfile", "gemfile",
        "jenkinsfile", "vagrantfile", "brewfile",
    )

    fun blocks(src: String): List<Blk> {
        val out = ArrayList<Blk>()
        val lines = src.split('\n')
        val text = StringBuilder()
        var i = 0
        fun flushText() {
            if (text.isEmpty()) return
            splitTables(text.toString(), out)
            text.setLength(0)
        }
        while (i < lines.size) {
            val line = lines[i]
            val m = FENCE.matchEntire(line)
            if (m != null) {
                val fence = m.groupValues[1]
                val info = m.groupValues[2].trim()
                val prev = lastNonEmptyLine(text)
                val code = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val l = lines[j]
                    val t = l.trim()
                    if (t.length >= fence.length && t.all { it == fence[0] }) {
                        closed = true
                        break
                    }
                    if (code.isNotEmpty() || j > i + 1) code.append('\n')
                    code.append(l)
                    j++
                }
                val (lang, fileFromInfo) = parseInfo(info)
                var file = fileFromInfo
                var dropPrevLine = false
                if (file == null && prev != null) {
                    file = pathFromLine(prev)
                    // a bare "`path`" line is only a label — remove it from the text
                    if (file != null && isBareLabel(prev)) dropPrevLine = true
                }
                if (dropPrevLine) removeLastNonEmptyLine(text)
                flushText()
                out.add(Blk.Code(lang, code.toString(), file, closed))
                i = if (closed) j + 1 else j
                continue
            }
            text.append(line).append('\n')
            i++
        }
        flushText()
        return out
    }

    private fun splitTables(md: String, out: MutableList<Blk>) {
        val lines = md.trimEnd('\n').split('\n')
        val buf = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
                if (buf.isNotBlank()) out.add(Blk.Text(buf.toString().trimEnd('\n')))
                buf.setLength(0)
                val rows = ArrayList<List<String>>()
                rows.add(cells(l))
                var j = i + 2
                while (j < lines.size && lines[j].trim().startsWith("|")) {
                    rows.add(cells(lines[j]))
                    j++
                }
                out.add(Blk.Table(rows))
                i = j
                continue
            }
            buf.append(l).append('\n')
            i++
        }
        if (buf.isNotBlank()) out.add(Blk.Text(buf.toString().trimEnd('\n')))
    }

    fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        val res = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '|') {
                cur.append('|')
                k += 2
                continue
            }
            if (c == '|') {
                res.add(cur.toString().trim())
                cur.setLength(0)
            } else cur.append(c)
            k++
        }
        res.add(cur.toString().trim())
        return res
    }

    /** "kotlin", "kotlin app/Main.kt", "kotlin:app/Main.kt", "kotlin title=\"a/b.kt\"", "app/Main.kt" */
    fun parseInfo(info: String): Pair<String, String?> {
        if (info.isEmpty()) return "" to null
        var lang = ""
        var file: String? = null
        val attr = Regex("""(?:title|file|filename|path|name)\s*=\s*["']?([^"'\s]+)["']?""").find(info)
        if (attr != null) file = cleanPath(attr.groupValues[1])
        val first = info.split(Regex("\\s+"))[0]
        if (first.contains(':') && !first.contains("://")) {
            val k = first.indexOf(':')
            lang = first.substring(0, k)
            if (file == null) file = cleanPath(first.substring(k + 1))
        } else if (first.contains('=')) {
            lang = ""
        } else if (looksLikePath(first) && (first.contains('/') || first.contains('.') && !first.startsWith("."))) {
            if (file == null) file = cleanPath(first)
            lang = first.substringAfterLast('.', "")
        } else {
            lang = first
        }
        if (file == null) {
            for (tok in info.split(Regex("\\s+")).drop(1)) {
                if (looksLikePath(tok)) {
                    file = cleanPath(tok)
                    break
                }
            }
        }
        return lang.lowercase() to file
    }

    private fun lastNonEmptyLine(sb: StringBuilder): String? {
        val lines = sb.toString().split('\n')
        for (k in lines.indices.reversed()) if (lines[k].isNotBlank()) return lines[k]
        return null
    }

    private fun removeLastNonEmptyLine(sb: StringBuilder) {
        val lines = sb.toString().split('\n').toMutableList()
        for (k in lines.indices.reversed()) if (lines[k].isNotBlank()) {
            lines.removeAt(k)
            break
        }
        sb.setLength(0)
        sb.append(lines.joinToString("\n"))
    }

    private fun isBareLabel(line: String): Boolean {
        val t = line.trim()
        return Regex("""^(#{1,6}\s*)?(\*\*|__)?`?[^\s`*]+`?(\*\*|__)?:?$""").matches(t) ||
            Regex("""^(FILE|File|file|الملف|ملف)\s*[:：]\s*\S+$""").matches(t) ||
            t.startsWith("📄")
    }

    /** Finds a file path in the line that precedes a code block ("FILE: a/b.kt", "**`a/b.kt`**", "### a/b.kt"…). */
    fun pathFromLine(line: String): String? {
        var t = line.trim()
        Regex("""(?:FILE|File|file|الملف|ملف|📄)\s*[:：]?\s*`?([^`\s]+)`?""").find(t)?.let {
            val p = cleanPath(it.groupValues[1])
            if (p != null && looksLikePath(p)) return p
        }
        t = t.trimStart('#', ' ', '-', '*', '>').trim()
        t = t.removeSuffix(":").trim()
        t = t.trim('*', '_').trim()
        val tick = Regex("""`([^`]+)`""").findAll(t).map { it.groupValues[1] }.toList()
        if (tick.size == 1 && looksLikePath(tick[0]) && (tick[0].contains('/') || tick[0].contains('.'))) {
            // "`a/b.kt`" or "Create `a/b.kt`:" — only accept when the line is short (a label, not a sentence)
            if (t.length <= tick[0].length + 40) return cleanPath(tick[0])
        }
        t = t.trim('`')
        if (!t.contains(' ') && looksLikePath(t) && (t.contains('/') || hasExtension(t) || t.lowercase() in NO_EXT_FILES)) {
            return cleanPath(t)
        }
        return null
    }

    private fun hasExtension(p: String): Boolean {
        val name = p.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return dot >= 0 && dot < name.length - 1 && name.substring(dot + 1).all { it.isLetterOrDigit() } &&
            name.substring(dot + 1).length <= 10 && !name.substring(dot + 1).all { it.isDigit() }
    }

    fun looksLikePath(p: String): Boolean {
        if (p.isEmpty() || p.length > 180 || p.contains("://") || p.contains("..")) return false
        if (!PATH.matches(p)) return false
        val name = p.substringAfterLast('/')
        return hasExtension(name) || name.lowercase() in NO_EXT_FILES || name.startsWith(".") && name.length > 1
    }

    private fun cleanPath(p: String): String? {
        var t = p.trim().trim('`', '"', '\'', '*', ':', ',')
        while (t.startsWith("./")) t = t.substring(2)
        t = t.trimStart('/')
        return if (looksLikePath(t)) t else null
    }

    /** Every code block of [src] that names its file, in order; later blocks for the same path win. */
    fun files(src: String): List<Pair<String, String>> {
        val map = LinkedHashMap<String, String>()
        for (b in blocks(src)) {
            if (b is Blk.Code && b.file != null && b.closed) map[b.file] = b.code.trimEnd() + "\n"
        }
        return map.toList()
    }

    /** Plain text for reading aloud: no code, no links, no markdown symbols. */
    fun speakable(src: String): String {
        val sb = StringBuilder()
        for (b in blocks(src)) {
            when (b) {
                is Blk.Code -> sb.append("\n")
                is Blk.Table -> for (r in b.rows) sb.append(r.joinToString("، ") { inlinePlain(it) }).append(".\n")
                is Blk.Text -> for (line in b.md.split('\n')) {
                    var l = line.trim()
                    if (Regex("""^(-{3,}|\*{3,}|_{3,})$""").matches(l)) continue
                    l = l.trimStart('#', '>', ' ')
                    l = Regex("""^([-*+]|\d+[.)])\s+""").replace(l, "")
                    sb.append(inlinePlain(l)).append('\n')
                }
            }
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun inlinePlain(s: String): String {
        var t = s
        t = Regex("""!\[([^\]]*)]\([^)]*\)""").replace(t, "$1")
        t = Regex("""\[([^\]]+)]\([^)]*\)""").replace(t, "$1")
        t = Regex("""https?://\S+""").replace(t, "")
        t = t.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
        t = Regex("""(?<![\w*])\*(?!\s)([^*]+?)\*(?![\w*])""").replace(t, "$1")
        return t
    }

    /** Text for the clipboard: markdown kept, but without the noise of the fences' info strings. */
    fun firstLineTitle(src: String, max: Int = 48): String {
        val first = src.lineSequence().map { inlinePlain(it.trim().trimStart('#', '>', '-', '*', ' ')) }
            .firstOrNull { it.isNotBlank() } ?: return ""
        return if (first.length <= max) first else first.take(max - 1).trimEnd() + "…"
    }
}
