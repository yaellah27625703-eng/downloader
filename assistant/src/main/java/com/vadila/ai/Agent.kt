package com.vadila.ai

/** Facts the person shared about themself, kept on the phone and given to the models. */
object Memory {
    fun on(): Boolean = Prefs.bool("memory_on", true)

    fun all(): List<String> = J.parse(Prefs.get("memory", "[]").ifBlank { "[]" }).list().mapNotNull { it as? String }

    private fun save(l: List<String>) = Prefs.set("memory", J.write(l))

    private fun key(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}،.؟]+"), "")

    fun add(fact: String) {
        if (!on()) return
        val f = fact.trim().take(280)
        if (f.length < 3) return
        val l = all().toMutableList()
        if (l.any { key(it) == key(f) }) return
        l.add(f)
        while (l.size > 80) l.removeAt(0)
        save(l)
    }

    fun remove(i: Int) {
        val l = all().toMutableList()
        if (i in l.indices) l.removeAt(i)
        save(l)
    }

    fun clear() = save(emptyList())

    fun prompt(): String {
        if (!on()) return ""
        val l = all()
        if (l.isEmpty()) return ""
        return "\n\n## ما تعرفه عن المستخدم (من محادثات سابقة؛ استعمله فقط عندما يفيد)\n- " + l.joinToString("\n- ")
    }
}

/**
 * The thinking layer on top of the models: decides when to look things up, lets any model call tools
 * (web search, reading a page, exact calculation), turns on deep thinking for hard questions, and
 * gives them to the strongest model available.
 */
object Agent {
    private const val MAX_STEPS = 4

    private val TOOLS_HELP = """

## أدواتك (استعملها بنفسك عند الحاجة)
لاستعمال أداة اكتبها وحدها في أول ردك دون أي نص قبلها ثم توقف، وسأعطيك نتيجتها لتكمل الإجابة:
<search>عبارة بحث قصيرة ودقيقة</search> — لأي معلومة حديثة أو متغيرة (أخبار، أسعار، أحداث بعد 2024، من يشغل منصبًا الآن، إصدارات، نتائج، طقس) أو حقيقة دقيقة لست متأكدًا منها.
<read>https://الرابط</read> — لقراءة صفحة ويب معيّنة.
<calc>تعبير رياضي</calc> — لكل حساب غير بسيط (يدعم + - * / ^ % ! وsqrt وlog وln وsin وcos وround وmin وmax).
لا تستعمل أداة إن كنت تعرف الجواب يقينًا، ولا تخترع نتائج الأدوات."""

    private const val CALC_ONLY_HELP = """

## أداة الحساب
لكل حساب غير بسيط اكتب وحده في أول ردك: <calc>تعبير رياضي</calc> ثم توقف، وسأعطيك النتيجة الدقيقة لتكمل."""

    private const val MEMORY_HELP = """

## الذاكرة
إذا ذكر المستخدم معلومة دائمة عن نفسه (اسمه، عمله، مدينته، تفضيلاته، مشاريعه) أو طلب صراحةً أن تتذكر شيئًا، فأضف في آخر ردك سطرًا: <remember>المعلومة باختصار</remember>. لا تشرح هذا الوسم."""

    private const val SUGGEST_HELP = """

## اقتراحات المتابعة
في آخر كل إجابة مفيدة أضف سطرًا أخيرًا: <suggest>سؤال متابعة قصير | سؤال ثانٍ | سؤال ثالث</suggest> بلغة المستخدم، يقترح ما قد يسأله بعد ذلك."""

    private const val THINK_HELP = """

## هذا سؤال يحتاج تفكيرًا عميقًا
فكّر خطوة بخطوة، وتحقق من كل خطوة ومن الحسابات قبل الإجابة النهائية، ثم اكتب الإجابة منظمة وواضحة."""

    class Plan(val task: Smart.Task, val think: Boolean, val preSearch: Boolean)

    fun plan(text: String, forceSearch: Boolean, forceThink: Boolean): Plan {
        val task = Smart.task(text)
        val auto = Prefs.bool("auto_tools", true)
        return Plan(
            task,
            forceThink || (Prefs.bool("smart_think", true) && Smart.hard(text, task)),
            forceSearch || (auto && Smart.needsFresh(text) && text.length < 1500),
        )
    }

