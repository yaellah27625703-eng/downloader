package com.vadila.downloader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** whisper.cpp (speech-to-text with timestamps), compiled into the app. */
object Whisper {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (!loaded) { System.loadLibrary("dubwhisper"); loaded = true }
    }

    fun interface Progress { fun onProgress(p: Int) }

    external fun initContext(path: String): Long
    external fun freeContext(ptr: Long)
    external fun setAbort(on: Boolean)
    external fun transcribe(ptr: Long, samples: FloatArray, lang: String, threads: Int, cb: Progress?): Int
    external fun segmentText(ptr: Long, i: Int): ByteArray
    external fun segmentT0(ptr: Long, i: Int): Long
    external fun segmentT1(ptr: Long, i: Int): Long
    external fun detectedLang(ptr: Long): String

    val models = linkedMapOf(
        "base" to Triple("ggml-base-q5_1.bin", 59_707_625L, "سريع (57 م.ب)"),
        "small" to Triple("ggml-small-q5_1.bin", 190_085_487L, "دقيق (181 م.ب)"),
    )

    fun modelFile(ctx: Context, key: String) = File(ctx.getExternalFilesDir("models") ?: ctx.filesDir, models.getValue(key).first)
}

/** One spoken line: start/end (ms), original text, Arabic text. */
class Line(val t0: Long, val t1: Long, val text: String, var ar: String = "")

/** The phone's own Text-to-Speech engine, used for the Arabic voice. */
class ArabicVoice(ctx: Context) {
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }

    fun awaitReady(): Boolean {
        ready.await(20, TimeUnit.SECONDS)
        return ok
    }

    fun arabicVoices(): List<Voice> = runCatching {
        tts.voices.orEmpty().filter { it.locale.language == "ar" }.sortedWith(
            compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name }
        )
    }.getOrDefault(emptyList())

    fun hasArabic(): Boolean =
        tts.isLanguageAvailable(Locale("ar")) >= TextToSpeech.LANG_AVAILABLE

    fun use(voiceName: String) {
        val v = arabicVoices().firstOrNull { it.name == voiceName } ?: arabicVoices().firstOrNull()
        if (v != null) tts.setVoice(v) else tts.setLanguage(Locale("ar"))
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Synthesizes [text] into a WAV file, waiting until it is written. */
    fun toFile(text: String, rate: Float, out: File): Boolean {
        tts.setSpeechRate(rate)
        val done = CountDownLatch(1)
        var success = false
        val id = "u" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) { success = true; done.countDown() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.countDown() }
        })
        out.delete()
        if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) return false
        done.await(60, TimeUnit.SECONDS)
        return success && out.length() > 44
    }

    fun shutdown() = runCatching { tts.stop(); tts.shutdown() }
}

/** The free, on-phone dubbing pipeline. */
object FreeDub {
    private const val SR = 16000            // Whisper input rate
    private const val OUT_SR = 24000         // Arabic voice track rate
    private const val CHUNK_SEC = 600        // transcribe 10 minutes at a time

    fun run(ctx: Context, j: DubJob, dir: File, src: File): File {
        // 1. model (downloaded once)
        val model = Whisper.modelFile(ctx, j.asrModel)
        ensureModel(j, model)

        // 2. transcription (cached so a retry doesn't redo it)
        val linesFile = File(dir, "lines.json")
        var lines: List<Line> = if (linesFile.exists()) readLines(linesFile) else emptyList()
        if (lines.isEmpty()) {
            val pcm = File(dir, "audio16k.pcm")
            if (!pcm.exists() || pcm.length() == 0L) {
                j.stage = DubStage.PREPARING
                j.status = "تجهيز الصوت…"
                j.progress = 0f
                val rc = Ffmpeg.run(ctx, listOf("-y", "-i", src.absolutePath, "-vn", "-ac", "1", "-ar", "$SR", "-f", "s16le", pcm.absolutePath),
                    j.durationMs, onProcess = { j.process = it }) { j.progress = it }
                if (rc != 0 || pcm.length() == 0L) throw IOException("تعذّر قراءة صوت الفيديو")
            }
            lines = transcribe(j, model, pcm)
            if (lines.isEmpty()) throw IOException("لم يُعثر على كلام في هذا الفيديو")
            writeLines(linesFile, lines)
        }

        // 3. translation to Arabic
        if (lines.any { it.ar.isBlank() }) {
            translate(j, lines)
            writeLines(linesFile, lines)
        }
        writeSrt(File(dir, "ar.srt"), lines)

        // 4. Arabic voice track, each line fitted into its time slot
        val voice = File(dir, "voice.wav")
        buildVoiceTrack(ctx, j, lines, voice, dir)
        return voice
    }

    // ------------------------------------------------------------------ model

