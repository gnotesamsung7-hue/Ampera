package com.example.eyebot

import com.example.eyebot.ToneSynth.Wave
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Every sound EyeBot can make. None of them are audio files: [SfxLibrary] synthesises them at
 * first launch (pure Kotlin, unit-tested), [SoundBank] caches them as WAVs and plays them
 * through SoundPool.
 */
enum class Sfx {
    HAPPY, EXCITED, CURIOUS, GASP, SEARCH, CONFUSED, SIGH, SNORE, YAWN, SNEEZE, LOVE, HYPNO,
    PURR, HUNGRY, NOM, SAD, PARTY, BOOP, GREET, POWER_UP, POWER_DOWN, QR_ACK,
    // ---- v4 ----
    DOG, CAT, BIRD, FARM, TOY, ALARM, OOF, WHOA, CHIME, TUMMY, CHARGE_UP, CHARGE_BURST, HMPH, WHO, DIZZY,
}

/** Tiny additive synthesiser producing mono float clips in -1..1 at [SR] Hz. */
object ToneSynth {
    const val SR = 22050

    enum class Wave { SINE, TRIANGLE, SQUARE, SAW }

    private fun samples(ms: Int) = max(1, SR * ms / 1000)

    /**
     * A tone gliding exponentially from [f0] to [f1] Hz, with optional vibrato
     * ([vibHz], [vibDepth] as a fraction of the frequency) and a linear attack/release envelope.
     */
    fun tone(
        f0: Float, f1: Float, ms: Int, wave: Wave = Wave.SINE, vol: Float = 0.6f,
        vibHz: Float = 0f, vibDepth: Float = 0f, attackMs: Int = 6, releaseMs: Int = 30,
    ): FloatArray {
        val n = samples(ms)
        val out = FloatArray(n)
        var phase = 0.0
        val ratio = (f1 / f0).toDouble()
        for (i in 0 until n) {
            val t = i.toDouble() / n
            var f = f0 * ratio.pow(t)
            if (vibHz > 0f) f *= 1.0 + vibDepth * sin(2 * PI * vibHz * i / SR)
            phase += f / SR
            val p = phase - kotlin.math.floor(phase)
            val s = when (wave) {
                Wave.SINE -> sin(2 * PI * p)
                Wave.TRIANGLE -> 4 * abs(p - 0.5) - 1
                Wave.SQUARE -> if (p < 0.5) 1.0 else -1.0
                Wave.SAW -> 2 * p - 1
            }
            out[i] = (s * vol * envelope(i, n, attackMs, releaseMs)).toFloat()
        }
        return out
    }

    /** Filtered noise burst ([lowpass] 0..1: lower = darker / breathier). */
    fun noise(ms: Int, vol: Float = 0.4f, lowpass: Float = 0.3f, attackMs: Int = 5, releaseMs: Int = 60, seed: Int = 7): FloatArray {
        val n = samples(ms)
        val rnd = Random(seed)
        val out = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            val x = rnd.nextFloat() * 2f - 1f
            y += lowpass * (x - y)
            out[i] = y * vol * envelope(i, n, attackMs, releaseMs).toFloat() * (1f / max(0.25f, sqrtApprox(lowpass)))
        }
        return out
    }

    fun silence(ms: Int) = FloatArray(samples(ms))

    fun seq(vararg clips: FloatArray): FloatArray {
        val out = FloatArray(clips.sumOf { it.size })
        var o = 0
        for (c in clips) { c.copyInto(out, o); o += c.size }
        return out
    }

    /** Mix clips starting together; result is as long as the longest. */
    fun mix(vararg clips: FloatArray): FloatArray {
        val out = FloatArray(clips.maxOf { it.size })
        for (c in clips) for (i in c.indices) out[i] += c[i]
        return out
    }

    /** Amplitude modulation (tremolo / purr / snore). */
    fun am(clip: FloatArray, hz: Float, depth: Float): FloatArray = FloatArray(clip.size) { i ->
        val m = 1f - depth * (0.5f + 0.5f * sin(2 * PI * hz * i / SR).toFloat())
        clip[i] * m
    }

    fun lowpass(clip: FloatArray, alpha: Float): FloatArray {
        val out = FloatArray(clip.size)
        var y = 0f
        for (i in clip.indices) { y += alpha * (clip[i] - y); out[i] = y }
        return out
    }

    fun repeat(clip: FloatArray, times: Int, gapMs: Int): FloatArray =
        seq(*Array(times * 2 - 1) { if (it % 2 == 0) clip else silence(gapMs) })

    /** Scale so the loudest sample hits [peak]; also removes any DC offset. */
    fun normalize(clip: FloatArray, peak: Float = 0.85f): FloatArray {
        if (clip.isEmpty()) return clip
        val mean = clip.average().toFloat()
        var mx = 0f
        for (v in clip) mx = max(mx, abs(v - mean))
        if (mx < 1e-6f) return clip
        val k = peak / mx
        return FloatArray(clip.size) { (clip[it] - mean) * k }
    }

    private fun envelope(i: Int, n: Int, attackMs: Int, releaseMs: Int): Double {
        val a = max(1, SR * attackMs / 1000)
        val r = max(1, SR * releaseMs / 1000)
        val up = min(1.0, i.toDouble() / a)
        val down = min(1.0, (n - 1 - i).toDouble() / r)
        return max(0.0, min(up, down))
    }

    private fun sqrtApprox(x: Float) = kotlin.math.sqrt(x)
}

