package com.vadila.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException

/**
 * Downloads media from web pages (YouTube, TikTok, Facebook, Instagram, X ...) with yt-dlp.
 * Public content only: no cookies / logins are passed, and DRM-protected media is not supported.
 */
object Media {
    @Volatile private var ready = false

    @Synchronized
    fun ensureInit(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    private fun processId(t: Task) = "task-${t.id}"

    fun cancel(t: Task) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId(t)) }
    }

    fun update(ctx: Context): String {
        ensureInit(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        val v = YoutubeDL.getInstance().versionName(ctx) ?: ""
        return if (status == YoutubeDL.UpdateStatus.DONE) "تم تحديث محرك التنزيل $v"
        else "محرك التنزيل محدَّث بالفعل $v"
    }

    private val speedRe = Regex("at\\s+~?\\s*([\\d.]+)\\s*([KMG]?i?B)/s")
    private val sizeRe = Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMG]?i?B)")
    private const val DEST = "Destination: "

    private fun toBytes(num: String, unit: String): Long {
        val v = num.toDoubleOrNull() ?: return -1
        val mul = when (unit.uppercase().first()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            else -> 1.0
        }
        return (v * mul).toLong()
    }

    /** Runs yt-dlp for [t] into [dir]; returns the finished files. */
    fun download(ctx: Context, t: Task, dir: File, connections: Int, limitBytes: Long): List<File> {
        t.statusLine = "جارٍ تجهيز محرك التنزيل…"
        ensureInit(ctx)
        dir.mkdirs()
        t.statusLine = "جارٍ قراءة الرابط…"
        val req = YoutubeDLRequest(t.url)
            .addOption("-o", dir.absolutePath + "/%(title).120B [%(id)s].%(ext)s")
            .addOption("-N", connections.coerceIn(1, 16))
            .addOption("--no-mtime")
            .addOption("--no-playlist")
        for ((k, v) in Quality.ytdlpOptions(t.quality)) {
            if (v == null) req.addOption(k) else req.addOption(k, v)
        }
        if (limitBytes > 0) req.addOption("-r", limitBytes.toString())
        if (t.referer.isNotBlank()) req.addOption("--referer", t.referer)
        if (t.userAgent.isNotBlank()) req.addOption("--user-agent", t.userAgent)

        var parts = 0
        try {
            YoutubeDL.getInstance().execute(req, processId(t)) { progress, eta, line ->
                if (progress >= 0) t.progressPct = progress
                t.eta = eta
                val d = line.indexOf(DEST)
                if (d >= 0) {
                    parts++
                    t.fileName = File(line.substring(d + DEST.length).trim()).name
                    t.statusLine = if (parts > 1) "الجزء $parts" else ""
                }
                speedRe.find(line)?.let { m -> t.speed = toBytes(m.groupValues[1], m.groupValues[2]) }
                sizeRe.find(line)?.let { m -> t.totalBytes = toBytes(m.groupValues[1], m.groupValues[2]) }
                if (line.startsWith("[Merger]") || line.startsWith("[FixupM")) t.statusLine = "جارٍ دمج الصوت والصورة…"
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Exception) {
            throw IOException(friendlyError(e.message ?: ""))
        }
        val files = dir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".part-Frag")
        }.orEmpty()
        if (files.isEmpty()) throw IOException("لم يُعثر على ملف بعد التنزيل")
        return files
    }

    /** Reads the qualities that really exist for [url] (with approximate sizes). */
    fun fetchQualities(ctx: Context, url: String): Quality.Available {
        ensureInit(ctx)
        val info = try {
            YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).addOption("--no-playlist"))
        } catch (e: Exception) {
            throw IOException(friendlyError(e.message ?: ""))
        }
        val formats = info.formats.orEmpty()
        val duration = info.duration
        fun size(f: com.yausername.youtubedl_android.mapper.VideoFormat): Long = when {
            f.fileSize > 0 -> f.fileSize
            f.fileSizeApproximate > 0 -> f.fileSizeApproximate
            f.tbr > 0 && duration > 0 -> f.tbr.toLong() * 1000 / 8 * duration
            else -> 0L
        }
        fun none(c: String?) = c == null || c == "none"
        val audioOnly = formats.filter { none(it.vcodec) && !none(it.acodec) }
        val bestAudio = audioOnly.maxOfOrNull { size(it) } ?: 0L
        val byHeight = formats.filter { it.height > 0 && !none(it.vcodec) }.groupBy { it.height }

        val options = ArrayList<Quality.Option>()
        options.add(Quality.Option("best", Quality.label("best")))
        for (h in byHeight.keys.sortedDescending()) {
            val fs = byHeight.getValue(h)
            val best = fs.maxByOrNull { size(it) }
            val s = if (best == null) 0L else size(best) + (if (none(best.acodec)) bestAudio else 0L)
            options.add(Quality.Option(h.toString(), "${h}p" + Quality.heightTag(h) + Quality.sizeText(s)))
        }
        if (byHeight.size > 1) options.add(Quality.Option("low", Quality.label("low")))
        if (audioOnly.isNotEmpty() || byHeight.isNotEmpty()) {
            options.add(Quality.Option("mp3", Quality.label("mp3") + Quality.sizeText(bestAudio)))
            options.add(Quality.Option("m4a", Quality.label("m4a") + Quality.sizeText(bestAudio)))
        }
        return Quality.Available(info.title ?: "", options)
    }

    private fun friendlyError(raw: String): String {
        val err = raw.lines().lastOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR:")?.trim()
            ?: raw.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "خطأ غير معروف"
        val low = err.lowercase()
        return when {
            "drm" in low -> "هذا المحتوى محمي بـ DRM ولا يمكن تنزيله"
            "unsupported url" in low -> "هذا الرابط أو الموقع غير مدعوم"
            "sign in" in low || "login" in low || "log in" in low || "private" in low || "cookies" in low ->
                "هذا المحتوى يتطلب تسجيل الدخول أو أنه خاص"
            "unable to resolve" in low || "network is unreachable" in low || "timed out" in low ->
                "تعذّر الاتصال بالإنترنت أو بالموقع"
            "http error 404" in low -> "الصفحة أو الملف غير موجود (404)"
            else -> err.take(300) + "\n(جرّب: القائمة ⋮ ← تحديث محرك التنزيل)"
        }
    }
}
