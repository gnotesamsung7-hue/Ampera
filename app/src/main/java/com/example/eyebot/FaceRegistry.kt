package com.example.eyebot

import kotlin.math.sqrt

/**
 * The household: who EyeBot knows (pure Kotlin, unit-tested).
 *
 * Each person keeps up to [MAX_SAMPLES] face fingerprints (192-number MobileFaceNet embeddings,
 * L2-normalised). No photos are ever stored. Matching uses cosine similarity against every
 * sample, takes each person's best score, and only names someone when that score is high enough
 * AND clearly beats the runner-up (so siblings / look-alikes fall back to "unknown").
 *
 * Thread-safety: reads from the camera thread, writes from the main thread -> every public
 * method is synchronized and [people] returns a copy.
 */
class FaceRegistry(
    private val matchThreshold: Float = 0.62f,
    private val marginOverRunnerUp: Float = 0.06f,
    private val learnAbove: Float = 0.75f,
) {
    data class Person(
        val id: Long,
        var name: String,
        val samples: MutableList<FloatArray> = mutableListOf(),
        var melodyVariant: Int = 0,
        /** ARGB eye colour while this person is around, or null for default. */
        var favoriteColor: Int? = null,
        var guest: Boolean = false,
        val createdAt: Long = 0L,
        var lastSeen: Long = 0L,
        var seenCount: Int = 0,
        var lastLearnedAt: Long = 0L,
        /** Month (1-12) and day of birthday, or 0 if unknown. */
        var birthMonth: Int = 0,
        var birthDay: Int = 0,
    )

    data class Match(val person: Person?, val similarity: Float, val runnerUp: Float)

    private val list = ArrayList<Person>()
    private var nextId = 1L

    /** Bumped on every change (used to know when to save). */
    @Volatile var version = 0
        private set

    @Synchronized fun people(): List<Person> = list.map { it.copy(samples = it.samples.toMutableList()) }

    @Synchronized fun size() = list.size

    @Synchronized fun find(id: Long): Person? = list.firstOrNull { it.id == id }

    @Synchronized
    fun add(name: String, samples: List<FloatArray>, guest: Boolean, now: Long): Person? {
        if (list.size >= MAX_PEOPLE || samples.isEmpty()) return null
        val p = Person(
            id = nextId++, name = cleanName(name), guest = guest, createdAt = now, lastSeen = now,
            samples = samples.map { normalize(it) }.takeLast(MAX_SAMPLES).toMutableList(),
        )
        list += p
        version++
        return p
    }

    @Synchronized fun replaceSamples(id: Long, samples: List<FloatArray>) {
        val p = find(id) ?: return
        p.samples.clear(); p.samples += samples.map { normalize(it) }.takeLast(MAX_SAMPLES); version++
    }

    @Synchronized fun update(id: Long, block: (Person) -> Unit) { find(id)?.let { block(it); version++ } }

    @Synchronized fun remove(id: Long) { if (list.removeAll { it.id == id }) version++ }

    @Synchronized fun clear() { list.clear(); version++ }

    /** Forget guests not seen for [guestDays] days. Returns the names removed. */
    @Synchronized fun expireGuests(now: Long, guestDays: Int = 7): List<String> {
        val cutoff = now - guestDays * 86_400_000L
        val gone = list.filter { it.guest && it.lastSeen < cutoff }
        if (gone.isNotEmpty()) { list.removeAll(gone.toSet()); version++ }
        return gone.map { it.name }
    }

    @Synchronized
    fun match(embedding: FloatArray): Match {
        val e = normalize(embedding)
        var best: Person? = null
        var bestSim = -1f
        var second = -1f
        for (p in list) {
            var ps = -1f
            for (s in p.samples) { val c = dot(e, s); if (c > ps) ps = c }
            if (ps > bestSim) { second = bestSim; bestSim = ps; best = p } else if (ps > second) second = ps
        }
        val ok = best != null && bestSim >= matchThreshold && bestSim - second.coerceAtLeast(0f) >= marginOverRunnerUp
        return Match(if (ok) best else null, bestSim, second)
    }

    /**
     * Called after a confident recognition: records the sighting and, when the match is very
     * strong, adds a fresh sample (at most once a minute) so it keeps up with growing kids,
     * haircuts and glasses.
     */
    @Synchronized
    fun recordSighting(id: Long, embedding: FloatArray, similarity: Float, now: Long, newVisit: Boolean) {
        val p = find(id) ?: return
        p.lastSeen = now
        if (newVisit) p.seenCount++
        if (similarity >= learnAbove && now - p.lastLearnedAt > 60_000) {
            p.samples += normalize(embedding)
            while (p.samples.size > MAX_SAMPLES) p.samples.removeAt(KEEP_ORIGINAL)   // keep the first enrolment shots
            p.lastLearnedAt = now
        }
        version++
    }

    // ---- persistence: one person per line, fields separated by '|' -----------------------

    @Synchronized
    fun serialize(): String = buildString {
        appendLine("EYEBOT_PEOPLE v1 next=$nextId")
        for (p in list) {
            append(p.id).append('|').append(escape(p.name)).append('|').append(p.melodyVariant).append('|')
            append(p.favoriteColor?.toString() ?: "").append('|').append(if (p.guest) 1 else 0).append('|')
            append(p.createdAt).append('|').append(p.lastSeen).append('|').append(p.seenCount).append('|')
            append(p.birthMonth).append('|').append(p.birthDay).append('|')
            append(p.samples.joinToString(";") { s -> s.joinToString(",") { "%.5f".format(java.util.Locale.US, it) } })
            appendLine()
        }
    }

    @Synchronized
    fun load(text: String?) {
        list.clear()
        if (text.isNullOrBlank()) return
        val lines = text.lines()
        nextId = Regex("next=(\\d+)").find(lines.firstOrNull() ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 1L
        for (line in lines.drop(1)) {
            val f = line.split('|')
            if (f.size < 11) continue
            try {
                val samples = f[10].split(';').filter { it.isNotBlank() }
                    .map { s -> s.split(',').map { it.toFloat() }.toFloatArray() }
                    .filter { it.size == EMBEDDING_SIZE }
                    .toMutableList()
                list += Person(
                    id = f[0].toLong(), name = unescape(f[1]), samples = samples, melodyVariant = f[2].toInt(),
                    favoriteColor = f[3].toIntOrNull(), guest = f[4] == "1", createdAt = f[5].toLong(),
                    lastSeen = f[6].toLong(), seenCount = f[7].toInt(), birthMonth = f[8].toInt(), birthDay = f[9].toInt(),
                )
                nextId = maxOf(nextId, f[0].toLong() + 1)
            } catch (_: Exception) { /* skip a corrupt line */ }
        }
        version++
    }

    companion object {
        const val EMBEDDING_SIZE = 192
        const val MAX_PEOPLE = 15
        const val MAX_SAMPLES = 20
        private const val KEEP_ORIGINAL = 4

        fun normalize(v: FloatArray): FloatArray {
            var n = 0f
            for (x in v) n += x * x
            val k = if (n > 1e-12f) 1f / sqrt(n) else 0f
            return FloatArray(v.size) { v[it] * k }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }

        fun cleanName(n: String) = n.trim().replace(Regex("[|\\n\\r]"), " ").take(24).ifEmpty { "Friend" }
        private fun escape(s: String) = s.replace("|", " ")
        private fun unescape(s: String) = s
    }
}

/**
 * Turns noisy per-frame recognitions of one tracked face into a stable identity: the same
 * answer must win [confirmFrames] times before it's believed.
 */
class IdentityVoter(private val confirmFrames: Int = 3, private val giveUpAfter: Int = 8) {
    sealed interface Verdict {
        data object Pending : Verdict
        data class Known(val personId: Long) : Verdict
        data object Stranger : Verdict
    }

    private val votes = HashMap<Long, Int>()   // personId (or -1 = unknown) -> count
    private var total = 0
    var verdict: Verdict = Verdict.Pending
        private set

    fun vote(personId: Long?): Verdict {
        if (verdict != Verdict.Pending) return verdict
        val key = personId ?: -1L
        val c = (votes[key] ?: 0) + 1
        votes[key] = c
        total++
        verdict = when {
            key != -1L && c >= confirmFrames -> Verdict.Known(key)
            key == -1L && c >= confirmFrames + 2 -> Verdict.Stranger
            total >= giveUpAfter -> Verdict.Stranger
            else -> Verdict.Pending
        }
        return verdict
    }
}
