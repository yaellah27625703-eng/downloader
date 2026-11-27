package com.vadila.ai

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AI that runs inside the phone: no internet, no key, no account.
 * Open models (Gemma 4 from Google, Apache-2.0) are downloaded once and run with Google's LiteRT-LM.
 */
object OnDevice {
    class Model(
        val id: String,
        val name: String,
        val repo: String,
        val file: String,
        val bytes: Long,
        val ramGb: Int,
        val multimodal: Boolean,
        val note: String,
    ) {
        val url get() = "https://huggingface.co/$repo/resolve/main/$file"
    }

    val MODELS = listOf(
        Model(
            "gemma-4-e2b", "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
            2_583_000_000L, 6, true, "الموصى به: سريع، ويفهم الصور والصوت",
        ),
        Model(
            "gemma-4-e4b", "Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
            3_654_000_000L, 8, true, "أذكى وأدق، لكنه أبطأ ويحتاج ذاكرة أكبر",
        ),
        Model(
            "qwen3-0.6b", "Qwen3 0.6B", "litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm",
            586_000_000L, 3, false, "صغير جدًا للهواتف الضعيفة، للنصوص فقط",
        ),
    )

    lateinit var dir: File
        private set
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }
    }

    fun byId(id: String): Model? = MODELS.firstOrNull { it.id == id }
    fun file(m: Model) = File(dir, m.file)

    /** A finished download (downloads are written to a ".part" file and renamed at the end). */
    fun installed(m: Model): Boolean = ::dir.isInitialized && file(m).let { it.exists() && it.length() > 1_000_000 }

    fun installedModels(): List<Model> = MODELS.filter { installed(it) }

    /** The model the person chose, else the first one downloaded. */
    fun preferred(): Model? {
        val chosen = byId(Prefs.get("model_device"))
        return if (chosen != null && installed(chosen)) chosen else installedModels().firstOrNull()
    }

    fun ramGb(): Double {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    fun freeBytes(): Long = try {
        StatFs(dir.path).availableBytes
    } catch (e: Exception) {
        0
    }

    fun delete(m: Model) {
        if (engineModel == m.id) release()
        file(m).delete()
        File(dir, m.file + ".part").delete()
    }

    /** True when the phone has a working internet connection. */
    fun online(): Boolean {
        if (!::app.isInitialized) return true
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            true
        }
    }

    // ---- the engine (one model kept in memory) ----------------------------------------------------
    private var engine: Engine? = null
    @Volatile
    private var engineModel: String? = null
    private var engineMedia = false
    private val lock = Any()

    private fun open(m: Model, media: Boolean, status: (String) -> Unit): Engine = synchronized(lock) {
        val current = engine
        if (current != null && engineModel == m.id && (!media || engineMedia)) return current
        release()
        status("يحمّل ${m.name} في ذاكرة الهاتف… (أول مرة قد تستغرق دقيقة)")
        val tryGpuFirst = Prefs.get("device_backend", "gpu") != "cpu"
        val attempts = if (tryGpuFirst) listOf(true, false) else listOf(false)
        var last: Throwable? = null
        for (gpu in attempts) {
            try {
                val withMedia = media && m.multimodal
                val e = Engine(
                    EngineConfig(
                        modelPath = file(m).path,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        visionBackend = if (withMedia) (if (gpu) Backend.GPU() else Backend.CPU()) else null,
                        audioBackend = if (withMedia) Backend.CPU() else null,
                        maxNumTokens = 4096,
                        cacheDir = app.cacheDir.path,
                    ),
                )
                e.initialize()
                engine = e
                engineModel = m.id
                engineMedia = withMedia
                Prefs.set("device_backend", if (gpu) "gpu" else "cpu")
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw ApiError(Kind.BAD, 0, "تعذّر تشغيل ${m.name} على هذا الهاتف (" + (last?.message ?: last?.toString() ?: "") + "). جرّب نموذجًا أصغر.")
    }

    fun release() = synchronized(lock) {
        try {
            engine?.close()
        } catch (t: Throwable) {
        }
        engine = null
        engineModel = null
        engineMedia = false
    }

    private fun textOf(msg: Message): String =
        msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** The first [seconds] of an attachment's sound, as 16 kHz WAV (what the model listens to). */
    private fun clip(a: Att, seconds: Int): File? {
        val wav = try {
            Media.wav16k(a)
        } catch (e: Exception) {
            return null
        }
        val max = 44L + seconds * 32000L
        if (wav.length() <= max) return wav
        val out = File(wav.path + ".clip$seconds.wav")
        if (out.exists()) return out
        RandomAccessFile(wav, "r").use { input ->
            RandomAccessFile(out, "rw").use { o ->
                o.setLength(0)
                val buf = ByteArray(1 shl 16)
                var left = max
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r <= 0) break
                    o.write(buf, 0, r)
                    left -= r
                }
                Media.writeWavHeader(o, o.length() - 44, 16000)
            }
        }
        return out
    }

    val SYSTEM = "أنت «Vadila AI»، مساعد ذكي يعمل داخل هاتف المستخدم دون إنترنت. أجب بلغة المستخدم نفسها، بدقة ووضوح، " +
        "ولا تخترع معلومات؛ إن لم تكن متأكدًا فقل ذلك. استعمل Markdown للتنسيق عند الحاجة."

    fun chat(b: Brain, req: Req, out: Out, cancel: Cancel, status: (String) -> Unit) {
        val m = byId(b.model) ?: throw ApiError(Kind.GONE, 0, "النموذج غير معروف")
        if (!installed(m)) throw ApiError(Kind.GONE, 0, "نزّل نموذج «بلا إنترنت» أولًا من الإعدادات.")
        val last = req.turns.last()
        val media = m.multimodal && last.atts.any { it.isImage || it.isPdf || it.isVideo || it.isAudio }
        val eng = open(m, media, status)
        cancel.check()
        val history = req.turns.dropLast(1).map { t ->
            val note = if (t.atts.isNotEmpty()) "\n[مرفقات: " + t.atts.joinToString("، ") { it.name } + "]" else ""
            if (t.role == "assistant") Message.model(t.text) else Message.user(t.text + note)
        }
        val today = req.system.substringAfterLast("التاريخ والوقت الآن", "").let { if (it.isNotBlank()) "\nالتاريخ والوقت الآن$it" else "" }
        val conv = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM + today),
                initialMessages = history,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            ),
        )
        try {
            val parts = ArrayList<Content>()
            val extra = StringBuilder()
            for (a in last.atts) {
                when {
                    a.isImage && m.multimodal -> parts.add(Content.ImageFile(a.path))
                    a.isPdf && m.multimodal -> {
                        val pages = Media.pdfPages(a, 3)
                        for (p in pages) parts.add(Content.ImageFile(p.path))
                        extra.append("\n[صور أول ${pages.size} صفحات من ${a.name}]")
                    }
                    a.isVideo && m.multimodal -> {
                        for (f in Media.videoFrames(a, 4)) parts.add(Content.ImageFile(f.path))
                        clip(a, 30)?.let { parts.add(Content.AudioFile(it.path)) }
                        extra.append("\n[لقطات وصوت من الفيديو ${a.name}]")
                    }
                    a.isAudio && m.multimodal -> {
                        clip(a, 30)?.let { parts.add(Content.AudioFile(it.path)) }
                        extra.append("\n[تسجيل صوتي: ${a.name}]")
                    }
                    a.text != null -> extra.append("\n\n📎 ${a.name}:\n").append(a.text!!.take(8_000))
                    else -> extra.append("\n[مرفق لا يستطيع هذا النموذج قراءته: ${a.name}]")
                }
            }
            parts.add(Content.Text((last.text + extra).ifBlank { "صِف هذا." }))
            status("${m.name} يكتب داخل هاتفك…")
            val done = CountDownLatch(1)
            var error: Throwable? = null
            val acc = StringBuilder()
            conv.sendMessageAsync(Contents.of(parts), object : MessageCallback {
                override fun onMessage(message: Message) {
                    val t = textOf(message)
                    if (t.isEmpty()) return
                    // chunks are normally new text; accept cumulative updates too
                    val delta = if (acc.isNotEmpty() && t.length > acc.length && t.startsWith(acc)) t.substring(acc.length) else t
                    acc.append(delta)
                    try {
                        out.add(Delta().also { it.text = delta })
                    } catch (e: Exception) {
                    }
                }

                override fun onDone() {
                    done.countDown()
                }

                override fun onError(throwable: Throwable) {
                    error = throwable
                    done.countDown()
                }
            })
            while (!done.await(150, TimeUnit.MILLISECONDS)) {
                if (cancel.stopped) {
                    try {
                        conv.cancelProcess()
                    } catch (t: Throwable) {
                    }
                    done.await(3, TimeUnit.SECONDS)
                    throw ApiError(Kind.CANCELLED, 0, "أُوقف")
                }
            }
            error?.let { e ->
                if (out.empty) throw ApiError(Kind.BAD, 0, "خطأ في النموذج: " + (e.message ?: e.toString()))
            }
        } finally {
            try {
                conv.close()
            } catch (t: Throwable) {
            }
        }
    }
}
