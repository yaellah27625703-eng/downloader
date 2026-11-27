package com.vadila.downloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class DubStage { PREPARING, TRANSCRIBING, TRANSLATING, VOICING, UPLOADING, WAITING, DOWNLOADING, MERGING, DONE, FAILED }

class DubJob(val id: Long) {
    @Volatile var sourceUri = ""
    @Volatile var name = ""
    @Volatile var hasVideo = true
    @Volatile var hasAudio = true
    @Volatile var durationMs = 0L
    @Volatile var sourceLang = ""          // "" = detect automatically
    @Volatile var targetLang = "ar"
    @Volatile var model = "dubbing_v2"
    /** "free" = on the phone (Whisper + translation + phone voice), "eleven" = ElevenLabs (original voices). */
    @Volatile var engine = "free"
    @Volatile var voiceName = ""
    @Volatile var asrModel = "base"
    @Volatile var stage = DubStage.PREPARING
    @Volatile var progress = -1f          // 0..100 for the current step, -1 = unknown
    @Volatile var status = ""
    @Volatile var projectId = ""
    @Volatile var languageId = ""
    @Volatile var error: String? = null
    @Volatile var resultUri: String? = null
    @Volatile var resultMime = ""
    @Volatile var resultName = ""
    @Volatile var cancelled = false
    @Volatile var process: Process? = null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("src", sourceUri); put("name", name); put("video", hasVideo); put("audio", hasAudio)
        put("dur", durationMs); put("sl", sourceLang); put("tl", targetLang); put("model", model)
        put("stage", stage.name); put("pid", projectId); put("lid", languageId); put("err", error ?: "")
        put("res", resultUri ?: ""); put("rmime", resultMime); put("rname", resultName)
        put("engine", engine); put("voice", voiceName); put("asr", asrModel)
    }

    companion object {
        fun fromJson(o: JSONObject) = DubJob(o.getLong("id")).apply {
            sourceUri = o.optString("src"); name = o.optString("name"); hasVideo = o.optBoolean("video", true)
            hasAudio = o.optBoolean("audio", true); durationMs = o.optLong("dur"); sourceLang = o.optString("sl")
            targetLang = o.optString("tl", "ar"); model = o.optString("model", "dubbing_v2")
            stage = runCatching { DubStage.valueOf(o.optString("stage")) }.getOrDefault(DubStage.FAILED)
            projectId = o.optString("pid"); languageId = o.optString("lid")
            error = o.optString("err").ifEmpty { null }; resultUri = o.optString("res").ifEmpty { null }
            resultMime = o.optString("rmime"); resultName = o.optString("rname")
            engine = o.optString("engine", "eleven"); voiceName = o.optString("voice"); asrModel = o.optString("asr", "base")
        }
    }
}

/** Dubbing into Arabic with the ElevenLabs Dubbing API, keeping each speaker's voice and the background sound. */
object Dub {
    private const val API = "https://api.elevenlabs.io"
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private val jobs = ArrayList<DubJob>()
    private var nextId = 1L

    val sourceLanguages: LinkedHashMap<String, String> = linkedMapOf(
        "" to "تلقائي", "en" to "الإنجليزية", "fr" to "الفرنسية", "es" to "الإسبانية", "tr" to "التركية",
        "hi" to "الهندية", "ko" to "الكورية", "ja" to "اليابانية", "zh" to "الصينية", "de" to "الألمانية",
        "it" to "الإيطالية", "pt" to "البرتغالية", "ru" to "الروسية", "ur" to "الأردية", "fa" to "الفارسية",
        "id" to "الإندونيسية",
    )
    val targetLanguages: LinkedHashMap<String, String> = linkedMapOf(
        "ar" to "العربية الفصحى", "ar-EG" to "المصرية",
    )

    var apiKey: String
        get() = prefs.getString("elKey", "") ?: ""
        set(v) { prefs.edit().putString("elKey", v.trim()).apply() }
    var model: String
        get() = prefs.getString("dubModel", "dubbing_v2") ?: "dubbing_v2"
        set(v) { prefs.edit().putString("dubModel", v).apply() }
    var engine: String
        get() = prefs.getString("dubEngine", "free") ?: "free"
        set(v) { prefs.edit().putString("dubEngine", v).apply() }
    var voiceName: String
        get() = prefs.getString("dubVoice", "") ?: ""
        set(v) { prefs.edit().putString("dubVoice", v).apply() }
    var asrModel: String
        get() = prefs.getString("dubAsr", "base") ?: "base"
        set(v) { prefs.edit().putString("dubAsr", v).apply() }
    /** Link shown by the Colab notebook: https://xxxx.trycloudflare.com/?k=TOKEN */
    var colabLink: String
        get() = prefs.getString("colabLink", "") ?: ""
        set(v) { prefs.edit().putString("colabLink", v.trim()).apply() }
    @Volatile var colabGpu = ""

