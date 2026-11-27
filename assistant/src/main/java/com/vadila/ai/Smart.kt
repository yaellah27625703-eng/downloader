package com.vadila.ai

import kotlin.math.abs

/** Reads a request to decide how to answer it best (pure Kotlin, unit-tested on a computer). */
object Smart {
    enum class Task { GENERAL, CODE, MATH, CREATIVE, CURRENT, TRANSLATE }

    private fun norm(s: String): String = s.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("[ً-ْـ]"), "")

    private val CODE = Regex(
        """```|\b(code|coding|kotlin|java|python|javascript|typescript|html|css|sql|flutter|dart|swift|c\+\+|c#|rust|golang|php|react|android|api|bug|error|exception|function|class|compile|regex|json|gradle|apk|script|github)\b|""" +
            """(كود|برمج|برنامج|تطبيق|داله|دالة|خطا في|الخطا|كلاس|مكتبه|سكربت|موقع ويب|قاعده بيانات|خوارزم)""",
        RegexOption.IGNORE_CASE,
    )
    private val MATH = Regex(
        """(\d\s*[-+*/×÷^%]\s*\d)|\b(calculate|solve|equation|integral|derivative|probability|percent|percentage|proof|prove|math)\b|""" +
            """(احسب|حساب|معادل|مساله|مسأله|تكامل|مشتق|اشتقاق|احتمال|نسبه مئويه|برهن|اثبت|رياضيات|جذر|لوغاريتم|مصفوف|كم يساوي|كم الناتج|فائده|قسط)""",
        RegexOption.IGNORE_CASE,
    )
    private val CREATIVE = Regex(
        """\b(poem|story|novel|lyrics|essay|slogan|joke)\b|(قصيده|شعر|قصه|روايه|خاطره|مقال|نكته|شعار|اغنيه|بيت شعر)""",
        RegexOption.IGNORE_CASE,
    )
    private val TRANSLATE = Regex("""\b(translate|translation)\b|(ترجم|ترجمه)""", RegexOption.IGNORE_CASE)
    private val FRESH = Regex(
        """\b(today|tonight|now|currently|latest|recent|news|this (week|month|year)|price|prices|cost of|weather|forecast|score|who won|election|stock|exchange rate|release date|202[5-9])\b|""" +
            """(اليوم|الان|حاليا|هذه الايام|هذا الاسبوع|هذا الشهر|هذه السنه|هذا العام|مؤخرا|اخبار|خبر عاجل|اخر الاخبار|اخر اصدار|احدث|اخر تحديث|""" +
            """سعر|اسعار|كم ثمن|بكم|الطقس|درجه الحراره|مباراه|نتيجه مباراه|من فاز|انتخابات|البورصه|سعر الصرف|202[5-9]|الرئيس الحالي|من يحكم|من هو رئيس|من هو وزير)""",
        RegexOption.IGNORE_CASE,
    )
    private val DEEP = Regex(
        """\b(why|explain in detail|compare|analy[sz]e|strategy|plan|step by step|pros and cons|optimi[sz]e|architecture|design)\b|""" +
            """(لماذا|اشرح بالتفصيل|بالتفصيل|قارن|حلل|تحليل|استراتيجيه|خطه|خطوه بخطوه|ايجابيات وسلبيات|افضل طريقه|صمم|هندسه)""",
        RegexOption.IGNORE_CASE,
    )

    fun task(text: String): Task {
        val t = norm(text)
        return when {
            TRANSLATE.containsMatchIn(t) && t.length < 600 -> Task.TRANSLATE
            CODE.containsMatchIn(t) -> Task.CODE
            MATH.containsMatchIn(t) -> Task.MATH
            CREATIVE.containsMatchIn(t) -> Task.CREATIVE
            FRESH.containsMatchIn(t) -> Task.CURRENT
            else -> Task.GENERAL
        }
    }

    /** Questions that deserve deep thinking and the strongest model. */
    fun hard(text: String, task: Task): Boolean {
        val t = norm(text)
        return when (task) {
            Task.MATH -> true
            Task.CODE -> t.length > 60
            Task.TRANSLATE, Task.CREATIVE -> false
            else -> DEEP.containsMatchIn(t) || t.length > 700
        }
    }

    /** Questions about things that change (news, prices, people in office): look them up first. */
    fun needsFresh(text: String): Boolean = FRESH.containsMatchIn(norm(text))

    /** Which services suit a kind of task best (lower = first). */
    fun rank(task: Task, providerId: String): Int {
        val order = when (task) {
            Task.CODE -> listOf("anthropic", "openai", "gemini", "github", "xai", "deepseek", "openrouter", "groq", "cerebras", "mistral")
            Task.MATH -> listOf("gemini", "openai", "anthropic", "groq", "cerebras", "deepseek", "github", "xai", "openrouter", "mistral")
            Task.CREATIVE -> listOf("anthropic", "gemini", "openai", "github", "mistral", "xai", "deepseek", "groq", "openrouter", "cerebras")
            else -> return 0
        }
        val i = order.indexOf(providerId)
        return if (i < 0) order.size else i
    }

    // ---- tags the model writes ------------------------------------------------------------------
    class Tool(val name: String, val arg: String, val raw: String)

    private val TOOL = Regex("""^\s*<(search|read|calc)>([\s\S]*?)</\1>""")
    private val TOOL_NAMES = listOf("<search>", "<read>", "<calc>")
    private val REMEMBER = Regex("""<remember>([\s\S]*?)</remember>""")
    private val SUGGEST = Regex("""<suggest>([\s\S]*?)</suggest>""")

    /** A tool call at the very start of an answer, if any. */
    fun toolCall(text: String): Tool? {
        val m = TOOL.find(text) ?: return null
        return Tool(m.groupValues[1], m.groupValues[2].trim(), m.value.trim())
    }

    /** While streaming: is this beginning (still) possibly a tool call? null = cannot tell yet. */
    fun startsWithTool(prefix: String): Boolean? {
        val t = prefix.trimStart()
        if (t.isEmpty()) return null
        for (n in TOOL_NAMES) {
            if (t.startsWith(n)) return true
            if (n.startsWith(t)) return null
        }
        return false
    }

    fun memories(text: String): List<String> = REMEMBER.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && it.length < 300 }.toList()

    fun suggestions(text: String): List<String> {
        val m = SUGGEST.findAll(text).lastOrNull() ?: return emptyList()
        return m.groupValues[1].split('|', '\n').map { it.trim().trimStart('-', '•', '*', ' ').trim() }.filter { it.length in 2..120 }.take(3)
    }

    /** The answer as the person should see it: hidden tags removed (also half-written ones while streaming). */
    fun visible(text: String): String {
        var t = REMEMBER.replace(text, "")
        t = SUGGEST.replace(t, "")
        t = Regex("""<(search|read|calc)>[\s\S]*?</\1>""").replace(t, "")
        // an unfinished tag at the end of a streamed text
        val open = Regex("""<(remember|suggest)>[\s\S]*$""").find(t)
        if (open != null) t = t.substring(0, open.range.first)
        for (n in listOf("<suggest", "<remember", "<sugges", "<sugge", "<sugg", "<sug", "<su", "<remembe", "<rememb", "<remem", "<reme", "<rem", "<re")) {
            if (t.endsWith(n)) {
                t = t.dropLast(n.length)
                break
            }
        }
        return t.trimEnd()
    }
}

