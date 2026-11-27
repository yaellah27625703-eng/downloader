package com.vadila.ai

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Chooses which model answers, and quietly moves on to the next one when a free allowance runs out,
 * so the app keeps answering for as long as any configured service still has quota.
 */
object Router {
    private val cool = ConcurrentHashMap<String, Long>()
    private var loaded = false

    class Result(
        val text: String,
        val sources: List<Src>,
        val images: List<Pair<String, String>>,
        val brain: Brain?,
        val model: String,
        val opinions: List<Opinion> = emptyList(),
    )

    // ---- cool-downs ------------------------------------------------------------------------
    private fun load() {
        if (loaded) return
        loaded = true
        val m = J.obj(Prefs.get("cool", "{}"))
        val now = System.currentTimeMillis()
        for ((k, v) in m) {
            val t = (v as? Number)?.toLong() ?: continue
            if (t > now) cool[k] = t
        }
    }

    private fun save() {
        val now = System.currentTimeMillis()
        Prefs.set("cool", J.write(cool.filterValues { it > now }))
    }

    fun cooling(id: String): Boolean {
        load()
        return (cool[id] ?: 0) > System.currentTimeMillis()
    }

    fun coolUntil(id: String): Long {
        load()
        return cool[id] ?: 0
    }

    fun coolDown(id: String, e: ApiError) {
        load()
        val ms = when (e.kind) {
            Kind.QUOTA -> if (e.waitMs > 0) e.waitMs else 3_600_000L
            Kind.RATE -> (if (e.waitMs > 0) e.waitMs else 30_000L).coerceAtMost(15 * 60_000L)
            Kind.SERVER -> if (e.waitMs > 0) e.waitMs.coerceAtMost(120_000L) else 20_000L
            Kind.GONE -> if (e.waitMs > 0) e.waitMs else 3 * 86_400_000L
            Kind.AUTH -> 30 * 60_000L
            else -> 0L
        }
        if (ms <= 0) return
        cool[id] = System.currentTimeMillis() + ms
        save()
    }

    /** Forget cool-downs of a provider (its key or plan changed). */
    fun reset(providerId: String) {
        load()
        cool.keys.filter { it.startsWith("$providerId:") || it.startsWith("search:$providerId:") }.forEach { cool.remove(it) }
        save()
    }

    // ---- chains ----------------------------------------------------------------------------
    fun brainsOf(pid: String, max: Int): List<Brain> {
        val p = Catalog.byId(pid) ?: return emptyList()
        if (!Prefs.hasKey(pid)) return emptyList()
        if (pid == "custom" && Prefs.get("model_custom").isBlank() && Prefs.models("custom").isEmpty()) return emptyList()
        return Catalog.modelsFor(p, max).map { Brain(p, it) }
    }

    fun familyChain(fam: String): List<Brain> = when (fam) {
        "claude" -> brainsOf("anthropic", 3)
        "gpt" -> brainsOf("openai", 2) + brainsOf("github", 3) + brainsOf("pollinations", 1).filter { it.model.startsWith("openai") }
        "gemini" -> brainsOf("gemini", 7)
        else -> (brainsOf("groq", 2) + brainsOf("cerebras", 2) + brainsOf("mistral", 2) + brainsOf("deepseek", 1) + brainsOf("xai", 1) +
            brainsOf("openrouter", 3) + brainsOf("custom", 1) + brainsOf("pollinations", 2) + brainsOf("free", 1)).distinctBy { it.id }
    }

    /** Models usable without internet: your own server on the home network, then the one inside the phone. */
    fun offlineChain(): List<Brain> = brainsOf("custom", 1) + brainsOf("device", 3)

    fun autoChain(): List<Brain> {
        if (!OnDevice.online()) return offlineChain()
        val g = brainsOf("gemini", 7)
        val gTop = g.filter { !it.model.contains("lite") && !it.model.startsWith("gemma") }
        val gRest = g.filter { it !in gTop }
        val list = ArrayList<Brain>()
        if (Prefs.bool("custom_first")) list += brainsOf("custom", 1)
        if (Prefs.bool("device_first")) list += brainsOf("device", 1)
        list += brainsOf("anthropic", 2)
        list += brainsOf("openai", 2)
        list += gTop.take(1)
        list += brainsOf("github", 1)
        list += gTop.drop(1)
        list += brainsOf("groq", 1)
        list += brainsOf("cerebras", 1)
        list += brainsOf("mistral", 1)
        list += brainsOf("deepseek", 1)
        list += brainsOf("xai", 1)
        list += gRest
        list += brainsOf("openrouter", 3)
        list += brainsOf("github", 3).drop(1)
        list += brainsOf("groq", 3).drop(1)
        list += brainsOf("cerebras", 2).drop(1)
        list += brainsOf("custom", 1)
        list += brainsOf("pollinations", 2)
        list += brainsOf("device", 3)
        list += brainsOf("free", 2)
        return list.distinctBy { it.id }
    }