    /** The strongest model of each service the person has, for hard questions. */
    private fun strongest(): List<Brain> {
        val out = ArrayList<Brain>()
        if (Prefs.hasKey("anthropic")) {
            val fetched = Prefs.models("anthropic")
            for (m in listOf("claude-opus-5-5", "claude-fable-5-2")) if (fetched.isEmpty() || m in fetched) {
                out += Brain(Catalog.byId("anthropic")!!, m)
                break
            }
        }
        out += Router.brainsOf("openai", 1)
        if (Prefs.hasKey("gemini")) {
            val fetched = Prefs.models("gemini")
            for (m in listOf("gemini-4-argon", "gemini-3.1-pro-preview")) if (m in fetched) out += Brain(Catalog.byId("gemini")!!, m)
        }
        return out
    }

    /** Puts the best service for this kind of task first (their top model only), keeping the rest as fallbacks. */
    fun arrange(chain: List<Brain>, plan: Plan, choice: String): List<Brain> {
        if (choice != "auto" || !OnDevice.online()) return chain
        val firsts = LinkedHashMap<String, Brain>()
        for (b in chain) if (b.p.id !in firsts) firsts[b.p.id] = b
        val ranked = firsts.values.filter { Smart.rank(plan.task, it.p.id) < 99 }.sortedBy { Smart.rank(plan.task, it.p.id) }
        val head = if (plan.task == Smart.Task.GENERAL || plan.task == Smart.Task.CURRENT || plan.task == Smart.Task.TRANSLATE) emptyList() else ranked
        val top = if (plan.think && Prefs.bool("smart_max", true)) strongest() else emptyList()
        return (top + head + chain).distinctBy { it.id }
    }

