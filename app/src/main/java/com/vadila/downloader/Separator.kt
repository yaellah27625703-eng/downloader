package com.vadila.downloader

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Streaming source separation: 44.1 kHz stereo PCM16 in → music + effects (44.1 kHz stereo PCM16) and the
 * voices (16 kHz mono PCM16) out, a block at a time so a whole film never has to fit in memory.
 * Each block is separated with half a second of real audio on both sides, which is thrown away: the
 * separator produces spikes at the very edges of its input, and the context also keeps blocks seamless.
 */
object Separator {
    const val SR = 44100
    const val PAD = 22050          // 0.5 s (a multiple of 441, like CORE, so 44.1 → 16 kHz stays exact)
    const val CORE = 463050        // 10.5 s kept per block; the 11.5 s block fits one Spleeter window (11.9 s)

    /** Returns the number of stereo frames processed. Throws IOException if the separator fails. */
    fun run(h: Long, input: InputStream, background: OutputStream, vocals16: OutputStream,
            progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): Long {
        val size = PAD + CORE + PAD
        val l = FloatArray(size)
        val r = FloatArray(size)
        val out = Array(4) { FloatArray(size + 8192) }
        val raw = ByteArray(size * 4)
        val bg = ByteArray(CORE * 4)
        val mono = ShortArray(CORE * 160 / 441 + 64)
        val monoBytes = ByteArray(mono.size * 2)
        var filled = PAD               // [0, PAD) is the left context: silence before the first block
        var eof = false
        var done = 0L
        while (true) {
            if (cancelled()) throw IOException("أُلغيت")
            if (!eof && filled < size) {
                val want = (size - filled) * 4
                val got = readFully(input, raw, want)
                val frames = got / 4
                for (i in 0 until frames) {
                    val o = i * 4
                    l[filled + i] = ((raw[o].toInt() and 0xff) or (raw[o + 1].toInt() shl 8)).toShort() / 32768f
                    r[filled + i] = ((raw[o + 2].toInt() and 0xff) or (raw[o + 3].toInt() shl 8)).toShort() / 32768f
                }
                filled += frames
                if (got < want) eof = true
            }
            val avail = filled - PAD
            if (avail <= 0) break
            val core = min(avail, CORE)
            val last = eof && avail <= CORE
            if (eof) java.util.Arrays.fill(l, filled, size, 0f).also { java.util.Arrays.fill(r, filled, size, 0f) }
            val block = PAD + core + PAD
            val m = Sherpa.sepProcess(h, l, r, block, SR, out)
            if (m < PAD + core) throw IOException("تعذّر فصل الأصوات")
            for (i in 0 until core) {
                val a = pcm(out[2][PAD + i])
                val b = pcm(out[3][PAD + i])
                val o = i * 4
                bg[o] = a.toByte(); bg[o + 1] = (a shr 8).toByte(); bg[o + 2] = b.toByte(); bg[o + 3] = (b shr 8).toByte()
            }
            background.write(bg, 0, core * 4)
            val k = Sherpa.toMono16k(out[0], out[1], PAD, core, SR, mono)
            for (i in 0 until k) { monoBytes[2 * i] = mono[i].toByte(); monoBytes[2 * i + 1] = (mono[i].toInt() shr 8).toByte() }
            vocals16.write(monoBytes, 0, k * 2)
            done += core
            progress(done)
            if (last) break
            // slide: the end of this block becomes the left context of the next one
            val keep = filled - core
            System.arraycopy(l, core, l, 0, keep)
            System.arraycopy(r, core, r, 0, keep)
            filled = keep
        }
        return done
    }

    /**
     * The separated voices (16 kHz mono PCM16) with every dubbed line muted (±120 ms, 60 ms soft edges):
     * what stays is laughter, screams, breathing and songs, which belong in the dubbed film too.
     */
    fun residual(vocals16: InputStream, out: OutputStream, spans: List<LongArray>, totalMs: Long) {
        val n = (totalMs / 10 + 2).toInt()                 // gain at 100 Hz
        val m = FloatArray(n)
        for (s in spans) {
            val a = ((s[0] - 120) / 10).toInt().coerceIn(0, n)
            val b = ((s[1] + 129) / 10).toInt().coerceIn(0, n)
            for (i in a until b) m[i] = 1f
        }
        val g = FloatArray(n) { i ->
            var sum = 0f
            var c = 0
            for (k in i - 3 until i + 3) if (k in 0 until n) { sum += m[k]; c++ }
            1f - sum / c
        }
        val buf = ByteArray(16000 * 2)
        var pos = 0L                                         // samples written so far
        while (true) {
            val got = readFully(vocals16, buf, buf.size) and 1.inv()
            if (got <= 0) break
            for (o in 0 until got step 2) {
                val s = ((buf[o].toInt() and 0xff) or (buf[o + 1].toInt() shl 8)).toShort()
                val f = pos / 160.0                          // position on the 100 Hz grid
                val i = f.toInt().coerceAtMost(n - 2)
                val gain = g[i] + (g[i + 1] - g[i]) * (f - i).toFloat().coerceIn(0f, 1f)
                val v = (s * gain).toInt()
                buf[o] = v.toByte(); buf[o + 1] = (v shr 8).toByte()
                pos++
            }
            out.write(buf, 0, got)
            if (got < buf.size) break
        }
    }

    private fun pcm(v: Float): Int {
        val x = (v * 32767f).toInt()
        return if (x > 32767) 32767 else if (x < -32768) -32768 else x
    }

    private fun readFully(inp: InputStream, b: ByteArray, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = inp.read(b, got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