/** Exact arithmetic for the assistant (so it never guesses a calculation). */
object Calc {
    fun eval(input: String): String {
        val e = input.trim()
            .map { c -> if (c in '٠'..'٩') ('0' + (c - '٠')) else if (c in '۰'..'۹') ('0' + (c - '۰')) else c }.joinToString("")
            .replace('×', '*').replace('÷', '/').replace('٫', '.').replace('−', '-').replace("**", "^")
            .replace(Regex("(?<=\\d),(?=\\d{3}\\b)"), "")
        val p = P(e)
        val v = p.expr()
        p.ws()
        if (p.i < e.length) throw IllegalArgumentException("رمز غير متوقع: '${e[p.i]}'")
        return format(v)
    }

    fun format(v: Double): String = when {
        v.isNaN() -> "غير معرّف"
        v.isInfinite() -> if (v > 0) "∞" else "-∞"
        abs(v) < 1e15 && v == Math.rint(v) -> v.toLong().toString()
        else -> java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class P(val s: String) {
        var i = 0
        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun expr(): Double {
            var v = term()
            while (true) {
                ws()
                if (i >= s.length) return v
                when (s[i]) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                ws()
                if (i >= s.length) return v
                when {
                    s[i] == '*' -> { i++; v *= unary() }
                    s[i] == '/' -> { i++; v /= unary() }
                    s.startsWith("mod", i) -> { i += 3; v %= unary() }
                    else -> return v
                }
            }
        }

        // -3^2 = -(3^2), as in mathematics
        fun unary(): Double {
            ws()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return power()
        }

        fun power(): Double {
            val b = postfix()
            ws()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(b, unary())
            }
            return b
        }

        fun postfix(): Double {
            var v = primary()
            while (true) {
                ws()
                if (i < s.length && s[i] == '!') {
                    i++
                    if (v < 0 || v != Math.rint(v) || v > 170) throw IllegalArgumentException("المضروب لأعداد صحيحة من 0 إلى 170")
                    var f = 1.0
                    for (k in 2..v.toInt()) f *= k
                    v = f
                } else if (i < s.length && s[i] == '%') {
                    i++
                    v /= 100.0
                } else return v
            }
        }

        fun primary(): Double {
            ws()
            if (i >= s.length) throw IllegalArgumentException("التعبير ناقص")
            val c = s[i]
            if (c == '(') {
                i++
                val v = expr()
                ws()
                if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException("قوس مفتوح")
                return v
            }
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(st, i).toDouble()
            }
            if (c == 'π') { i++; return Math.PI }
            if (c.isLetter()) {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i).lowercase()
                ws()
                if (i < s.length && s[i] == '(') {
                    i++
                    val args = ArrayList<Double>()
                    ws()
                    if (i < s.length && s[i] != ')') {
                        while (true) {
                            args.add(expr())
                            ws()
                            if (i < s.length && s[i] == ',') { i++; continue }
                            break
                        }
                    }
                    ws()
                    if (i < s.length && s[i] == ')') i++ else throw IllegalArgumentException("قوس مفتوح")
                    val a = args.getOrElse(0) { Double.NaN }
                    return when (name) {
                        "sqrt" -> Math.sqrt(a)
                        "cbrt" -> Math.cbrt(a)
                        "abs" -> abs(a)
                        "sin" -> Math.sin(a)
                        "cos" -> Math.cos(a)
                        "tan" -> Math.tan(a)
                        "asin" -> Math.asin(a)
                        "acos" -> Math.acos(a)
                        "atan" -> Math.atan(a)
                        "sind" -> Math.sin(Math.toRadians(a))
                        "cosd" -> Math.cos(Math.toRadians(a))
                        "tand" -> Math.tan(Math.toRadians(a))
                        "ln" -> Math.log(a)
                        "log" -> if (args.size == 2) Math.log(args[1]) / Math.log(a) else Math.log10(a)
                        "log2" -> Math.log(a) / Math.log(2.0)
                        "exp" -> Math.exp(a)
                        "floor" -> Math.floor(a)
                        "ceil" -> Math.ceil(a)
                        "round" -> if (args.size == 2) Math.round(a * Math.pow(10.0, args[1])) / Math.pow(10.0, args[1]) else Math.rint(a)
                        "min" -> args.minOrNull() ?: Double.NaN
                        "max" -> args.maxOrNull() ?: Double.NaN
                        "pow" -> Math.pow(a, args.getOrElse(1) { 1.0 })
                        "sum" -> args.sum()
                        "avg", "mean" -> if (args.isEmpty()) Double.NaN else args.average()
                        else -> throw IllegalArgumentException("دالة غير معروفة: $name")
                    }
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("اسم غير معروف: $name")
                }
            }
            throw IllegalArgumentException("رمز غير متوقع: '$c'")
        }
    }
}
