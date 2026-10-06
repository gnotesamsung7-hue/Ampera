package com.example.eyebot

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * SoundPool wrapper. On first launch every [Sfx] x [SfxLibrary.VARIANTS] is synthesised to a
 * small WAV in the cache folder (about 1 s of CPU in total, on a background thread) and loaded
 * into SoundPool; later launches just load the cached files.
 */
class SoundBank(context: Context) {

    var enabled = true
    var volume = 0.9f

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = ConcurrentHashMap<Sfx, IntArray>()
    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var released = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded.add(sampleId) }
        val dir = File(context.cacheDir, "sfx_v$CACHE_VERSION")
        thread(name = "sfx-synth", isDaemon = true) {
            try {
                dir.mkdirs()
                for (sfx in Sfx.entries) {
                    val arr = IntArray(SfxLibrary.VARIANTS)
                    for (v in 0 until SfxLibrary.VARIANTS) {
                        val f = File(dir, "${sfx.name.lowercase()}_$v.wav")
                        if (!f.exists() || f.length() < 64) f.writeBytes(WavWriter.encode(SfxLibrary.render(sfx, v)))
                        if (released) return@thread
                        arr[v] = pool.load(f.path, 1)
                    }
                    ids[sfx] = arr
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not prepare sound effects", e)
            }
        }
    }

    /** Plays a random variant with a slightly random pitch. Returns false if not ready / muted. */
    fun play(sfx: Sfx, gain: Float = 1f): Boolean {
        if (!enabled || released) return false
        val arr = ids[sfx] ?: return false
        val id = arr[Random.nextInt(arr.size)]
        if (id !in loaded) return false
        val v = (volume * gain).coerceIn(0f, 1f)
        val rate = 0.94f + Random.nextFloat() * 0.14f
        return pool.play(id, v, v, 1, 0, rate) != 0
    }

    fun release() {
        released = true
        pool.release()
    }

    companion object {
        private const val TAG = "SoundBank"
        /** Bump when the sound design in SfxLibrary changes, to re-synthesise the cache. */
        const val CACHE_VERSION = 3
    }
}

/**
 * Maps emotions to sound effects: one sound when an emotion starts, plus gentle repeating
 * sounds for ongoing states (chomping, purring, snoring, party music). Rate-limited so it chirps
 * rather than chatters.
 */
class AudioPersonality(private val bank: SoundBank) {

    /** Screensaver: stay silent (except explicit sounds). */
    var quiet = false

    private var current: Emotion? = null
    private var since = 0L
    private var lastAny = 0L
    private val lastBySfx = HashMap<Sfx, Long>()
    private var lastLoopAt = 0L

    /** Call with the current emotion as often as you like (every frame / tick). */
    fun onEmotion(e: Emotion, now: Long = System.currentTimeMillis()) {
        if (e != current) {
            current = e
            since = now
            lastLoopAt = now
            if (quiet) return
            val sfx = ENTRY[e] ?: return
            if (Random.nextFloat() <= (ENTRY_CHANCE[e] ?: 1f)) play(sfx, now)
            return
        }
        if (quiet) return
        val loop = LOOPS[e] ?: return
        if (now - since >= loop.startAfterMs && now - lastLoopAt >= loop.everyMs) {
            lastLoopAt = now
            play(loop.sfx, now, force = true, gain = loop.gain)
        }
    }

    /** Explicit sound requested by the brain (boop, QR beep, power up/down...). */
    fun play(sfx: Sfx, now: Long = System.currentTimeMillis(), force: Boolean = false, gain: Float = 1f) {
        if (!force) {
            if (now - lastAny < GLOBAL_GAP_MS) return
            val last = lastBySfx[sfx] ?: 0L
            if (now - last < (COOLDOWN[sfx] ?: DEFAULT_COOLDOWN_MS)) return
        }
        if (bank.play(sfx, gain)) {
            lastAny = now
            lastBySfx[sfx] = now
        }
    }

    private class Loop(val sfx: Sfx, val everyMs: Long, val startAfterMs: Long = 0, val gain: Float = 1f)

    companion object {
        private const val GLOBAL_GAP_MS = 250L
        private const val DEFAULT_COOLDOWN_MS = 1_500L

        private val ENTRY: Map<Emotion, Sfx> = mapOf(
            Emotion.HAPPY to Sfx.HAPPY,
            Emotion.EXCITED to Sfx.EXCITED,
            Emotion.CURIOUS to Sfx.CURIOUS,
            Emotion.SURPRISED to Sfx.GASP,
            Emotion.SEARCHING to Sfx.SEARCH,
            Emotion.CONFUSED to Sfx.CONFUSED,
            Emotion.BORED to Sfx.SIGH,
            Emotion.SLEEPY to Sfx.YAWN,
            Emotion.YAWN to Sfx.YAWN,
            Emotion.SNEEZE to Sfx.SNEEZE,
            Emotion.LOVESTRUCK to Sfx.LOVE,
            Emotion.HYPNOTIZED to Sfx.HYPNO,
            Emotion.CONTENT to Sfx.PURR,
            Emotion.HUNGRY to Sfx.HUNGRY,
            Emotion.EATING to Sfx.NOM,
            Emotion.SAD to Sfx.SAD,
            Emotion.PARTY to Sfx.PARTY,
            Emotion.GRUMPY to Sfx.HMPH,
            Emotion.ALERT to Sfx.ALARM,
            Emotion.HOT to Sfx.SIGH,
            Emotion.POWER_UP to Sfx.CHARGE_UP,
            Emotion.LOW_BATTERY to Sfx.TUMMY,
            Emotion.DIZZY to Sfx.DIZZY,
        )
        /** Some states come and go often; only chirp sometimes. */
        private val ENTRY_CHANCE: Map<Emotion, Float> = mapOf(
            Emotion.SEARCHING to 0.5f,
            Emotion.HAPPY to 0.8f,
            Emotion.CURIOUS to 0.8f,
        )
        private val COOLDOWN: Map<Sfx, Long> = mapOf(
            Sfx.HAPPY to 4_000L,
            Sfx.CURIOUS to 3_000L,
            Sfx.SEARCH to 5_000L,
            Sfx.GASP to 1_200L,
            Sfx.YAWN to 6_000L,
            Sfx.SIGH to 6_000L,
            Sfx.BOOP to 300L,
            Sfx.QR_ACK to 300L,
        )
        private val LOOPS: Map<Emotion, Loop> = mapOf(
            Emotion.EATING to Loop(Sfx.NOM, everyMs = 480),
            Emotion.PARTY to Loop(Sfx.PARTY, everyMs = 2_100),
            Emotion.CONTENT to Loop(Sfx.PURR, everyMs = 1_100),
            Emotion.SLEEPY to Loop(Sfx.SNORE, everyMs = 4_200, startAfterMs = 3_000, gain = 0.7f),
            Emotion.HYPNOTIZED to Loop(Sfx.HYPNO, everyMs = 1_800),
            Emotion.HUNGRY to Loop(Sfx.HUNGRY, everyMs = 6_000, startAfterMs = 4_000, gain = 0.8f),
            Emotion.LOW_BATTERY to Loop(Sfx.TUMMY, everyMs = 9_000, startAfterMs = 5_000, gain = 0.9f),
            Emotion.ALERT to Loop(Sfx.ALARM, everyMs = 1_300),
        )
    }
}
