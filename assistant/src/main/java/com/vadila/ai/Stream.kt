package com.vadila.ai

/** One web source cited by an answer. */
data class Src(val title: String, val url: String)

/** Splits a text/event-stream into events. Feed it raw lines; a blank line completes an event. */
class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Returns (eventName, data) when [line] completes an event. */
    fun line(l: String): Pair<String, String>? {
        if (l.isEmpty()) {
            if (!hasData && event.isEmpty()) return null
            val r = event to data.toString()
            event = ""
            data.setLength(0)
            hasData = false
            return r
        }
        if (l.startsWith(":")) return null // comment / keep-alive
        val c = l.indexOf(':')
        val field = if (c < 0) l else l.substring(0, c)
        var v = if (c < 0) "" else l.substring(c + 1)
        if (v.startsWith(" ")) v = v.substring(1)
        when (field) {
            "event" -> event = v
            "data" -> {
                if (hasData) data.append('\n')
                data.append(v)
                hasData = true
            }
        }
        return null
    }

    /** Flushes a final event that was not followed by a blank line. */
    fun finish(): Pair<String, String>? = if (hasData) line("") else null
}

/** What one streamed event contributed to the answer. */
class Delta {
    var text = ""
    var sources: List<Src> = emptyList()
    /** Generated pictures: (mimeType, base64). */
    var images: List<Pair<String, String>> = emptyList()
    var status: String? = null
    var error: String? = null
    var errorKind: Kind? = null
    var done = false
    var model: String? = null
    var finish: String? = null
}

/** Decoders for the streamed formats of the three API families (pure, unit-testable). */
object Decode {

    // ---------------- Google Gemini (generateContent / streamGenerateContent?alt=sse) -----------
    fun gemini(data: String): Delta {
        val d = Delta()
        val j = try { J.parse(data) } catch (e: Exception) { return d }
        j.at("error")?.let {
            d.error = it.str("message") ?: data
            return d
        }
        j.str("modelVersion")?.let { d.model = it }
        val block = j.str("promptFeedback", "blockReason")
        if (block != null) {
            d.error = "رفض النموذج الطلب (السبب: $block)"
            d.errorKind = Kind.BLOCKED
            return d
        }
        val cand = j.at("candidates", 0)
        val sb = StringBuilder()
        val imgs = ArrayList<Pair<String, String>>()
        for (part in cand.list("content", "parts")) {
            if (part.bool("thought") == true) {
                d.status = "يفكّر…"
                continue
            }
            part.str("text")?.let { sb.append(it) }
            val inline = part.at("inlineData") ?: part.at("inline_data")
            if (inline != null) {
                val mime = inline.str("mimeType") ?: inline.str("mime_type") ?: "image/png"
                val b64 = inline.str("data")
                if (b64 != null && mime.startsWith("image")) imgs.add(mime to b64)
            }
            val code = part.at("executableCode") ?: part.at("executable_code")
            if (code != null) {
                val lang = (code.str("language") ?: "python").lowercase().let { if (it.contains("python")) "python" else it }
                sb.append("\n```").append(lang).append('\n').append(code.str("code") ?: "").append("\n```\n")
            }
            val res = part.at("codeExecutionResult") ?: part.at("code_execution_result")
            if (res != null) {
                val out = res.str("output") ?: ""
                if (out.isNotBlank()) sb.append("\n```text\n").append(out.trimEnd()).append("\n```\n")
            }
        }
        d.text = sb.toString()
        d.images = imgs
        val srcs = ArrayList<Src>()
        for (ch in cand.list("groundingMetadata", "groundingChunks")) {
            val url = ch.str("web", "uri") ?: continue
            srcs.add(Src(ch.str("web", "title") ?: url, url))
        }
        d.sources = srcs
        cand.str("finishReason")?.let { fr ->
            d.finish = fr
            if (fr in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")) {
                if (sb.isEmpty() && imgs.isEmpty()) {
                    d.error = "رفض النموذج الإجابة لأسباب تتعلق بالأمان ($fr)"
                    d.errorKind = Kind.BLOCKED
                }
            }
        }
        return d
    }

