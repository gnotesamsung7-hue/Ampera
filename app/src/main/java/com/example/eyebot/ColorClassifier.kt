package com.example.eyebot

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-Kotlin colour logic for the "colour card" feature (no Android imports, unit-tested).
 *
 *  1. [ColorClassifier] receives RGB samples from a camera region, converts them to HSV and
 *     decides whether one saturated hue dominates (a coloured card held up to the camera).
 *  2. [ColorStabilizer] debounces that per-frame decision so the eyes don't flicker.
 */
enum class ColorBucket(
    val label: String,
    /** Hue range in degrees, [from, to). A range with from > to wraps through 0. */
    val hueFrom: Float,
    val hueTo: Float,
    /** Minimum HSV saturation / value for a sample to count for this bucket. */
    val minSat: Float,
    val minVal: Float,
) {
    // Red and orange-ish hues need higher saturation so skin tones don't trigger them.
    RED("red", 345f, 15f, 0.60f, 0.25f),
    YELLOW("yellow", 42f, 70f, 0.50f, 0.35f),
    GREEN("green", 75f, 160f, 0.40f, 0.18f),
    CYAN("cyan", 160f, 200f, 0.40f, 0.25f),
    BLUE("blue", 200f, 255f, 0.40f, 0.18f),
    PURPLE("purple", 255f, 290f, 0.40f, 0.18f),
    MAGENTA("magenta", 290f, 345f, 0.45f, 0.25f);

    fun containsHue(h: Float): Boolean =
        if (hueFrom <= hueTo) h >= hueFrom && h < hueTo else h >= hueFrom || h < hueTo

    companion object {
        fun forHue(h: Float, s: Float, v: Float): ColorBucket? =
            entries.firstOrNull { it.containsHue(h) && s >= it.minSat && v >= it.minVal }
    }
}

object ColorMath {
    /** RGB 0..255 -> HSV (h 0..360, s 0..1, v 0..1) written into [out]. */
    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == rf -> 60f * (((gf - bf) / d) % 6f)
            mx == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    /** HSV -> opaque ARGB int (same layout as android.graphics.Color). */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1 - abs((hh / 60f) % 2f - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** BT.601 full-range YUV -> packed RGB (0xRRGGBB). */
    fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val uu = u - 128; val vv = v - 128
        val r = (y + 1.402f * vv).toInt().coerceIn(0, 255)
        val g = (y - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
        val b = (y + 1.772f * uu).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}

class ColorClassifier(
    /** Share of *all* sampled cells that must belong to the winning colour. */
    private val minCoverage: Float = 0.50f,
) {
    data class Result(val bucket: ColorBucket, val meanHue: Float, val coverage: Float)

    private val counts = IntArray(ColorBucket.entries.size)
    private val sumSin = DoubleArray(ColorBucket.entries.size)
    private val sumCos = DoubleArray(ColorBucket.entries.size)
    private var total = 0
    private val hsv = FloatArray(3)

    fun reset() {
        counts.fill(0); sumSin.fill(0.0); sumCos.fill(0.0); total = 0
    }

    fun addRgb(r: Int, g: Int, b: Int) {
        total++
        ColorMath.rgbToHsv(r, g, b, hsv)
        val bucket = ColorBucket.forHue(hsv[0], hsv[1], hsv[2]) ?: return
        val i = bucket.ordinal
        counts[i]++
        val rad = Math.toRadians(hsv[0].toDouble())
        sumSin[i] += sin(rad); sumCos[i] += cos(rad)
    }

    /** Dominant saturated colour of the samples added since [reset], or null. */
    fun result(): Result? {
        if (total < MIN_SAMPLES) return null
        var best = -1
        for (i in counts.indices) if (best < 0 || counts[i] > counts[best]) best = i
        if (best < 0 || counts[best] == 0) return null
        val coverage = counts[best].toFloat() / total
        if (coverage < minCoverage) return null
        var hue = Math.toDegrees(atan2(sumSin[best], sumCos[best])).toFloat()
        if (hue < 0f) hue += 360f
        return Result(ColorBucket.entries[best], hue, coverage)
    }

    companion object {
        const val MIN_SAMPLES = 40
    }
}

/**
 * Debounces per-frame colour results: a colour must win [enterFrames] frames in a row to be
 * shown, and must be missing for [exitFrames] frames before it is dropped.
 */
class ColorStabilizer(
    private val enterFrames: Int = 4,
    private val exitFrames: Int = 10,
) {
    data class Detection(val bucket: ColorBucket, val argb: Int, val coverage: Float)

    var current: Detection? = null
        private set
    private var candidate: ColorBucket? = null
    private var candidateCount = 0
    private var missCount = 0
    private var hueEma = Float.NaN

    /** Feed one frame's raw result; returns the stable detection (or null). */
    fun update(raw: ColorClassifier.Result?): Detection? {
        if (raw == null) {
            candidate = null; candidateCount = 0
            if (current != null && ++missCount >= exitFrames) { current = null; hueEma = Float.NaN }
            return current
        }
        missCount = 0
        if (current?.bucket == raw.bucket) {
            hueEma = blendHue(hueEma, raw.meanHue, 0.3f)
            current = Detection(raw.bucket, displayColor(hueEma), raw.coverage)
            return current
        }
        if (candidate == raw.bucket) candidateCount++ else { candidate = raw.bucket; candidateCount = 1 }
        if (candidateCount >= enterFrames) {
            hueEma = raw.meanHue
            current = Detection(raw.bucket, displayColor(hueEma), raw.coverage)
            candidate = null; candidateCount = 0
        }
        return current
    }

    fun reset() { current = null; candidate = null; candidateCount = 0; missCount = 0; hueEma = Float.NaN }

    private fun blendHue(a: Float, b: Float, k: Float): Float {
        if (a.isNaN()) return b
        var d = b - a
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return ((a + d * k) % 360f + 360f) % 360f
    }

    companion object {
        /** Mirror the card's hue, but as a bright glowing eye colour. */
        fun displayColor(hue: Float): Int = ColorMath.hsvToArgb(hue, 0.78f, 1f)
    }
}
