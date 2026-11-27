package com.vadila.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder

/** One message of the conversation as sent to a model. */
class Turn(val role: String, val text: String, val atts: List<Att>)

class Req(
    val turns: List<Turn>,
    val system: String,
    val search: Boolean = false,
    val wantImage: Boolean = false,
)

/** A provider + model pair. */
class Brain(val p: ProviderDef, val model: String) {
    val id get() = p.id + ":" + model
    val caps get() = Catalog.caps(p, model)
    val label get() = Catalog.pretty(model)
    fun has(c: Int) = (caps and c) == c

    /** Rough input budget in characters (free plans of some services accept little). */
    val budget: Int
        get() = when (p.id) {
            "gemini" -> 700_000
            "anthropic" -> 500_000
            "openai", "xai" -> 300_000
            "deepseek" -> 150_000
            "mistral" -> 120_000
            "openrouter", "cerebras" -> 60_000
            "pollinations" -> 40_000
            "github" -> 14_000
            "groq" -> if (model.contains("compound")) 12_000 else 18_000
            "custom" -> 24_000
            else -> 12_000
        }
}

/** Accumulates a streamed answer. */
class Out(private val onDelta: (Delta) -> Unit) {
    val text = StringBuilder()
    val sources = LinkedHashMap<String, Src>()
    val images = ArrayList<Pair<String, String>>()
    var model = ""
    val empty get() = text.isEmpty() && images.isEmpty()

    fun add(d: Delta) {
        if (d.error != null) throw ApiError(d.errorKind ?: Kind.SERVER, 0, d.error!!)
        text.append(d.text)
        for (s in d.sources) if (s.url !in sources) sources[s.url] = s
        images.addAll(d.images)
        d.model?.let { model = it }
        onDelta(d)
    }
}

// =================================================================================================
// Google Gemini
// =================================================================================================
object GeminiApi {
    const val BASE = "https://generativelanguage.googleapis.com"
    private const val INLINE_MAX = 14L * 1024 * 1024
    val YT = Regex("""https?://(?:www\.|m\.)?(?:youtube\.com/(?:watch\?v=|shorts/|live/)|youtu\.be/)[\w\-]{6,}[^\s)]*""")

    fun headers() = mapOf("x-goog-api-key" to Prefs.key("gemini"))

    private val SAFETY = listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
        .map { J.o("category" to it, "threshold" to "BLOCK_ONLY_HIGH") }