    // ---------------- Anthropic Claude (Messages API, stream: true) ----------------------------
    fun claude(event: String, data: String): Delta {
        val d = Delta()
        val j = try { J.parse(data) } catch (e: Exception) { return d }
        val type = j.str("type") ?: event
        when (type) {
            "message_start" -> d.model = j.str("message", "model")
            "content_block_start" -> {
                val b = j.at("content_block")
                when (b.str("type")) {
                    "text" -> d.text = b.str("text") ?: ""
                    "server_tool_use" -> {
                        val q = b.str("input", "query")
                        d.status = if (q.isNullOrBlank()) "يبحث في الويب…" else "يبحث: $q"
                    }
                    "web_search_tool_result" -> {
                        val srcs = ArrayList<Src>()
                        for (r in b.list("content")) {
                            val url = r.str("url") ?: continue
                            srcs.add(Src(r.str("title") ?: url, url))
                        }
                        d.sources = srcs
                    }
                    "thinking", "redacted_thinking" -> d.status = "يفكّر…"
                }
            }
            "content_block_delta" -> {
                val dl = j.at("delta")
                when (dl.str("type")) {
                    "text_delta" -> d.text = dl.str("text") ?: ""
                    "citations_delta" -> {
                        val url = dl.str("citation", "url")
                        if (url != null) d.sources = listOf(Src(dl.str("citation", "title") ?: url, url))
                    }
                    "thinking_delta" -> d.status = "يفكّر…"
                }
            }
            "message_delta" -> d.finish = j.str("delta", "stop_reason")
            "message_stop" -> d.done = true
            "error" -> {
                d.error = j.str("error", "message") ?: data
                val et = j.str("error", "type") ?: ""
                d.errorKind = when {
                    et.contains("overloaded") || et.contains("api_error") -> Kind.SERVER
                    et.contains("rate_limit") -> Kind.RATE
                    et.contains("authentication") || et.contains("permission") -> Kind.AUTH
                    else -> null
                }
            }
        }
        return d
    }

    // ---------------- OpenAI-compatible (chat/completions, stream: true) ------------------------
    fun openai(data: String): Delta {
        val d = Delta()
        if (data.trim() == "[DONE]") {
            d.done = true
            return d
        }
        val j = try { J.parse(data) } catch (e: Exception) { return d }
        j.at("error")?.let { e ->
            d.error = (e as? String) ?: e.str("message") ?: data
            val code = (e.str("code") ?: "") + " " + (e.str("type") ?: "")
            d.errorKind = when {
                code.contains("insufficient_quota") || code.contains("quota") -> Kind.QUOTA
                code.contains("rate_limit") -> Kind.RATE
                else -> null
            }
            return d
        }
        j.str("model")?.let { d.model = it }
        val ch = j.at("choices", 0)
        val delta = ch.at("delta") ?: ch.at("message")
        when (val c = delta.at("content")) {
            is String -> d.text = c
            is List<*> -> d.text = c.joinToString("") { it.str("text") ?: "" }
        }
        if (d.text.isEmpty() && (delta.str("reasoning_content") ?: delta.str("reasoning")).orEmpty().isNotEmpty()) {
            d.status = "يفكّر…"
        }
        val srcs = ArrayList<Src>()
        // OpenRouter / OpenAI url citations
        for (a in delta.list("annotations")) {
            val url = a.str("url_citation", "url") ?: a.str("url") ?: continue
            srcs.add(Src(a.str("url_citation", "title") ?: a.str("title") ?: url, url))
        }
        // Groq Compound: results of the built-in web search
        for (t in delta.list("executed_tools")) {
            for (r in t.list("search_results", "results")) {
                val url = r.str("url") ?: continue
                srcs.add(Src(r.str("title") ?: url, url))
            }
            if (d.status == null && t.str("type")?.contains("search") == true) d.status = "يبحث في الويب…"
        }
        // Perplexity-style top-level citations
        for (u in j.list("citations")) {
            val url = u as? String ?: continue
            srcs.add(Src(url, url))
        }
        d.sources = srcs
        ch.str("finish_reason")?.let { d.finish = it }
        return d
    }
}