    /** Fast, cheap models first — for small helper jobs (titles, translating a drawing prompt). */
    fun quickChain(): List<Brain> {
        val g = brainsOf("gemini", 7)
        return (brainsOf("groq", 2) + brainsOf("cerebras", 1) + g.filter { it.model.contains("lite") } + g + brainsOf("mistral", 1) +
            brainsOf("github", 3).reversed() + brainsOf("openrouter", 2) + brainsOf("anthropic", 3).reversed() + brainsOf("openai", 2).reversed() +
            brainsOf("pollinations", 1) + brainsOf("free", 2)).distinctBy { it.id }
    }

    /** brain choice from the chips: auto | claude | gpt | gemini | council | <providerId:model> */
    fun chainFor(choice: String): List<Brain> = when (choice) {
        "auto", "council", "" -> autoChain()
        "device" -> brainsOf("device", 3).ifEmpty { autoChain() }
        "claude", "gpt", "gemini" -> familyChain(choice)
        else -> {
            val pid = choice.substringBefore(':')
            val model = choice.substringAfter(':', "")
            val p = Catalog.byId(pid)
            if (p != null && model.isNotBlank() && Prefs.hasKey(pid)) listOf(Brain(p, model)) + autoChain() else autoChain()
        }
    }

    // ---- what the request needs ----------------------------------------------------------------
    private fun sortForInput(chain: List<Brain>, req: Req): List<Brain> {
        val atts = req.turns.flatMap { it.atts }
        if (atts.isEmpty()) return chain
        fun score(b: Brain): Int {
            var s = 0
            for (a in atts) {
                s += when {
                    a.isImage -> if (b.has(Catalog.VISION)) 0 else 100
                    a.isVideo -> if (b.has(Catalog.VIDEO)) 0 else if (b.has(Catalog.VISION)) 3 else 20
                    a.isAudio -> if (b.has(Catalog.AUDIO)) 0 else 5
                    a.isPdf -> if (b.has(Catalog.PDF)) 0 else if (b.has(Catalog.VISION)) 4 else 50
                    else -> 0
                }
            }
            return s
        }
        val hasImage = atts.any { it.isImage }
        return chain.filter { !hasImage || it.has(Catalog.VISION) }.sortedBy { score(it) }.ifEmpty { chain }
    }

    private fun prepare(b: Brain, req: Req, cancel: Cancel, status: (String) -> Unit) {
        for (t in req.turns) for (a in t.atts) {
            val needs = (a.isAudio && !b.has(Catalog.AUDIO)) || (a.isVideo && !b.has(Catalog.VIDEO))
            if (needs && a.text == null) {
                status("يحوّل الكلام في «${a.name}» إلى نص…")
                try {
                    a.text = Transcriber.transcribe(a, cancel)
                } catch (e: ApiError) {
                    if (e.kind == Kind.CANCELLED || a.isAudio) throw e
                    a.text = ""
                }
            }
        }
    }

