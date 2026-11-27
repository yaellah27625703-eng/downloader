package com.vadila.downloader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Who speaks each line, and which Arabic voice each character gets.
 * Pure Kotlin (no Android), so it is unit-tested on the computer.
 */
object Voices {

    // ------------------------------------------------------------------ speakers

    /**
     * Groups lines by voice fingerprint.
     * Strict average-linkage clustering first (two different actors are never merged), then lines left in
     * tiny clusters join the closest real actor only if they clearly sound like him/her, so a minor
     * character with one or two lines keeps his own voice. Lines without a fingerprint take the speaker
     * closest in time. Returns labels numbered by amount of speech (0 = main character).
     */
    fun cluster(embs: List<FloatArray?>, durs: DoubleArray, starts: DoubleArray, threshold: Double = 0.6,
                minDur: Double = 1.0, coreMin: Int = 3, attach: Double = 0.45): IntArray {
        val n = embs.size
        if (n == 0) return IntArray(0)
        val unit = embs.map { e -> e?.let { normalize(it) } }
        var idx = (0 until n).filter { unit[it] != null && durs[it] >= minDur }
        if (idx.size < 2) idx = (0 until n).filter { unit[it] != null }
        val labels = IntArray(n) { -1 }
        if (idx.isEmpty()) return IntArray(n)
        if (idx.size == 1) labels[idx[0]] = 0 else {
            val x = idx.map { unit[it]!! }
            var lab = averageLinkage(x, threshold)
            lab = attachToCores(lab, x, coreMin, attach)
            for ((k, i) in idx.withIndex()) labels[i] = lab[k]
        }
        // centroids of the clusters found so far
        val cent = HashMap<Int, FloatArray>()
        for (l in labels.filter { it >= 0 }.distinct()) {
            val members = (0 until n).filter { labels[it] == l }.map { unit[it]!! }
            cent[l] = normalize(mean(members))
        }
        for (i in 0 until n) {
            val e = unit[i]
            if (labels[i] < 0 && e != null) labels[i] = cent.maxByOrNull { dot(e, it.value) }!!.key
        }
        val known = (0 until n).filter { labels[it] >= 0 }
        for (i in 0 until n) if (labels[i] < 0) labels[i] = labels[known.minByOrNull { abs(starts[it] - starts[i]) }!!]
        val total = HashMap<Int, Double>()
        for (i in 0 until n) total[labels[i]] = (total[labels[i]] ?: 0.0) + durs[i]
        val order = total.keys.sortedByDescending { total[it] }
        val remap = order.withIndex().associate { it.value to it.index }
        return IntArray(n) { remap.getValue(labels[it]) }
    }

    /**
     * Speakers for the phone engine, where every group gets its own synthetic voice: a character split in two
     * would change voice in the middle of the film, which is worse than two minor characters sharing a voice.
     * So the grouping is looser (calibrated on CAM++ fingerprints against pyannote diarization), and a group
     * that mixes clearly male and clearly female lines is split again.
     */
    fun speakers(embs: List<FloatArray?>, durs: DoubleArray, starts: DoubleArray, pitches: DoubleArray): IntArray {
        val lab = cluster(embs, durs, starts, threshold = 0.75, attach = 0.35)
        var next = (lab.maxOrNull() ?: -1) + 1
        for (g in lab.toSet()) {
            val members = lab.indices.filter { lab[it] == g }
            val low = members.filter { pitches[it] in 1.0..150.0 }
            val high = members.filter { pitches[it] >= 180.0 }
            val decisive = low.size + high.size
            if (low.size < 2 || high.size < 2 || low.size < decisive / 4 || high.size < decisive / 4) continue
            // mixed group: men and women merged by mistake → split by voice type
            val lowC = normalize(meanOf(low.mapNotNull { embs[it] }.map { normalize(it) }) ?: continue)
            val highC = normalize(meanOf(high.mapNotNull { embs[it] }.map { normalize(it) }) ?: continue)
            for (i in members) {
                val p = pitches[i]
                val toHigh = when {
                    p >= 180.0 -> true
                    p in 1.0..150.0 -> false
                    embs[i] != null -> dot(normalize(embs[i]!!), highC) > dot(normalize(embs[i]!!), lowC)
                    else -> false
                }
                if (toHigh) lab[i] = next
            }
            next++
        }
        // renumber by amount of speech
        val total = HashMap<Int, Double>()
        for (i in lab.indices) total[lab[i]] = (total[lab[i]] ?: 0.0) + durs[i]
        val remap = total.keys.sortedByDescending { total[it] }.withIndex().associate { it.value to it.index }
        return IntArray(lab.size) { remap.getValue(lab[it]) }
    }

