package com.vadila.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** Thrown by the direct-file probe when the link must go through the media extractor. */
class NeedsExtractor : Exception()

class Segment(val start: Long, val end: Long, done: Long) {
    val done = AtomicLong(done)
    @Volatile var finishedUnknown = false
    fun complete(): Boolean =
        if (end >= 0) done.get() >= end - start + 1 else finishedUnknown
}

class Task(val id: Long, val url: String) {
    @Volatile var finalUrl: String = url
    @Volatile var fileName: String = ""
    @Volatile var mime: String = ""
    @Volatile var totalBytes: Long = -1
    @Volatile var supportsRanges = false
    @Volatile var status: Status = Status.QUEUED
    @Volatile var error: String? = null
    @Volatile var savedUri: String? = null
    @Volatile var speed: Long = 0
    @Volatile var stop = false
    @Volatile var pauseRequested = false
    @Volatile var removed = false
    @Volatile var segments: List<Segment> = emptyList()
    /** true when the link is a web page handled by yt-dlp instead of a direct file. */
    @Volatile var media = false
    @Volatile var progressPct = -1f
    @Volatile var eta = -1L
    @Volatile var statusLine = ""
    @Volatile var quality = "best"
    /** Extra request headers, used for files started from the in-app browser. */
    @Volatile var userAgent = ""
    @Volatile var referer = ""
    @Volatile var cookie = ""

    fun downloaded(): Long = segments.sumOf { it.done.get() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("finalUrl", finalUrl)
        put("name", fileName); put("mime", mime); put("total", totalBytes)
        put("ranges", supportsRanges); put("status", status.name); put("media", media)
        put("quality", quality); put("ua", userAgent); put("ref", referer); put("cookie", cookie)
        put("error", error ?: ""); put("uri", savedUri ?: "")
        val arr = JSONArray()
        for (s in segments) arr.put(JSONObject().apply {
            put("s", s.start); put("e", s.end); put("d", s.done.get())
        })
        put("segs", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val t = Task(o.getLong("id"), o.getString("url"))
            t.finalUrl = o.optString("finalUrl", t.url)
            t.fileName = o.optString("name")
            t.mime = o.optString("mime")
            t.totalBytes = o.optLong("total", -1)
            t.supportsRanges = o.optBoolean("ranges")
            t.media = o.optBoolean("media")
            t.quality = o.optString("quality", "best").ifEmpty { "best" }
            t.userAgent = o.optString("ua", "")
            t.referer = o.optString("ref", "")
            t.cookie = o.optString("cookie", "")
            t.status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PAUSED)
            t.error = o.optString("error").ifEmpty { null }
            t.savedUri = o.optString("uri").ifEmpty { null }
            val arr = o.optJSONArray("segs") ?: JSONArray()
            val list = ArrayList<Segment>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                list.add(Segment(s.getLong("s"), s.getLong("e"), s.getLong("d")))
            }
            t.segments = list
            // Downloads that were interrupted by the app closing wait for the user.
            if (t.status == Status.RUNNING || t.status == Status.QUEUED) t.status = Status.PAUSED
            if (!t.supportsRanges && t.status != Status.DONE) t.segments = emptyList()
            if (t.media && t.status == Status.DONE) t.progressPct = 100f
            return t
        }
    }
}

/** Global speed limit shared by every connection (0 = unlimited). */
object Limiter {
    @Volatile var bytesPerSec = 0L
    private var windowStart = 0L
    private var used = 0L

    fun acquire(n: Int) {
        while (true) {
            val limit = bytesPerSec
            if (limit <= 0) return
            val pause: Long = synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (now - windowStart >= 1000) { windowStart = now; used = 0 }
                if (used == 0L || used + n <= limit) { used += n; return }
                1000 - (now - windowStart)
            }
            Thread.sleep(max(5L, pause))
        }
    }
}

object Engine {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private val tasks = ArrayList<Task>()
    private var nextId = 1L

    var connections: Int
        get() = prefs.getInt("connections", 8)
        set(v) { prefs.edit().putInt("connections", v.coerceIn(1, 16)).apply() }
    var maxConcurrent: Int
        get() = prefs.getInt("maxConcurrent", 3)
        set(v) { prefs.edit().putInt("maxConcurrent", v.coerceIn(1, 5)).apply(); schedule() }
    var speedLimitKb: Int
        get() = prefs.getInt("speedLimitKb", 0)
        set(v) {
            val k = max(0, v)
            prefs.edit().putInt("speedLimitKb", k).apply()
            Limiter.bytesPerSec = k * 1024L
        }

