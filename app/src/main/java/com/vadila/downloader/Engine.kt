package com.vadila.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

class Segment(val start: Long, end: Long, done: Long) {
    /** Last byte (inclusive). Shrinks when another connection takes over half of this part. */
    @Volatile var end: Long = end
    val done = AtomicLong(done)
    @Volatile var finishedUnknown = false
    @Volatile var active = false
    fun remaining(): Long = if (end >= 0) end - start - done.get() + 1 else Long.MAX_VALUE
    fun complete(): Boolean =
        if (end >= 0) done.get() >= end - start + 1 else finishedUnknown
}

/** An HTTP error answer from the server (not a network problem). */
class HttpError(val code: Int, msg: String) : IOException(msg)

/** Thrown inside a connection to drop it and open a fresh one right away. */
class Reconnect : Exception()

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
    /** Live number of open connections and the ceiling the server tolerates. */
    @Volatile var activeConns = 0
    @Volatile var connCap = 64
    /** Failed because the internet dropped: resumed automatically when it returns. */
    @Volatile var netFailed = false

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
            if (t.status == Status.RUNNING || t.status == Status.QUEUED) t.status = Status.QUEUED
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
        get() = prefs.getInt("connections2", 16)
        set(v) { prefs.edit().putInt("connections2", v.coerceIn(1, 32)).apply() }
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
        watchNetwork()
        // Downloads interrupted by the app being closed or killed carry on by themselves.
        if (tasks.any { it.status == Status.QUEUED }) schedule()
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

    fun networkUp(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchNetwork() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    resumeNetworkFailed()
                }
            })
        }
    }

    @Synchronized
    fun resumeNetworkFailed() {
        var any = false
        for (t in tasks) if (t.status == Status.FAILED && t.netFailed) {
            t.status = Status.QUEUED; t.error = null; t.netFailed = false; any = true
        }
        if (any) schedule()
    }

    /** Waits (without giving up) while there is no internet connection. */
    private fun waitForNetwork(t: Task) {
        if (networkUp()) return
        while (!t.stop && !networkUp()) {
            t.statusLine = "بانتظار عودة الإنترنت…"
            t.speed = 0
            Thread.sleep(1000)
        }
        t.statusLine = ""
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
    var useAria2: Boolean
        get() = prefs.getBoolean("aria2", true)
        set(v) { prefs.edit().putBoolean("aria2", v).apply() }
    var bubble: Boolean
        get() = prefs.getBoolean("bubble", false)
        set(v) { prefs.edit().putBoolean("bubble", v).apply() }
    var askedBattery: Boolean
        get() = prefs.getBoolean("askedBattery", false)
        set(v) { prefs.edit().putBoolean("askedBattery", v).apply() }
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
                t.connCap = 64
                t.netFailed = false
                if (t.status != Status.DONE) t.statusLine = ""
                active++
                Thread({ runTask(t) }, "task-${t.id}").start()
            }
        }
        save()
        if (activeCount() > 0 && !DownloadService.running) DownloadService.start(ctx)
    }

    // ---------------------------------------------------------------- download

    private fun open(url: String, t: Task, follow: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = follow
            connectTimeout = 12_000
            readTimeout = 15_000
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
        if (conn.responseCode == 206) {
            // The probe asked for 1 byte: drain it so the connection can be reused (saves a handshake).
            runCatching { conn.inputStream.use { it.readBytes() } }.onFailure { conn.disconnect() }
        } else conn.disconnect()

        if (t.supportsRanges) {
            val minPart = 256L * 1024
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
        waitForNetwork(t)
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
            val workers = ArrayList<Thread>()
            fun spawn(seg: Segment) {
                seg.active = true
                val th = Thread({
                    try {
                        runSegment(t, seg, temp)
                    } catch (e: Throwable) {
                        if (!t.stop) { failure.compareAndSet(null, e); t.stop = true }
                    } finally {
                        seg.active = false
                    }
                    Unit
                }, "seg-${t.id}")
                workers.add(th)
                th.start()
            }
            for (seg in t.segments) if (!seg.complete()) spawn(seg)

            var last = t.downloaded()
            var lastTime = SystemClock.elapsedRealtime()
            var lastSave = lastTime
            while (true) {
                workers.removeAll { !it.isAlive }
                if (!t.stop && t.supportsRanges) {
                    // Work stealing: every free connection takes over half of the biggest remaining part,
                    // so all connections stay busy until the very last byte.
                    val target = min(connections, t.connCap)
                    var guard = 0
                    while (workers.size < target && guard++ < 64) {
                        val ns = splitLargest(t) ?: break
                        spawn(ns)
                    }
                }
                t.activeConns = workers.size
                if (workers.isEmpty()) break
                Thread.sleep(250)
                val now = SystemClock.elapsedRealtime()
                if (now - lastTime >= 1000) {
                    val d = t.downloaded()
                    val inst = (d - last) * 1000 / max(1L, now - lastTime)
                    // Smoothed speed for a steady display.
                    t.speed = if (t.speed == 0L) inst else (t.speed * 2 + inst) / 3
                    last = d; lastTime = now
                }
                if (now - lastSave > 3000) { save(); lastSave = now }
            }
            t.activeConns = 0
            if (!t.stop && failure.get() == null && t.segments.any { !it.complete() }) {
                failure.set(IOException("لم يكتمل التنزيل"))
            }
            t.speed = 0
            when {
                t.removed -> { temp.delete(); return }
                t.pauseRequested -> t.status = Status.PAUSED
                failure.get() != null -> {
                    val f = failure.get()
                    t.status = Status.FAILED
                    t.error = f?.message ?: f?.javaClass?.simpleName
                    t.netFailed = !networkUp() || (f is IOException && f !is HttpError)
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
            else {
                t.status = Status.FAILED
                t.error = e.message ?: e.javaClass.simpleName
                t.netFailed = !networkUp()
            }
        } finally {
            schedule()
        }
    }

    /** Takes over the second half of the largest part still being downloaded. */
    private fun splitLargest(t: Task): Segment? {
        synchronized(t) {
            val victim = t.segments
                .filter { it.end >= 0 && it.active && !it.complete() }
                .maxByOrNull { it.remaining() } ?: return null
            val rem = victim.remaining()
            if (rem < 2 * STEAL_MIN) return null
            val mid = victim.start + victim.done.get() + rem / 2
            val ns = Segment(mid, victim.end, 0)
            victim.end = mid - 1
            t.segments = t.segments + ns
            return ns
        }
    }

    private const val STEAL_MIN = 512L * 1024
    private const val BUF = 256 * 1024

    private fun runSegment(t: Task, seg: Segment, temp: File) {
        var attempts = 0
        val buf = ByteArray(BUF)
        while (!t.stop && !seg.complete()) {
            waitForNetwork(t)
            if (t.stop) return
            var conn: HttpURLConnection? = null
            var reusable = false
            try {
                RandomAccessFile(temp, "rw").use { raf ->
                    if (!t.supportsRanges) { seg.done.set(0); raf.setLength(0) }
                    val from = seg.start + seg.done.get()
                    val c = open(t.finalUrl, t)
                    conn = c
                    if (t.supportsRanges) {
                        c.setRequestProperty("Range", if (seg.end >= 0) "bytes=$from-${seg.end}" else "bytes=$from-")
                    }
                    val code = c.responseCode
                    if (code == 429 || code == 503) {
                        // Server says "too many connections": use fewer from now on.
                        t.connCap = max(2, min(t.connCap, t.activeConns - 1))
                        throw IOException("الخادم مشغول ($code)")
                    }
                    if (t.supportsRanges && code != 206) throw HttpError(code, "الخادم رفض التنزيل المجزأ ($code)")
                    if (code !in 200..299) throw HttpError(code, "ردّ الخادم بالخطأ $code")
                    raf.seek(from)
                    val input = c.inputStream
                    var overshoot = false
                    var ended = false
                    var winBytes = 0L
                    var winStart = SystemClock.elapsedRealtime()
                    var slow = 0
                    while (!t.stop) {
                        val want = if (seg.end >= 0) min(buf.size.toLong(), seg.remaining()).toInt() else buf.size
                        if (want <= 0) break
                        val n0 = input.read(buf, 0, want)
                        if (n0 < 0) { ended = true; break }
                        // The part may have been shortened meanwhile: never write past its end.
                        val n = if (seg.end >= 0) min(n0.toLong(), seg.remaining()).toInt() else n0
                        if (n > 0) {
                            Limiter.acquire(n)
                            raf.write(buf, 0, n)
                            seg.done.addAndGet(n.toLong())
                        }
                        if (n < n0) { overshoot = true; break }
                        attempts = 0

                        // Drop a connection that stays far slower than the others and open a fresh one.
                        winBytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - winStart >= 4000) {
                            val mine = winBytes * 1000 / (now - winStart)
                            val avg = t.speed / max(1, t.activeConns)
                            slow = if (t.activeConns > 1 && avg > 32 * 1024 && mine < avg / 4 &&
                                seg.remaining() > 2L * 1024 * 1024 && Limiter.bytesPerSec <= 0
                            ) slow + 1 else 0
                            winBytes = 0
                            winStart = now
                            if (slow >= 2) throw Reconnect()
                        }
                    }
                    if (t.stop) return
                    if (seg.end < 0) {
                        if (ended) seg.finishedUnknown = true
                    }
                    reusable = !overshoot && (ended || seg.complete())
                    if (reusable) input.close() // return the socket to the keep-alive pool
                }
                if (t.stop) return
                if (!seg.complete()) throw IOException("انقطع الاتصال قبل اكتمال الجزء")
            } catch (e: Reconnect) {
                // open a new connection immediately
            } catch (e: Exception) {
                if (t.stop) return
                attempts++
                val limit = if (e is HttpError) 6 else 40
                if (attempts > limit) throw e
                Thread.sleep(min(400L * attempts, 6000L))
            } finally {
                if (!reusable) conn?.disconnect()
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