    fun chat(b: Brain, req: Req, out: Out, cancel: Cancel) {
        val contents = contents(req, cancel)
        val body = J.o("contents" to contents)
        if (req.system.isNotBlank()) body["systemInstruction"] = J.o("parts" to J.a(J.o("text" to req.system)))
        body["safetySettings"] = SAFETY
        if (req.wantImage || b.model.contains("image") || b.model.contains("banana")) {
            body["generationConfig"] = J.o("responseModalities" to J.a("TEXT", "IMAGE"))
        }
        if (req.search) body["tools"] = J.a(J.o("google_search" to J.o()))
        val url = "$BASE/v1beta/models/${b.model}:streamGenerateContent?alt=sse"
        Net.sse(Net.jsonPost(url, headers(), body), "gemini", cancel) { _, data -> out.add(Decode.gemini(data)) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun contents(req: Req, cancel: Cancel): List<Any?> {
        val out = ArrayList<Any?>()
        var inlineBytes = 0L
        for ((idx, t) in req.turns.withIndex()) {
            val parts = ArrayList<Any?>()
            val last = idx == req.turns.size - 1
            for (a in t.atts) {
                cancel.check()
                val media = Media.forGemini(a)
                if (media != null) {
                    val (file, mime) = media
                    if (file.length() + inlineBytes <= INLINE_MAX && !a.isVideo || file.length() < 4L * 1024 * 1024) {
                        inlineBytes += file.length()
                        parts.add(J.o("inlineData" to J.o("mimeType" to mime, "data" to Net.b64(file))))
                    } else {
                        val uri = upload(a, file, mime, cancel)
                        parts.add(J.o("fileData" to J.o("mimeType" to mime, "fileUri" to uri)))
                    }
                } else if (a.text != null) {
                    parts.add(J.o("text" to "📎 ${a.name}:\n${a.text}"))
                } else {
                    parts.add(J.o("text" to "[مرفق لا يمكن قراءته: ${a.name}]"))
                }
            }
            if (last && t.role == "user") {
                YT.find(t.text)?.let { parts.add(J.o("fileData" to J.o("fileUri" to it.value))) }
            }
            if (t.text.isNotBlank() || parts.isEmpty()) parts.add(J.o("text" to t.text.ifBlank { "…" }))
            val role = if (t.role == "assistant") "model" else "user"
            val prev = out.lastOrNull()
            if (prev is MutableMap<*, *> && prev["role"] == role) {
                (prev["parts"] as MutableList<Any?>).addAll(parts)
            } else out.add(J.o("role" to role, "parts" to parts))
        }
        return out
    }

    /** Uploads a big file to Google's File API (kept 48 h) and returns its URI. */
    fun upload(a: Att, file: File, mime: String, cancel: Cancel): String {
        val now = System.currentTimeMillis()
        a.gUri?.let { if (a.gExp > now + 3_600_000) return it }
        val start = Request.Builder().url("$BASE/upload/v1beta/files")
            .header("x-goog-api-key", Prefs.key("gemini"))
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(J.write(J.o("file" to J.o("display_name" to a.name.take(100)))).toRequestBody(Net.JSON))
            .build()
        val uploadUrl = Net.exec(start, "gemini", cancel).use { it.header("x-goog-upload-url") }
            ?: throw ApiError(Kind.SERVER, 0, "لم يُرجع Google رابط الرفع")
        val up = Request.Builder().url(uploadUrl)
            .header("x-goog-api-key", Prefs.key("gemini"))
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mime.toMediaType()))
            .build()
        val info = J.parse(Net.text(up, "gemini", cancel))
        val name = info.str("file", "name") ?: throw ApiError(Kind.SERVER, 0, "فشل رفع الملف")
        var uri = info.str("file", "uri") ?: ""
        var state = info.str("file", "state") ?: "ACTIVE"
        val deadline = now + 15 * 60_000
        while (state == "PROCESSING" && System.currentTimeMillis() < deadline) {
            Thread.sleep(2500)
            cancel.check()
            val s = J.parse(Net.text(Net.get("$BASE/v1beta/$name", headers()), "gemini", cancel))
            state = s.str("state") ?: state
            uri = s.str("uri") ?: uri
        }
        if (state == "FAILED") throw ApiError(Kind.BAD, 0, "تعذّرت معالجة الملف لدى Google")
        a.gUri = uri
        a.gExp = System.currentTimeMillis() + 46 * 3_600_000L
        return uri
    }

    /** Lists models; also records which ones do live calls, speech and pictures. */
    fun listModels(): List<String> {
        val all = ArrayList<Pair<String, List<String>>>()
        var token: String? = null
        do {
            val url = "$BASE/v1beta/models?pageSize=1000" + (token?.let { "&pageToken=$it" } ?: "")
            val j = J.parse(Net.text(Net.get(url, headers()), "gemini"))
            for (m in j.list("models")) {
                val id = (m.str("name") ?: continue).removePrefix("models/")
                all.add(id to m.list("supportedGenerationMethods").mapNotNull { it as? String })
            }
            token = j.str("nextPageToken")
        } while (!token.isNullOrBlank())
        val bad = Regex("image|tts|embedding|live|native-audio|transcribe|robotics|omni|lyria|deep-research|antigravity|aqa|imagen|veo|learnlm|computer-use|banana|translate")
        val chat = all.filter { (id, m) -> "generateContent" in m && (id.startsWith("gemini") || id.startsWith("gemma")) && !bad.containsMatchIn(id) }.map { it.first }
        Prefs.setTagged("gemini", "live", all.filter { (id, m) -> "bidiGenerateContent" in m && !id.contains("translate") && !id.contains("transcribe") }.map { it.first })
        Prefs.setTagged("gemini", "tts", all.filter { it.first.contains("tts") }.map { it.first })
        Prefs.setTagged("gemini", "image", all.filter { (id, m) -> (id.contains("image") || id.contains("banana")) && "generateContent" in m }.map { it.first })
        val pref = Catalog.byId("gemini")!!.defaults
        return chat.sortedWith(compareBy<String> { val i = pref.indexOf(it); if (i < 0) 100 else i }
            .thenBy { if (it.startsWith("gemma")) 1 else 0 }
            .thenByDescending { versionOf(it) })
    }