    @Synchronized
    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        Limiter.bytesPerSec = speedLimitKb * 1024L
        val f = stateFile()
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) tasks.add(Task.fromJson(arr.getJSONObject(i)))
        }
        nextId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1
        // Keep the extractor fresh: sites change often, so update yt-dlp every 3 days.
        val last = prefs.getLong("ytdlpUpdated", 0)
        if (System.currentTimeMillis() - last > 3L * 24 * 3600 * 1000) {
            Thread {
                runCatching {
                    Media.update(ctx)
                    prefs.edit().putLong("ytdlpUpdated", System.currentTimeMillis()).apply()
                }
            }.start()
        }
    }

    fun updateExtractor(): String = runCatching {
        val msg = Media.update(ctx)
        prefs.edit().putLong("ytdlpUpdated", System.currentTimeMillis()).apply()
        msg
    }.getOrElse { "تعذّر التحديث: " + (it.message ?: "").take(150) }

    private fun stateFile() = File(ctx.filesDir, "tasks.json")
    private fun tempFile(t: Task) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "part_${t.id}")

    @Synchronized fun snapshot(): List<Task> = ArrayList(tasks)

    @Synchronized fun activeCount(): Int =
        tasks.count { it.status == Status.RUNNING || it.status == Status.QUEUED }

    @Synchronized
    fun save() {
        runCatching {
            val arr = JSONArray()
            for (t in tasks) arr.put(t.toJson())
            val tmp = File(ctx.filesDir, "tasks.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(stateFile())
        }
    }

    var defaultQuality: String
        get() = prefs.getString("quality", "best") ?: "best"
        set(v) { prefs.edit().putString("quality", v).apply() }
    var askQuality: Boolean
        get() = prefs.getBoolean("askQuality", true)
        set(v) { prefs.edit().putBoolean("askQuality", v).apply() }

    @Synchronized
    fun add(urls: List<String>, quality: String = defaultQuality) {
        for (u in urls) {
            val t = Task(nextId++, u)
            t.fileName = guessNameFromUrl(u)
            t.quality = quality
            tasks.add(0, t)
        }
        schedule()
    }

    /** A single download started from the in-app browser (keeps the page's headers). */
    @Synchronized
    fun addFromBrowser(
        url: String, quality: String, name: String?, mime: String?,
        userAgent: String?, referer: String?, cookie: String?, forceMedia: Boolean = false,
    ) {
        val t = Task(nextId++, url)
        t.fileName = if (!name.isNullOrBlank()) clean(name) else guessNameFromUrl(url)
        t.mime = mime ?: ""
        t.quality = quality
        t.userAgent = userAgent ?: ""
        t.referer = referer ?: ""
        t.cookie = cookie ?: ""
        t.media = forceMedia
        tasks.add(0, t)
        schedule()
    }

    @Synchronized
    fun pause(id: Long) {
        val t = tasks.find { it.id == id } ?: return
        when (t.status) {
            Status.RUNNING -> { t.pauseRequested = true; t.stop = true; if (t.media) Media.cancel(t) }
            Status.QUEUED -> t.status = Status.PAUSED
            else -> {}
        }
        save()
    }

    @Synchronized
    fun resume(id: Long) {
        val t = tasks.find { it.id == id } ?: return
        if (t.status == Status.PAUSED || t.status == Status.FAILED) {
            t.status = Status.QUEUED
            t.error = null
        }
        schedule()
    }

    @Synchronized
    fun remove(id: Long) {
        val t = tasks.find { it.id == id } ?: return
        t.removed = true
        t.stop = true
        if (t.media) Media.cancel(t)
        tasks.remove(t)
        if (t.status != Status.RUNNING) { tempFile(t).delete(); mediaDir(t).deleteRecursively() }
        save()
        schedule()
    }

    @Synchronized
    fun pauseAll() { tasks.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }.forEach { pause(it.id) } }

    @Synchronized
    fun resumeAll() {
        tasks.filter { it.status == Status.PAUSED || it.status == Status.FAILED }.forEach {
            it.status = Status.QUEUED; it.error = null
        }
        schedule()
    }

    @Synchronized
    fun clearFinished() {
        tasks.removeAll { it.status == Status.DONE }
        save()
    }

    @Synchronized
    fun schedule() {
        var active = tasks.count { it.status == Status.RUNNING }
        // Oldest queued first.
        for (t in tasks.asReversed()) {
            if (active >= maxConcurrent) break
            if (t.status == Status.QUEUED) {
                t.status = Status.RUNNING
                t.stop = false
                t.pauseRequested = false
                t.error = null
                t.speed = 0
                if (t.media && t.status != Status.DONE) t.statusLine = ""
                active++
                Thread({ runTask(t) }, "task-${t.id}").start()
            }
        }
        save()
        if (activeCount() > 0 && !DownloadService.running) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            }
        }
    }

    // ---------------------------------------------------------------- download

    private fun open(url: String, t: Task, follow: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = follow
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", t.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36" })
            setRequestProperty("Accept-Encoding", "identity")
            if (t.referer.isNotBlank()) setRequestProperty("Referer", t.referer)
            if (t.cookie.isNotBlank()) setRequestProperty("Cookie", t.cookie)
        }

    /** Follows redirects manually (also http <-> https) and returns the final connection. */
    private fun resolve(t: Task): HttpURLConnection {
        var url = t.url
        repeat(12) {
            val c = open(url, t, follow = false)
            c.setRequestProperty("Range", "bytes=0-0")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("تحويل غير صالح من الخادم")
                url = URL(URL(url), loc).toString()
                return@repeat
            }
            if (code >= 400) { c.disconnect(); throw NeedsExtractor() }
            t.finalUrl = url
            return c
        }
        throw IOException("تحويلات كثيرة جدًا")
    }

    private fun probe(t: Task) {
        val conn = resolve(t)
        val url = t.finalUrl
        val type = conn.contentType ?: ""
        val lowType = type.lowercase()
        if (lowType.startsWith("text/html") || "mpegurl" in lowType || "dash+xml" in lowType) {
            conn.disconnect()
            throw NeedsExtractor()
        }
        val contentRange = conn.getHeaderField("Content-Range")
        if (conn.responseCode == 206 && contentRange != null) {
            t.totalBytes = contentRange.substringAfter('/', "").trim().toLongOrNull() ?: -1
            t.supportsRanges = t.totalBytes > 0
        } else {
            t.supportsRanges = false
            t.totalBytes = conn.contentLengthLong
        }
        val cd = conn.getHeaderField("Content-Disposition")
        if (cd != null || t.fileName.isBlank() || t.fileName == guessNameFromUrl(t.url)) t.fileName = nameFrom(cd, url)
        val ext = t.fileName.substringAfterLast('.', "").lowercase()
        t.mime = type.substringBefore(';').trim().takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        conn.disconnect()

        if (t.supportsRanges) {
            val minPart = 512L * 1024
            val n = min(connections.toLong(), max(1L, t.totalBytes / minPart)).toInt()
            val size = t.totalBytes / n
            t.segments = (0 until n).map { i ->
                val s = i * size
                val e = if (i == n - 1) t.totalBytes - 1 else (i + 1) * size - 1
                Segment(s, e, 0)
            }
        } else {
            t.segments = listOf(Segment(0, -1, 0))
        }
    }

    private fun mediaDir(t: Task) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "media_${t.id}")

    private fun runMedia(t: Task) {
        t.media = true
        t.segments = emptyList()
        save()
        val dir = mediaDir(t)
        val files = Media.download(ctx, t, dir, connections, Limiter.bytesPerSec)
        var total = 0L
        for (f in files) {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            total += f.length()
            t.savedUri = publishFile(f.name, mime, f).toString()
            t.mime = mime
        }
        t.fileName = if (files.size == 1) files[0].name else "${files.size} ملفات"
        t.totalBytes = total
        t.progressPct = 100f
        dir.deleteRecursively()
    }

    private fun runTask(t: Task) {
        val temp = tempFile(t)
        try {
            if (!t.media && t.segments.isEmpty()) {
                temp.delete()
                try { probe(t) } catch (e: NeedsExtractor) { t.media = true } catch (e: IOException) {
                    // Sites that refuse simple requests usually still work through the extractor.
                    if (t.url.startsWith("http")) t.media = true else throw e
                }
            }
            if (t.media) {
                t.speed = 0
                runMedia(t)
                t.speed = 0
                when {
                    t.removed -> mediaDir(t).deleteRecursively()
                    t.pauseRequested -> t.status = Status.PAUSED
                    else -> t.status = Status.DONE
                }
                return
            }
            save()
            RandomAccessFile(temp, "rw").use { raf ->
                if (t.supportsRanges && raf.length() != t.totalBytes) raf.setLength(t.totalBytes)
            }
            val failure = AtomicReference<Throwable?>(null)
            val threads = t.segments.filter { !it.complete() }.map { seg ->
                Thread({
                    try {
                        runSegment(t, seg, temp)
                    } catch (e: Throwable) {
                        t.stop = true
                        failure.compareAndSet(null, e)
                    }
                    Unit
                }, "seg-${t.id}").apply { start() }
            }
            var last = t.downloaded()
            var lastTime = SystemClock.elapsedRealtime()
            var lastSave = lastTime
            while (threads.any { it.isAlive }) {
                threads.firstOrNull { it.isAlive }?.join(1000)
                val now = SystemClock.elapsedRealtime()
                val d = t.downloaded()
                if (now - lastTime >= 500) {
                    t.speed = (d - last) * 1000 / max(1L, now - lastTime)
                    last = d; lastTime = now
                }
                if (now - lastSave > 3000) { save(); lastSave = now }
            }
            t.speed = 0
            when {
                t.removed -> { temp.delete(); return }
                t.pauseRequested -> t.status = Status.PAUSED
                failure.get() != null -> {
                    t.status = Status.FAILED
                    t.error = failure.get()?.message ?: failure.get()?.javaClass?.simpleName
                }
                else -> {
                    if (t.totalBytes < 0) t.totalBytes = temp.length()
                    t.savedUri = publishFile(t.fileName.ifBlank { "file_${t.id}" }, t.mime, temp).toString()
                    temp.delete()
                    t.status = Status.DONE
                }
            }
        } catch (e: Throwable) {
            t.speed = 0
            if (t.removed) { temp.delete(); if (t.media) mediaDir(t).deleteRecursively(); return }
            if (t.pauseRequested) t.status = Status.PAUSED
            else { t.status = Status.FAILED; t.error = e.message ?: e.javaClass.simpleName }
        } finally {
            schedule()
        }
    }

    private fun runSegment(t: Task, seg: Segment, temp: File) {
        var attempts = 0
        while (!t.stop && !seg.complete()) {
            var conn: HttpURLConnection? = null
            try {
                RandomAccessFile(temp, "rw").use { raf ->
                    if (!t.supportsRanges) { seg.done.set(0); raf.setLength(0) }
                    val from = seg.start + seg.done.get()
                    conn = open(t.finalUrl, t).also { c ->
                        if (t.supportsRanges) {
                            c.setRequestProperty("Range", if (seg.end >= 0) "bytes=$from-${seg.end}" else "bytes=$from-")
                        }
                    }
                    val c = conn!!
                    val code = c.responseCode
                    if (t.supportsRanges && code != 206) throw IOException("الخادم رفض التنزيل المجزأ ($code)")
                    if (code !in 200..299) throw IOException("ردّ الخادم بالخطأ $code")
                    raf.seek(from)
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (!t.stop) {
                            val want = if (seg.end >= 0)
                                min(buf.size.toLong(), seg.end - seg.start - seg.done.get() + 1).toInt()
                            else buf.size
                            if (want <= 0) break
                            val n = input.read(buf, 0, want)
                            if (n < 0) break
                            Limiter.acquire(n)
                            raf.write(buf, 0, n)
                            seg.done.addAndGet(n.toLong())
                            attempts = 0
                        }
                    }
                }
                if (t.stop) return
                if (seg.end < 0) seg.finishedUnknown = true
                else if (!seg.complete()) throw IOException("انقطع الاتصال قبل اكتمال الجزء")
            } catch (e: Exception) {
                if (t.stop) return
                attempts++
                if (attempts > 6) throw e
                Thread.sleep(1500L * attempts)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** Copies the finished file into the public Download/Downloader folder. */
    private fun publishFile(name: String, mime: String, temp: File): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("تعذّر إنشاء الملف في مجلد التنزيلات")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذّر الكتابة في مجلد التنزيلات")
            out.use { o -> temp.inputStream().use { it.copyTo(o, 256 * 1024) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    // ---------------------------------------------------------------- helpers

    fun clean(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(150).ifBlank { "file" }

    fun guessNameFromUrl(url: String): String {
        val seg = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        return clean(if (seg.isNullOrBlank()) url.substringAfter("://").substringBefore('/') else seg)
    }

    private fun nameFrom(cd: String?, url: String): String {
        if (cd != null) {
            Regex("filename\\*\\s*=\\s*(?:[\\w-]+'[^']*')?([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
                val v = runCatching { URLDecoder.decode(m.groupValues[1].trim(' ', '"'), "UTF-8") }.getOrNull()
                if (!v.isNullOrBlank()) return clean(v)
            }
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
                return clean(m.groupValues[1])
            }
        }
        return guessNameFromUrl(url)
    }

    fun fmtBytes(b: Long): String {
        if (b < 0) return "?"
        val units = arrayOf("بايت", "ك.ب", "م.ب", "ج.ب", "ت.ب")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$b ${units[0]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    @Synchronized
    fun totalSpeed(): Long = tasks.filter { it.status == Status.RUNNING }.sumOf { it.speed }
}
