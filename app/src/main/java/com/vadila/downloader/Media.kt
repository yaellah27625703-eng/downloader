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
            .addOption("-f", "bv*+ba/b")
            .addOption("--merge-output-format", "mp4")
            .addOption("-N", connections.coerceIn(1, 16))
            .addOption("--no-mtime")
            .addOption("--no-playlist")
        if (limitBytes > 0) req.addOption("-r", limitBytes.toString())

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
