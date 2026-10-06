package com.example.eyebot

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Cheap frame-difference motion detector on the luminance (Y) plane. Samples a coarse grid,
 * compares it with the previous frame and reports which fraction of cells changed and where
 * the moving area's centroid is (upright, normalised 0..1). The face box is excluded so head
 * movement doesn't count as a hand gesture. (Unchanged from v2.)
 */
class MotionDetector(private val cols: Int = 48, private val rows: Int = 36) {

    data class Result(val fraction: Float, val centroidX: Float, val centroidY: Float)

    private var prev = IntArray(cols * rows)
    private var cur = IntArray(cols * rows)
    private var hasPrev = false

    fun process(image: ImageProxy, rotation: Int, excludeBox: RectF?): Result {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height

        var valid = 0
        var changed = 0
        var sumX = 0f
        var sumY = 0f

        for (r in 0 until rows) {
            val v = (r + 0.5f) / rows
            val py = (h * v).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val u = (c + 0.5f) / cols
                val px = (w * u).toInt().coerceIn(0, w - 1)
                val i = r * cols + c
                val lum = buffer.get(py * rowStride + px * pixelStride).toInt() and 0xFF
                cur[i] = lum
                if (!hasPrev) continue

                // Sensor (u, v) -> upright (ux, uy)
                val ux: Float
                val uy: Float
                when (rotation) {
                    90 -> { ux = 1f - v; uy = u }
                    180 -> { ux = 1f - u; uy = 1f - v }
                    270 -> { ux = v; uy = 1f - u }
                    else -> { ux = u; uy = v }
                }
                if (excludeBox != null && excludeBox.contains(ux, uy)) continue
                valid++
                if (abs(lum - prev[i]) > DIFF_THRESHOLD) {
                    changed++
                    sumX += ux
                    sumY += uy
                }
            }
        }

        val t = prev; prev = cur; cur = t
        val first = !hasPrev
        hasPrev = true
        if (first || valid == 0) return Result(0f, 0.5f, 0.5f)
        return Result(
            changed.toFloat() / valid,
            if (changed > 0) sumX / changed else 0.5f,
            if (changed > 0) sumY / changed else 0.5f,
        )
    }

    companion object {
        const val DIFF_THRESHOLD = 28
    }
}

/** Detects a side-to-side hand wave from the motion centroid's X reversals. */
class WaveDetector {
    private data class Sample(val t: Long, val active: Boolean, val cx: Float, val cy: Float)

    private val window = ArrayDeque<Sample>()
    private var lastTrigger = 0L

    fun update(now: Long, motion: MotionDetector.Result, faceMoved: Boolean): Boolean {
        val f = motion.fraction
        val active = f in MIN_FRACTION..MAX_FRACTION && !faceMoved
        window.addLast(Sample(now, active, motion.centroidX, motion.centroidY))
        while (window.isNotEmpty() && now - window.first().t > WINDOW_MS) window.removeFirst()

        if (now - lastTrigger < COOLDOWN_MS) return false
        if (window.size < 6 || window.last().t - window.first().t < MIN_SPAN_MS) return false

        val act = window.filter { it.active }
        if (act.size.toFloat() / window.size < MIN_ACTIVE_RATIO) return false

        val spanX = act.maxOf { it.cx } - act.minOf { it.cx }
        val spanY = act.maxOf { it.cy } - act.minOf { it.cy }
        if (spanY > 0.7f * spanX) return false

        var reversals = 0
        var dir = 0
        var extreme = act.first().cx
        for (s in act) {
            val d = s.cx - extreme
            when {
                dir >= 0 && d < -AMPLITUDE -> { if (dir > 0) reversals++; dir = -1; extreme = s.cx }
                dir <= 0 && d > AMPLITUDE -> { if (dir < 0) reversals++; dir = 1; extreme = s.cx }
                dir > 0 && s.cx > extreme -> extreme = s.cx
                dir < 0 && s.cx < extreme -> extreme = s.cx
            }
        }
        if (reversals < MIN_REVERSALS) return false
        lastTrigger = now
        window.clear()
        return true
    }

    companion object {
        const val WINDOW_MS = 1500L
        const val MIN_SPAN_MS = 900L
        const val COOLDOWN_MS = 4000L
        const val MIN_FRACTION = 0.025f
        const val MAX_FRACTION = 0.45f
        const val MIN_ACTIVE_RATIO = 0.6f
        const val AMPLITUDE = 0.035f
        const val MIN_REVERSALS = 2
    }
}

/** Detects a hand moving in a circle: the centroid sweeps > 1.25 turns around its mean. */
class CircleDetector {
    private data class Sample(val t: Long, val x: Float, val y: Float)

    private val window = ArrayDeque<Sample>()
    private var lastTrigger = 0L

    fun update(now: Long, motion: MotionDetector.Result, faceMoved: Boolean): Boolean {
        val f = motion.fraction
        val active = f in WaveDetector.MIN_FRACTION..WaveDetector.MAX_FRACTION && !faceMoved
        if (active) window.addLast(Sample(now, motion.centroidX, motion.centroidY))
        while (window.isNotEmpty() && now - window.first().t > WINDOW_MS) window.removeFirst()

        if (now - lastTrigger < COOLDOWN_MS || window.size < MIN_SAMPLES) return false
        val mx = window.sumOf { it.x.toDouble() } / window.size
        val my = window.sumOf { it.y.toDouble() } / window.size
        val spanX = window.maxOf { it.x } - window.minOf { it.x }
        val spanY = window.maxOf { it.y } - window.minOf { it.y }
        if (spanX < MIN_SPAN || spanY < MIN_SPAN) return false

        var total = 0.0
        var prevA = Double.NaN
        for (s in window) {
            val a = atan2(s.y - my, s.x - mx)
            if (!prevA.isNaN()) {
                var d = a - prevA
                if (d > Math.PI) d -= 2 * Math.PI
                if (d < -Math.PI) d += 2 * Math.PI
                total += d
            }
            prevA = a
        }
        if (abs(total) <= MIN_TURNS * 2 * Math.PI) return false
        lastTrigger = now
        window.clear()
        return true
    }

    companion object {
        // Tuned down in v4.0.1 so HYPNOTIZED only happens on a deliberate hand circle.
        // Lower these again to make it easier to trigger.
        const val WINDOW_MS = 3000L        // the circles must happen within this time
        const val COOLDOWN_MS = 45_000L    // at most once every 45 s (was 5 s)
        const val MIN_SPAN = 0.12f         // circle must be at least 12 % of the frame wide AND tall (was 6 %)
        const val MIN_TURNS = 2.0          // two full circles (was 1.25)
        const val MIN_SAMPLES = 18         // enough motion frames to be sure (was 12)
    }
}