    fun versionOf(id: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    // ---- speech -----------------------------------------------------------------------------
    val TTS_MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

    /** Natural speech: returns 16-bit mono PCM at 24 kHz. */
    fun speak(text: String, voice: String, cancel: Cancel?): ByteArray {
        val models = (Prefs.tagged("gemini", "tts").sortedBy { val i = TTS_MODELS.indexOf(it); if (i < 0) 50 else i } + TTS_MODELS).distinct()
        var last: Exception? = null
        for (m in models) {
            if (Router.cooling("gemini:$m")) continue
            val body = J.o(
                "contents" to J.a(J.o("parts" to J.a(J.o("text" to text)))),
                "generationConfig" to J.o(
                    "responseModalities" to J.a("AUDIO"),
                    "speechConfig" to J.o("voiceConfig" to J.o("prebuiltVoiceConfig" to J.o("voiceName" to voice))),
                ),
            )
            try {
                val j = J.parse(Net.text(Net.jsonPost("$BASE/v1beta/models/$m:generateContent", headers(), body), "gemini", cancel))
                val data = j.list("candidates", 0, "content", "parts").firstNotNullOfOrNull { it.str("inlineData", "data") }
                    ?: throw ApiError(Kind.BAD, 0, "لا صوت في الرد")
                return Net.unb64(data)
            } catch (e: ApiError) {
                last = e
                if (e.kind == Kind.CANCELLED || e.kind == Kind.AUTH || e.kind == Kind.NETWORK) throw e
                Router.coolDown("gemini:$m", e)
            }
        }
        throw last ?: ApiError(Kind.GONE, 0, "لا يتوفر نموذج صوت")
    }

    // ---- pictures ---------------------------------------------------------------------------
    val IMAGE_MODELS = listOf("gemini-3.1-flash-image", "gemini-nano-banana-2.1", "gemini-3.1-flash-lite-image", "gemini-3-pro-image", "gemini-2.5-flash-image")

    /** Creates or edits a picture. Returns (bytes, mime) and any text the model added. */
    fun image(prompt: String, input: File?, inputMime: String?, aspect: String?, cancel: Cancel?): Triple<ByteArray, String, String> {
        val preferred = Prefs.get("model_gemini_image")
        val models = (listOf(preferred).filter { it.isNotBlank() } +
            Prefs.tagged("gemini", "image").sortedBy { val i = IMAGE_MODELS.indexOf(it); if (i < 0) 50 else i } + IMAGE_MODELS).distinct()
        val parts = ArrayList<Any?>()
        if (input != null) parts.add(J.o("inlineData" to J.o("mimeType" to (inputMime ?: "image/jpeg"), "data" to Net.b64(input))))
        parts.add(J.o("text" to prompt))
        var last: Exception? = null
        for (m in models) {
            if (Router.cooling("gemini:$m")) continue
            val gen = J.o("responseModalities" to J.a("TEXT", "IMAGE"))
            if (aspect != null) gen["imageConfig"] = J.o("aspectRatio" to aspect)
            val body = J.o("contents" to J.a(J.o("role" to "user", "parts" to parts)), "generationConfig" to gen, "safetySettings" to SAFETY)
            try {
                val j = J.parse(Net.text(Net.jsonPost("$BASE/v1beta/models/$m:generateContent", headers(), body), "gemini", cancel))
                val d = Decode.gemini(J.write(j))
                if (d.error != null) throw ApiError(d.errorKind ?: Kind.BAD, 0, d.error!!)
                val img = d.images.firstOrNull() ?: throw ApiError(Kind.BAD, 0, d.text.ifBlank { "لم يُرجع النموذج صورة" })
                return Triple(Net.unb64(img.second), img.first, d.text)
            } catch (e: ApiError) {
                last = e
                if (e.kind == Kind.CANCELLED || e.kind == Kind.AUTH || e.kind == Kind.NETWORK || e.kind == Kind.BLOCKED) throw e
                Router.coolDown("gemini:$m", e)
            }
        }
        throw last ?: ApiError(Kind.GONE, 0, "لا يتوفر نموذج صور")
    }

    /** Speech to text with a cheap model. */
    fun transcribe(file: File, mime: String, cancel: Cancel?): String {
        val chain = Router.familyChain("gemini").sortedBy { if (it.model.contains("lite")) 0 else 1 }
        var last: Exception? = null
        for (b in chain) {
            try {
                val c = cancel ?: Cancel()
                val att = Att("t", file.name, mime, file.path, file.length())
                val out = Out {}
                chat(b, Req(listOf(Turn("user", "اكتب نصّ الكلام في هذا الملف حرفيًا بلغته الأصلية، دون أي تعليق.", listOf(att))), ""), out, c)
                return out.text.toString().trim()
            } catch (e: ApiError) {
                last = e
                if (e.kind == Kind.CANCELLED || e.kind == Kind.AUTH || e.kind == Kind.NETWORK) throw e
                Router.coolDown(b.id, e)
            }
        }
        throw last ?: ApiError(Kind.GONE, 0, "لا يتوفر نموذج")
    }
}

// =================================================================================================
// Anthropic Claude
// =================================================================================================
object ClaudeApi {
    const val URL = "https://api.anthropic.com/v1"
    private val IMG = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    fun headers() = mapOf("x-api-key" to Prefs.key("anthropic"), "anthropic-version" to "2023-06-01")

