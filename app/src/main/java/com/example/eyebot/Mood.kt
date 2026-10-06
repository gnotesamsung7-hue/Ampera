package com.example.eyebot

import kotlin.math.exp

/**
 * Long-lasting mood (pure Kotlin, unit-tested). Three slow values, 0..1:
 *
 *  - happiness: rises with petting, play, food and familiar faces; sinks when ignored
 *  - energy:    follows the time of day (perky mornings, sleepy nights) and drains with play
 *  - social:    how much company it has had lately; low social = lonely / bored
 *
 * The mood colours everything else: the beep voice's pitch and tempo, how often it gets bored,
 * and whether it greets you happily or with a little "hmph" first.
 */
class Mood(
    var happiness: Float = 0.6f,
    var energy: Float = 0.7f,
    var social: Float = 0.5f,
) {
    enum class Event(val dHappy: Float, val dEnergy: Float, val dSocial: Float) {
        PETTED(0.06f, 0f, 0.04f),
        BOOPED(0.03f, 0.01f, 0.03f),
        POKED(-0.02f, 0.02f, 0.02f),
        POKE_SPAM(-0.06f, 0.02f, 0.0f),
        PLAYED(0.05f, -0.03f, 0.05f),
        FED(0.12f, 0.05f, 0.02f),
        STARVED(-0.15f, -0.05f, 0f),
        PARTY(0.10f, -0.06f, 0.06f),
        KNOWN_PERSON(0.05f, 0.02f, 0.12f),
        STRANGER(0.0f, 0.01f, 0.04f),
        ANIMAL(0.06f, 0.03f, 0.04f),
        SLEPT(0.02f, 0.25f, 0f),
    }

    fun apply(e: Event) {
        happiness = (happiness + e.dHappy).coerceIn(0f, 1f)
        energy = (energy + e.dEnergy).coerceIn(0f, 1f)
        social = (social + e.dSocial).coerceIn(0f, 1f)
    }

    /**
     * Advance time.
     * @param dtSec seconds since the last update
     * @param hourOfDay 0..24 (fractional) for the energy rhythm
     * @param alone true when nobody is around
     */
    fun tick(dtSec: Float, hourOfDay: Float, alone: Boolean) {
        if (dtSec <= 0f) return
        val dt = dtSec.coerceAtMost(3600f)
        // Energy drifts towards the circadian target over ~20 minutes.
        energy = approach(energy, circadianEnergy(hourOfDay), dt / 1200f)
        // Social fades slowly while alone (half-life ~40 min), recovers a little with company.
        social = if (alone) social * decay(dt, 2400f) else approach(social, 0.6f, dt / 1800f)
        // Happiness relaxes to a baseline that depends on social; very lonely = sadder.
        val baseline = 0.35f + 0.35f * social
        happiness = approach(happiness, baseline, dt / 1800f)
    }

    val isGrumpy get() = happiness < GRUMPY_BELOW
    val isLonely get() = social < 0.2f
    val isTired get() = energy < 0.3f

    /** Voice tone derived from the mood (used by the beep voice). */
    fun voiceTone(): VoiceTone = VoiceTone(
        pitch = (0.85f + 0.35f * happiness + 0.1f * energy) * 1.18f,   // Ampera: higher beeps
        tempo = 0.8f + 0.45f * energy,
        bounce = happiness,
    )

    fun serialize() = "%.4f,%.4f,%.4f".format(java.util.Locale.US, happiness, energy, social)

    companion object {
        const val GRUMPY_BELOW = 0.25f

        fun parse(s: String?): Mood {
            val p = s?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
            return if (p != null && p.size == 3) Mood(p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f), p[2].coerceIn(0f, 1f)) else Mood()
        }

        /** Low at 3 am, peaks late morning, gentle dip after lunch, low again at night. */
        fun circadianEnergy(hour: Float): Float {
            val h = ((hour % 24f) + 24f) % 24f
            return when {
                h < 5f -> 0.15f
                h < 9f -> 0.15f + (h - 5f) / 4f * 0.7f
                h < 13f -> 0.85f
                h < 15f -> 0.7f
                h < 19f -> 0.8f
                h < 22f -> 0.8f - (h - 19f) / 3f * 0.45f
                else -> 0.3f - (h - 22f) / 2f * 0.15f
            }
        }

        private fun approach(v: Float, target: Float, k: Float): Float = v + (target - v) * k.coerceIn(0f, 1f)
        private fun decay(dt: Float, halfLifeSec: Float) = exp(-0.6931f * dt / halfLifeSec).toFloat()
    }
}

/** How the beep voice should sound right now. */
data class VoiceTone(
    /** 1 = normal; higher = squeakier. */
    val pitch: Float = 1f,
    /** 1 = normal; higher = faster. */
    val tempo: Float = 1f,
    /** 0..1, how much the pitch jumps between syllables (happy = bouncy). */
    val bounce: Float = 0.6f,
)