/** The actual sound design. [variant] 0..2 shifts pitch a little so repeats don't sound canned. */
object SfxLibrary {
    const val VARIANTS = 3
    private val PITCH = floatArrayOf(1f, 1.12f, 0.9f)

    fun render(sfx: Sfx, variant: Int): FloatArray {
        val p = PITCH[variant.coerceIn(0, VARIANTS - 1)]
        val seed = sfx.ordinal * 31 + variant
        return ToneSynth.normalize(build(sfx, p, seed), if (sfx == Sfx.SNORE || sfx == Sfx.PURR) 0.6f else 0.85f)
    }

    private fun build(s: Sfx, p: Float, seed: Int): FloatArray = with(ToneSynth) {
        when (s) {
            Sfx.HAPPY -> seq(
                tone(880 * p, 880 * p, 70, Wave.TRIANGLE), silence(20),
                tone(1175 * p, 1175 * p, 70, Wave.TRIANGLE), silence(20),
                tone(1568 * p, 1760 * p, 130, Wave.TRIANGLE, vibHz = 9f, vibDepth = 0.03f),
            )
            Sfx.EXCITED -> seq(
                *floatArrayOf(660f, 880f, 1100f, 1320f, 1760f, 2200f)
                    .map { tone(it * p, it * p, 45, Wave.SQUARE, vol = 0.35f, releaseMs = 10) }.toTypedArray(),
                tone(2200 * p, 2400 * p, 160, Wave.TRIANGLE, vibHz = 14f, vibDepth = 0.04f),
            )
            Sfx.CURIOUS -> seq(
                tone(500 * p, 950 * p, 260, Wave.SINE, vibHz = 6f, vibDepth = 0.03f),
                silence(30),
                tone(900 * p, 1200 * p, 100, Wave.SINE),
            )
            Sfx.GASP -> seq(
                mix(noise(170, 0.35f, 0.25f, attackMs = 10, seed = seed), tone(600 * p, 1500 * p, 170, Wave.SINE, vol = 0.45f)),
                tone(1500 * p, 1350 * p, 120, Wave.SINE, vol = 0.5f),
            )
            Sfx.SEARCH -> seq(
                tone(1200 * p, 1200 * p, 60, Wave.SQUARE, vol = 0.25f), silence(90),
                tone(900 * p, 900 * p, 60, Wave.SQUARE, vol = 0.25f), silence(200),
                tone(1300 * p, 1550 * p, 90, Wave.SINE),
            )
            Sfx.CONFUSED -> seq(
                tone(700 * p, 500 * p, 140, Wave.TRIANGLE),
                tone(500 * p, 820 * p, 190, Wave.TRIANGLE, vibHz = 10f, vibDepth = 0.05f),
            )
            Sfx.SIGH -> mix(
                noise(750, 0.3f, 0.08f, attackMs = 150, releaseMs = 420, seed = seed),
                tone(330 * p, 210 * p, 750, Wave.SINE, vol = 0.25f, attackMs = 120, releaseMs = 400),
            )
            Sfx.SNORE -> mix(
                am(lowpass(tone(105 * p, 92 * p, 1200, Wave.SAW, vol = 0.5f, attackMs = 250, releaseMs = 400), 0.15f), 28f, 0.6f),
                noise(1200, 0.15f, 0.05f, attackMs = 300, releaseMs = 500, seed = seed),
            )
            Sfx.YAWN -> mix(
                tone(620 * p, 230 * p, 1300, Wave.TRIANGLE, vol = 0.5f, vibHz = 5f, vibDepth = 0.05f, attackMs = 200, releaseMs = 350),
                noise(1300, 0.12f, 0.06f, attackMs = 300, releaseMs = 400, seed = seed),
            )
            Sfx.SNEEZE -> seq(
                tone(500 * p, 700 * p, 220, Wave.TRIANGLE, vol = 0.45f), silence(120),
                tone(600 * p, 880 * p, 260, Wave.TRIANGLE, vol = 0.5f), silence(160),
                mix(
                    noise(280, 0.9f, 0.6f, attackMs = 2, releaseMs = 220, seed = seed),
                    tone(1300 * p, 300 * p, 220, Wave.SQUARE, vol = 0.3f, attackMs = 2),
                ),
            )
            Sfx.LOVE -> seq(
                tone(523 * p, 659 * p, 250, Wave.SINE, vibHz = 6f, vibDepth = 0.02f, attackMs = 30),
                silence(40),
                tone(659 * p, 784 * p, 400, Wave.SINE, vibHz = 6f, vibDepth = 0.03f, attackMs = 30, releaseMs = 150),
            )
            Sfx.HYPNO -> seq(
                tone(400 * p, 800 * p, 500, Wave.SINE, vol = 0.5f, vibHz = 9f, vibDepth = 0.15f, attackMs = 60),
                tone(800 * p, 400 * p, 500, Wave.SINE, vol = 0.5f, vibHz = 9f, vibDepth = 0.15f, releaseMs = 120),
            )
            Sfx.PURR -> mix(
                am(tone(70f, 72f, 900, Wave.TRIANGLE, vol = 0.6f, attackMs = 120, releaseMs = 250), 24f, 0.85f),
                tone(220 * p, 230 * p, 900, Wave.SINE, vol = 0.12f, attackMs = 150, releaseMs = 300),
            )
            Sfx.HUNGRY -> mix(
                am(noise(800, 0.5f, 0.03f, attackMs = 80, releaseMs = 200, seed = seed), 7f, 0.8f),
                seq(silence(250), tone(720 * p, 400 * p, 550, Wave.TRIANGLE, vol = 0.3f, vibHz = 7f, vibDepth = 0.04f)),
            )
            Sfx.NOM -> seq(
                mix(noise(45, 0.5f, 0.35f, attackMs = 1, releaseMs = 30, seed = seed), tone(320 * p, 200 * p, 70, Wave.SQUARE, vol = 0.3f, attackMs = 1)),
                silence(40),
            )
            Sfx.SAD -> seq(
                tone(660 * p, 622 * p, 300, Wave.TRIANGLE, vol = 0.45f), silence(40),
                tone(587 * p, 554 * p, 300, Wave.TRIANGLE, vol = 0.45f), silence(40),
                tone(523 * p, 440 * p, 650, Wave.TRIANGLE, vol = 0.45f, vibHz = 5f, vibDepth = 0.03f, releaseMs = 250),
            )
            Sfx.PARTY -> seq(
                *floatArrayOf(523f, 659f, 784f, 1047f, 784f, 659f, 784f, 1047f)
                    .map { seq(tone(it * p, it * p, 85, Wave.SQUARE, vol = 0.3f, releaseMs = 15), silence(15)) }.toTypedArray(),
                tone(1319 * p, 1319 * p, 220, Wave.SQUARE, vol = 0.3f, vibHz = 12f, vibDepth = 0.02f),
            )
            Sfx.BOOP -> tone(1500 * p, 1850 * p, 70, Wave.SINE, vol = 0.6f)
            Sfx.GREET -> seq(
                tone(700 * p, 1100 * p, 90, Wave.SINE), silence(30),
                tone(1100 * p, 800 * p, 90, Wave.SINE), silence(30),
                tone(900 * p, 1450 * p, 150, Wave.SINE, vibHz = 8f, vibDepth = 0.02f),
            )
            Sfx.POWER_UP -> seq(
                lowpass(tone(200f, 1600f, 600, Wave.SAW, vol = 0.4f, attackMs = 20), 0.35f),
                tone(1600f, 1600f, 110, Wave.SINE, vol = 0.4f),
            )
            Sfx.POWER_DOWN -> lowpass(tone(1600f, 150f, 750, Wave.SAW, vol = 0.4f, releaseMs = 250), 0.35f)
            // ---- v4 ----
            Sfx.DOG -> seq(   // "arf-arf"
                mix(noise(70, 0.5f, 0.4f, attackMs = 2, releaseMs = 40, seed = seed), tone(620 * p, 380 * p, 90, Wave.SQUARE, vol = 0.45f, attackMs = 2)),
                silence(90),
                mix(noise(70, 0.5f, 0.4f, attackMs = 2, releaseMs = 40, seed = seed + 1), tone(680 * p, 400 * p, 100, Wave.SQUARE, vol = 0.45f, attackMs = 2)),
            )
            Sfx.CAT -> tone(700 * p, 1100 * p, 320, Wave.TRIANGLE, vol = 0.5f, vibHz = 18f, vibDepth = 0.04f, attackMs = 30, releaseMs = 120) // "mrrp?"
            Sfx.BIRD -> repeat(tone(2600 * p, 3400 * p, 70, Wave.SINE, vol = 0.5f, attackMs = 3, releaseMs = 20), 3, 60)
            Sfx.FARM -> lowpass(tone(240 * p, 180 * p, 650, Wave.SAW, vol = 0.5f, vibHz = 5f, vibDepth = 0.04f, attackMs = 60, releaseMs = 200), 0.3f) // "moo/baa"
            Sfx.TOY -> seq(tone(1200 * p, 1500 * p, 60, Wave.SINE), silence(30), tone(1500 * p, 1900 * p, 90, Wave.SINE))
            Sfx.ALARM -> repeat(seq(tone(1800f, 1800f, 120, Wave.SQUARE, vol = 0.5f, releaseMs = 10), tone(1350f, 1350f, 120, Wave.SQUARE, vol = 0.5f, releaseMs = 10)), 4, 20)
            Sfx.OOF -> tone(420 * p, 220 * p, 140, Wave.TRIANGLE, vol = 0.55f, attackMs = 3)
            Sfx.WHOA -> tone(500 * p, 1300 * p, 260, Wave.SINE, vol = 0.55f, vibHz = 9f, vibDepth = 0.05f)
            Sfx.CHIME -> seq(tone(1319f, 1319f, 160, Wave.SINE, vol = 0.5f, releaseMs = 120), tone(1047f, 1047f, 260, Wave.SINE, vol = 0.5f, releaseMs = 200))
            Sfx.TUMMY -> mix(  // long gurgly stomach rumble
                am(noise(1300, 0.6f, 0.025f, attackMs = 100, releaseMs = 300, seed = seed), 5f, 0.85f),
                am(lowpass(tone(95 * p, 70 * p, 1300, Wave.SAW, vol = 0.35f, attackMs = 150, releaseMs = 300), 0.12f), 9f, 0.7f),
            )
            Sfx.CHARGE_UP -> mix(  // rising power-up hum, ~2.2 s
                lowpass(tone(90f, 520f, 2200, Wave.SAW, vol = 0.45f, vibHz = 14f, vibDepth = 0.06f, attackMs = 200, releaseMs = 60), 0.25f),
                am(noise(2200, 0.25f, 0.5f, attackMs = 600, releaseMs = 60, seed = seed), 23f, 0.7f),
            )
            Sfx.CHARGE_BURST -> mix(
                noise(600, 0.9f, 0.7f, attackMs = 2, releaseMs = 500, seed = seed),
                tone(1600f, 200f, 600, Wave.SQUARE, vol = 0.35f, attackMs = 2, releaseMs = 400),
            )
            Sfx.HMPH -> mix(noise(220, 0.4f, 0.2f, attackMs = 10, releaseMs = 120, seed = seed), tone(300 * p, 240 * p, 220, Wave.TRIANGLE, vol = 0.4f))
            Sfx.WHO -> seq(tone(700 * p, 640 * p, 120, Wave.SINE), silence(30), tone(640 * p, 1150 * p, 220, Wave.SINE, vibHz = 6f, vibDepth = 0.02f)) // "bwee?"
            Sfx.DIZZY -> seq(   // wobbly "wooo-ooo" spinning down, then little cartoon chirps
                tone(900 * p, 300 * p, 900, Wave.SINE, vol = 0.5f, vibHz = 7f, vibDepth = 0.12f, attackMs = 30, releaseMs = 120),
                silence(60),
                repeat(tone(1800 * p, 2300 * p, 60, Wave.TRIANGLE, vol = 0.4f, releaseMs = 20), 3, 70),
            )
            Sfx.QR_ACK -> seq(
                tone(2000f, 2000f, 55, Wave.SQUARE, vol = 0.22f, releaseMs = 10), silence(50),
                tone(2600f, 2600f, 70, Wave.SQUARE, vol = 0.22f, releaseMs = 15),
            )
        }
    }
}

