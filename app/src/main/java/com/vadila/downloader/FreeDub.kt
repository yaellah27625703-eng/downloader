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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    fun modelFile(ctx: Context, key: String) = File(modelsDir(ctx), models.getValue(key).first)

    fun modelsDir(ctx: Context): File = ctx.getExternalFilesDir("models") ?: ctx.filesDir
}

/**
 * One spoken line: start/end (ms), original text, Arabic text, speaker (-1 = not known yet), pitch (Hz),
 * and how a trained classifier hears it: [speech, man, woman, child] probabilities (null = unknown).
 */
class Line(val t0: Long, val t1: Long, val text: String, var ar: String = "", var spk: Int = -1, var f0: Double = 0.0,
           var sex: FloatArray? = null)

/** What the phone pipeline produced: the Arabic voices, (when separation worked) the music/effects bed, the cast. */
class FreeResult(val voice: File, val background: File?, val residual: File?, val characters: Int, val cast: String)

/** The phone's own Text-to-Speech engine, used for the Arabic voices. */
class ArabicVoice(ctx: Context) {
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    // Google's engine has several Arabic voices (men and women, with natural online versions); if it is
    // missing, Android falls back to the phone's default engine.
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext, { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }, "com.google.android.tts")
    private var cached: List<Voice>? = null
    private var current = ""
    private var currentPitch = 1f

    fun awaitReady(): Boolean {
        ready.await(20, TimeUnit.SECONDS)
        return ok
    }

    fun arabicVoices(): List<Voice> = cached ?: runCatching {
        tts.voices.orEmpty().filter { it.locale.language == "ar" }.sortedWith(
            compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name }
        )
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cached = it }

    fun hasArabic(): Boolean =
        tts.isLanguageAvailable(Locale("ar")) >= TextToSpeech.LANG_AVAILABLE

    fun use(voiceName: String) = select(voiceName, 1f)

    /** Switches to [voiceName] at [pitch] (1 = normal). */
    fun select(voiceName: String, pitch: Float) {
        if (voiceName != current || current.isEmpty()) {
            val v = arabicVoices().firstOrNull { it.name == voiceName } ?: arabicVoices().firstOrNull()
            if (v != null) tts.setVoice(v) else tts.setLanguage(Locale("ar"))
            current = v?.name ?: voiceName
        }
        if (pitch != currentPitch) { tts.setPitch(pitch); currentPitch = pitch }
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

/**
 * The free, on-phone dubbing pipeline:
 * 1. separate the voices from the music and effects (Spleeter, on the phone's CPU)
 * 2. speech-to-text on the clean voices (Whisper)
 * 3. who speaks each line (voice fingerprints + pitch)
 * 4. translation to Arabic (ML Kit)
 * 5. a different Arabic voice for every character, as loud as the original line
 * 6. the original laughs/screams/breaths outside the dubbed lines are kept
 */
object FreeDub {
    private const val SR = 16000            // Whisper input rate
    private const val OUT_SR = 24000         // Arabic voice track rate
    private const val CHUNK_SEC = 600        // transcribe 10 minutes at a time
    // the models as small single files (re-hosted by this app's build), the official archives as a fallback
    private const val OWN = "https://github.com/yaellah27625703-eng/downloader/releases/download/models-1"
    private const val REL = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val SPLEETER_URL = "$REL/source-separation-models/sherpa-onnx-spleeter-2stems-fp16.tar.bz2"
    private const val VOICEPRINT_URL = "$REL/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
    private const val TAGGER_URL = "$REL/audio-tagging-models/sherpa-onnx-ced-mini-audio-tagging-2024-04-19.tar.bz2"

    fun run(ctx: Context, j: DubJob, dir: File, src: File): FreeResult {
        // 1. speech-recognition model (downloaded once)
        val model = Whisper.modelFile(ctx, j.asrModel)
        ensureModel(j, model)

        // 2. voices / music + effects
        val separated = separate(ctx, j, dir, src)
        val pcm = if (separated) File(dir, "vocals16.pcm") else File(dir, "audio16k.pcm")
        if (!separated && (!pcm.exists() || pcm.length() == 0L)) {
            j.stage = DubStage.PREPARING
            j.status = "تجهيز الصوت…"
            j.progress = 0f
            val rc = Ffmpeg.run(ctx, listOf("-y", "-i", src.absolutePath, "-vn", "-ac", "1", "-ar", "$SR", "-f", "s16le", pcm.absolutePath),
                j.durationMs, onProcess = { j.process = it }) { j.progress = it }
            if (rc != 0 || pcm.length() == 0L) throw IOException("تعذّر قراءة صوت الفيديو")
        }

        // 3. speech to text (cached so a retry doesn't redo it)
        val linesFile = File(dir, "lines.json")
        var lines: List<Line> = if (linesFile.exists()) readLines(linesFile) else emptyList()
        if (lines.isEmpty()) {
            lines = transcribe(j, model, pcm)
            if (lines.isEmpty()) throw IOException("لم يُعثر على كلام في هذا الفيديو")
            writeLines(linesFile, lines)
        }

        // 4. who speaks each line
        if (lines.any { it.spk < 0 }) {
            lines = speakers(ctx, j, lines, pcm)
            writeLines(linesFile, lines)
        }

        // 5. translation to Arabic
        if (lines.any { it.ar.isBlank() }) {
            translate(j, lines)
            writeLines(linesFile, lines)
        }
        writeSrt(File(dir, "ar.srt"), lines)

        // 6. an Arabic voice for every character, each line fitted into its time slot
        val voice = File(dir, "voice.wav")
        val placed = buildVoiceTrack(ctx, j, lines, voice, dir, pcm, separated)

        // 7. the original non-dialogue sounds (laughs, screams, breaths, songs)
        var residual: File? = null
        if (separated) {
            residual = File(dir, "residual16.pcm")
            val totalMs = pcm.length() / 2 * 1000 / SR
            pcm.inputStream().buffered(1 shl 20).use { inp ->
                residual.outputStream().buffered(1 shl 20).use { out -> Separator.residual(inp, out, placed, totalMs) }
            }
        }
        return FreeResult(voice, if (separated) File(dir, "bg44.pcm") else null, residual,
            lines.map { it.spk }.distinct().size, cast(lines))
    }

    private fun threads() = min(4, max(2, Runtime.getRuntime().availableProcessors() / 2))

    // ------------------------------------------------------------------ models

    private fun ensureModel(j: DubJob, model: File) {
        val (name, approx, _) = Whisper.models.getValue(j.asrModel)
        if (model.exists() && model.length() > 1_000_000) return
        fetch(j, "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name", model,
            "تنزيل نموذج التعرّف على الكلام (مرة واحدة فقط)…", approx)
    }

    /** Spleeter 2-stems (fp16): two small models that split voices from music + effects. */
    private fun ensureSpleeter(ctx: Context, j: DubJob): Pair<File, File> {
        val dir = Whisper.modelsDir(ctx).apply { mkdirs() }
        val voc = File(dir, "spleeter-vocals.fp16.onnx")
        val acc = File(dir, "spleeter-accompaniment.fp16.onnx")
        if (voc.length() > 1_000_000 && acc.length() > 1_000_000) return voc to acc
        val label = "تنزيل نموذج فصل الأصوات عن الموسيقى (مرة واحدة فقط)…"
        if (runCatching { fetch(j, "$OWN/${voc.name}", voc, label, 19_700_000L); fetch(j, "$OWN/${acc.name}", acc, label, 19_700_000L) }.isSuccess
            && voc.length() > 1_000_000 && acc.length() > 1_000_000) return voc to acc
        if (j.cancelled) throw IOException("أُلغيت")
        val tar = File(dir, "spleeter-2stems-fp16.tar.bz2")
        fetch(j, SPLEETER_URL, tar, "تنزيل نموذج فصل الأصوات عن الموسيقى (مرة واحدة فقط)…", 35_271_738L)
        j.status = "تجهيز نموذج فصل الأصوات…"
        TarArchiveInputStream(BZip2CompressorInputStream(tar.inputStream().buffered(1 shl 16))).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                val dst = when (e.name.substringAfterLast('/')) {
                    "vocals.fp16.onnx" -> voc
                    "accompaniment.fp16.onnx" -> acc
                    else -> null
                } ?: continue
                val part = File(dir, dst.name + ".part")
                part.outputStream().use { t.copyTo(it, 1 shl 16) }
                part.renameTo(dst)
            }
        }
        tar.delete()
        if (voc.length() < 1_000_000 || acc.length() < 1_000_000) throw IOException("نموذج فصل الأصوات ناقص")
        return voc to acc
    }

    /** CAM++ voice fingerprints (trained on VoxCeleb). */
    private fun ensureVoiceprint(ctx: Context, j: DubJob): File {
        val f = File(Whisper.modelsDir(ctx).apply { mkdirs() }, "campplus_sv_en_voxceleb_16k.onnx")
        if (f.length() > 1_000_000) return f
        val label = "تنزيل نموذج تمييز الأصوات (مرة واحدة فقط)…"
        if (runCatching { fetch(j, "$OWN/${f.name}", f, label, 29_596_979L) }.isFailure) {
            if (j.cancelled) throw IOException("أُلغيت")
            fetch(j, VOICEPRINT_URL, f, label, 29_596_979L)
        }
        return f
    }

    /** CED (AudioSet): hears whether a line is spoken by a man, a woman or a child. */
    private fun ensureTagger(ctx: Context, j: DubJob): Pair<File, File> {
        val dir = Whisper.modelsDir(ctx).apply { mkdirs() }
        val model = File(dir, "ced-mini.int8.onnx")
        val labels = File(dir, "audioset-labels.csv")
        if (model.length() > 1_000_000 && labels.length() > 1000) return model to labels
        val label = "تنزيل نموذج تمييز الرجال والنساء والأطفال (مرة واحدة فقط)…"
        if (runCatching { fetch(j, "$OWN/${model.name}", model, label, 10_451_715L); fetch(j, "$OWN/${labels.name}", labels, label, 14_675L) }.isSuccess
            && model.length() > 1_000_000 && labels.length() > 1000) return model to labels
        if (j.cancelled) throw IOException("أُلغيت")
        val tar = File(dir, "ced-mini.tar.bz2")
        fetch(j, TAGGER_URL, tar, label, 47_658_399L)
        TarArchiveInputStream(BZip2CompressorInputStream(tar.inputStream().buffered(1 shl 16))).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                val dst = when (e.name.substringAfterLast('/')) {
                    "model.int8.onnx" -> model
                    "class_labels_indices.csv" -> labels
                    else -> null
                } ?: continue
                val part = File(dir, dst.name + ".part")
                part.outputStream().use { t.copyTo(it, 1 shl 16) }
                part.renameTo(dst)
            }
        }
        tar.delete()
        if (model.length() < 1_000_000 || labels.length() < 1000) throw IOException("نموذج تمييز الأصوات ناقص")
        return model to labels
    }

    /** Downloads [url] into [dest], resuming after interruptions. */
    private fun fetch(j: DubJob, url: String, dest: File, label: String, approx: Long) {
        j.stage = DubStage.PREPARING
        j.status = label
        j.progress = 0f
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = c.responseCode
                if (code == 416) { part.renameTo(dest); return }          // already complete
                if (code == 404) throw java.io.FileNotFoundException(url)  // not there: no point retrying
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
                part.renameTo(dest)
                return
            } catch (e: IOException) {
                if (j.cancelled || e is java.io.FileNotFoundException || ++attempt >= 8) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }

    // ------------------------------------------------------------------ voices / music + effects

    /** Writes bg44.pcm (music + effects, 44.1 kHz stereo) and vocals16.pcm (voices, 16 kHz mono). */
    private fun separate(ctx: Context, j: DubJob, dir: File, src: File): Boolean {
        val bg = File(dir, "bg44.pcm")
        val voc = File(dir, "vocals16.pcm")
        if (bg.length() > 0 && voc.length() > 0) return true
        if (!j.hasAudio) return false
        val models = try { ensureSpleeter(ctx, j) } catch (e: IOException) {
            if (j.cancelled) throw e
            return false                      // no model (offline?): dub without separation
        }
        j.stage = DubStage.PREPARING
        j.status = "فصل أصوات الشخصيات عن الموسيقى والمؤثرات…"
        j.progress = 0f
        val bgPart = File(dir, "bg44.part")
        val vocPart = File(dir, "vocals16.part")
        var h = 0L
        try {
            Sherpa.load()
            h = Sherpa.sepCreate(models.first.absolutePath, models.second.absolutePath, "", threads())
            if (h == 0L) return false
            val p = Ffmpeg.pipe(ctx, listOf("-i", src.absolutePath, "-vn", "-ac", "2", "-ar", "44100", "-f", "s16le", "-"),
                File(dir, "separate.log"))
            j.process = p
            val total = max(1L, j.durationMs * 44100 / 1000)
            try {
                bgPart.outputStream().buffered(1 shl 20).use { b ->
                    vocPart.outputStream().buffered(1 shl 20).use { v ->
                        Separator.run(h, p.inputStream.buffered(1 shl 20), b, v,
                            progress = { j.progress = (it * 100f / total).coerceAtMost(99f) },
                            cancelled = { j.cancelled })
                    }
                }
            } finally {
                p.destroy()
            }
            if (bgPart.length() == 0L || vocPart.length() == 0L) return false
            bgPart.renameTo(bg)
            vocPart.renameTo(voc)
            return true
        } catch (e: Throwable) {
            if (j.cancelled) throw IOException("أُلغيت")
            bgPart.delete(); vocPart.delete()
            return false                      // separation failed: dub without it
        } finally {
            if (h != 0L) Sherpa.sepFree(h)
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
                        if (text.isEmpty() || text.startsWith("[") || text.startsWith("(") || text.startsWith("♪")) continue
                        raw.add(Line(base + Whisper.segmentT0(ctxPtr, i), base + Whisper.segmentT1(ctxPtr, i), text))
                    }
                    start += n
                    j.progress = start * 100f / totalSamples
                }
            }
        } finally {
            Whisper.freeContext(ctxPtr)
        }
        return raw
    }

    // ------------------------------------------------------------------ who speaks each line

    private fun speakers(ctx: Context, j: DubJob, raw: List<Line>, pcm: File): List<Line> {
        val model = runCatching { ensureVoiceprint(ctx, j) }.getOrNull()
        if (j.cancelled) throw IOException("أُلغيت")
        val tagger = runCatching { ensureTagger(ctx, j) }.getOrNull()
        if (j.cancelled) throw IOException("أُلغيت")
        j.stage = DubStage.PREPARING
        j.status = "تمييز أصوات الشخصيات…"
        j.progress = 0f
        val n = raw.size
        val embs = arrayOfNulls<FloatArray>(n)
        val pitches = DoubleArray(n)
        val sex = arrayOfNulls<FloatArray>(n)
        var h = 0L
        var tg = 0L
        try {
            if (model != null) runCatching { Sherpa.load(); h = Sherpa.embCreate(model.absolutePath, threads()) }
            if (tagger != null) runCatching {
                Sherpa.load(); tg = Sherpa.tagCreate(tagger.first.absolutePath, tagger.second.absolutePath, threads())
            }
            RandomAccessFile(pcm, "r").use { raf ->
                for ((i, l) in raw.withIndex()) {
                    if (j.cancelled) throw IOException("أُلغيت")
                    val x = readPcm(raf, l.t0, l.t1)
                    pitches[i] = Voices.pitch(if (x.size > SR * 4) x.copyOf(SR * 4) else x, SR)
                    if (h != 0L && x.size >= SR / 2) embs[i] = runCatching { Sherpa.embCompute(h, x, SR) }.getOrNull()
                    if (tg != 0L && x.size >= SR / 2) sex[i] = runCatching { Sherpa.tagSpeech(tg, x, SR) }.getOrNull()
                    j.progress = (i + 1) * 100f / n
                }
            }
        } finally {
            if (h != 0L) Sherpa.embFree(h)
            if (tg != 0L) Sherpa.tagFree(tg)
        }
        val durs = DoubleArray(n) { (raw[it].t1 - raw[it].t0) / 1000.0 }
        val starts = DoubleArray(n) { raw[it].t0 / 1000.0 }
        // no fingerprints (model unavailable): one voice for everyone rather than voices that flip
        val labels = if (embs.any { it != null }) Voices.speakers(embs.toList(), durs, starts, sex.toList()) else IntArray(n)
        for (i in 0 until n) { raw[i].spk = labels[i]; raw[i].f0 = pitches[i]; raw[i].sex = sex[i] }
        return mergeSameSpeaker(raw)
    }

    /** Joins consecutive fragments of the same speaker into whole sentences: better translation, more natural speech. */
    private fun mergeSameSpeaker(raw: List<Line>): List<Line> {
        val out = ArrayList<Line>()
        var cur: Line? = null
        for (l in raw) {
            val c = cur
            if (c == null) { cur = l; continue }
            val gap = l.t0 - c.t1
            val ends = c.text.trimEnd().lastOrNull()?.let { it in ".!?؟。！？" } ?: false
            if (l.spk == c.spk && !ends && gap < 700 && c.text.length + l.text.length < 220 && l.t1 - c.t0 < 15000) {
                val d1 = (c.t1 - c.t0).toDouble().coerceAtLeast(1.0)
                val d2 = (l.t1 - l.t0).toDouble().coerceAtLeast(1.0)
                val f0 = when {
                    c.f0 > 0 && l.f0 > 0 -> (c.f0 * d1 + l.f0 * d2) / (d1 + d2)
                    c.f0 > 0 -> c.f0
                    else -> l.f0
                }
                val a = c.sex; val b = l.sex
                val sex = when {
                    a != null && b != null -> FloatArray(4) { ((a[it] * d1 + b[it] * d2) / (d1 + d2)).toFloat() }
                    else -> a ?: b
                }
                cur = Line(c.t0, l.t1, c.text + " " + l.text, spk = c.spk, f0 = f0, sex = sex)
            } else {
                out.add(c); cur = l
            }
        }
        cur?.let { out.add(it) }
        return out
    }

    /** 16 kHz mono PCM16 samples between two times (ms). */
    private fun readPcm(raf: RandomAccessFile, t0: Long, t1: Long): FloatArray {
        val a = max(0L, t0 * SR / 1000)
        val b = min(raf.length() / 2, t1 * SR / 1000)
        if (b <= a) return FloatArray(0)
        val bytes = ByteArray(((b - a) * 2).toInt())
        raf.seek(a * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(bytes.size / 2) { sb.get(it) / 32768f }
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

    // ------------------------------------------------------------------ Arabic voices

    /**
     * The phone's Arabic voices: one per voice identity, the natural online version when it works now
     * (Google's network voices), otherwise the offline one; each with its pitch and whether it is a man's
     * or a woman's voice (measured once, then remembered).
     */
    private fun profileVoices(ctx: Context, tts: ArabicVoice, clip: File): List<Voices.PhoneVoice> {
        val prefs = ctx.getSharedPreferences("voices", Context.MODE_PRIVATE)
        val out = ArrayList<Voices.PhoneVoice>()
        val byIdentity = tts.arabicVoices().groupBy { it.name.removeSuffix("-network").removeSuffix("-local") }
        for ((_, variants) in byIdentity) {
            if (out.size >= 12) break
            for (v in variants.sortedBy { if (it.isNetworkConnectionRequired) 0 else 1 }) {
                var f0 = prefs.getFloat("f0_" + v.name, -1f).toDouble()
                if (v.isNetworkConnectionRequired || f0 < 0) {
                    tts.select(v.name, 1f)
                    if (!tts.toFile("مرحبًا، كيف حالك اليوم؟ أنا بخير، والحمد لله.", 1f, clip)) continue   // offline: try the next
                    if (f0 < 0) {
                        val pcm = readWav(clip)
                        f0 = Voices.pitch(FloatArray(pcm.size) { pcm[it] / 32768f }, OUT_SR)
                        if (f0 <= 0) continue
                        prefs.edit().putFloat("f0_" + v.name, f0.toFloat()).apply()
                    }
                }
                out.add(Voices.PhoneVoice(v.name, f0))
                break
            }
        }
        return out
    }

    /** "رجال: 3 • نساء: 2 • أطفال: 1" for the characters that were dubbed. */
    private fun cast(lines: List<Line>): String {
        val kinds = lines.filter { it.ar.isNotBlank() }.groupBy { it.spk.coerceAtLeast(0) }.values.map { mine ->
            Voices.kindOfCharacter(mine.map { it.sex }, mine.map { (it.t1 - it.t0) / 1000.0 }, mine.map { it.f0 })
        }
        val parts = ArrayList<String>()
        val men = kinds.count { it == Voices.Kind.MALE }
        val women = kinds.count { it == Voices.Kind.FEMALE }
        val kids = kinds.count { it == Voices.Kind.CHILD }
        if (men > 0) parts += "رجال: $men"
        if (women > 0) parts += "نساء: $women"
        if (kids > 0) parts += "أطفال: $kids"
        return parts.joinToString(" • ")
    }

    /** Builds the Arabic track; returns the time spans (ms) of the lines that were dubbed. */
    private fun buildVoiceTrack(ctx: Context, j: DubJob, lines: List<Line>, out: File, dir: File, pcm: File,
                                matchLoudness: Boolean): List<LongArray> {
        j.stage = DubStage.VOICING
        j.status = "تجهيز أصوات الشخصيات…"
        j.progress = 0f
        val placed = ArrayList<LongArray>()
        val tts = ArabicVoice(ctx)
        try {
            if (!tts.awaitReady()) throw IOException("محرّك الأصوات في الهاتف لا يعمل")
            if (!tts.hasArabic()) throw IOException("لا يوجد صوت عربي في هاتفك. من الإعدادات ← تحويل النص إلى كلام ← ثبّت اللغة العربية، ثم أعد المحاولة")
            val clip = File(dir, "clip.wav")

            // a voice for every character
            val phone = runCatching { profileVoices(ctx, tts, clip) }.getOrDefault(emptyList())
            val speakers = lines.map { it.spk.coerceAtLeast(0) }.distinct().map { id ->
                val mine = lines.filter { it.spk.coerceAtLeast(0) == id }
                val f0s = mine.map { it.f0 }.filter { it > 0 }.sorted()
                val kind = Voices.kindOfCharacter(mine.map { it.sex }, mine.map { (it.t1 - it.t0) / 1000.0 }, mine.map { it.f0 })
                Voices.Speaker(id, if (f0s.isEmpty()) 0.0 else f0s[f0s.size / 2], mine.sumOf { (it.t1 - it.t0) / 1000.0 }, kind)
            }
            val characterF0 = speakers.associate { it.id to it.f0 }
            val missing = speakers.map { it.kind == Voices.Kind.MALE }.distinct().filter { want ->
                phone.none { (it.kind == Voices.Kind.MALE) == want }
            }
            if (missing.isNotEmpty()) j.status = if (missing.first()) "لا يوجد صوت رجل عربي في هاتفك: سيُستعمل صوت امرأة بطبقة أخفض"
                else "لا يوجد صوت امرأة عربي في هاتفك: سيُستعمل صوت رجل بطبقة أعلى"
            val picks = if (phone.isEmpty()) emptyMap() else Voices.assign(speakers, phone, j.voiceName)
            if (picks.isEmpty()) tts.use(j.voiceName)
            if (missing.isEmpty()) j.status = if (picks.size > 1) "تسجيل أصوات الشخصيات (${cast(lines).ifEmpty { "${picks.size}" }})…" else "تسجيل الصوت العربي…"

            val totalMs = max(j.durationMs, (lines.lastOrNull()?.t1 ?: 0L) + 2000)
            val totalSamples = totalMs * OUT_SR / 1000
            RandomAccessFile(pcm, "r").use { orig ->
                RandomAccessFile(out, "rw").use { track ->
                    track.setLength(0)
                    track.write(wavHeader(totalSamples * 2))
                    track.setLength(44 + totalSamples * 2)   // silence everywhere
                    var prevEnd = 0L
                    for ((i, l) in lines.withIndex()) {
                        if (j.cancelled) throw IOException("أُلغيت")
                        j.progress = i * 100f / lines.size
                        if (l.ar.isBlank()) continue
                        val text = l.ar
                        val pick = picks[l.spk.coerceAtLeast(0)]
                        // the character's voice, following the actor's intonation on this line a little
                        val tone = Voices.lineIntonation(l.f0, characterF0[l.spk.coerceAtLeast(0)] ?: 0.0)
                        if (pick != null) tts.select(pick.voice, (pick.pitch * tone).coerceIn(0.5f, 2f))
                        val baseRate = pick?.rate ?: 1f
                        val next = if (i + 1 < lines.size) lines[i + 1].t0 else totalMs
                        val start = max(l.t0, prevEnd + 60)
                        val allowed = max(600L, max(l.t1, next - 120) - start)   // may run into the following pause
                        if (!tts.toFile(text, baseRate, clip)) {
                            // an online voice that lost the connection: its offline twin
                            if (pick == null || !pick.voice.endsWith("-network")) continue
                            val twin = pick.voice.replace("-network", "-local")
                            if (tts.arabicVoices().none { it.name == twin }) continue
                            tts.select(twin, (pick.pitch * tone).coerceIn(0.5f, 2f))
                            if (!tts.toFile(text, baseRate, clip)) continue
                        }
                        var samples = readWav(clip)
                        var dur = samples.size * 1000L / OUT_SR
                        if (dur > allowed) {
                            // too long for its slot: speak faster, but never so fast that it sounds like a machine
                            val rate = min(1.45f, baseRate * dur.toFloat() / allowed * 1.05f)
                            if (tts.toFile(text, rate, clip)) { samples = readWav(clip); dur = samples.size * 1000L / OUT_SR }
                        }
                        if (matchLoudness) matchLevel(samples, readPcm(orig, l.t0, l.t1))
                        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (s in samples) bb.putShort(s)
                        val pos = start * OUT_SR / 1000
                        if (pos + samples.size <= totalSamples) {
                            track.seek(44 + pos * 2)
                            track.write(bb.array())
                            placed.add(longArrayOf(l.t0, l.t1))
                        }
                        prevEnd = start + dur
                    }
                }
            }
            clip.delete()
            j.progress = 100f
        } finally {
            tts.shutdown()
        }
        return placed
    }

    /** Scales the Arabic clip to the loudness of the original line: shouting stays loud, whispers stay soft. */
    private fun matchLevel(clip: ShortArray, original: FloatArray) {
        val ref = activeRms(original, SR)
        val cur = activeRms(FloatArray(clip.size) { clip[it] / 32768f }, OUT_SR)
        if (ref <= 0 || cur <= 0) return
        var gain = (max(ref, 0.012) / cur).coerceIn(0.25, 4.0)
        var peak = 0
        for (s in clip) peak = max(peak, abs(s.toInt()))
        if (peak * gain > 32000) gain = 32000.0 / max(1, peak)
        for (i in clip.indices) clip[i] = (clip[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Loudness of the speech itself, ignoring the pauses inside a line. */
    private fun activeRms(x: FloatArray, sr: Int): Double {
        val n = max(1, sr / 50)
        val k = x.size / n
        if (k < 2) return if (x.isEmpty()) 0.0 else sqrt(x.sumOf { (it * it).toDouble() } / x.size)
        val e = DoubleArray(k) { f -> var s = 0.0; for (i in f * n until (f + 1) * n) s += x[i] * x[i]; sqrt(s / n) }
        val thr = (e.maxOrNull() ?: 0.0) * 0.0178            // -35 dB
        val act = e.filter { it >= thr }
        return if (act.isEmpty()) 0.0 else sqrt(act.sumOf { it * it } / act.size)
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
        for (l in lines) {
            val o = JSONObject().put("a", l.t0).put("b", l.t1).put("t", l.text).put("ar", l.ar).put("s", l.spk).put("f", l.f0)
            l.sex?.let { g -> o.put("g", JSONArray().apply { g.forEach { put(it.toDouble()) } }) }
            arr.put(o)
        }
        f.writeText(arr.toString())
    }

    private fun readLines(f: File): List<Line> = runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val g = o.optJSONArray("g")
            Line(o.getLong("a"), o.getLong("b"), o.getString("t"), o.optString("ar"), o.optInt("s", -1), o.optDouble("f", 0.0),
                if (g != null && g.length() == 4) FloatArray(4) { k -> g.getDouble(k).toFloat() } else null)
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
