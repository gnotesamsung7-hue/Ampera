package com.example.eyebot

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Turns raw touches on the face into robot-ish meanings:
 *
 *  - slow stroke / rub (anywhere, best on the "head" = top of the screen) -> PET (repeats while stroking)
 *  - quick tap on the head zone                                            -> BOOP
 *  - quick tap on the eyes / lower face                                    -> POKE (startled)
 *  - 3+ taps within 1.2 s                                                  -> POKE_SPAM
 *  - fast fling                                                            -> FLING
 *  - double tap -> debug overlay, long press -> talk to Ampera (mic)
 *  - two-finger tap -> mute / unmute, two-finger hold -> settings
 */
class TouchInterpreter(
    context: Context,
    private val view: View,
    private val callbacks: Callbacks,
) : View.OnTouchListener {

    interface Callbacks {
        fun onTouchKind(kind: TouchKind, x: Float, y: Float)
        fun onFingerAt(x: Float, y: Float)
        fun onDoubleTap()
        fun onLongPress()
        fun onTwoFingerTap()
        fun onTwoFingerLongPress()
    }

    private val density = context.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var strokeDistance = 0f
    private val recentTaps = ArrayDeque<Long>()

    private var multiTouch = false
    private var twoDownAt = 0L
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoMoved = false
    private var twoLongFired = false
    private val twoFingerLong = Runnable { twoLongFired = true; callbacks.onTwoFingerLongPress() }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            strokeDistance = 0f
            callbacks.onFingerAt(e.x, e.y)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (multiTouch) return false
            val now = e.eventTime
            recentTaps.addLast(now)
            while (recentTaps.isNotEmpty() && now - recentTaps.first() > RAPID_WINDOW_MS) recentTaps.removeFirst()
            val kind = when {
                recentTaps.size >= RAPID_TAPS -> { recentTaps.clear(); TouchKind.POKE_SPAM }
                e.y < view.height * HEAD_ZONE -> TouchKind.BOOP
                else -> TouchKind.POKE
            }
            callbacks.onTouchKind(kind, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (multiTouch) return false
            callbacks.onFingerAt(e2.x, e2.y)
            strokeDistance += hypot(dx, dy)
            if (strokeDistance > view.width * PET_STROKE_FRACTION) {
                strokeDistance = 0f
                callbacks.onTouchKind(TouchKind.PET, e2.x, e2.y)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (multiTouch) return false
            if (hypot(vx, vy) > FLING_DP_PER_SEC * density) {
                callbacks.onTouchKind(TouchKind.FLING, e2.x, e2.y)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!multiTouch) callbacks.onDoubleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!multiTouch) callbacks.onLongPress()
        }
    })

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> multiTouch = false
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                multiTouch = true
                twoDownAt = ev.eventTime
                twoStartX = ev.getX(1); twoStartY = ev.getY(1)
                twoMoved = false
                twoLongFired = false
                handler.postDelayed(twoFingerLong, TWO_FINGER_HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> if (multiTouch && ev.pointerCount >= 2 && !twoMoved) {
                if (hypot(ev.getX(1) - twoStartX, ev.getY(1) - twoStartY) > slop * 2) {
                    twoMoved = true
                    handler.removeCallbacks(twoFingerLong)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (multiTouch && ev.pointerCount == 2) {
                handler.removeCallbacks(twoFingerLong)
                if (!twoMoved && !twoLongFired && ev.eventTime - twoDownAt < TWO_FINGER_TAP_MS) callbacks.onTwoFingerTap()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(twoFingerLong)
        }
        detector.onTouchEvent(ev)
        return true
    }

    companion object {
        /** Top part of the screen counts as the robot's "head". */
        const val HEAD_ZONE = 0.3f
        const val PET_STROKE_FRACTION = 0.18f
        const val FLING_DP_PER_SEC = 2200f
        const val RAPID_TAPS = 3
        const val RAPID_WINDOW_MS = 1200L
        const val TWO_FINGER_TAP_MS = 350L
        const val TWO_FINGER_HOLD_MS = 800L
    }
}

/** Persistent user settings (two-finger long-press opens the dialog). */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("eyebot", Context.MODE_PRIVATE)

    var sound by flag("sound", true)
    var voice by flag("voice", true)
    var robotVoiceFx by flag("robot_voice_fx", false)   // Ampera: natural voice by default
    var colorEyes by flag("color_eyes", true)
    var qrCards by flag("qr_cards", true)
    var servo by flag("servo", true)
    var invertPan by flag("invert_pan", false)
    var invertTilt by flag("invert_tilt", false)
    var bleAutoConnect by flag("ble_auto", false)

    // ---- v4 ----
    var recognition by flag("recognition", true)
    var animals by flag("animals", true)
    var wifiAutoConnect by flag("wifi_auto", false)
    var oneEye by flag("one_eye", true)

    // ---- Ampera talk-back ----
    /** Background "Hey Ampera" listening (uses more battery). */
    var wakeWord by flag("wake_word", false)

    // ---- Ampera: Fish Audio voice ----
    var fishEnabled by flag("fish_enabled", false)
    var fishKey: String
        get() = prefs.getString("fish_key", "") ?: ""
        set(v) { prefs.edit().putString("fish_key", v.trim()).apply() }
    var fishVoice: String
        get() = prefs.getString("fish_voice", "") ?: ""
        set(v) { prefs.edit().putString("fish_voice", v.trim()).apply() }
    var fishModel: String
        get() = prefs.getString("fish_model", FishVoice.DEFAULT_MODEL) ?: FishVoice.DEFAULT_MODEL
        set(v) { prefs.edit().putString("fish_model", v).apply() }

    var chattiness: Chattiness
        get() = Chattiness.entries.firstOrNull { it.name == prefs.getString("chattiness", null) } ?: Chattiness.NORMAL
        set(v) { prefs.edit().putString("chattiness", v.name).apply() }

    /** Eye size relative to v3 (0.5 = the v4 default "Small"). */
    var eyeScale: Float
        get() = prefs.getFloat("eye_scale", VectorFaceView.DEFAULT_EYE_SCALE)
        set(v) { prefs.edit().putFloat("eye_scale", v).apply() }

    var voiceMode: VoiceMode
        get() = VoiceMode.entries.firstOrNull { it.name == prefs.getString("voice_mode", null) } ?: VoiceMode.WORDS   // Ampera talks in words by default
        set(v) { prefs.edit().putString("voice_mode", v.name).apply() }

    var carMode: CarModeSetting
        get() = CarModeSetting.entries.firstOrNull { it.name == prefs.getString("car_mode", null) } ?: CarModeSetting.AUTO
        set(v) { prefs.edit().putString("car_mode", v.name).apply() }

    var mood: String?
        get() = prefs.getString("mood", null)
        set(v) { prefs.edit().putString("mood", v).apply() }

    private fun flag(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
}

/** Settings ▸ Car mode. */
enum class CarModeSetting(val label: String) {
    AUTO("Automatic (GPS speed)"),
    ALWAYS("Always on (test / passenger)"),
    OFF("Off"),
}
