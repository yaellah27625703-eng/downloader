package com.vadila.ai

import java.io.File
import java.util.zip.ZipFile

/** Text extraction for office documents and archives (pure JVM, no Android dependency). */
object Docs {
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx",
        "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "m", "mm", "php", "sh", "bash",
        "zsh", "bat", "ps1", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "gradle", "sql", "lua", "dart", "r",
        "pl", "scala", "vue", "svelte", "tex", "srt", "vtt", "log", "env", "gitignore", "dockerfile", "makefile", "cmake",
        "proto", "graphql", "rst", "adoc", "ipynb", "plist", "smali", "asm", "s", "v", "sv", "vhd", "f90", "jl", "ex", "exs",
        "erl", "hs", "clj", "groovy", "nim", "zig", "pro", "qml",
    )

    fun isTextName(name: String): Boolean {
        val n = name.lowercase()
        val ext = n.substringAfterLast('.', n)
        return ext in TEXT_EXT || n.endsWith("makefile") || n.endsWith("dockerfile") || n.endsWith("license")
    }

    /** Returns extracted text, or null when the file type is not a document we can read. */
    fun extract(file: File, name: String, maxChars: Int = 400_000): String? {
        val n = name.lowercase()
        return try {
            when {
                n.endsWith(".docx") || n.endsWith(".dotx") -> docx(file)
                n.endsWith(".xlsx") || n.endsWith(".xlsm") -> xlsx(file, maxChars)
                n.endsWith(".pptx") -> pptx(file)
                n.endsWith(".odt") || n.endsWith(".ods") || n.endsWith(".odp") -> odf(file)
                n.endsWith(".epub") -> epub(file, maxChars)
                n.endsWith(".zip") -> zipListing(file, maxChars)
                n.endsWith(".rtf") -> rtf(file.readText(Charsets.ISO_8859_1))
                n.endsWith(".html") || n.endsWith(".htm") -> htmlToText(readTextSmart(file, maxChars * 2))
                isTextName(n) -> readTextSmart(file, maxChars)
                else -> null
            }?.let { if (it.length > maxChars) it.take(maxChars) + "\n…[اقتُطع الباقي]" else it }
        } catch (e: Exception) {
            null
        }
    }

    fun readTextSmart(file: File, maxChars: Int): String {
        val bytes = file.inputStream().use { s ->
            val cap = minOf(file.length(), (maxChars * 4L).coerceAtMost(16L * 1024 * 1024)).toInt()
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val r = s.read(buf, off, cap - off)
                if (r <= 0) break
                off += r
            }
            buf.copyOf(off)
        }
        // UTF-8 BOM / UTF-16 BOMs
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        val s = String(bytes, Charsets.UTF_8)
        return if (s.startsWith("\uFEFF")) s.substring(1) else s
    }

    fun unescapeXml(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i && semi - i <= 10) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) {
                        sb.append(rep)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun entry(z: ZipFile, name: String): String? {
        val e = z.getEntry(name) ?: return null
        return z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun docxXml(xml: String): String {
        val sb = StringBuilder()
        val para = Regex("""<w:p[ >].*?</w:p>|<w:p/>""", RegexOption.DOT_MATCHES_ALL)
        val token = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab/>|<w:br/>|<w:cr/>""")
        // table cells end with </w:tc>; keep paragraph structure, separate cells with " | "
        val body = xml.replace(Regex("""</w:tc>"""), "<w:p><w:t> | </w:t></w:p>")
        for (p in para.findAll(body)) {
            val line = StringBuilder()
            for (t in token.findAll(p.value)) {
                when {
                    t.value.startsWith("<w:tab") -> line.append('\t')
                    t.value.startsWith("<w:br") || t.value.startsWith("<w:cr") -> line.append('\n')
                    else -> line.append(unescapeXml(t.groupValues[1]))
                }
            }
            val l = line.toString()
            if (l == " | ") {
                if (sb.endsWith("\n")) sb.setLength(sb.length - 1)
                sb.append(" | ")
            } else sb.append(l).append('\n')
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun docx(f: File): String? = ZipFile(f).use { z ->
        val main = entry(z, "word/document.xml") ?: return null
        val parts = ArrayList<String>()
        parts.add(docxXml(main))
        for (e in z.entries().toList().map { it.name }.filter { it.matches(Regex("word/(footnotes|endnotes)\\.xml")) }) {
            entry(z, e)?.let { x -> docxXml(x).takeIf { it.isNotBlank() }?.let { parts.add(it) } }
        }
        parts.joinToString("\n\n")
    }

    fun sheetToText(xml: String, shared: List<String>): String {
        val sb = StringBuilder()
        val row = Regex("""<row[ >].*?</row>""", RegexOption.DOT_MATCHES_ALL)
        val cell = Regex("""<c\s([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
        val v = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
        val inl = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
        for (r in row.findAll(xml)) {
            val vals = ArrayList<String>()
            for (c in cell.findAll(r.value)) {
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                val type = Regex("""\bt="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                val raw = v.find(inner)?.groupValues?.get(1)
                val text = when (type) {
                    "s" -> raw?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> inl.findAll(inner).joinToString("") { unescapeXml(it.groupValues[1]) }
                    else -> unescapeXml(raw ?: "")
                }
                vals.add(text)
            }
            while (vals.isNotEmpty() && vals.last().isEmpty()) vals.removeAt(vals.size - 1)
            if (vals.isNotEmpty()) sb.append(vals.joinToString("\t")).append('\n')
        }
        return sb.toString()
    }

    private fun xlsx(f: File, maxChars: Int): String? = ZipFile(f).use { z ->
        val shared = ArrayList<String>()
        entry(z, "xl/sharedStrings.xml")?.let { x ->
            val si = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
            val t = Regex("""<t(?:\s[^>]*)?>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
            for (m in si.findAll(x)) shared.add(t.findAll(m.groupValues[1]).joinToString("") { unescapeXml(it.groupValues[1]) })
        }
        val names = ArrayList<String>()
        entry(z, "xl/workbook.xml")?.let { wb ->
            Regex("""<sheet\s[^>]*name="([^"]*)"""").findAll(wb).forEach { names.add(unescapeXml(it.groupValues[1])) }
        }
        val sheets = z.entries().toList().map { it.name }
            .filter { it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return null
        val sb = StringBuilder()
        for ((k, s) in sheets.withIndex()) {
            val xml = entry(z, s) ?: continue
            sb.append("## ").append(names.getOrNull(k) ?: "Sheet${k + 1}").append('\n')
            sb.append(sheetToText(xml, shared)).append('\n')
            if (sb.length > maxChars) break
        }
        sb.toString()
    }

    private fun pptx(f: File): String? = ZipFile(f).use { z ->
        val slides = z.entries().toList().map { it.name }
            .filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return null
        val t = Regex("""<a:t>(.*?)</a:t>|</a:p>""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        for ((k, s) in slides.withIndex()) {
            val xml = entry(z, s) ?: continue
            sb.append("## شريحة ").append(k + 1).append('\n')
            for (m in t.findAll(xml)) {
                if (m.value == "</a:p>") sb.append('\n') else sb.append(unescapeXml(m.groupValues[1]))
            }
            sb.append('\n')
        }
        sb.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun odf(f: File): String? = ZipFile(f).use { z ->
        val xml = entry(z, "content.xml") ?: return null
        val withBreaks = xml.replace(Regex("""</text:p>|</text:h>|<text:line-break/>|</table:table-row>"""), "\n")
            .replace(Regex("""</table:table-cell>"""), "\t")
            .replace(Regex("""<text:tab/>"""), "\t")
            .replace(Regex("""<text:s(?:\s[^>]*)?/>"""), " ")
        unescapeXml(withBreaks.replace(Regex("<[^>]+>"), "")).replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun epub(f: File, maxChars: Int): String? = ZipFile(f).use { z ->
        val pages = z.entries().toList().map { it.name }
            .filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
            .sorted()
        if (pages.isEmpty()) return null
        val sb = StringBuilder()
        for (p in pages) {
            sb.append(htmlToText(entry(z, p) ?: continue)).append("\n\n")
            if (sb.length > maxChars) break
        }
        sb.toString()
    }

    fun zipListing(f: File, maxChars: Int): String? = ZipFile(f).use { z ->
        val entries = z.entries().toList().filter { !it.isDirectory }
        val sb = StringBuilder()
        sb.append("محتويات الأرشيف (").append(entries.size).append(" ملف):\n")
        for (e in entries.take(400)) sb.append("- ").append(e.name).append(" (").append(e.size).append(" بايت)\n")
        for (e in entries) {
            if (sb.length > maxChars) break
            if (!isTextName(e.name) || e.size > 200_000) continue
            val text = z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
            sb.append("\n===== ").append(e.name).append(" =====\n").append(text).append('\n')
        }
        sb.toString()
    }

    fun htmlToText(html: String): String {
        var t = html
        t = Regex("""<(script|style|noscript|svg|head)[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(t, " ")
        t = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(t, " ")
        t = Regex("""<(br|/p|/div|/h[1-6]|/li|/tr|/section|/article|/header|/footer|/blockquote|/pre)[^>]*>""", RegexOption.IGNORE_CASE).replace(t, "\n")
        t = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE).replace(t, "\n• ")
        t = Regex("""<(td|th)[^>]*>""", RegexOption.IGNORE_CASE).replace(t, "\t")
        t = Regex("""<[^>]+>""").replace(t, "")
        t = unescapeXml(t)
        t = Regex("""[ \t\u00A0]+""").replace(t, " ")
        t = Regex(""" *\n *""").replace(t, "\n")
        t = Regex("""\n{3,}""").replace(t, "\n\n")
        return t.trim()
    }

    fun rtf(s: String): String {
        var t = s
        t = Regex("""\\par[d]?""").replace(t, "\n")
        t = Regex("""\\'([0-9a-fA-F]{2})""").replace(t) { (it.groupValues[1].toInt(16)).toChar().toString() }
        t = Regex("""\\u(-?\d+)\??""").replace(t) {
            val v = it.groupValues[1].toInt()
            (if (v < 0) v + 65536 else v).toChar().toString()
        }
        t = Regex("""\{\\\*[^{}]*}""").replace(t, "")
        t = Regex("""\\[a-zA-Z]+-?\d* ?""").replace(t, "")
        t = t.replace("{", "").replace("}", "")
        return t.trim()
    }
}