    @Suppress("UNCHECKED_CAST")
    fun chat(b: Brain, req: Req, out: Out, cancel: Cancel, maxTokens: Int = 32000) {
        val msgs = ArrayList<MutableMap<String, Any?>>()
        for (t in req.turns) {
            val blocks = ArrayList<Any?>()
            val extra = StringBuilder()
            for (a in t.atts) {
                when {
                    a.isImage && a.mime in IMG -> blocks.add(J.o("type" to "image", "source" to J.o("type" to "base64", "media_type" to a.mime, "data" to Net.b64(a.file))))
                    a.isPdf && a.size < 30L * 1024 * 1024 -> blocks.add(J.o("type" to "document", "source" to J.o("type" to "base64", "media_type" to "application/pdf", "data" to Net.b64(a.file)), "title" to a.name.take(200)))
                    a.isVideo -> {
                        for (f in Media.videoFrames(a, 6)) blocks.add(J.o("type" to "image", "source" to J.o("type" to "base64", "media_type" to "image/jpeg", "data" to Net.b64(f))))
                        extra.append("\n\n📎 ${a.name}: (لقطات من الفيديو مرفقة)")
                        a.text?.let { extra.append("\nنص الكلام في الفيديو:\n").append(it) }
                    }
                    a.isAudio && a.text != null -> extra.append("\n\n🎙 ${a.name} — نص الكلام المسجَّل:\n").append(a.text)
                    a.text != null -> extra.append("\n\n📎 ${a.name}:\n").append(a.text)
                    else -> extra.append("\n[مرفق لا يستطيع Claude قراءته: ${a.name}]")
                }
            }
            val text = (t.text + extra).ifBlank { "…" }
            blocks.add(J.o("type" to "text", "text" to text))
            val role = if (t.role == "assistant") "assistant" else "user"
            val prev = msgs.lastOrNull()
            if (prev != null && prev["role"] == role) (prev["content"] as MutableList<Any?>).addAll(blocks)
            else msgs.add(J.o("role" to role, "content" to blocks))
        }
        if (msgs.firstOrNull()?.get("role") == "assistant") msgs.removeAt(0)
        val body = J.o("model" to b.model, "max_tokens" to maxTokens, "messages" to msgs, "stream" to true)
        if (req.system.isNotBlank()) body["system"] = req.system
        if (req.search) body["tools"] = J.a(J.o("type" to "web_search_20250305", "name" to "web_search", "max_uses" to 5))
        try {
            Net.sse(Net.jsonPost("$URL/messages", headers(), body), "anthropic", cancel) { ev, data -> out.add(Decode.claude(ev, data)) }
        } catch (e: ApiError) {
            if (e.kind == Kind.BAD && out.empty && maxTokens > 8192 && (e.message ?: "").contains("max_tokens")) {
                chat(b, req, out, cancel, 8192)
                return
            }
            throw e
        }
    }