    /** Keeps the newest messages that fit the model's input budget. */
    fun trim(req: Req, budget: Int): Req {
        fun size(t: Turn) = t.text.length + t.atts.sumOf { (it.text?.length ?: 0) + if (it.isImage || it.isPdf || it.isVideo) 2500 else 0 }
        val turns = req.turns
        if (turns.isEmpty()) return req
        val last = turns.last()
        var used = req.system.length + size(last)
        val kept = ArrayList<Turn>()
        for (k in turns.size - 2 downTo 0) {
            val s = size(turns[k])
            if (used + s > budget) break
            used += s
            kept.add(0, turns[k])
        }
        var lastTurn = last
        if (used > budget) {
            val docs = last.atts.filter { (it.text?.length ?: 0) > 0 }
            val over = used - budget
            val totalDoc = docs.sumOf { it.text!!.length }
            if (totalDoc > 0) {
                val keepRatio = ((totalDoc - over).coerceAtLeast(totalDoc / 10)).toDouble() / totalDoc
                val atts = last.atts.map { a ->
                    val t = a.text
                    if (t != null && t.isNotEmpty()) {
                        val n = (t.length * keepRatio).toInt().coerceAtLeast(500)
                        Att(a.id, a.name, a.mime, a.path, a.size, if (n < t.length) t.take(n) + "\n…[اقتُطع الباقي لأن الملف أكبر من حدّ هذا النموذج]" else t, a.gUri, a.gExp)
                    } else a
                }
                lastTurn = Turn(last.role, last.text, atts)
            }
        }
        // models must see a user message first
        while (kept.isNotEmpty() && kept.first().role != "user") kept.removeAt(0)
        return Req(kept + lastTurn, req.system, req.search, req.wantImage, req.think)
    }

    // ---- running --------------------------------------------------------------------------------
    /** Tries each brain of [chain] until one answers. */
    fun run(chain0: List<Brain>, req: Req, cancel: Cancel, status: (String) -> Unit, onDelta: (Brain, Delta) -> Unit): Result {
        val chain = sortForInput(chain0, req)
        if (chain.isEmpty()) {
            if (!OnDevice.online()) throw ApiError(Kind.NETWORK, 0, "لا يوجد اتصال بالإنترنت. نزّل نموذج «بلا إنترنت» من الإعدادات ليعمل التطبيق دون اتصال.")
            throw ApiError(Kind.GONE, 0, "لا يوجد نموذج متاح — أضف مفتاحًا في الإعدادات.")
        }
        val coolKey = { b: Brain -> if (req.search) "search:" + b.id else b.id }
        var candidates = chain.filter { !cooling(it.id) && !cooling(coolKey(it)) }
        if (candidates.isEmpty()) candidates = chain.sortedBy { maxOf(coolUntil(it.id), coolUntil(coolKey(it))) }.take(2)
        var lastErr: ApiError? = null
        val failed = ArrayList<String>()
        for ((i, b) in candidates.withIndex()) {
            cancel.check()
            status(if (i == 0) "${b.label} يكتب…" else "${failed.lastOrNull() ?: ""} — يتحوّل إلى ${b.label}…")
            val out = Out { d ->
                d.status?.let { status("${b.label}: $it") }
                onDelta(b, d)
            }
            try {
                prepare(b, req, cancel, status)
                Api.chat(b, trim(req, b.budget), out, cancel)
                if (out.empty) throw ApiError(Kind.SERVER, 0, "رد فارغ")
                return Result(out.text.toString(), out.sources.values.toList(), out.images, b, out.model.ifBlank { b.model })
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                if (!out.empty) {
                    return Result(out.text.toString() + "\n\n_(انقطع الرد: ${Errors.friendly(e)})_", out.sources.values.toList(), out.images, b, b.model)
                }
                lastErr = e
                if (req.search && (e.kind == Kind.BAD || e.kind == Kind.QUOTA || e.kind == Kind.GONE)) {
                    coolDown(coolKey(b), ApiError(Kind.QUOTA, e.http, e.message ?: "", if (e.waitMs > 0) e.waitMs else 86_400_000L))
                } else coolDown(b.id, e)
                failed.add(when (e.kind) {
                    Kind.QUOTA -> "انتهت حصة ${b.label}"
                    Kind.RATE -> "${b.label} مزدحم"
                    Kind.AUTH -> "مفتاح ${b.p.name} غير صالح"
                    else -> "تعذّر ${b.label}"
                })
            }
        }
        throw lastErr ?: ApiError(Kind.GONE, 0, "لا يوجد نموذج متاح")
    }

