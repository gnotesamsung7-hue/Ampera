package com.example.eyebot

import kotlin.math.sqrt

/*
 * Pure-Kotlin logic for Car Mode and power (no Android imports, unit-tested).
 * The Android side (CarModeController) only feeds these classes sensor numbers.
 */

/** Watches the driver's eyes and mouth for microsleeps, heavy blinking (PERCLOS) and yawns. */
class DrowsinessMonitor(
    private val closedBelow: Float = 0.25f,
    private val microsleepMs: Long = 1_000,
    private val perclosWindowMs: Long = 60_000,
    private val perclosLimit: Float = 0.20f,
    private val yawnOpenRatio: Float = 0.45f,
    private val yawnMinMs: Long = 1_200,
    private val yawnWindowMs: Long = 10 * 60_000,
    private val yawnsForDrowsy: Int = 3,
) {
    enum class Event { MICROSLEEP, DROWSY, YAWN }

    private data class Sample(val t: Long, val closed: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var closedSince = 0L
    private var microsleepFired = false
    private var mouthOpenSince = 0L
    private var yawnCounted = false
    private val yawns = ArrayDeque<Long>()
    private var lastDrowsyAt = -1_000_000L

    /** Current PERCLOS: share of the last minute with eyes closed. */
    var perclos = 0f
        private set

    /**
     * @param eyesOpen min(left, right) eye-open probability, or null if not visible / not frontal
     * @param mouthOpen mouth opening / face height, or null if unknown
     */
    fun update(t: Long, eyesOpen: Float?, mouthOpen: Float?): List<Event> {
        val out = ArrayList<Event>(1)
        if (eyesOpen == null) {
            closedSince = 0L; microsleepFired = false
        } else {
            val closed = eyesOpen < closedBelow
            samples.addLast(Sample(t, closed))
            while (samples.isNotEmpty() && t - samples.first().t > perclosWindowMs) samples.removeFirst()
            // Only judge PERCLOS once we have at least half a minute of eye data.
            perclos = if (samples.size < 20 || t - samples.first().t < PERCLOS_MIN_SPAN_MS) 0f
            else samples.count { it.closed }.toFloat() / samples.size

            if (closed) {
                if (closedSince == 0L) closedSince = t
                if (!microsleepFired && t - closedSince >= microsleepMs) { microsleepFired = true; out += Event.MICROSLEEP }
            } else {
                closedSince = 0L; microsleepFired = false
            }
        }

        if (mouthOpen != null && mouthOpen > yawnOpenRatio) {
            if (mouthOpenSince == 0L) mouthOpenSince = t
            if (!yawnCounted && t - mouthOpenSince >= yawnMinMs) {
                yawnCounted = true
                yawns.addLast(t)
                out += Event.YAWN
            }
        } else {
            mouthOpenSince = 0L; yawnCounted = false
        }
        while (yawns.isNotEmpty() && t - yawns.first() > yawnWindowMs) yawns.removeFirst()

        val drowsy = perclos > perclosLimit || yawns.size >= yawnsForDrowsy
        if (drowsy && t - lastDrowsyAt > DROWSY_REPEAT_MS) { lastDrowsyAt = t; out += Event.DROWSY }
        return out
    }

    fun reset() {
        samples.clear(); yawns.clear(); closedSince = 0L; microsleepFired = false
        mouthOpenSince = 0L; yawnCounted = false; perclos = 0f
    }

    companion object {
        const val DROWSY_REPEAT_MS = 5 * 60_000L
        const val PERCLOS_MIN_SPAN_MS = 30_000L
    }
}

/** Turns accelerometer readings (linear acceleration, m/s²) into bumps and hard manoeuvres. */
class RoadFeel(
    private val bumpPeak: Float = 7.0f,
    private val maneuverLevel: Float = 3.5f,
    private val maneuverMs: Long = 400,
    private val cooldownMs: Long = 2_500,
) {
    enum class Event { BUMP, HARD_MANEUVER }

    private var sustainedSince = 0L
    private var lastEventAt = -1_000_000L
    private var smooth = 0f

    fun update(t: Long, ax: Float, ay: Float, az: Float): Event? {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        smooth += (mag - smooth) * 0.25f
        if (t - lastEventAt < cooldownMs) return null
        if (mag > bumpPeak && smooth < maneuverLevel * 1.5f) {      // short sharp spike
            lastEventAt = t; sustainedSince = 0L
            return Event.BUMP
        }
        if (smooth > maneuverLevel) {
            if (sustainedSince == 0L) sustainedSince = t
            if (t - sustainedSince >= maneuverMs) { lastEventAt = t; sustainedSince = 0L; return Event.HARD_MANEUVER }
        } else sustainedSince = 0L
        return null
    }
}

/** Detects the phone being shaken by hand: several hard jolts within a short window. */
class ShakeDetector(
    private val jolt: Float = 12f,          // m/s² of linear acceleration
    private val joltsNeeded: Int = 3,
    private val windowMs: Long = 1_200,
    private val cooldownMs: Long = 6_000,
) {
    private val jolts = ArrayDeque<Long>()
    private var lastJoltAt = -1_000L
    private var lastShakeAt = -1_000_000L

    fun update(t: Long, ax: Float, ay: Float, az: Float): Boolean {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        if (mag > jolt && t - lastJoltAt > 120) { jolts.addLast(t); lastJoltAt = t }
        while (jolts.isNotEmpty() && t - jolts.first() > windowMs) jolts.removeFirst()
        if (jolts.size >= joltsNeeded && t - lastShakeAt > cooldownMs) {
            lastShakeAt = t
            jolts.clear()
            return true
        }
        return false
    }
}

/** Decides driving vs parked from GPS speed, and tracks the current trip. */
class TripTracker(
    private val driveAboveKmh: Float = 10f,
    private val driveConfirmMs: Long = 5_000,
    private val parkBelowKmh: Float = 3f,
    private val parkConfirmMs: Long = 60_000,
    private val tripEndParkedMs: Long = 3 * 60_000,
    private val breakEveryMs: Long = 2 * 60 * 60_000,
) {
    enum class Event { STARTED_DRIVING, PARKED, TRIP_ENDED, BREAK_DUE }

    var driving = false
        private set
    var inTrip = false
        private set
    var tripStartedAt = 0L
        private set
    private var drivingSince = 0L        // cumulative since last break
    private var aboveSince = 0L
    private var belowSince = 0L
    private var lastBreakNagAt = 0L
    var passengersSeen = false
    var animalsSeen = false

    /** @param speedKmh null when GPS has no fix. */
    fun update(t: Long, speedKmh: Float?): List<Event> {
        val out = ArrayList<Event>(1)
        val v = speedKmh ?: return out
        if (v >= driveAboveKmh) {
            belowSince = 0L
            if (aboveSince == 0L) aboveSince = t
            if (!driving && t - aboveSince >= driveConfirmMs) {
                driving = true
                if (!inTrip) {
                    inTrip = true; tripStartedAt = t; drivingSince = t; lastBreakNagAt = t
                    passengersSeen = false; animalsSeen = false
                }
                out += Event.STARTED_DRIVING
            }
        } else if (v <= parkBelowKmh) {
            aboveSince = 0L
            if (belowSince == 0L) belowSince = t
            if (driving && t - belowSince >= parkConfirmMs) { driving = false; out += Event.PARKED }
            if (inTrip && !driving && t - belowSince >= tripEndParkedMs) { inTrip = false; out += Event.TRIP_ENDED }
        }
        if (driving && t - lastBreakNagAt >= breakEveryMs) { lastBreakNagAt = t; out += Event.BREAK_DUE }
        return out
    }

    /** A rest stop (e.g. parked 10+ min) resets the break timer. */
    fun tookBreak(t: Long) { lastBreakNagAt = t; drivingSince = t }

    /** Force a state for testing / the manual "Car mode: driving" setting. */
    fun forceDriving(on: Boolean, t: Long) {
        if (on && !driving) { driving = true; if (!inTrip) { inTrip = true; tripStartedAt = t; lastBreakNagAt = t; passengersSeen = false; animalsSeen = false } }
        if (!on) driving = false
    }

    fun endTrip() { inTrip = false; driving = false }
}

/** Battery + temperature state machine (power-up animation, low battery, heat protection). */
class PowerWatch(
    private val lowPercent: Int = 15,
    private val criticalPercent: Int = 5,
    private val hotC: Float = 42f,
    private val coolDownC: Float = 46f,
    private val recoverC: Float = 40f,
) {
    enum class Event { PLUGGED_IN, UNPLUGGED, LOW, CRITICAL, HOT, COOL_DOWN, COOLED }
    enum class Heat { OK, HOT, COOL_DOWN }

    var percent = 100; private set
    var charging = false; private set
    var heat = Heat.OK; private set
    private var known = false
    private var lowFired = false
    private var criticalFired = false

    val isLow get() = !charging && percent <= lowPercent
    val isCritical get() = !charging && percent <= criticalPercent

    fun update(percentNow: Int, chargingNow: Boolean, tempC: Float?): List<Event> {
        val out = ArrayList<Event>(2)
        if (known && chargingNow != charging) out += if (chargingNow) Event.PLUGGED_IN else Event.UNPLUGGED
        charging = chargingNow
        percent = percentNow.coerceIn(0, 100)
        known = true

        if (charging || percent > lowPercent + 3) { lowFired = false; criticalFired = false }
        if (!charging && percent <= lowPercent && !lowFired) { lowFired = true; out += Event.LOW }
        if (!charging && percent <= criticalPercent && !criticalFired) { criticalFired = true; out += Event.CRITICAL }

        if (tempC != null) {
            val newHeat = when {
                tempC >= coolDownC -> Heat.COOL_DOWN
                heat == Heat.COOL_DOWN && tempC > recoverC -> Heat.COOL_DOWN
                tempC >= hotC -> Heat.HOT
                heat == Heat.HOT && tempC > recoverC -> Heat.HOT
                else -> Heat.OK
            }
            if (newHeat != heat) {
                out += when (newHeat) { Heat.HOT -> Event.HOT; Heat.COOL_DOWN -> Event.COOL_DOWN; Heat.OK -> Event.COOLED }
                heat = newHeat
            }
        }
        return out
    }
}