    fun listModels(): List<String> {
        val j = J.parse(Net.text(Net.get("$URL/models?limit=100", headers()), "anthropic"))
        val ids = j.list("data").mapNotNull { it.str("id") }
        val pref = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-2", "claude-haiku-5-5")
        return ids.sortedWith(compareBy<String> { val i = pref.indexOf(it); if (i < 0) 100 else i }.thenByDescending { it })
    }
}

// =================================================================================================
// OpenAI and every service that speaks its "chat/completions" format
// =================================================================================================
object OaiApi {
    fun chatUrl(p: ProviderDef) = if (p.id == "free") p.base else Catalog.base(p) + "/chat/completions"

    fun headers(p: ProviderDef): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        val k = Prefs.key(p.id)
        if (k.isNotEmpty()) h["Authorization"] = "Bearer $k"
        if (p.id == "openrouter") {
            h["HTTP-Referer"] = "https://github.com/yaellah27625703-eng/downloader"
            h["X-Title"] = "Vadila AI"
        }
        return h
    }

    private fun imagePart(p: ProviderDef, mime: String, b64: String): Map<String, Any?> {
        val url = "data:$mime;base64,$b64"
        return if (p.id == "mistral") J.o("type" to "image_url", "image_url" to url)
        else J.o("type" to "image_url", "image_url" to J.o("url" to url))
    }

    fun chat(b: Brain, req: Req, out: Out, cancel: Cancel) {
        val p = b.p
        val vision = b.has(Catalog.VISION)
        val msgs = ArrayList<Any?>()
        if (req.system.isNotBlank()) msgs.add(J.o("role" to "system", "content" to req.system))
        for (t in req.turns) {
            if (t.role == "assistant") {
                msgs.add(J.o("role" to "assistant", "content" to t.text.ifBlank { "…" }))
                continue
            }
            val parts = ArrayList<Any?>()
            val sb = StringBuilder(t.text)
            for (a in t.atts) {
                cancel.check()
                when {
                    a.isImage && vision -> parts.add(imagePart(p, a.mime, Net.b64(a.file)))
                    a.isPdf && p.id == "openai" ->
                        parts.add(J.o("type" to "file", "file" to J.o("filename" to a.name, "file_data" to "data:application/pdf;base64," + Net.b64(a.file))))
                    a.isPdf && vision -> {
                        val pages = Media.pdfPages(a, if (p.id == "github" || p.id == "groq") 3 else 8)
                        for (f in pages) parts.add(imagePart(p, "image/jpeg", Net.b64(f)))
                        sb.append("\n\n📎 ${a.name}: (صور أول ${pages.size} صفحات مرفقة)")
                    }
                    a.isVideo -> {
                        if (vision) {
                            val frames = Media.videoFrames(a, if (p.id == "github" || p.id == "groq") 3 else 6)
                            for (f in frames) parts.add(imagePart(p, "image/jpeg", Net.b64(f)))
                            sb.append("\n\n📎 ${a.name}: (لقطات من الفيديو مرفقة)")
                        }
                        a.text?.let { sb.append("\n\n📎 نص الكلام في ${a.name}:\n").append(it) }
                        if (!vision && a.text == null) sb.append("\n[فيديو لا يستطيع هذا النموذج مشاهدته: ${a.name}]")
                    }
                    a.isAudio && a.text != null -> sb.append("\n\n🎙 ${a.name} — نص الكلام المسجَّل:\n").append(a.text)
                    a.text != null -> sb.append("\n\n📎 ${a.name}:\n").append(a.text)
                    a.isImage -> sb.append("\n[صورة لا يستطيع هذا النموذج رؤيتها: ${a.name}]")
                    else -> sb.append("\n[مرفق لا يستطيع هذا النموذج قراءته: ${a.name}]")
                }
            }
            if (parts.isEmpty()) msgs.add(J.o("role" to "user", "content" to sb.toString().ifBlank { "…" }))
            else {
                parts.add(0, J.o("type" to "text", "text" to sb.toString().ifBlank { "…" }))
                msgs.add(J.o("role" to "user", "content" to parts))
            }
        }
        val body = J.o("model" to b.model, "messages" to msgs, "stream" to true)
        try {
            Net.sse(Net.jsonPost(chatUrl(p), headers(p), body), p.id, cancel) { _, data -> out.add(Decode.openai(data)) }
        } catch (e: ApiError) {
            // some accounts may not stream certain models (e.g. unverified OpenAI organisations): ask once without streaming
            if (e.kind == Kind.BAD && out.empty && (e.message ?: "").contains("stream", ignoreCase = true)) {
                body["stream"] = false
                val j = J.parse(Net.text(Net.jsonPost(chatUrl(p), headers(p), body), p.id, cancel))
                out.add(Decode.openai(J.write(j)))
                return
            }
            throw e
        }
    }

