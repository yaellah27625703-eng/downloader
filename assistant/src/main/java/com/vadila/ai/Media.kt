package com.vadila.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object Media {
    lateinit var dir: File
        private set

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "att").apply { mkdirs() }
    }

    // ---- importing -------------------------------------------------------------------------------
    fun nameOf(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun mimeOf(name: String, reported: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val byExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        val r = reported?.lowercase()
        return when {
            r != null && r != "application/octet-stream" && r != "*/*" -> r
            byExt != null -> byExt
            Docs.isTextName(name) -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** Copies a picked file into the app (pictures are resized), and reads the text of documents. */
    fun import(ctx: Context, uri: Uri): Att {
        val name = nameOf(ctx, uri)
        val mime = mimeOf(name, ctx.contentResolver.getType(uri))
        val id = UUID.randomUUID().toString()
        if (mime.startsWith("image/") && mime != "image/svg+xml") {
            val bmp = decodeBitmap(ctx, uri, 2048) ?: throw IllegalStateException("تعذّرت قراءة الصورة")
            val keepPng = mime == "image/png" && bmp.hasAlpha()
            val out = File(dir, "$id." + if (keepPng) "png" else "jpg")
            FileOutputStream(out).use { bmp.compress(if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            val base = name.substringBeforeLast('.')
            return Att(id, base + if (keepPng) ".png" else ".jpg", if (keepPng) "image/png" else "image/jpeg", out.path, out.length())
        }
        val ext = name.substringAfterLast('.', "bin").take(8)
        val out = File(dir, "$id.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it, 1 shl 16) } }
            ?: throw IllegalStateException("تعذّر فتح الملف")
        val a = Att(id, name, mime, out.path, out.length())
        if (!a.isImage && !a.isAudio && !a.isVideo && !a.isPdf) a.text = Docs.extract(out, name)
        return a
    }

    fun fromFile(f: File, name: String, mime: String): Att = Att(UUID.randomUUID().toString(), name, mime, f.path, f.length())

    fun decodeBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxSide || o.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rot != 0 || scale < 1f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rot != 0) m.postRotate(rot.toFloat())
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }

    fun decodeFile(path: String, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxSide || o.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- what Gemini can take directly ------------------------------------------------------------
    private val GEMINI_AUDIO = mapOf(
        "audio/wav" to "audio/wav", "audio/x-wav" to "audio/wav", "audio/wave" to "audio/wav",
        "audio/mpeg" to "audio/mp3", "audio/mp3" to "audio/mp3", "audio/aac" to "audio/aac", "audio/x-aac" to "audio/aac",
        "audio/ogg" to "audio/ogg", "audio/opus" to "audio/ogg", "audio/flac" to "audio/flac", "audio/x-flac" to "audio/flac",
        "audio/aiff" to "audio/aiff", "audio/x-aiff" to "audio/aiff",
    )
    private val GEMINI_VIDEO = mapOf(
        "video/mp4" to "video/mp4", "video/mpeg" to "video/mpeg", "video/quicktime" to "video/mov", "video/mov" to "video/mov",
        "video/x-msvideo" to "video/avi", "video/avi" to "video/avi", "video/x-flv" to "video/x-flv", "video/webm" to "video/webm",
        "video/x-ms-wmv" to "video/wmv", "video/3gpp" to "video/3gpp", "video/mpg" to "video/mpg",
    )

    /** (file, mime) to send to Gemini as media, or null when the attachment is not media. */
    fun forGemini(a: Att): Pair<File, String>? = when {
        a.isImage -> a.file to a.mime
        a.isPdf -> a.file to "application/pdf"
        a.isAudio -> GEMINI_AUDIO[a.mime]?.let { a.file to it } ?: (wav16k(a) to "audio/wav")
        a.isVideo -> a.file to (GEMINI_VIDEO[a.mime] ?: "video/mp4")
        else -> null
    }

    // ---- PDF pages and video frames as pictures (for models that only see images) -------------------
    fun pdfPages(a: Att, max: Int): List<File> {
        val out = ArrayList<File>()
        for (i in 0 until max) {
            val f = File(a.path + ".p$i.jpg")
            if (f.exists()) out.add(f) else break
        }
        if (out.isNotEmpty()) return out
        try {
            ParcelFileDescriptor.open(a.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until minOf(max, r.pageCount)) {
                        r.openPage(i).use { page ->
                            val w = 1240
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtMost(2400)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val f = File(a.path + ".p$i.jpg")
                            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                            bmp.recycle()
                            out.add(f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun videoFrames(a: Att, n: Int): List<File> {
        val cached = (0 until n).map { File(a.path + ".f$it.jpg") }
        if (cached.all { it.exists() }) return cached
        val out = ArrayList<File>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(a.path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            for (i in 0 until n) {
                val t = if (durMs > 0) (durMs * 1000 * (i + 0.5) / n).toLong() else i * 1_000_000L
                val frame = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val s = minOf(1f, 960f / maxOf(frame.width, frame.height))
                val bmp = if (s < 1f) Bitmap.createScaledBitmap(frame, (frame.width * s).toInt(), (frame.height * s).toInt(), true) else frame
                val f = File(a.path + ".f$i.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (bmp != frame) bmp.recycle()
                frame.recycle()
                out.add(f)
            }
        } catch (e: Exception) {
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ---- audio --------------------------------------------------------------------------------------
    /** The sound of an audio or video file as 16 kHz mono WAV (cached next to it). */
    fun wav16k(a: Att): File {
        val out = File(a.path + ".16k.wav")
        if (out.exists() && out.length() > 44) return out
        if (!decodeToWav(a.path, out, 16000)) throw ApiError(Kind.BAD, 0, "تعذّرت قراءة الصوت في «${a.name}»")
        return out
    }

    fun writeWavHeader(raf: RandomAccessFile, dataBytes: Long, rate: Int) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(b.array())
    }

    /** Decodes any audio track (also of a video) to mono 16-bit WAV at [rate]. */
    fun decodeToWav(src: String, dst: File, rate: Int): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val tmp = File(dst.path + ".raw")
        try {
            ex.setDataSource(src)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) return false
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { raw ->
                val tmpBytes = ByteArray(8192)
                while (!outDone) {
                    if (!inDone) {
                        val ii = c.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = c.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = c.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = c.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (oi >= 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        // mix to mono 16-bit at the source rate
                        val frames = if (isFloat) info.size / 4 / channels else info.size / 2 / channels
                        var k = 0
                        for (fr in 0 until frames) {
                            var sum = 0f
                            for (ch in 0 until channels) {
                                sum += if (isFloat) ob.float * 32767f else ob.short.toFloat()
                            }
                            val v = (sum / channels).toInt().coerceIn(-32768, 32767)
                            tmpBytes[k++] = (v and 0xFF).toByte()
                            tmpBytes[k++] = ((v shr 8) and 0xFF).toByte()
                            if (k == tmpBytes.size) {
                                raw.write(tmpBytes, 0, k)
                                k = 0
                            }
                        }
                        if (k > 0) raw.write(tmpBytes, 0, k)
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            resampleToWav(tmp, srcRate, dst, rate)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
            }
            ex.release()
            tmp.delete()
        }
    }

    private fun resampleToWav(raw: File, srcRate: Int, dst: File, rate: Int) {
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(0)
            out.write(ByteArray(44))
            val total = raw.length() / 2
            val ratio = srcRate.toDouble() / rate
            val outCount = (total / ratio).toLong()
            DataInputStream(raw.inputStream().buffered(1 shl 16)).use { input ->
                var srcIndex = 0L
                var s0 = 0
                var s1 = 0
                fun next(): Int {
                    val lo = input.read()
                    val hi = input.read()
                    if (lo < 0 || hi < 0) return 0
                    return ((hi shl 8) or lo).toShort().toInt()
                }
                if (total > 0) {
                    s0 = next()
                    s1 = if (total > 1) next() else s0
                }
                val buf = ByteArray(8192)
                var bk = 0
                for (j in 0 until outCount) {
                    val pos = j * ratio
                    val ip = pos.toLong()
                    while (srcIndex < ip && srcIndex + 2 < total) {
                        s0 = s1
                        s1 = next()
                        srcIndex++
                    }
                    val frac = pos - ip
                    val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
                    buf[bk++] = (v and 0xFF).toByte()
                    buf[bk++] = ((v shr 8) and 0xFF).toByte()
                    if (bk == buf.size) {
                        out.write(buf, 0, bk)
                        bk = 0
                    }
                }
                if (bk > 0) out.write(buf, 0, bk)
            }
            writeWavHeader(out, out.length() - 44, rate)
        }
    }

    /** Splits a 16 kHz mono WAV into pieces of at most [seconds]. */
    fun splitWav(wav: File, seconds: Int): List<File> {
        val bytesPer = seconds * 32000L
        val data = wav.length() - 44
        if (data <= bytesPer) return listOf(wav)
        val out = ArrayList<File>()
        RandomAccessFile(wav, "r").use { input ->
            var off = 0L
            var k = 0
            while (off < data) {
                val n = minOf(bytesPer, data - off)
                val f = File(wav.path + ".part$k.wav")
                RandomAccessFile(f, "rw").use { o ->
                    o.setLength(0)
                    o.write(ByteArray(44))
                    input.seek(44 + off)
                    val buf = ByteArray(1 shl 16)
                    var left = n
                    while (left > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r <= 0) break
                        o.write(buf, 0, r)
                        left -= r
                    }
                    writeWavHeader(o, o.length() - 44, 16000)
                }
                out.add(f)
                off += n
                k++
            }
        }
        return out
    }

    // ---- saving to the phone's gallery / downloads ---------------------------------------------------
    fun saveToGallery(ctx: Context, bytes: ByteArray, mime: String, name: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vadila AI")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }

    fun saveToDownloads(ctx: Context, file: File, mime: String, name: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vadila AI")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        return uri
    }

    /** A camera photo target in the gallery. */
    fun newCameraUri(ctx: Context): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "VadilaAI_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vadila AI")
        }
        return ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
    }
}

/** Speech to text for models that cannot listen. */
object Transcriber {
    fun transcribe(a: Att, cancel: Cancel): String {
        var last: ApiError? = null
        if (Prefs.hasKey("groq") && !Router.cooling("groq:whisper")) {
            try {
                val parts = if (a.isAudio && a.size < 24L * 1024 * 1024 && a.mime in setOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/flac", "audio/ogg", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/webm"))
                    listOf(a.file to a.mime) else Media.splitWav(Media.wav16k(a), 600).map { it to "audio/wav" }
                return parts.joinToString(" ") { (f, m) -> OaiApi.groqTranscribe(f, m, cancel) }.trim()
            } catch (e: ApiError) {
                if (e.kind == Kind.CANCELLED) throw e
                last = e
                Router.coolDown("groq:whisper", e)
            }
        }
        if (Prefs.hasKey("gemini")) {
            val m = Media.forGemini(a) ?: (Media.wav16k(a) to "audio/wav")
            return GeminiApi.transcribe(m.first, m.second, cancel)
        }
        throw last ?: ApiError(Kind.GONE, 0, "تحويل الكلام إلى نص يحتاج مفتاح Groq أو Gemini (كلاهما مجاني) — أضفه من الإعدادات.")
    }
}
