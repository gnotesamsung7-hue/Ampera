package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {
    @Test fun everySfxRendersAudibleCleanClips() {
        for (s in Sfx.entries) for (v in 0 until SfxLibrary.VARIANTS) {
            val clip = SfxLibrary.render(s, v)
            assertTrue("$s too short", clip.size > ToneSynth.SR / 50)
            assertTrue("$s too long", clip.size < ToneSynth.SR * 3)
            val peak = clip.maxOf { abs(it) }
            assertTrue("$s silent", peak > 0.3f)
            assertTrue("$s clips", peak <= 1f)
            assertTrue("$s NaN", clip.none { it.isNaN() })
        }
    }

    @Test fun wavRoundTrip() {
        val clip = ToneSynth.tone(440f, 440f, 100)
        val wav = WavWriter.encode(clip)
        assertEquals(44 + clip.size * 2, wav.size)
        val pcm = WavWriter.decodePcm16(wav)
        assertNotNull(pcm)
        assertEquals(ToneSynth.SR, pcm!!.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(clip.size, pcm.samples.size)
    }

    @Test fun robotFxProcessesTtsStyleWav() {
        val speechLike = ToneSynth.seq(ToneSynth.tone(180f, 260f, 300, ToneSynth.Wave.SAW), ToneSynth.tone(260f, 150f, 300, ToneSynth.Wave.SAW))
        val pcm = ShortArray(speechLike.size * 2) { i -> (speechLike[i / 2] * 20000).toInt().toShort() }   // stereo
        val out = RobotFx.process(WavWriter.encode(pcm, 24000, 2))
        assertNotNull(out)
        val decoded = WavWriter.decodePcm16(out!!)!!
        assertEquals(1, decoded.channels)
        assertEquals(24000, decoded.sampleRate)
        assertEquals(speechLike.size, decoded.samples.size)
    }

    @Test fun rejectsNonWav() {
        assertEquals(null, RobotFx.process(ByteArray(100)))
    }
}
