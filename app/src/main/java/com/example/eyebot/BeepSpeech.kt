package com.example.eyebot

import com.example.eyebot.ToneSynth.Wave
import kotlin.math.abs
import kotlin.random.Random

/**
 * EyeBot's "robot language" (pure Kotlin, unit-tested).
 *
 * Text becomes a little melody: one chirp per syllable, pitch chosen from a hash of the word
 * (so the same word always has roughly the same tune, and you learn its "words"), a gentle
 * random wobble so it never sounds canned, and intonation from punctuation:
 *   "?" -> the last syllable glides up,  "!" -> higher, louder, faster,  "." -> falls at the end.
 * [VoiceTone] (from the mood) shifts pitch, speed and bounciness.
 */
object BeepSpeech {

    private const val BASE_HZ = 880f

    // Pentatonic-ish steps (semitones) so random melodies always sound sweet.
    private val STEPS = intArrayOf(-5, -3, 0, 2, 4, 7, 9, 12)

    fun render(text: String, tone: VoiceTone = VoiceTone(), seed: Int = Random.nextInt()): FloatArray {
        val words = Regex("[\\p{L}\\p{N}']+").findAll(text).map { it.value }.toList().take(MAX_WORDS)
        if (words.isEmpty()) return ToneSynth.silence(10)
        val question = text.trimEnd().endsWith("?")
        val exclaim = text.trimEnd().endsWith("!")
        val rnd = Random(seed)
        val tempo = tone.tempo.coerceIn(0.5f, 2f) * if (exclaim) 1.15f else 1f
        val pitch = tone.pitch.coerceIn(0.5f, 2f) * if (exclaim) 1.08f else 1f
        val clips = ArrayList<FloatArray>()

        words.forEachIndexed { wi, word ->
            val syl = syllables(word)
            val h = stableHash(word.lowercase())
            for (s in 0 until syl) {
                val lastOfAll = wi == words.lastIndex && s == syl - 1
                val step = STEPS[((h ushr (s * 3)) and 7)]
                val jitter = (rnd.nextFloat() - 0.5f) * 1.5f * tone.bounce
                var f0 = semis(BASE_HZ * pitch, step + jitter)
                var f1 = semis(f0, (rnd.nextFloat() - 0.5f) * 3f * tone.bounce)
                if (lastOfAll && question) { f1 = semis(f0, 7f) }
                if (lastOfAll && !question && !exclaim) { f1 = semis(f0, -5f) }
                if (lastOfAll && exclaim) { f0 = semis(f0, 2f); f1 = semis(f0, 4f) }
                val len = ((if (lastOfAll) 150 else 70 + (h ushr (s + 5) and 31)) / tempo).toInt().coerceIn(35, 260)
                val wave = if ((h ushr s) and 1 == 0) Wave.TRIANGLE else Wave.SINE
                val vib = if (lastOfAll && question) 0f else 6f + 6f * tone.bounce
                clips += ToneSynth.tone(f0, f1, len, wave, vol = if (exclaim) 0.75f else 0.6f,
                    vibHz = vib, vibDepth = 0.015f + 0.02f * tone.bounce, attackMs = 4, releaseMs = 18)
                clips += ToneSynth.silence((18 / tempo).toInt())
            }
            clips += ToneSynth.silence((55 / tempo).toInt())
        }
        return ToneSynth.normalize(ToneSynth.seq(*clips.toTypedArray()), 0.8f)
    }

    /**
     * A person's signature melody: 3-5 notes derived from their name and a [variant]
     * (bump the variant to "re-roll"). Always the same for the same name + variant.
     */
    fun signature(name: String, variant: Int = 0): FloatArray {
        val notes = signatureNotes(name, variant)
        val clips = ArrayList<FloatArray>()
        for ((semi, ms) in notes) {
            val f = semis(BASE_HZ, semi.toFloat())
            clips += ToneSynth.tone(f, f * 1.01f, ms, Wave.TRIANGLE, vol = 0.65f, vibHz = 7f, vibDepth = 0.015f, attackMs = 5, releaseMs = 25)
            clips += ToneSynth.silence(28)
        }
        return ToneSynth.normalize(ToneSynth.seq(*clips.toTypedArray()), 0.8f)
    }

    /** (semitone offset, duration ms) pairs; exposed for tests. */
    fun signatureNotes(name: String, variant: Int): List<Pair<Int, Int>> {
        val h = stableHash(name.trim().lowercase() + "#" + variant)
        val count = 3 + (h and 0x3).let { if (it == 3) 2 else it }      // 3..5
        val rhythms = intArrayOf(90, 130, 180, 110)
        return List(count) { i ->
            val idx = (h ushr (4 + i * 3)) and 7
            STEPS[idx] to rhythms[(h ushr (20 + i * 2)) and 3] + if (i == count - 1) 80 else 0
        }
    }

    /** Rough English-ish syllable count: vowel groups, 1..4. */
    fun syllables(word: String): Int {
        val w = word.lowercase()
        var count = 0
        var prevVowel = false
        for (c in w) {
            val v = c in "aeiouy"
            if (v && !prevVowel) count++
            prevVowel = v
        }
        if (w.length > 3 && w.endsWith("e") && count > 1) count--
        if (count == 0) count = if (w.any { it.isDigit() }) w.count { it.isDigit() }.coerceAtMost(3) else 1
        return count.coerceIn(1, 4)
    }

    private fun semis(f: Float, s: Float) = f * Math.pow(2.0, s / 12.0).toFloat()

    /** FNV-1a: stable across runs and devices (unlike String.hashCode, which is also stable, but this mixes better). */
    fun stableHash(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (c in s) { h = h xor c.code; h *= 16777619 }
        return abs(h % Int.MAX_VALUE)
    }

    private const val MAX_WORDS = 14
}
