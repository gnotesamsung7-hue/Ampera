package com.example.eyebot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Turns the tracked face offset into pan / tilt servo angles (pure Kotlin, unit-tested).
 *
 * Control law: velocity proportional to the face's offset from the frame centre, so the
 * phone keeps turning until the face is centred (closed loop through the camera). A dead zone
 * stops jitter, and the speed is capped so the servos don't whip around.
 *
 * Output protocol (one ASCII line per update, see firmware/esp32_pantilt):
 *     S<pan>,<tilt>\n      e.g. "S97,84\n"   angles in whole degrees, 0..180
 */
class PanTiltController(var config: Config = Config()) {

    data class Config(
        val panCenter: Float = 90f,
        val tiltCenter: Float = 90f,
        val panMin: Float = 15f,
        val panMax: Float = 165f,
        val tiltMin: Float = 55f,
        val tiltMax: Float = 125f,
        /** Flip if the stand turns away from you instead of towards you. */
        val invertPan: Boolean = false,
        val invertTilt: Boolean = false,
        /** Degrees per second at full (±1) offset. */
        val gainDegPerSec: Float = 70f,
        val maxSpeedDegPerSec: Float = 120f,
        /** Offsets smaller than this (of the half-frame) are ignored. */
        val deadZone: Float = 0.08f,
        val searchAmplitudeDeg: Float = 45f,
        val searchPeriodSec: Float = 8f,
        val sleepTilt: Float = 70f,
        /** Minimum time between serial commands. */
        val minSendIntervalMs: Long = 40,
    )

    enum class Behavior { TRACK, SEARCH, SLEEP, PARTY, CENTER, HOLD }

    var pan = config.panCenter
        private set
    var tilt = config.tiltCenter
        private set

    private var t = 0f
    private var lastSentPan = Int.MIN_VALUE
    private var lastSentTilt = Int.MIN_VALUE
    private var lastSentAt = 0L

    /**
     * Advance one step.
     * @param offsetX face X offset, -1 (left of screen) .. +1 (right of screen)
     * @param offsetY face Y offset, -1 (top) .. +1 (bottom)
     */
    fun step(behavior: Behavior, offsetX: Float, offsetY: Float, dtSec: Float) {
        val dt = dtSec.coerceIn(0f, 0.25f)
        t += dt
        val c = config
        when (behavior) {
            Behavior.TRACK -> {
                val ex = deadZone(offsetX, c.deadZone)
                val ey = deadZone(offsetY, c.deadZone)
                val vp = (c.gainDegPerSec * ex).coerceIn(-c.maxSpeedDegPerSec, c.maxSpeedDegPerSec)
                val vt = (c.gainDegPerSec * ey).coerceIn(-c.maxSpeedDegPerSec, c.maxSpeedDegPerSec)
                pan += vp * dt * (if (c.invertPan) -1f else 1f)
                tilt += vt * dt * (if (c.invertTilt) -1f else 1f)
            }
            Behavior.SEARCH -> {
                val target = c.panCenter + c.searchAmplitudeDeg * sin(2 * PI * t / c.searchPeriodSec).toFloat()
                pan = approach(pan, target, c.maxSpeedDegPerSec * 0.4f * dt)
                tilt = approach(tilt, c.tiltCenter, c.maxSpeedDegPerSec * 0.3f * dt)
            }
            Behavior.SLEEP -> {
                pan = approach(pan, c.panCenter, 25f * dt)
                tilt = approach(tilt, c.sleepTilt, 15f * dt)
            }
            Behavior.PARTY -> {
                val tp = c.panCenter + 22f * sin(2 * PI * 1.0 * t).toFloat()
                val tt = c.tiltCenter + 8f * sin(2 * PI * 2.0 * t).toFloat()
                pan = approach(pan, tp, c.maxSpeedDegPerSec * dt)
                tilt = approach(tilt, tt, c.maxSpeedDegPerSec * dt)
            }
            Behavior.CENTER -> {
                pan = approach(pan, c.panCenter, c.maxSpeedDegPerSec * 0.5f * dt)
                tilt = approach(tilt, c.tiltCenter, c.maxSpeedDegPerSec * 0.5f * dt)
            }
            Behavior.HOLD -> Unit
        }
        pan = pan.coerceIn(c.panMin, c.panMax)
        tilt = tilt.coerceIn(c.tiltMin, c.tiltMax)
    }

    /** The next serial line to send, or null if nothing changed / too soon. */
    fun pendingCommand(nowMs: Long): String? {
        val p = pan.roundToInt(); val tl = tilt.roundToInt()
        if (p == lastSentPan && tl == lastSentTilt) return null
        if (nowMs - lastSentAt < config.minSendIntervalMs) return null
        lastSentPan = p; lastSentTilt = tl; lastSentAt = nowMs
        return format(p, tl)
    }

    /** Always returns the current position (e.g. right after connecting). */
    fun forceCommand(nowMs: Long = 0L): String {
        lastSentPan = pan.roundToInt(); lastSentTilt = tilt.roundToInt(); lastSentAt = nowMs
        return format(lastSentPan, lastSentTilt)
    }

    fun recenter() { pan = config.panCenter; tilt = config.tiltCenter }

    companion object {
        fun format(pan: Int, tilt: Int) = "S$pan,$tilt\n"

        fun deadZone(v: Float, dz: Float): Float {
            val a = abs(v)
            if (a <= dz) return 0f
            return sign(v) * ((a - dz) / (1f - dz)).coerceAtMost(1f)
        }

        fun approach(cur: Float, target: Float, maxStep: Float): Float {
            val d = target - cur
            return if (abs(d) <= maxStep) target else cur + sign(d) * maxStep
        }
    }
}

/** Splits a byte stream (serial / BLE notifications) into text lines. */
class LineAssembler(private val maxLine: Int = 256) {
    private val sb = StringBuilder()

    fun feed(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        for (b in bytes) {
            val ch = (b.toInt() and 0xFF).toChar()
            when {
                ch == '\n' || ch == '\r' -> { if (sb.isNotEmpty()) out += sb.toString().trim(); sb.setLength(0) }
                sb.length < maxLine -> sb.append(ch)
            }
        }
        return out.filter { it.isNotEmpty() }
    }
}
