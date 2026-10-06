package com.example.eyebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorClassifierTest {
    private fun classify(fill: Int, fillShare: Float, other: Int = 0x808080, n: Int = 400): ColorClassifier.Result? {
        val c = ColorClassifier()
        c.reset()
        val k = (n * fillShare).toInt()
        repeat(n) { i ->
            val rgb = if (i < k) fill else other
            c.addRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }
        return c.result()
    }

    @Test fun primaryCardsAreDetected() {
        assertEquals(ColorBucket.RED, classify(0xD02020, 0.8f)?.bucket)
        assertEquals(ColorBucket.GREEN, classify(0x20B040, 0.8f)?.bucket)
        assertEquals(ColorBucket.BLUE, classify(0x2040D0, 0.8f)?.bucket)
        assertEquals(ColorBucket.CYAN, classify(0x20C0D0, 0.8f)?.bucket)
        assertEquals(ColorBucket.YELLOW, classify(0xE0D020, 0.8f)?.bucket)
        assertEquals(ColorBucket.MAGENTA, classify(0xD020B0, 0.8f)?.bucket)
    }

    @Test fun smallPatchOrGreyIsIgnored() {
        assertNull(classify(0x2040D0, 0.3f))          // card too small in the region
        assertNull(classify(0x808080, 1f))            // grey wall
        assertNull(classify(0xF0F0F0, 1f))            // white
        assertNull(classify(0x101010, 1f))            // dark room
    }

    @Test fun skinTonesAreNotRed() {
        assertNull(classify(0xE0AC90, 1f))            // light skin
        assertNull(classify(0xC68642, 1f))            // tan skin (orange hue, excluded)
        assertNull(classify(0x8D5524, 1f))            // darker skin
    }

    @Test fun hueMeanWrapsAroundZero() {
        val c = ColorClassifier()
        repeat(100) { c.addRgb(230, 20, 40) }         // hue ~354
        repeat(100) { c.addRgb(230, 40, 20) }         // hue ~6
        val r = c.result()
        assertNotNull(r)
        assertEquals(ColorBucket.RED, r!!.bucket)
        assertTrue(r.meanHue < 10f || r.meanHue > 350f)
    }

    @Test fun stabilizerNeedsConsecutiveFrames() {
        val s = ColorStabilizer(enterFrames = 3, exitFrames = 2)
        val blue = ColorClassifier.Result(ColorBucket.BLUE, 220f, 0.8f)
        assertNull(s.update(blue))
        assertNull(s.update(blue))
        assertEquals(ColorBucket.BLUE, s.update(blue)?.bucket)
        assertEquals(ColorBucket.BLUE, s.update(null)?.bucket)   // one miss is tolerated
        assertNull(s.update(null))
    }

    @Test fun hsvRoundTrip() {
        val hsv = FloatArray(3)
        ColorMath.rgbToHsv(0, 0, 255, hsv)
        assertEquals(240f, hsv[0], 0.5f)
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToArgb(0f, 1f, 1f))
        val rgb = ColorMath.yuvToRgb(76, 85, 255)     // pure red in BT.601
        assertTrue(((rgb shr 16) and 0xFF) > 240)
    }
}