    /** Answers with web search: native search tools first, then a Wikipedia-based fallback. */
    fun runSearch(chain0: List<Brain>, req: Req, cancel: Cancel, status: (String) -> Unit, onDelta: (Brain, Delta) -> Unit): Result {
        if (!OnDevice.online()) {
            status("لا يوجد إنترنت — يجيب دون بحث")
            return run(offlineChain(), req, cancel, status, onDelta)
        }
        val searchers = ArrayList<Brain>()
        searchers += chain0.filter { it.p.id == "anthropic" }.take(1)
        searchers += chain0.filter { it.p.id == "gemini" }
        if (Prefs.hasKey("groq")) {
            val g = Catalog.byId("groq")!!
            searchers += Brain(g, "groq/compound")
            searchers += Brain(g, "groq/compound-mini")
        }
        val sreq = Req(req.turns, req.system + "\n\nابحث في الويب عن أحدث المعلومات واذكر المصادر.", true, false)
        if (searchers.any { !cooling("search:" + it.id) && !cooling(it.id) }) {
            try {
                return run(searchers.filter { !cooling("search:" + it.id) }, sreq, cancel, status, onDelta)
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
            }
        }
        // free fallback: Wikipedia + the normal chain
        status("يبحث في ويكيبيديا…")
        val q = req.turns.lastOrNull()?.text ?: ""
        val ctx = Web.wikipedia(q, cancel)
        val last = req.turns.last()
        val extra = if (ctx.first.isNotBlank()) "\n\n[نتائج بحث من ويكيبيديا — استعملها إن كانت مفيدة واذكرها كمصدر]\n${ctx.first}" else ""
        val turns = req.turns.dropLast(1) + Turn(last.role, last.text + extra, last.atts)
        val r = run(chain0, Req(turns, req.system, false, false), cancel, status, onDelta)
        return Result(r.text, r.sources + ctx.second, r.images, r.brain, r.model)
    }

    /** Asks Claude, ChatGPT and Gemini (whichever are set up) in parallel, then merges their answers. */
    fun runCouncil(req: Req, cancel: Cancel, status: (String) -> Unit, onDelta: (Brain, Delta) -> Unit): Result {
        if (!OnDevice.online()) return run(offlineChain(), req, cancel, status, onDelta)
        val members = ArrayList<Pair<String, List<Brain>>>()
        for ((fam, name) in listOf("claude" to "Claude", "gpt" to "ChatGPT", "gemini" to "Gemini")) {
            val c = familyChain(fam)
            if (c.isNotEmpty()) members.add(name to c)
        }
        if (members.size < 3) {
            for (pid in listOf("groq", "mistral", "cerebras", "deepseek", "xai", "openrouter", "custom", "pollinations", "free")) {
                if (members.size >= 3) break
                val c = brainsOf(pid, 2)
                if (c.isNotEmpty()) members.add(Catalog.byId(pid)!!.name to c)
            }
        }
        if (members.size < 2) return run(autoChain(), req, cancel, status, onDelta)
        val results = arrayOfNulls<Result>(members.size)
        val errors = arrayOfNulls<String>(members.size)
        val done = CountDownLatch(members.size)
        val names = members.map { it.first }
        val state = Array(members.size) { "…" }
        fun report() = status("المجلس: " + names.indices.joinToString("  ") { "${names[it]} ${state[it]}" })
        report()
        for ((k, m) in members.withIndex()) {
            Thread {
                try {
                    results[k] = run(m.second, req, cancel, {}, { _, _ -> })
                    state[k] = "✓"
                } catch (e: Exception) {
                    errors[k] = if (e is ApiError) Errors.friendly(e) else (e.message ?: "خطأ")
                    state[k] = "✗"
                } finally {
                    synchronized(state) { report() }
                    done.countDown()
                }
            }.start()
        }
        done.await()
        cancel.check()
        val opinions = members.indices.map { k ->
            val r = results[k]
            Opinion(names[k] + (r?.brain?.let { " — ${it.label}" } ?: ""), r?.text ?: ("تعذّر: " + (errors[k] ?: "")), r != null)
        }
        val ok = members.indices.filter { results[it] != null }
        if (ok.isEmpty()) throw ApiError(Kind.GONE, 0, "لم يُجب أي نموذج: " + errors.filterNotNull().joinToString("؛ "))
        if (ok.size == 1) {
            val r = results[ok[0]]!!
            onDelta(r.brain!!, Delta().also { it.text = r.text })
            return Result(r.text, r.sources, r.images, r.brain, r.model, opinions)
        }
        status("يجمع الإجابات في جواب واحد…")
        val sb = StringBuilder()
        for (k in ok) sb.append("\n\n### إجابة ").append(names[k]).append("\n").append(results[k]!!.text)
        val last = req.turns.last()
        val judge = last.text + "\n\n---\nسأل المستخدم السؤال أعلاه، وهذه إجابات عدة نماذج ذكاء اصطناعي عنه:" + sb +
            "\n---\nاكتب الآن الإجابة النهائية الأدق والأكمل للمستخدم مباشرةً: اجمع أفضل ما في الإجابات، وصحّح أي خطأ، " +
            "وإن اختلفت الإجابات في معلومة مهمة فاذكر ذلك باختصار ورجّح الأصح مع السبب. لا تذكر أنك تجمع إجابات إلا عند الاختلاف."
        val jreq = Req(req.turns.dropLast(1) + Turn("user", judge, last.atts), req.system, false, false)
        val judgeChain = familyChain("claude") + autoChain()
        val r = run(judgeChain.distinctBy { it.id }, jreq, cancel, status, onDelta)
        val srcs = LinkedHashMap<String, Src>()
        for (k in ok) for (s in results[k]!!.sources) srcs[s.url] = s
        for (s in r.sources) srcs[s.url] = s
        return Result(r.text, srcs.values.toList(), r.images, r.brain, r.model, opinions)
    }