    private val SKIP = Regex(
        "whisper|tts|transcri|embed|moderation|dall-e|davinci|babbage|realtime|audio|guard|playai|orpheus|distil|image|search-preview|" +
            "computer-use|codex|instruct|rerank|ocr|safeguard|sora|veo|speech|voxtral",
    )

    fun listModels(p: ProviderDef): List<String> {
        return when (p.id) {
            "github" -> {
                val h = headers(p) + mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
                val arr = J.parse(Net.text(Net.get("https://models.github.ai/catalog/models", h), "github")).list()
                val chat = arr.filter { m ->
                    val outM = m.list("supported_output_modalities").mapNotNull { it as? String }
                    (outM.isEmpty() || "text" in outM) && !SKIP.containsMatchIn((m.str("id") ?: "").lowercase())
                }
                Prefs.setTagged("github", "vision", chat.filter { m -> m.list("supported_input_modalities").contains("image") }.mapNotNull { it.str("id") })
                val ids = chat.mapNotNull { it.str("id") }
                rank(ids, listOf("openai/gpt-5", "openai/gpt-4.1", "openai/gpt-4o", "openai/o4", "openai/o3", "deepseek/", "xai/", "meta/", "mistral-ai/"))
            }
            "openrouter" -> {
                val arr = J.parse(Net.text(Net.get("https://openrouter.ai/api/v1/models"), "openrouter")).list("data")
                val free = arr.filter { m ->
                    val id = m.str("id") ?: ""
                    id.endsWith(":free") || (m.str("pricing", "prompt") == "0" && m.str("pricing", "completion") == "0")
                }
                Prefs.setTagged("openrouter", "vision", free.filter { it.list("architecture", "input_modalities").contains("image") }.mapNotNull { it.str("id") })
                rank(free.mapNotNull { it.str("id") }.filter { !SKIP.containsMatchIn(it.lowercase()) },
                    listOf("deepseek/deepseek-r1", "deepseek/deepseek-chat", "deepseek/", "qwen/qwen3", "moonshotai/", "z-ai/", "meta-llama/llama-4", "openai/gpt-oss", "google/gemma", "mistralai/", "meta-llama/"))
            }
            else -> {
                val url = if (p.id == "free") "https://text.pollinations.ai/models" else Catalog.base(p) + "/models"
                val j = J.parse(Net.text(Net.get(url, headers(p)), p.id))
                val ids = (if (j is List<*>) j.mapNotNull { it.str("id") ?: it.str("name") } else j.list("data").mapNotNull { it.str("id") })
                    .filter { !SKIP.containsMatchIn(it.lowercase()) }
                when (p.id) {
                    "openai" -> {
                        val chat = ids.filter { Regex("""^(gpt-\d|o\d|chatgpt)""").containsMatchIn(it) && !it.contains("-pro") }
                        chat.sortedWith(compareByDescending<String> { versionOf(it) }
                            .thenBy { if (Regex("""-\d{4}-\d{2}-\d{2}$""").containsMatchIn(it)) 1 else 0 }
                            .thenBy { if (it.contains("nano")) 2 else if (it.contains("mini")) 1 else 0 }
                            .thenBy { it.length })
                    }
                    "groq" -> rank(ids, listOf("openai/gpt-oss-120b", "moonshotai/kimi-k2", "qwen/qwen3", "llama-3.3-70b", "meta-llama/llama-4-maverick", "meta-llama/llama-4-scout", "openai/gpt-oss-20b"))
                        .filter { !it.startsWith("groq/") } + ids.filter { it.startsWith("groq/") }
                    "mistral" -> rank(ids.filter { it.endsWith("-latest") || !ids.contains(it.replace(Regex("-\\d{4}$"), "-latest")) },
                        listOf("mistral-large-latest", "mistral-medium-latest", "magistral-medium", "pixtral-large-latest", "codestral-latest", "mistral-small-latest"))
                    "cerebras" -> rank(ids, listOf("gpt-oss-120b", "qwen-3-235b", "zai-glm", "llama-3.3-70b", "llama-4"))
                    else -> ids
                }
            }
        }
    }