    private fun meanOf(v: List<FloatArray>): FloatArray? = if (v.isEmpty()) null else mean(v)

    /** Average-linkage agglomerative clustering on cosine distance, cut at [threshold] (nearest-neighbour chain, O(n²)). */
    fun averageLinkage(x: List<FloatArray>, threshold: Double): IntArray {
        val n = x.size
        val d = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val v = (1.0 - dot(x[i], x[j])).toFloat()
            d[i * n + j] = v; d[j * n + i] = v
        }
        val size = IntArray(n) { 1 }
        val active = BooleanArray(n) { true }
        var left = n
        val merges = ArrayList<Triple<Int, Int, Float>>()
        val chain = ArrayList<Int>()
        while (left > 1) {
            if (chain.isEmpty()) chain.add((0 until n).first { active[it] })
            val a = chain.last()
            val prev = if (chain.size >= 2) chain[chain.size - 2] else -1
            var b = -1
            var best = Float.MAX_VALUE
            for (k in 0 until n) {
                if (!active[k] || k == a) continue
                val v = d[a * n + k]
                if (v < best || (v == best && k == prev)) { best = v; b = k }
            }
            if (b == prev) {
                chain.removeAt(chain.size - 1); chain.removeAt(chain.size - 1)
                merges.add(Triple(a, b, best))
                // Lance–Williams update for average linkage: the new cluster keeps index a
                for (k in 0 until n) {
                    if (!active[k] || k == a || k == b) continue
                    val v = (size[a] * d[a * n + k] + size[b] * d[b * n + k]) / (size[a] + size[b])
                    d[a * n + k] = v; d[k * n + a] = v
                }
                size[a] += size[b]
                active[b] = false
                left--
            } else chain.add(b)
        }
        // cut the tree: apply the merges closer than the threshold (average linkage has no inversions)
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for ((a, b, v) in merges.sortedBy { it.third }) if (v <= threshold) parent[find(b)] = find(a)
        val ids = HashMap<Int, Int>()
        return IntArray(n) { ids.getOrPut(find(it)) { ids.size } }
    }

    fun attachToCores(lab: IntArray, x: List<FloatArray>, coreMin: Int, attach: Double): IntArray {
        val out = lab.copyOf()
        repeat(2) {
            val counts = out.toList().groupingBy { it }.eachCount()
            val cores = counts.filter { it.value >= coreMin }.keys.toList()
            if (cores.isEmpty()) return out
            val cent = cores.map { c -> normalize(mean(x.indices.filter { out[it] == c }.map { x[it] })) }
            for (i in x.indices) {
                if (out[i] in cores) continue
                var bestK = -1
                var best = -2.0
                for ((k, c) in cent.withIndex()) { val s = dot(x[i], c); if (s > best) { best = s; bestK = k } }
                if (best >= attach) out[i] = cores[bestK]
            }
        }
        return out
    }

    // ------------------------------------------------------------------ pitch (gender)

    /**
     * Median pitch (Hz) of the voiced parts of a 16 kHz clip, or 0 when there is none.
     * Normalized autocorrelation over 40 ms frames, 70–420 Hz.
     */
    fun pitch(x: FloatArray, sr: Int = 16000): Double {
        val frame = (0.04 * sr).toInt()
        val hop = frame / 2
        val minLag = sr / 420
        val maxLag = sr / 70
        if (x.size < frame + maxLag) return 0.0
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-4f) return 0.0
        val found = ArrayList<Double>()
        var p = 0
        while (p + frame + maxLag <= x.size) {
            var e = 0.0
            for (i in p until p + frame) e += x[i] * x[i]
            if (sqrt(e / frame) > peak * 0.05) {
                var bestLag = -1
                var bestR = 0.0
                for (lag in minLag..maxLag) {
                    var num = 0.0
                    var e2 = 0.0
                    for (i in p until p + frame) { num += x[i] * x[i + lag]; e2 += x[i + lag] * x[i + lag] }
                    val r = num / (sqrt(e * e2) + 1e-12)
                    if (r > bestR) { bestR = r; bestLag = lag }
                }
                // prefer the shortest lag that is almost as good (avoids octave errors)
                if (bestLag > 0 && bestR > 0.6) {
                    var lag = bestLag
                    for (div in 2..4) {
                        val l2 = bestLag / div
                        if (l2 < minLag) break
                        var num = 0.0; var e2 = 0.0
                        for (i in p until p + frame) { num += x[i] * x[i + l2]; e2 += x[i + l2] * x[i + l2] }
                        if (num / (sqrt(e * e2) + 1e-12) > bestR * 0.92) lag = l2
                    }
                    found.add(sr.toDouble() / lag)
                }
            }
            p += hop
        }
        if (found.size < 3) return 0.0
        found.sort()
        return found[found.size / 2]
    }

    enum class Kind { MALE, FEMALE, CHILD }

    fun kindOf(f0: Double): Kind = when {
        f0 <= 0 -> Kind.MALE
        f0 < 165 -> Kind.MALE
        f0 < 265 -> Kind.FEMALE
        else -> Kind.CHILD
    }

    // ------------------------------------------------------------------ voices

    class Speaker(val id: Int, val f0: Double, val seconds: Double)
    class PhoneVoice(val name: String, val f0: Double)
    class Pick(val voice: String, val pitch: Float, val rate: Float)

    /**
     * Gives every character a different Arabic voice that suits him/her: men get male voices, women and
     * children female ones (children a little higher). When there are more characters than voices,
     * the same voice is reused with a clearly different pitch, so no two characters sound alike.
     * [preferred] (the voice the user picked) goes to the main character when it fits.
     */
    fun assign(speakers: List<Speaker>, voices: List<PhoneVoice>, preferred: String = ""): Map<Int, Pick> {
        val out = LinkedHashMap<Int, Pick>()
        if (voices.isEmpty()) return out
        val male = voices.filter { kindOf(it.f0) == Kind.MALE }
        val female = voices.filter { kindOf(it.f0) != Kind.MALE }
        val shifts = listOf(1.0f, 0.88f, 1.12f, 0.8f, 1.22f, 0.94f, 1.06f, 0.74f, 1.3f)
        val used = HashMap<String, MutableList<Float>>()       // voice → pitches already given to characters
        for (s in speakers.sortedByDescending { it.seconds }) {
            val kind = kindOf(s.f0)
            val pool = if (kind == Kind.MALE) male else female
            val (cands, cross) = if (pool.isNotEmpty()) pool to false else voices to true
            val chosen = (if (out.isEmpty() && preferred.isNotEmpty()) cands.firstOrNull { it.name == preferred } else null)
                ?: cands.minByOrNull { (used[it.name]?.size ?: 0) * 1000 + abs(it.f0 - (if (s.f0 > 0) s.f0 else it.f0)) }!!
            val taken = used.getOrPut(chosen.name) { mutableListOf() }
            // follow the actor's own pitch a little
            var base = if (s.f0 > 0 && chosen.f0 > 0) sqrt(s.f0 / chosen.f0).toFloat().coerceIn(0.85f, 1.2f) else 1f
            if (cross) base *= if (kind == Kind.MALE) 0.72f else 1.35f      // no voice of that gender on the phone
            if (kind == Kind.CHILD) base *= 1.18f
            val (lo, hi) = when {
                cross && kind == Kind.MALE -> 0.55f to 0.92f
                cross -> 1.1f to 1.8f
                kind == Kind.CHILD -> 1.05f to 1.6f
                else -> 0.7f to 1.4f
            }
            // a reused voice gets a pitch clearly apart from the characters that already have it
            val options = shifts.map { (base * it).coerceIn(lo, hi) }
            val pitch = options.firstOrNull { p -> taken.all { abs(it - p) >= 0.07f } }
                ?: options.maxByOrNull { p -> taken.minOfOrNull { abs(it - p) } ?: 1f }!!
            val rate = when (taken.size) { 0 -> 1f; 1 -> 1.05f; 2 -> 0.95f; else -> if (taken.size % 2 == 1) 1.08f else 0.92f }
            taken.add(pitch)
            out[s.id] = Pick(chosen.name, pitch, rate)
        }
        return out
    }

    // ------------------------------------------------------------------ small vector helpers

    fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    fun normalize(a: FloatArray): FloatArray {
        val nrm = sqrt(dot(a, a)) + 1e-9
        return FloatArray(a.size) { (a[it] / nrm).toFloat() }
    }

    private fun mean(v: List<FloatArray>): FloatArray {
        val m = FloatArray(v[0].size)
        for (x in v) for (i in m.indices) m[i] += x[i]
        for (i in m.indices) m[i] /= v.size
        return m
    }
}