    const val COLAB_NOTEBOOK =
        "https://colab.research.google.com/github/yaellah27625703-eng/downloader/blob/main/colab/arabic_dub_server.ipynb"

    fun parseColab(text: String): String? =
        Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/?\\?k=[A-Za-z0-9_-]+").find(text)?.value

    private fun colabEndpoint(): Pair<String, String>? {
        val link = colabLink.ifBlank { return null }
        val u = Uri.parse(link)
        val key = u.getQueryParameter("k") ?: return null
        return "https://" + u.host to key
    }

    /** Checks the Colab server; returns the GPU name or throws. */
    fun checkColab(link: String): String {
        val u = Uri.parse(link)
        val key = u.getQueryParameter("k") ?: throw IOException("الرابط غير صحيح")
        val c = (URL("https://" + u.host + "/health?k=" + key).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
        }
        if (c.responseCode != 200) throw IOException("الخادم لا يستجيب (${c.responseCode})")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return o.optString("gpu").ifEmpty { "GPU" }
    }

    var keepOriginal: Boolean
        get() = prefs.getBoolean("dubKeepOriginal", true)
        set(v) { prefs.edit().putBoolean("dubKeepOriginal", v).apply() }

    @Synchronized
    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val f = File(ctx.filesDir, "dubs.json")
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) jobs.add(DubJob.fromJson(arr.getJSONObject(i)))
        }
        nextId = (jobs.maxOfOrNull { it.id } ?: 0L) + 1
        // The app was closed while dubbing: work already sent to ElevenLabs continues there, so pick it up again.
        for (j in jobs) {
            if (j.stage == DubStage.DONE || j.stage == DubStage.FAILED) continue
            if ((j.engine == "eleven" || j.engine == "colab") && j.projectId.isNotEmpty()) launch(j, resume = true)
            else if (j.engine == "free") launch(j, resume = true)   // continues from the saved transcript
            else { j.stage = DubStage.FAILED; j.error = "توقفت العملية قبل الرفع. اضغط إعادة المحاولة." }
        }
        save()
    }

    @Synchronized fun snapshot(): List<DubJob> = ArrayList(jobs)

    @Synchronized fun activeCount() = jobs.count { it.stage != DubStage.DONE && it.stage != DubStage.FAILED }

    @Synchronized
    fun save() {
        runCatching {
            val arr = JSONArray()
            for (j in jobs) arr.put(j.toJson())
            val tmp = File(ctx.filesDir, "dubs.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(File(ctx.filesDir, "dubs.json"))
        }
    }

    @Synchronized
    fun start(uri: Uri, name: String, hasVideo: Boolean, hasAudio: Boolean, durationMs: Long, sourceLang: String, targetLang: String) {
        val j = DubJob(nextId++)
        j.sourceUri = uri.toString(); j.name = name; j.hasVideo = hasVideo; j.hasAudio = hasAudio
        j.durationMs = durationMs; j.sourceLang = sourceLang; j.targetLang = targetLang; j.model = model
        j.engine = engine; j.voiceName = voiceName; j.asrModel = asrModel
        j.status = "في الانتظار"
        jobs.add(0, j)
        save()
        launch(j, resume = false)
    }

    @Synchronized
    fun retry(id: Long) {
        val j = jobs.find { it.id == id } ?: return
        if (j.stage != DubStage.FAILED) return
        j.error = null
        j.cancelled = false
        // Already dubbed or being dubbed on ElevenLabs: just fetch it again (no new charge).
        launch(j, resume = j.engine == "free" || j.projectId.isNotEmpty())
    }

    @Synchronized
    fun remove(id: Long) {
        val j = jobs.find { it.id == id } ?: return
        j.cancelled = true
        j.process?.destroy()
        if (j.stage == DubStage.TRANSCRIBING) runCatching { Whisper.setAbort(true) }
        jobs.remove(j)
        workDir(j).deleteRecursively()
        save()
    }

    private fun launch(j: DubJob, resume: Boolean) {
        DownloadService.start(ctx)
        Thread({ run(j, resume) }, "dub-${j.id}").start()
    }

    private fun workDir(j: DubJob) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dub_${j.id}")

    // ------------------------------------------------------------------ pipeline

    private fun run(j: DubJob, resume: Boolean) {
        try {
            val dir = workDir(j).apply { mkdirs() }
            val src = File(dir, "source")
            if (j.engine == "free") { runFree(j, dir, src); return }
            if (j.engine == "colab") { runColab(j, dir, src); return }
            if (apiKey.isBlank()) throw IOException("أضف مفتاح ElevenLabs من الإعدادات أولًا")

            if (!resume || j.projectId.isEmpty()) {
                j.projectId = ""; j.languageId = ""
                // 1. local copy of the video (FFmpeg needs a real file)
                ensureSource(j, src)
                // 2. send only the audio: far smaller, so the upload is much faster
                var upload = src
                if (j.hasAudio) {
                    j.stage = DubStage.PREPARING
                    j.status = "استخراج الصوت لتسريع الرفع…"
                    j.progress = 0f
                    val audio = File(dir, "audio.m4a")
                    val rc = Ffmpeg.run(ctx, listOf("-y", "-i", src.absolutePath, "-vn", "-c:a", "aac", "-b:a", "128k", audio.absolutePath),
                        j.durationMs, onProcess = { j.process = it }) { j.progress = it }
                    if (rc == 0 && audio.length() > 0) upload = audio
                }
                check(j)
                // 3. create the dubbing project (upload)
                j.stage = DubStage.UPLOADING
                j.status = "رفع الصوت إلى ElevenLabs…"
                createProject(j, upload)
                save()
            }

            // 4. wait for ElevenLabs
            j.stage = DubStage.WAITING
            j.progress = -1f
            waitProject(j)
            if (j.languageId.isEmpty()) createLanguage(j)
            save()
            val outputs = waitLanguage(j)

            // 5. download the Arabic track (+ subtitles)
            j.stage = DubStage.DOWNLOADING
            j.status = "تنزيل الصوت العربي…"
            val flac = File(dir, "dub.flac")
            val audioUrl = outputs.optString("lossless_audio").ifEmpty { throw IOException("لم تُرجع ElevenLabs ملف الصوت") }
            fetch(audioUrl, flac, j)
            val vtt = File(dir, "dub.vtt")
            outputs.optString("subtitles").takeIf { it.isNotEmpty() }?.let { runCatching { fetch(it, vtt, j) } }
            check(j)

            // 6. merge into the video
            j.stage = DubStage.MERGING
            j.progress = 0f
            j.status = "دمج الصوت العربي مع الفيديو…"
            val base = j.name.substringBeforeLast('.').take(100).ifBlank { "video" } + " - مدبلج"
            if (j.hasVideo) {
                ensureSource(j, src)
                j.status = "دمج الصوت العربي مع الفيديو…"
                val out = mux(j, src, flac, vtt, dir)
                j.resultMime = if (out.extension == "mkv") "video/x-matroska" else "video/mp4"
                j.resultName = "$base.${out.extension}"
                j.resultUri = Engine.publishFile(j.resultName, j.resultMime, out).toString()
            } else {
                val out = File(dir, "dub.m4a")
                val rc = Ffmpeg.run(ctx, listOf("-y", "-i", flac.absolutePath, "-c:a", "aac", "-b:a", "192k", out.absolutePath),
                    j.durationMs, onProcess = { j.process = it }) { j.progress = it }
                val final = if (rc == 0 && out.length() > 0) out else flac
                j.resultMime = if (final == out) "audio/mp4" else "audio/flac"
                j.resultName = "$base.${final.extension}"
                j.resultUri = Engine.publishFile(j.resultName, j.resultMime, final).toString()
            }
            j.stage = DubStage.DONE
            j.status = "اكتملت الدبلجة"
            j.progress = 100f
            dir.deleteRecursively()
        } catch (e: Throwable) {
            if (j.cancelled) return
            j.stage = DubStage.FAILED
            j.error = e.message ?: e.javaClass.simpleName
        } finally {
            j.process = null
            if (!j.cancelled) save()
        }
    }

    private fun check(j: DubJob) {
        if (j.cancelled) throw IOException("أُلغيت")
    }

    private fun ensureSource(j: DubJob, dst: File) {
        if (dst.exists() && dst.length() > 0) return
        j.stage = DubStage.PREPARING
        j.status = "تجهيز الملف…"
        j.progress = 0f
        val uri = Uri.parse(j.sourceUri)
        val total = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر فتح الملف الأصلي")
        val tmp = File(dst.parentFile, "source.part")
        input.use { inp -> tmp.outputStream().use { out -> copy(inp, out, total, j) } }
        tmp.renameTo(dst)
    }

    private fun copy(inp: InputStream, out: java.io.OutputStream, total: Long, j: DubJob) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            check(j)
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) j.progress = done * 100f / total
        }
    }

    /** Free, on-phone dubbing: separation → Whisper → characters → translation → a voice per character → film mix. */
    private fun runFree(j: DubJob, dir: File, src: File) {
        ensureSource(j, src)
        val res = FreeDub.run(ctx, j, dir, src)
        check(j)
        j.stage = DubStage.MERGING
        j.status = "دمج الصوت العربي مع الفيديو…"
        j.progress = 0f
        val srt = File(dir, "ar.srt")
        val base = j.name.substringBeforeLast('.').take(100).ifBlank { "video" } + " - مدبلج"
        val out = mixFree(j, src, res, srt, dir)
        j.resultMime = when (out.extension) { "mkv" -> "video/x-matroska"; "m4a" -> "audio/mp4"; else -> "video/mp4" }
        j.resultName = "$base.${out.extension}"
        j.resultUri = Engine.publishFile(j.resultName, j.resultMime, out).toString()
        j.stage = DubStage.DONE
        j.status = "اكتملت الدبلجة" + (if (res.cast.isNotEmpty()) " • " + res.cast else "")
        j.progress = 100f
        dir.deleteRecursively()
    }

    private fun mixFree(j: DubJob, src: File, res: FreeResult, srt: File, dir: File): File {
        val subs = srt.exists() && srt.length() > 0
        val keep = keepOriginal
        val voice = res.voice
        val film = res.background != null && res.residual != null && res.background.length() > 0
        // Separated: music + effects at full level, the original laughs/screams outside the dubbed lines,
        // and the Arabic voices (already as loud as the original lines).
        val bed = "[1:a]aformat=sample_fmts=fltp:channel_layouts=stereo[bg];" +
            "[2:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[rv];" +
            // a touch of room (two faint early reflections) so the voices sit in the scene instead of on top of it
            "[3:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,highpass=f=70,aecho=1.0:1.0:17|31:0.12|0.07[v];" +
            "[bg][rv][v]amix=inputs=3:duration=first:normalize=0"
        val filmLimited = "$bed,alimiter=limit=0.89:attack=5:release=60:level=0[mix]"
        val filmPlain = "$bed,volume=0.9[mix]"
        // Not separated: the Arabic voice pushes the original speech down while it talks.
        val ducking = "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=1.5,asplit=2[vk][vm];" +
            "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[orig];" +
            "[orig][vk]sidechaincompress=threshold=0.04:ratio=4:attack=20:release=500[duck];" +
            "[duck][vm]amix=inputs=2:duration=first:normalize=0[mix]"
        val simple = "[0:a:0]aresample=48000,volume=0.3[o];[1:a]aresample=48000,volume=1.5[v];" +
            "[o][v]amix=inputs=2:duration=first[mix]"
        fun args(filter: String, separated: Boolean, withSubs: Boolean, out: File, kind: String): List<String> {
            val a = mutableListOf("-y", "-i", src.absolutePath)
            if (separated) {
                a += listOf("-f", "s16le", "-ar", "44100", "-ac", "2", "-i", res.background!!.absolutePath)
                a += listOf("-f", "s16le", "-ar", "16000", "-ac", "1", "-i", res.residual!!.absolutePath)
            }
            a += listOf("-i", voice.absolutePath)
            val subIdx = if (separated) 4 else 2
            if (withSubs) a += listOf("-i", srt.absolutePath)
            a += listOf("-filter_complex", filter)
            if (kind != "m4a") a += listOf("-map", "0:v:0")
            a += listOf("-map", "[mix]")
            if (keep && kind != "m4a") a += listOf("-map", "0:a:0")
            if (withSubs) a += listOf("-map", "$subIdx:s:0", "-c:s", if (kind == "mp4") "mov_text" else "srt", "-metadata:s:s:0", "language=ara")
            if (kind != "m4a") a += listOf("-c:v", "copy")
            a += listOf("-c:a", "aac", "-b:a", "192k",
                "-metadata:s:a:0", "language=ara", "-metadata:s:a:0", "title=العربية", "-disposition:a:0", "default")
            if (keep && kind != "m4a") a += listOf("-metadata:s:a:1", "title=الصوت الأصلي", "-disposition:a:1", "0")
            if (kind != "mkv") a += listOf("-movflags", "+faststart")
            a += out.absolutePath
            return a
        }
        class Plan(val filter: String, val separated: Boolean, val subs: Boolean, val out: File)
        val mp4 = File(dir, "out.mp4"); val mkv = File(dir, "out.mkv"); val m4a = File(dir, "out.m4a")
        val plan = ArrayList<Plan>()
        if (j.hasVideo) {
            if (film) {
                if (subs) plan += Plan(filmLimited, true, true, mp4)
                plan += Plan(filmLimited, true, false, mp4)
                plan += Plan(filmPlain, true, false, mp4)
                plan += Plan(filmPlain, true, false, mkv)
            }
            if (subs) plan += Plan(ducking, false, true, mp4)
            plan += Plan(ducking, false, false, mp4)
            plan += Plan(simple, false, false, mp4)
            plan += Plan(simple, false, false, mkv)
        } else {
            if (film) { plan += Plan(filmLimited, true, false, m4a); plan += Plan(filmPlain, true, false, m4a) }
            plan += Plan(ducking, false, false, m4a)
            plan += Plan(simple, false, false, m4a)
        }
        for (pl in plan) {
            check(j)
            pl.out.delete()
            val rc = Ffmpeg.run(ctx, args(pl.filter, pl.separated, pl.subs, pl.out, pl.out.extension), j.durationMs,
                onProcess = { j.process = it }) { j.progress = it }
            if (rc == 0 && pl.out.length() > 0) return pl.out
        }
        throw IOException("تعذّر دمج الصوت العربي مع الفيديو")
    }

    // ------------------------------------------------------------------ Colab (free GPU, original voices)

    private fun colabUrl(path: String, params: Map<String, String>): URL {
        val (base, key) = colabEndpoint() ?: throw IOException("اتصل بخادم Colab أولًا من صفحة الدبلجة")
        val q = StringBuilder("?k=").append(key)
        for ((k, v) in params) q.append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
        return URL(base + path + q)
    }

    private fun colabJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 403) throw IOException("رابط Colab قديم. انسخ الرابط الجديد من صفحة Colab")
        if (code == 530 || code == 502 || code == 404 && body.isBlank())
            throw IOException("خادم Colab متوقف. افتح صفحة Colab وشغّلها، ثم انسخ الرابط الجديد")
        if (code !in 200..299) throw IOException("خطأ من خادم Colab ($code): " + body.take(200))
        return JSONObject(body.ifBlank { "{}" })
    }

    private fun runColab(j: DubJob, dir: File, src: File) {
        ensureSource(j, src)
        if (j.projectId.isEmpty()) {
            // only the audio travels to Colab: much smaller and faster than the whole video
            j.stage = DubStage.PREPARING
            j.status = "استخراج الصوت…"
            j.progress = 0f
            val audio = File(dir, "colab_in.m4a")
            val rc = Ffmpeg.run(ctx, listOf("-y", "-i", src.absolutePath, "-vn", "-ac", "2", "-ar", "44100", "-c:a", "aac", "-b:a", "160k", audio.absolutePath),
                j.durationMs, onProcess = { j.process = it }) { j.progress = it }
            if (rc != 0 || audio.length() == 0L) throw IOException("تعذّر استخراج صوت الفيديو")
            check(j)

            j.stage = DubStage.UPLOADING
            val jobId = "a${j.id}x${System.currentTimeMillis() % 1_000_000}"
            val chunk = 40L * 1024 * 1024          // stays under the tunnel's request size limit
            val parts = ((audio.length() + chunk - 1) / chunk).toInt()
            val buf = ByteArray(256 * 1024)
            for (part in 0 until parts) {
                var attempt = 0
                while (true) {
                    try {
                        val off = part * chunk
                        val len = minOf(chunk, audio.length() - off)
                        val c = (colabUrl("/upload", mapOf("job" to jobId, "part" to "$part")).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"; doOutput = true
                            connectTimeout = 30_000; readTimeout = 120_000
                            setRequestProperty("Content-Type", "application/octet-stream")
                            setFixedLengthStreamingMode(len)
                        }
                        java.io.RandomAccessFile(audio, "r").use { raf ->
                            raf.seek(off)
                            c.outputStream.use { out ->
                                var left = len
                                while (left > 0) {
                                    check(j)
                                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    left -= n
                                    j.progress = (off + len - left) * 100f / audio.length()
                                }
                            }
                        }
                        colabJson(c)
                        break
                    } catch (e: IOException) {
                        if (j.cancelled || ++attempt >= 4) throw e
                        Thread.sleep(2000L * attempt)
                    }
                }
            }
            val lang = j.sourceLang.substringBefore('-')
            val start = (colabUrl("/start", mapOf("job" to jobId, "parts" to "$parts", "lang" to lang, "speakers" to "0"))
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; setFixedLengthStreamingMode(0)
            }
            start.outputStream.use { }
            colabJson(start)
            j.projectId = jobId
            save()
        }

        // follow the work on the Colab GPU
        j.stage = DubStage.WAITING
        var failures = 0
        while (true) {
            check(j)
            try {
                val o = colabJson(colabUrl("/status", mapOf("job" to j.projectId)).openConnection() as HttpURLConnection)
                failures = 0
                j.status = o.optString("message").ifBlank { "تجري الدبلجة على Colab…" }
                j.progress = o.optDouble("progress", -1.0).toFloat()
                if (o.optBoolean("done")) break
                val err = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (o.optString("stage") == "unknown") {
                    j.projectId = ""
                    throw IOException("أُعيد تشغيل خادم Colab وضاعت المهمة. اضغط إعادة المحاولة")
                }
                if (err != null) { j.projectId = ""; throw IOException("فشلت الدبلجة على Colab: $err") }
            } catch (e: IOException) {
                if (j.projectId.isEmpty()) throw e          // the job is lost or failed on the server
                if (++failures > 40) throw IOException("انقطع الاتصال بخادم Colab. تأكد أن صفحة Colab ما زالت مفتوحة، ثم اضغط إعادة المحاولة")
            }
            Thread.sleep(3000)
        }

        // fetch the Arabic audio + subtitles and put them into the video here on the phone
        j.stage = DubStage.DOWNLOADING
        j.status = "تنزيل الصوت العربي…"
        val dubAudio = File(dir, "colab_dub.m4a")
        fetch(colabUrl("/result", mapOf("job" to j.projectId, "file" to "audio")).toString(), dubAudio, j)
        val subs = File(dir, "colab_ar.srt")
        runCatching { fetch(colabUrl("/result", mapOf("job" to j.projectId, "file" to "srt")).toString(), subs, j) }
        check(j)
        j.stage = DubStage.MERGING
        j.progress = 0f
        j.status = "دمج الصوت العربي مع الفيديو…"
        val base = j.name.substringBeforeLast('.').take(100).ifBlank { "video" } + " - مدبلج"
        if (j.hasVideo) {
            val out = mux(j, src, dubAudio, subs, dir)
            j.resultMime = if (out.extension == "mkv") "video/x-matroska" else "video/mp4"
            j.resultName = "$base.${out.extension}"
            j.resultUri = Engine.publishFile(j.resultName, j.resultMime, out).toString()
        } else {
            j.resultMime = "audio/mp4"
            j.resultName = "$base.m4a"
            j.resultUri = Engine.publishFile(j.resultName, j.resultMime, dubAudio).toString()
        }
        j.stage = DubStage.DONE
        j.status = "اكتملت الدبلجة"
        j.progress = 100f
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ ElevenLabs API

    private fun api(path: String, method: String = "GET"): HttpURLConnection =
        (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
        }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException(apiError(code, body))
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private fun apiError(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body)
            val d = o.opt("detail")
            when (d) {
                is JSONObject -> d.optString("message").ifEmpty { d.optString("status") }
                is JSONArray -> d.optJSONObject(0)?.optString("msg") ?: d.toString()
                else -> d?.toString() ?: o.optString("message")
            }
        }.getOrNull().orEmpty().ifEmpty { body.take(200) }
        val low = msg.lowercase()
        return when {
            code == 401 || "invalid_api_key" in low || "api key" in low -> "مفتاح ElevenLabs غير صحيح. راجعه في الإعدادات."
            code == 402 || "quota" in low || "credits" in low || "insufficient" in low || "payment" in low ->
                "رصيدك في ElevenLabs لا يكفي لهذه الدبلجة. اشحن رصيدك أو رقِّ اشتراكك ثم أعد المحاولة."
            "too_many_concurrent" in low -> "تعمل 3 دبلجات حاليًا في حسابك. انتظر انتهاء إحداها ثم أعد المحاولة."
            "permission" in low || code == 403 -> "المفتاح لا يملك صلاحية الدبلجة. فعّل صلاحية Dubbing للمفتاح في ElevenLabs."
            else -> "خطأ من ElevenLabs ($code): $msg"
        }
    }

    private fun createProject(j: DubJob, file: File) {
        val boundary = "----DubBoundary" + System.nanoTime()
        val fields = LinkedHashMap<String, String>()
        fields["model_id"] = j.model
        fields["target_language"] = j.targetLang
        if (j.sourceLang.isNotEmpty()) fields["source_language"] = j.sourceLang
        fields["reference"] = j.name.take(200)

        val sb = StringBuilder()
        for ((k, v) in fields) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        val mime = if (file.name.endsWith(".m4a")) "audio/mp4" else "application/octet-stream"
        val fname = if (file.name.endsWith(".m4a")) "audio.m4a" else (j.name.ifBlank { "video.mp4" })
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(fname.replace("\"", "")).append("\"\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val total = head.size + file.length() + tail.size

        val c = api("/v1/dubbing/project", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(total)
        c.outputStream.use { out ->
            out.write(head)
            var sent = head.size.toLong()
            val buf = ByteArray(256 * 1024)
            file.inputStream().use { inp ->
                while (true) {
                    check(j)
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    j.progress = sent * 100f / total
                }
            }
            out.write(tail)
        }
        val o = readJson(c)
        j.projectId = o.optString("project_id").ifEmpty { throw IOException("لم تُرجع ElevenLabs رقم المشروع") }
        o.optJSONArray("language_ids")?.let { if (it.length() > 0) j.languageId = it.getString(0) }
    }

    private fun waitProject(j: DubJob) {
        while (true) {
            check(j)
            val o = readJson(api("/v1/dubbing/project/${j.projectId}"))
            when (o.optString("status")) {
                "ready" -> {
                    if (j.languageId.isEmpty()) o.optJSONArray("language_ids")?.let { if (it.length() > 0) j.languageId = it.getString(0) }
                    return
                }
                "failed" -> {
                    val msg = errorText(o)
                    j.projectId = ""; j.languageId = ""   // next retry starts over
                    throw IOException("فشل تحليل الملف في ElevenLabs: $msg")
                }
                "queued" -> j.status = "في طابور ElevenLabs…"
                else -> j.status = "تحويل الكلام إلى نص وتمييز المتحدثين…"
            }
            Thread.sleep(4000)
        }
    }

    private fun createLanguage(j: DubJob) {
        val c = api("/v1/dubbing/project/${j.projectId}/language", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("target_language", j.targetLang).toString().toByteArray()) }
        j.languageId = readJson(c).optString("language_id").ifEmpty { throw IOException("تعذّر إضافة اللغة العربية") }
    }

    private fun waitLanguage(j: DubJob): JSONObject {
        while (true) {
            check(j)
            val o = readJson(api("/v1/dubbing/project/${j.projectId}/language/${j.languageId}"))
            when (o.optString("status")) {
                "completed", "stale" -> return o.optJSONObject("outputs") ?: throw IOException("لا توجد ملفات ناتجة")
                "failed" -> {
                    val msg = errorText(o)
                    j.languageId = ""                     // next retry asks for the Arabic version again
                    throw IOException("فشلت الدبلجة في ElevenLabs: $msg")
                }
                "queued" -> j.status = "في الطابور للدبلجة…"
                else -> j.status = "ترجمة ودبلجة بأصوات المتحدثين الأصلية…"
            }
            Thread.sleep(4000)
        }
    }

    private fun errorText(o: JSONObject): String {
        val e = o.optJSONObject("error") ?: return "سبب غير معروف"
        return e.optString("message").ifEmpty { e.optString("code").ifEmpty { e.toString() } }
    }

    /** Downloads a signed output URL (no API key is sent to the storage host). */
    private fun fetch(url: String, dst: File, j: DubJob) {
        var attempt = 0
        while (true) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000; readTimeout = 60_000
                }
                if (c.responseCode !in 200..299) throw IOException("تعذّر تنزيل الناتج (${c.responseCode})")
                val total = c.contentLengthLong
                c.inputStream.use { inp -> dst.outputStream().use { out -> copy(inp, out, total, j) } }
                return
            } catch (e: IOException) {
                if (j.cancelled || ++attempt >= 5) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }

    // ------------------------------------------------------------------ merging

    private fun mux(j: DubJob, src: File, flac: File, vtt: File, dir: File): File {
        val subs = vtt.exists() && vtt.length() > 0
        val keep = keepOriginal && j.hasAudio
        fun args(withSubs: Boolean, out: File, mp4: Boolean): List<String> {
            val a = mutableListOf("-y", "-i", src.absolutePath, "-i", flac.absolutePath)
            if (withSubs) a += listOf("-i", vtt.absolutePath)
            a += listOf("-map", "0:v:0", "-map", "1:a:0")
            if (keep) a += listOf("-map", "0:a:0")
            if (withSubs) a += listOf("-map", "2:s:0")
            a += listOf("-c:v", "copy", "-c:a", "aac", "-b:a", "192k")
            if (withSubs) a += listOf("-c:s", if (mp4) "mov_text" else "srt", "-metadata:s:s:0", "language=ara")
            a += listOf("-metadata:s:a:0", "language=ara", "-metadata:s:a:0", "title=العربية", "-disposition:a:0", "default")
            if (keep) a += listOf("-metadata:s:a:1", "title=الصوت الأصلي", "-disposition:a:1", "0")
            if (mp4) a += listOf("-movflags", "+faststart")
            a += out.absolutePath
            return a
        }
        val mp4 = File(dir, "out.mp4")
        val mkv = File(dir, "out.mkv")
        val attempts = listOfNotNull(
            if (subs) Triple(true, mp4, true) else null,
            Triple(false, mp4, true),
            Triple(false, mkv, false),
        )
        for ((withSubs, out, isMp4) in attempts) {
            check(j)
            out.delete()
            val rc = Ffmpeg.run(ctx, args(withSubs, out, isMp4), j.durationMs, onProcess = { j.process = it }) { j.progress = it }
            if (rc == 0 && out.length() > 0) return out
        }
        throw IOException("تعذّر دمج الصوت العربي مع الفيديو")
    }
}

