package com.vadila.ai

/**
 * Small JSON library (no Android dependency, so it can be unit-tested on a PC).
 * Objects become LinkedHashMap<String, Any?>, arrays ArrayList<Any?>, numbers Long or Double.
 */
object J {
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        if (p.i < s.length) throw IllegalArgumentException("JSON: trailing data at ${p.i}")
        return v
    }

    /** Parses and returns an object, or an empty map when the text is not a JSON object. */
    @Suppress("UNCHECKED_CAST")
    fun obj(s: String): Map<String, Any?> = try {
        parse(s) as? Map<String, Any?> ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun write(v: Any?): String = StringBuilder().also { w(it, v) }.toString()

    fun o(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = linkedMapOf(*pairs)
    fun a(vararg items: Any?): ArrayList<Any?> = arrayListOf(*items)

    private fun w(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> num(sb, v)
            is Float -> num(sb, v.toDouble())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString())
                    sb.append(':')
                    w(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    w(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> w(sb, v.asList())
            else -> str(sb, v.toString())
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.floor(d) && Math.abs(d) < 1e15 -> sb.append(d.toLong())
            else -> sb.append(d.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    val h = Integer.toHexString(c.code)
                    for (k in h.length until 4) sb.append('0')
                    sb.append(h)
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("JSON: unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') number()
                else throw IllegalArgumentException("JSON: unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("JSON: bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("JSON: key expected at $i")
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("JSON: ':' expected at $i")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("JSON: unexpected end in object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalArgumentException("JSON: ',' or '}' expected at $i")
            }
        }

        private fun arr(): ArrayList<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("JSON: unexpected end in array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalArgumentException("JSON: ',' or ']' expected at $i")
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            var start = i
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("JSON: unterminated string")
                val c = s[i]
                if (c == '"') {
                    sb.append(s, start, i)
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    sb.append(s, start, i)
                    i++
                    if (i >= s.length) throw IllegalArgumentException("JSON: bad escape")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) throw IllegalArgumentException("JSON: bad \\u escape")
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("JSON: bad escape '$e'")
                    }
                    i++
                    start = i
                    continue
                }
                i++
            }
        }

        private fun number(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && s[i] == '.') {
                frac = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                frac = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(st, i)
            if (!frac) {
                t.toLongOrNull()?.let { return it }
            }
            return t.toDouble()
        }
    }
}

// ---- convenient, null-safe access ------------------------------------------------------------

/** Walks a parsed JSON tree: keys are Strings, list positions Ints. Returns null when anything is missing. */
fun Any?.at(vararg path: Any): Any? {
    var cur: Any? = this
    for (p in path) {
        cur = when {
            p is String && cur is Map<*, *> -> cur[p]
            p is Int && cur is List<*> -> cur.getOrNull(p)
            else -> return null
        }
    }
    return cur
}

fun Any?.str(vararg path: Any): String? = when (val v = this.at(*path)) {
    null -> null
    is String -> v
    is Long, is Int -> v.toString()
    is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    else -> v.toString()
}

fun Any?.num(vararg path: Any): Double? = when (val v = this.at(*path)) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

fun Any?.bool(vararg path: Any): Boolean? = this.at(*path) as? Boolean

@Suppress("UNCHECKED_CAST")
fun Any?.list(vararg path: Any): List<Any?> = this.at(*path) as? List<Any?> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Any?.map(vararg path: Any): Map<String, Any?> = this.at(*path) as? Map<String, Any?> ?: emptyMap()