    /** A short one-off answer from a fast model (no streaming). */
    fun quick(prompt: String, system: String, cancel: Cancel = Cancel()): String =
        run(quickChain(), Req(listOf(Turn("user", prompt, emptyList())), system), cancel, {}, { _, _ -> }).text.trim()
}

/** Reading the web without a search key. */
object Web {
    private val URL = Regex("""https?://[^\s<>"'()\[\]]+""")

    /** Returns (text for the model, sources). */
    fun wikipedia(query: String, cancel: Cancel): Pair<String, List<Src>> {
        val q = query.take(200)
        val arabic = q.any { it in '؀'..'ۿ' }
        val langs = if (arabic) listOf("ar", "en") else listOf("en")
        val sb = StringBuilder()
        val srcs = ArrayList<Src>()
        for (lang in langs) {
            try {
                val url = "https://$lang.wikipedia.org/w/rest.php/v1/search/page?limit=3&q=" + URLEncoder.encode(q, "UTF-8")
                val j = J.parse(Net.text(Net.get(url, mapOf("User-Agent" to "VadilaAI/1.0")), "wiki", cancel))
                for (p in j.list("pages").take(3)) {
                    val key = p.str("key") ?: continue
                    val title = p.str("title") ?: key
                    val sum = try {
                        J.parse(Net.text(Net.get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(key, "UTF-8"),
                            mapOf("User-Agent" to "VadilaAI/1.0")), "wiki", cancel)).str("extract") ?: ""
                    } catch (e: ApiError) {
                        if (e.kind == Kind.CANCELLED) throw e
                        Docs.htmlToText(p.str("excerpt") ?: "")
                    }
                    if (sum.isBlank()) continue
                    val link = "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(key, "UTF-8")
                    sb.append("• ").append(title).append(" (").append(link).append("): ").append(sum).append('\n')
                    srcs.add(Src("ويكيبيديا: $title", link))
                }
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
            }
            if (sb.isNotEmpty()) break
        }
        return sb.toString() to srcs
    }

    /** Contents of the web pages linked in [text] (at most 3), for the model to read. */
    fun readLinks(text: String, skipYouTube: Boolean, cancel: Cancel, status: (String) -> Unit): String {
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', '،', '؛', '!', '?', '؟') }.distinct()
            .filter { !(skipYouTube && GeminiApi.YT.matches(it)) }.take(3).toList()
        if (urls.isEmpty()) return ""
        val sb = StringBuilder()
        for (u in urls) {
            cancel.check()
            status("يقرأ الرابط ${u.take(60)}…")
            val content = try {
                Net.text(Net.get("https://r.jina.ai/$u", mapOf("Accept" to "text/plain", "X-Return-Format" to "markdown")), "jina", cancel)
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                try {
                    val raw = Net.text(Net.get(u, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) VadilaAI/1.0")), "web", cancel)
                    if (raw.trimStart().startsWith("<")) Docs.htmlToText(raw) else raw
                } catch (e2: ApiError) {
                    if (e2.kind == Kind.CANCELLED) throw e2
                    ""
                }
            }
            if (content.isBlank()) continue
            sb.append("\n\n[محتوى الرابط ").append(u).append("]\n").append(content.take(40_000))
        }
        return sb.toString()
    }
}