    private fun ensureModel(j: DubJob, model: File) {
        val (name, approx, _) = Whisper.models.getValue(j.asrModel)
        if (model.exists() && model.length() > 1_000_000) return
        j.stage = DubStage.PREPARING
        j.status = "تنزيل نموذج التعرّف على الكلام (مرة واحدة فقط)…"
        j.progress = 0f
        model.parentFile?.mkdirs()
        val part = File(model.parentFile, "$name.part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = (URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = c.responseCode
                if (code !in 200..299) throw IOException("تعذّر تنزيل النموذج ($code)")
                val append = code == 206
                val expected = if (c.contentLengthLong > 0) c.contentLengthLong + (if (append) have else 0L) else -1L
                val size = if (expected > 0) expected else approx
                java.io.FileOutputStream(part, append).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        var done = if (append) have else 0L
                        while (true) {
                            if (j.cancelled) throw IOException("أُلغيت")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            j.progress = done * 100f / size
                        }
                    }
                }
                if (expected > 0 && part.length() < expected) throw IOException("انقطع تنزيل النموذج")
                part.renameTo(model)
                return
            } catch (e: IOException) {
                if (j.cancelled || ++attempt >= 8) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }

    // ------------------------------------------------------------------ speech to text

    private fun transcribe(j: DubJob, model: File, pcm: File): List<Line> {
        j.stage = DubStage.TRANSCRIBING
        j.status = "تحويل الكلام إلى نص…"
        j.progress = 0f
        Whisper.load()
        Whisper.setAbort(false)
        val ctxPtr = Whisper.initContext(model.absolutePath)
        if (ctxPtr == 0L) {
            model.delete()
            throw IOException("ملف النموذج تالف، أعد المحاولة لتنزيله من جديد")
        }
        val raw = ArrayList<Line>()
        try {
            val totalSamples = pcm.length() / 2
            val chunk = CHUNK_SEC * SR
            val threads = min(6, max(2, Runtime.getRuntime().availableProcessors() / 2 + 1))
            var lang = j.sourceLang.substringBefore('-').ifEmpty { "auto" }
            RandomAccessFile(pcm, "r").use { raf ->
                var start = 0L
                while (start < totalSamples) {
                    if (j.cancelled) { Whisper.setAbort(true); throw IOException("أُلغيت") }
                    val n = min(chunk.toLong(), totalSamples - start).toInt()
                    val bytes = ByteArray(n * 2)
                    raf.seek(start * 2)
                    raf.readFully(bytes)
                    val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = FloatArray(n) { sb.get(it) / 32768f }
                    val base = start * 1000 / SR
                    val doneBefore = start.toFloat() / totalSamples
                    val count = Whisper.transcribe(ctxPtr, samples, lang, threads) { p ->
                        j.progress = (doneBefore + p / 100f * n / totalSamples) * 100f
                    }
                    if (count < 0) {
                        if (j.cancelled) throw IOException("أُلغيت")
                        throw IOException("تعذّر تحويل الكلام إلى نص")
                    }
                    if (lang == "auto") {
                        lang = Whisper.detectedLang(ctxPtr).ifEmpty { "auto" }
                        if (j.sourceLang.isEmpty() && lang != "auto") j.sourceLang = lang
                    }
                    for (i in 0 until count) {
                        val text = String(Whisper.segmentText(ctxPtr, i), Charsets.UTF_8).trim()
                        if (text.isEmpty() || text.startsWith("[") || text.startsWith("(")) continue
                        raw.add(Line(base + Whisper.segmentT0(ctxPtr, i), base + Whisper.segmentT1(ctxPtr, i), text))
                    }
                    start += n
                    j.progress = start * 100f / totalSamples
                }
            }
        } finally {
            Whisper.freeContext(ctxPtr)
        }
        return mergeSentences(raw)
    }

    /** Joins Whisper fragments into whole sentences: better translation and more natural speech. */
    private fun mergeSentences(raw: List<Line>): List<Line> {
        val out = ArrayList<Line>()
        var cur: Line? = null
        for (l in raw) {
            val c = cur
            if (c == null) { cur = l; continue }
            val gap = l.t0 - c.t1
            val ends = c.text.trimEnd().lastOrNull()?.let { it in ".!?؟。！？" } ?: false
            if (!ends && gap < 700 && c.text.length + l.text.length < 220 && l.t1 - c.t0 < 15000) {
                cur = Line(c.t0, l.t1, c.text + " " + l.text)
            } else {
                out.add(c); cur = l
            }
        }
        cur?.let { out.add(it) }
        return out
    }

    // ------------------------------------------------------------------ translation

    private fun translate(j: DubJob, lines: List<Line>) {
        j.stage = DubStage.TRANSLATING
        j.progress = 0f
        val srcTag = j.sourceLang.substringBefore('-').ifEmpty { "en" }
        if (srcTag == "ar") { lines.forEach { if (it.ar.isBlank()) it.ar = it.text }; return }
        val src = TranslateLanguage.fromLanguageTag(srcTag)
            ?: throw IOException("لغة الفيديو ($srcTag) غير مدعومة في الترجمة المجانية")
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ARABIC).build()
        )
        try {
            j.status = "تنزيل حزمة الترجمة (مرة واحدة فقط)…"
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
            j.status = "الترجمة إلى العربية…"
            for ((i, l) in lines.withIndex()) {
                if (j.cancelled) throw IOException("أُلغيت")
                if (l.ar.isBlank()) l.ar = runCatching { Tasks.await(translator.translate(l.text), 60, TimeUnit.SECONDS) }
                    .getOrDefault("").trim()
                j.progress = (i + 1) * 100f / lines.size
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("تعذّرت الترجمة: " + (e.message ?: "تحقق من الإنترنت لتنزيل حزمة الترجمة أول مرة"))
        } finally {
            translator.close()
        }
    }

    // ------------------------------------------------------------------ Arabic voice

    private fun buildVoiceTrack(ctx: Context, j: DubJob, lines: List<Line>, out: File, dir: File) {
        j.stage = DubStage.VOICING
        j.status = "تسجيل الصوت العربي…"
        j.progress = 0f
        val tts = ArabicVoice(ctx)
        try {
            if (!tts.awaitReady()) throw IOException("محرّك الأصوات في الهاتف لا يعمل")
            if (!tts.hasArabic()) throw IOException("لا يوجد صوت عربي في هاتفك. من الإعدادات ← تحويل النص إلى كلام ← ثبّت اللغة العربية، ثم أعد المحاولة")
            tts.use(j.voiceName)

            val totalMs = max(j.durationMs, (lines.lastOrNull()?.t1 ?: 0L) + 2000)
            val totalSamples = totalMs * OUT_SR / 1000
            RandomAccessFile(out, "rw").use { track ->
                track.setLength(0)
                track.write(wavHeader(totalSamples * 2))
                track.setLength(44 + totalSamples * 2)   // silence everywhere
                val clip = File(dir, "clip.wav")
                var prevEnd = 0L
                for ((i, l) in lines.withIndex()) {
                    if (j.cancelled) throw IOException("أُلغيت")
                    j.progress = i * 100f / lines.size
                    if (l.ar.isBlank()) continue
                    val text = l.ar
                    val next = if (i + 1 < lines.size) lines[i + 1].t0 else totalMs
                    val start = max(l.t0, prevEnd + 60)
                    val allowed = max(600L, max(l.t1, next - 120) - start)   // may run into the following pause
                    if (!tts.toFile(text, 1.0f, clip)) continue
                    var pcm = readWav(clip)
                    var dur = pcm.size * 1000L / OUT_SR
                    if (dur > allowed) {
                        // too long for its slot: speak faster (natural up to ~1.7x)
                        val rate = min(1.75f, dur.toFloat() / allowed * 1.05f)
                        if (tts.toFile(text, rate, clip)) { pcm = readWav(clip); dur = pcm.size * 1000L / OUT_SR }
                    }
                    val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (s in pcm) bb.putShort(s)
                    val pos = start * OUT_SR / 1000
                    if (pos + pcm.size <= totalSamples) {
                        track.seek(44 + pos * 2)
                        track.write(bb.array())
                    }
                    prevEnd = start + dur
                }
                clip.delete()
            }
            j.progress = 100f
        } finally {
            tts.shutdown()
        }
    }

    /** Reads a PCM16 WAV and returns mono samples at [OUT_SR]. */
    private fun readWav(f: File): ShortArray {
        val b = f.readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var rate = OUT_SR
        var channels = 1
        var bits = 16
        var pos = 12
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                dataStart = pos + 8
                val avail = b.size - dataStart
                dataLen = if (len <= 0 || len > avail) avail else len
                break
            }
            pos += 8 + len + (len and 1)
        }
        if (dataStart < 0 || bits != 16) return ShortArray(0)
        val frames = dataLen / (2 * channels)
        val mono = ShortArray(frames) { i -> bb.getShort(dataStart + i * 2 * channels) }
        if (rate == OUT_SR || rate <= 0) return mono
        // linear resampling to the track rate
        val outN = (frames.toLong() * OUT_SR / rate).toInt()
        return ShortArray(outN) { i ->
            val x = i.toDouble() * rate / OUT_SR
            val k = x.toInt()
            val f2 = x - k
            val a = mono[min(k, frames - 1)].toInt()
            val c = mono[min(k + 1, frames - 1)].toInt()
            (a + (c - a) * f2).toInt().toShort()
        }
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(1); bb.putInt(OUT_SR); bb.putInt(OUT_SR * 2)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return bb.array()
    }

    // ------------------------------------------------------------------ files

    private fun writeLines(f: File, lines: List<Line>) {
        val arr = JSONArray()
        for (l in lines) arr.put(JSONObject().put("a", l.t0).put("b", l.t1).put("t", l.text).put("ar", l.ar))
        f.writeText(arr.toString())
    }

    private fun readLines(f: File): List<Line> = runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Line(o.getLong("a"), o.getLong("b"), o.getString("t"), o.optString("ar"))
        }
    }.getOrDefault(emptyList())

    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)

    private fun writeSrt(f: File, lines: List<Line>) {
        val sb = StringBuilder()
        var n = 1
        for (l in lines) {
            if (l.ar.isBlank()) continue
            sb.append(n++).append('\n').append(srtTime(l.t0)).append(" --> ").append(srtTime(l.t1)).append('\n')
                .append(l.ar).append("\n\n")
        }
        f.writeText(sb.toString())
    }
}