    /** Runs a request through tools until the model gives its answer. Returns the raw text (hidden tags included). */
    fun run(
        chain0: List<Brain>,
        req: Req,
        plan: Plan,
        cancel: Cancel,
        status: (String) -> Unit,
        onDelta: (Brain, Delta) -> Unit,
    ): Router.Result {
        val online = OnDevice.online()
        val sources = ArrayList<Src>()
        var turns = req.turns
        var chain = chain0
        // 1. obvious questions about current events: look them up before answering
        if (plan.preSearch && online) {
            val q = turns.last().text.take(300)
            try {
                val (digest, srcs) = searchDigest(q, cancel, status)
                if (digest.isNotBlank()) {
                    sources += srcs
                    val last = turns.last()
                    turns = turns.dropLast(1) + Turn(
                        last.role,
                        last.text + "\n\n[نتائج بحث حديثة في الويب عن سؤالي — اعتمد عليها في المعلومات المتغيرة واذكر المصادر]\n" + digest.take(7000),
                        last.atts,
                    )
                }
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
            }
        }
        val tools = Prefs.bool("auto_tools", true)
        val system = req.system + Memory.prompt() +
            (if (tools) (if (online) TOOLS_HELP else CALC_ONLY_HELP) else "") +
            (if (Memory.on()) MEMORY_HELP else "") +
            (if (Prefs.bool("suggestions", true)) SUGGEST_HELP else "") +
            (if (plan.think) THINK_HELP else "")
        for (step in 0 until MAX_STEPS) {
            val stepCancel = cancel.child()
            val gate = Gate(stepCancel, onDelta)
            val r = try {
                Router.run(chain, Req(turns, system, false, req.wantImage, plan.think), stepCancel, status, gate::feed)
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED && !cancel.stopped && gate.tool != null) null else throw e
            }
            val brain = r?.brain ?: gate.brain
            val call = gate.tool ?: r?.let { Smart.toolCall(it.text) }
            if (call == null || step == MAX_STEPS - 1 || !tools) {
                if (r == null) throw ApiError(Kind.SERVER, 0, "انقطع الرد")
                return Router.Result(r.text, (sources + r.sources).distinctBy { it.url }, r.images, r.brain, r.model, r.opinions)
            }
            // 2. the model asked for a tool
            val result = when (call.name) {
                "search" -> if (!online) "لا يوجد اتصال بالإنترنت؛ أجب بما تعرفه وبيّن ذلك." else try {
                    val (digest, srcs) = searchDigest(call.arg, cancel, status)
                    sources += srcs
                    digest.ifBlank { "لم يُعثر على نتائج." }.take(7000)
                } catch (e: ApiError) {
                    if (e.kind == Kind.CANCELLED) throw e
                    "تعذّر البحث: " + Errors.friendly(e)
                }
                "read" -> if (!online) "لا يوجد اتصال بالإنترنت." else {
                    status("يقرأ ${call.arg.take(60)}…")
                    Web.readLinks(call.arg, false, cancel, status).ifBlank { "تعذّرت قراءة الصفحة." }.take(12_000)
                        .also { sources += Src(call.arg, call.arg) }
                }
                else -> {
                    status("يحسب ${call.arg.take(40)}…")
                    try {
                        "النتيجة الدقيقة: " + Calc.eval(call.arg)
                    } catch (e: Exception) {
                        "تعذّر الحساب (${e.message}). صحّح التعبير أو احسبه بنفسك بعناية."
                    }
                }
            }
            turns = turns + Turn("assistant", call.raw, emptyList()) +
                Turn("user", "[نتيجة ${call.name}]\n$result\n\nأكمل الآن الإجابة عن سؤالي الأصلي مباشرةً.", emptyList())
            if (brain != null) chain = (listOf(brain) + chain).distinctBy { it.id }
        }
        throw ApiError(Kind.SERVER, 0, "لم تكتمل الإجابة")
    }

    /** Looks something up on the web and returns a short digest of facts with links. */
    fun searchDigest(q: String, cancel: Cancel, status: (String) -> Unit): Pair<String, List<Src>> {
        status("يبحث في الويب: ${q.take(60)}…")
        val searchers = ArrayList<Brain>()
        if (Prefs.hasKey("groq")) {
            val g = Catalog.byId("groq")!!
            searchers += Brain(g, "groq/compound")
            searchers += Brain(g, "groq/compound-mini")
        }
        searchers += Router.brainsOf("gemini", 7).filter { !it.model.startsWith("gemma") }.take(3)
        searchers += Router.brainsOf("anthropic", 1)
        val ready = searchers.filter { !Router.cooling("search:" + it.id) && !Router.cooling(it.id) }
        if (ready.isNotEmpty()) {
            val prompt = "ابحث في الويب عن أحدث وأدق المعلومات حول: «$q».\n" +
                "اكتب ملخصًا مكثفًا بالحقائق والأرقام والتواريخ فقط، دون مقدمات، واذكر بعد كل معلومة رابط مصدرها."
            try {
                val r = Router.run(ready, Req(listOf(Turn("user", prompt, emptyList())), "أنت باحث دقيق يعتمد على مصادر حديثة موثوقة.", true), cancel.child(), {}, { _, _ -> })
                if (r.text.isNotBlank()) return r.text to r.sources
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED && cancel.stopped) throw e
            }
        }
        status("يبحث في ويكيبيديا…")
        return Web.wikipedia(q, cancel)
    }

    /** Holds back the start of an answer until it is clear whether the model is calling a tool. */
    class Gate(private val stepCancel: Cancel, private val emit: (Brain, Delta) -> Unit) {
        private val buf = StringBuilder()
        private var decided = false
        private var isTool = false
        @Volatile
        var tool: Smart.Tool? = null
            private set
        @Volatile
        var brain: Brain? = null
            private set

        fun feed(b: Brain, d: Delta) {
            brain = b
            if (d.text.isEmpty()) {
                if (!isTool) emit(b, d)
                return
            }
            if (decided) {
                if (isTool) {
                    buf.append(d.text)
                    catchTool()
                } else emit(b, d)
                return
            }
            buf.append(d.text)
            when (Smart.startsWithTool(buf.toString())) {
                null -> return
                true -> {
                    decided = true
                    isTool = true
                    catchTool()
                }
                false -> {
                    decided = true
                    emit(b, Delta().also { it.text = buf.toString(); it.model = d.model; it.sources = d.sources })
                }
            }
        }

        private fun catchTool() {
            if (tool != null) return
            val t = Smart.toolCall(buf.toString()) ?: return
            tool = t
            stepCancel.stop() // the rest of this reply is not needed
        }
    }
}