/** 16-bit PCM mono WAV encoder. */
object WavWriter {
    fun encode(clip: FloatArray, sampleRate: Int = ToneSynth.SR): ByteArray =
        encode(ShortArray(clip.size) { (clip[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, sampleRate, 1)

    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels)
        i32(sampleRate); i32(sampleRate * channels * 2); i16(channels * 2); i16(16)
        out.write("data".toByteArray()); i32(dataLen)
        val buf = ByteArray(dataLen)
        for (i in pcm.indices) {
            buf[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            buf[i * 2 + 1] = ((pcm[i].toInt() shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        return out.toByteArray()
    }

    /** Parsed PCM-16 WAV (any channel count), or null if the format isn't PCM-16. */
    class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun decodePcm16(bytes: ByteArray): Pcm? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        var pos = 12
        var rate = 0; var ch = 0; var bits = 0; var fmt = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            var len = u32(pos + 4)
            val body = pos + 8
            if (id != "data" && (len < 0 || body + len > bytes.size)) return null
            if (id == "fmt " && body + 16 <= bytes.size) {
                fmt = u16(body); ch = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14)
            } else if (id == "data") {
                if (fmt != 1 || bits != 16 || ch < 1 || rate <= 0) return null
                // Some engines write 0 / 0xFFFFFFFF while streaming: clamp to the real size.
                if (len <= 0 || body + len > bytes.size) len = bytes.size - body
                val n = len / 2
                val s = ShortArray(n) { i -> u16(body + i * 2).toShort() }
                return Pcm(rate, ch, s)
            }
            pos = body + len + (len and 1)
        }
        return null
    }
}

/**
 * Post-processes a TTS WAV so it sounds like a little robot: partial ring modulation (metallic
 * buzz) plus a short comb echo (tin-can resonance). The pitch itself is raised by the TTS engine.
 */
object RobotFx {
    fun process(wav: ByteArray, ringHz: Float = 55f, ringMix: Float = 0.4f, combMs: Float = 6f, combGain: Float = 0.35f): ByteArray? {
        val pcm = WavWriter.decodePcm16(wav) ?: return null
        val ch = pcm.channels
        val frames = pcm.samples.size / ch
        if (frames == 0) return null
        val delay = max(1, (pcm.sampleRate * combMs / 1000f).toInt())
        val mono = FloatArray(frames) { f ->
            var acc = 0f
            for (c in 0 until ch) acc += pcm.samples[f * ch + c] / 32768f
            acc / ch
        }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val x = mono[i]
            val ring = x * sin(2 * PI * ringHz * i / pcm.sampleRate).toFloat()
            var y = x * (1f - ringMix) + ring * ringMix * 1.6f
            if (i >= delay) y += out[i - delay] * combGain
            out[i] = y
        }
        val norm = ToneSynth.normalize(out, 0.9f)
        // Short fade so the clip doesn't click.
        val fade = min(frames, pcm.sampleRate / 100)
        for (i in 0 until fade) { val k = i.toFloat() / fade; norm[i] *= k; norm[frames - 1 - i] *= k }
        return WavWriter.encode(ShortArray(frames) { (norm[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }, pcm.sampleRate, 1)
    }
}