/** Runs the FFmpeg binary bundled with the video engine. */
object Ffmpeg {
    private val timeRe = Regex("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")

    fun run(ctx: Context, args: List<String>, durationMs: Long, onProcess: (Process) -> Unit, onProgress: (Float) -> Unit): Int {
        Media.ensureInit(ctx)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val ld = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(base, "$it/usr/lib").absolutePath }
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner") + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = ld
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        val p = pb.start()
        onProcess(p)
        val sb = StringBuilder()
        p.inputStream.use { inp ->
            while (true) {
                val ch = inp.read()
                if (ch < 0) break
                if (ch == '\r'.code || ch == '\n'.code) {
                    val line = sb.toString()
                    sb.setLength(0)
                    timeRe.find(line)?.let { m ->
                        val sec = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
                        if (durationMs > 0) onProgress((sec * 1000 / durationMs * 100).toFloat().coerceIn(0f, 100f))
                    }
                } else if (sb.length < 4000) sb.append(ch.toChar())
            }
        }
        return p.waitFor()
    }

    /** Starts FFmpeg with its output on stdout (e.g. raw PCM) for the caller to read; errors go to [log]. */
    fun pipe(ctx: Context, args: List<String>, log: File): Process {
        Media.ensureInit(ctx)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val ld = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(base, "$it/usr/lib").absolutePath }
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-nostdin", "-loglevel", "error") + args)
            .redirectError(log)
        pb.environment()["LD_LIBRARY_PATH"] = ld
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        return pb.start()
    }
}