    fun versionOf(id: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun rank(ids: List<String>, prefixes: List<String>): List<String> =
        ids.sortedWith(compareBy<String> { id -> prefixes.indexOfFirst { id.startsWith(it) }.let { if (it < 0) 1000 else it } }
            .thenBy { if (it.contains("mini") || it.contains("nano") || it.contains("small") || it.contains("8b")) 1 else 0 })

    // ---- pictures from OpenAI -------------------------------------------------------------------
    fun image(prompt: String, input: File?, size: String, cancel: Cancel?): ByteArray {
        val model = Prefs.get("model_openai_image").ifBlank { "gpt-image-1" }
        val h = headers(Catalog.byId("openai")!!)
        val text = if (input == null) {
            Net.text(Net.jsonPost("https://api.openai.com/v1/images/generations", h, J.o("model" to model, "prompt" to prompt, "size" to size)), "openai", cancel)
        } else {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("size", size)
                .addFormDataPart("image", input.name, input.asRequestBody("image/png".toMediaType()))
                .build()
            val b = Request.Builder().url("https://api.openai.com/v1/images/edits").post(body)
            for ((k, v) in h) b.header(k, v)
            Net.text(b.build(), "openai", cancel)
        }
        val b64 = J.parse(text).str("data", 0, "b64_json") ?: throw ApiError(Kind.BAD, 0, "لم تُرجع OpenAI صورة")
        return Net.unb64(b64)
    }

    /** Free pictures (Flux) without any key. */
    fun freeImage(prompt: String, w: Int, h: Int, cancel: Cancel?): ByteArray {
        val q = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val seed = (Math.random() * 1_000_000).toInt()
        val url = "https://image.pollinations.ai/prompt/$q?width=$w&height=$h&nologo=true&model=flux&seed=$seed"
        val req = Request.Builder().url(url).get().build()
        return Net.exec(req, "free", cancel).use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (bytes.size < 1000) throw ApiError(Kind.SERVER, 0, "لم تصل صورة")
            bytes
        }
    }

    /** Speech to text with Groq's Whisper (free). */
    fun groqTranscribe(file: File, mime: String, cancel: Cancel?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val b = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions").post(body)
            .header("Authorization", "Bearer " + Prefs.key("groq"))
        return J.parse(Net.text(b.build(), "groq", cancel)).str("text")?.trim() ?: ""
    }
}

/** Dispatches to the right API family. */
object Api {
    fun chat(b: Brain, req: Req, out: Out, cancel: Cancel) = when (b.p.api) {
        ProviderDef.GEMINI -> GeminiApi.chat(b, req, out, cancel)
        ProviderDef.CLAUDE -> ClaudeApi.chat(b, req, out, cancel)
        else -> OaiApi.chat(b, req, out, cancel)
    }

    fun listModels(p: ProviderDef): List<String> = when (p.api) {
        ProviderDef.GEMINI -> GeminiApi.listModels()
        ProviderDef.CLAUDE -> ClaudeApi.listModels()
        else -> OaiApi.listModels(p)
    }
}
