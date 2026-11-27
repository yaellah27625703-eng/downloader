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
     * that mixes lines a trained classifier hears clearly as a man with lines it hears clearly as a woman is
     * split again. [sex] per line: [speech, man, woman, child] probabilities (AudioSet), or null.
     */
    fun speakers(embs: List<FloatArray?>, durs: DoubleArray, starts: DoubleArray, sex: List<FloatArray?>): IntArray {
        val lab = cluster(embs, durs, starts, threshold = 0.75, attach = 0.35)
        var next = (lab.maxOrNull() ?: -1) + 1
        fun isMan(i: Int) = sex[i]?.let { it[1] > 0.3f && it[1] > 3 * it[2] } ?: false
        fun isWoman(i: Int) = sex[i]?.let { it[2] > 0.3f && it[2] > 3 * it[1] } ?: false
        for (g in lab.toSet()) {
            val members = lab.indices.filter { lab[it] == g }
            val men = members.filter { isMan(it) }
            val women = members.filter { isWoman(it) }
            val decisive = men.size + women.size
            if (men.size < 2 || women.size < 2 || men.size < decisive / 4 || women.size < decisive / 4) continue
            // a man and a woman merged by mistake → split, undecided lines go to the closer voice
            val mc = normalize(meanOf(men.mapNotNull { embs[it] }.map { normalize(it) }) ?: continue)
            val wc = normalize(meanOf(women.mapNotNull { embs[it] }.map { normalize(it) }) ?: continue)
            for (i in members) {
                val toWoman = when {
                    isWoman(i) -> true
                    isMan(i) -> false
                    embs[i] != null -> dot(normalize(embs[i]!!), wc) > dot(normalize(embs[i]!!), mc)
                    else -> false
                }
                if (toWoman) lab[i] = next
            }
            next++
        }
        val total = HashMap<Int, Double>()
        for (i in lab.indices) total[lab[i]] = (total[lab[i]] ?: 0.0) + durs[i]
        val remap = total.keys.sortedByDescending { total[it] }.withIndex().associate { it.value to it.index }
        return IntArray(lab.size) { remap.getValue(lab[it]) }
    }

    /**
     * Man, woman or child for one character, from all of his/her lines: the trained classifier decides
     * (it is right where pitch fails, e.g. a man shouting at 250 Hz); pitch only helps to spot children,
     * whom the classifier often hears as women.
     * [sex]: [speech, man, woman, child] per line (null = unknown); [f0]: pitch per line (0 = unknown).
     */
    fun kindOfCharacter(sex: List<FloatArray?>, durs: List<Double>, f0: List<Double>): Kind {
        var m = 0.0; var w = 0.0; var c = 0.0; var total = 0.0
        for (i in sex.indices) {
            val p = sex[i] ?: continue
            val d = durs[i]
            m += d * p[1]; w += d * p[2]; c += d * p[3]; total += d
        }
        val pitches = f0.filter { it > 0 }.sorted()
        val med = if (pitches.isEmpty()) 0.0 else pitches[pitches.size / 2]
        if (total <= 0 || m + w + c < 0.05 * total) return kindOf(med)          // classifier unsure: use pitch
        if (c >= 0.5 * max(m, w) || (med > 290 && w >= m)) return Kind.CHILD
        return if (m >= w) Kind.MALE else Kind.FEMALE
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
     * Median pitch (Hz) of the voiced parts of a clip, or 0 when there is none.
     * YIN (cumulative mean normalized difference) over 40 ms frames, 60–500 Hz: it takes the first clear
     * period, so a man's voice is not read an octave up (which would make him sound like a woman).
     */
    fun pitch(x: FloatArray, sr: Int = 16000): Double {
        val w = (0.04 * sr).toInt()
        val hop = w / 2
        val minLag = sr / 500
        val maxLag = sr / 60
        if (x.size < w + maxLag + 2) return 0.0
        // energy gate: only the louder, voiced parts of the line
        val frames = (x.size - w - maxLag - 1) / hop + 1
        val energy = DoubleArray(frames) { f ->
            var e = 0.0
            val p = f * hop
            for (i in p until p + w) e += x[i] * x[i]
            sqrt(e / w)
        }
        val gate = (energy.maxOrNull() ?: 0.0) * 0.1
        if (gate <= 1e-5) return 0.0
        val d = DoubleArray(maxLag + 2)
        val found = ArrayList<Double>()
        for (f in 0 until frames) {
            if (energy[f] < gate) continue
            val p = f * hop
            for (tau in 1..maxLag + 1) {
                var s2 = 0.0
                for (j in p until p + w) { val v = x[j] - x[j + tau]; s2 += v * v }
                d[tau] = s2
            }
            // cumulative mean normalized difference
            var run = 0.0
            val cm = DoubleArray(maxLag + 2) { 1.0 }
            for (tau in 1..maxLag + 1) { run += d[tau]; cm[tau] = if (run > 0) d[tau] * tau / run else 1.0 }
            var tau = -1
            var t = minLag
            while (t <= maxLag) {
                if (cm[t] < 0.15) {
                    while (t + 1 <= maxLag && cm[t + 1] < cm[t]) t++
                    tau = t
                    break
                }
                t++
            }
            if (tau < 0) continue                       // no clear period: unvoiced frame
            // parabolic interpolation for a finer period
            val a = cm[tau - 1]; val b = cm[tau]; val c = cm[tau + 1]
            val den = a - 2 * b + c
            val shift = if (abs(den) > 1e-12) (0.5 * (a - c) / den).coerceIn(-1.0, 1.0) else 0.0
            found.add(sr / (tau + shift))
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

    class Speaker(val id: Int, val f0: Double, val seconds: Double, val kind: Kind = kindOf(f0))
    class PhoneVoice(val name: String, val f0: Double, val kind: Kind = kindOf(f0))
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
        val male = voices.filter { it.kind == Kind.MALE }
        val female = voices.filter { it.kind != Kind.MALE }
        val shifts = listOf(1.0f, 0.88f, 1.12f, 0.8f, 1.22f, 0.94f, 1.06f, 0.74f, 1.3f)
        val used = HashMap<String, MutableList<Float>>()       // voice → pitches already given to characters
        for (s in speakers.sortedByDescending { it.seconds }) {
            val kind = s.kind
            val pool = if (kind == Kind.MALE) male else female
            val (cands, cross) = if (pool.isNotEmpty()) pool to false else voices to true
            val chosen = (if (out.isEmpty() && preferred.isNotEmpty()) cands.firstOrNull { it.name == preferred } else null)
                ?: cands.minByOrNull { (used[it.name]?.size ?: 0) * 1000 + abs(it.f0 - (if (s.f0 > 0) s.f0 else it.f0)) }!!
            val taken = used.getOrPut(chosen.name) { mutableListOf() }
            // follow the actor's own pitch a little
            val sameRegister = s.f0 > 0 && chosen.f0 > 0 && kindOf(s.f0) == chosen.kind
            var base = if (sameRegister) sqrt(s.f0 / chosen.f0).toFloat().coerceIn(0.9f, 1.12f) else 1f
            if (cross) base *= if (kind == Kind.MALE) 0.72f else 1.35f      // no voice of that gender on the phone
            if (kind == Kind.CHILD) base *= 1.3f                                // a child: higher and a bit faster
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
            var rate = when (taken.size) { 0 -> 1f; 1 -> 1.05f; 2 -> 0.95f; else -> if (taken.size % 2 == 1) 1.08f else 0.92f }
            if (kind == Kind.CHILD) rate *= 1.06f
            taken.add(pitch)
            out[s.id] = Pick(chosen.name, pitch, rate)
        }
        return out
    }

    /** Pitch factor for one line: follows the actor's own intonation on that line, gently (±10 %). */
    fun lineIntonation(lineF0: Double, characterF0: Double): Float {
        if (lineF0 <= 0 || characterF0 <= 0) return 1f
        return Math.pow(lineF0 / characterF0, 0.35).toFloat().coerceIn(0.92f, 1.1f)
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
