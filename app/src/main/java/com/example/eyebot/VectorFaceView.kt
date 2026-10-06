package com.example.eyebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Custom View that draws the Vector-style face: two glowing rounded eyes that blink, follow a
 * gaze target and blend smoothly between emotion poses.
 *
 * v3 additions:
 *  - [setEyeColor]: animated eye colour (colour cards / COLOR: QR cards)
 *  - [partyMode]: rainbow colour cycling and bouncing
 *  - [dim] and [lowPower]: dark sleep mode / low-power screensaver (redraws at ~10 fps)
 *  - animations for CONTENT, HUNGRY, EATING, SAD, PARTY, SNEEZE and YAWN
 *  - [blink] with a slow "heavy" variant, [lookAtScreenPoint] for touch
 */
class VectorFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var emotion: Emotion = Emotion.IDLE
        set(value) {
            if (field == value) return
            field = value
            emotionTime = 0f
            targetPose = Poses.of(value)
            if (value != Emotion.SLEEPY && value != Emotion.HYPNOTIZED &&
                value != Emotion.SNEEZE && value != Emotion.YAWN
            ) blink()
        }

    /** Rainbow eyes + bounce. */
    var partyMode = false

    /** 0 = normal, 1 = black. Animated towards [dimTarget]. */
    var dim: Float
        get() = dimTarget
        set(value) { dimTarget = value.coerceIn(0f, 1f) }

    /** Redraw at ~10 fps instead of every vsync (screensaver). */
    var lowPower = false

    /** One big centred eye instead of two (Settings ▸ Eye style). */
    var oneEye = true

    /** Ampera: draw the eyes as chunky 8-bit pixels, with pixel lashes. */
    var pixelEyes = false

    /** v4: eye size relative to v3 (Settings ▸ Eye size). */
    var eyeScale = DEFAULT_EYE_SCALE

    /** v4: speech bubble text (null = hidden). */
    var bubbleText: String? = null
        set(value) { field = value; bubbleShownAt = elapsed }

    private var icon: PixelIcon? = null
    private var iconShownAt = 0f
    private var iconSeconds = 0f
    private var bubbleShownAt = 0f

    private val defaultEyeColor = ContextCompat.getColor(context, R.color.eye_color)
    private val heartColor = ContextCompat.getColor(context, R.color.heart_color)
    private val bgColor = ContextCompat.getColor(context, R.color.face_background)

    // Eye colour transition
    private var colorFrom = defaultEyeColor
    private var colorTo = defaultEyeColor
    private var colorT = 1f
    private var currentEyeColor = defaultEyeColor

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
    private val spiralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val zzzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fxPath = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val fxRect = RectF()
    private val rect = RectF()
    private val lidRect = RectF()
    private val clipPath = Path()
    private val spiralPath = Path()
    private val heartPath = buildUnitHeart()

    private var targetPose = Poses.of(Emotion.IDLE)
    private val curLeft = MutableEye()
    private val curRight = MutableEye()
    private val soloEye = MutableEye()

    private var gazeX = 0f
    private var gazeY = 0f
    private var targetGazeX = 0f
    private var targetGazeY = 0f
    private var lastExternalGaze = -10f

    private var elapsed = 0f
    private var emotionTime = 0f
    private var lastFrameNanos = 0L

    private var blinkT = -1f
    private var pendingDoubleBlink = false
    private var slowBlink = false
    private var nextBlinkAt = 2f
    private var nextGlanceAt = 3f

    private var dimTarget = 0f
    private var dimCurrent = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Gaze target, -1..+1 on both axes (0,0 = straight ahead). */
    fun lookAt(x: Float, y: Float) {
        targetGazeX = x.coerceIn(-1f, 1f)
        targetGazeY = y.coerceIn(-1f, 1f)
        lastExternalGaze = elapsed
    }

    /** Look at a point on this view, in pixels (used while being touched). */
    fun lookAtScreenPoint(px: Float, py: Float) {
        if (width == 0 || height == 0) return
        lookAt(px / width * 2f - 1f, py / height * 2f - 1f)
    }

    fun blink(double: Boolean = false, slow: Boolean = false) {
        if (blinkT < 0f) {
            blinkT = 0f
            pendingDoubleBlink = double
            slowBlink = slow
        }
    }

    /** Pop a pixel-art icon (dog, cat, bolt...) next to the eyes for [seconds]. */
    fun showIcon(i: PixelIcon, seconds: Float = 2.8f) {
        icon = i; iconShownAt = elapsed; iconSeconds = seconds
    }

    /** Animate the eyes to [argb]; null = default teal. */
    fun setEyeColor(argb: Int?, animate: Boolean = true) {
        val target = argb ?: defaultEyeColor
        if (target == colorTo) return
        colorFrom = currentEyeColor
        colorTo = target
        colorT = if (animate) 0f else 1f
    }

    // ---------------------------------------------------------------------------------------
    // Frame loop
    // ---------------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            lastFrameNanos = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val maxDt = if (lowPower) 0.15f else 0.05f
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(maxDt)
        lastFrameNanos = now
        update(dt)
        drawFace(canvas)
        if (isAttachedToWindow && windowVisibility == VISIBLE) {
            if (lowPower) postInvalidateDelayed(LOW_POWER_FRAME_MS) else postInvalidateOnAnimation()
        }
    }

    private fun update(dt: Float) {
        elapsed += dt
        emotionTime += dt

        val shapeSpeed = when (emotion) { Emotion.SLEEPY, Emotion.SAD -> 3f; else -> 10f }
        val shapeK = smoothing(dt, shapeSpeed)
        curLeft.approach(targetPose.left, shapeK)
        curRight.approach(targetPose.right, shapeK)

        updateProceduralGaze()
        val gazeSpeed = when (emotion) {
            Emotion.SLEEPY, Emotion.BORED, Emotion.SAD, Emotion.YAWN -> 3f
            Emotion.HYPNOTIZED -> 8f
            else -> 16f
        }
        val gK = smoothing(dt, gazeSpeed)
        gazeX += (targetGazeX - gazeX) * gK
        gazeY += (targetGazeY - gazeY) * gK

        updateBlink(dt)

        // Colour transition (~0.3 s) and dimming (~0.6 s)
        if (colorT < 1f) colorT = min(1f, colorT + dt / 0.3f)
        currentEyeColor = ColorUtils.blendARGB(colorFrom, colorTo, colorT)
        dimCurrent += (dimTarget - dimCurrent) * smoothing(dt, 4f)
    }

    private fun updateProceduralGaze() {
        val externalFresh = elapsed - lastExternalGaze < 0.5f
        when (emotion) {
            Emotion.SLEEPY -> { targetGazeX = 0f; targetGazeY = 0.35f }
            Emotion.SAD -> { targetGazeX = 0f; targetGazeY = 0.45f }
            Emotion.EATING -> { targetGazeX = 0f; targetGazeY = 0.3f }
            Emotion.YAWN -> { targetGazeX = 0f; targetGazeY = -0.4f }
            Emotion.SNEEZE -> { targetGazeX = 0f; targetGazeY = if (emotionTime < 0.9f) -0.3f else 0.25f }
            Emotion.BORED -> if (elapsed > nextGlanceAt) {
                when (Random.nextInt(4)) {
                    0 -> { targetGazeX = -0.7f; targetGazeY = 0.35f }
                    1 -> { targetGazeX = 0.7f; targetGazeY = 0.35f }
                    2 -> { targetGazeX = 0.2f; targetGazeY = -0.75f }   // eye roll
                    else -> { targetGazeX = 0f; targetGazeY = 0.2f }
                }
                nextGlanceAt = elapsed + 2.5f + Random.nextFloat() * 2.5f
            }
            Emotion.HYPNOTIZED -> {
                targetGazeX = cos(emotionTime * 2.4f) * 0.3f
                targetGazeY = sin(emotionTime * 2.4f) * 0.22f
            }
            Emotion.PARTY -> if (!externalFresh) {
                targetGazeX = cos(emotionTime * 4f) * 0.45f
                targetGazeY = sin(emotionTime * 8f) * 0.2f
            }
            Emotion.SEARCHING -> {
                val phase = (emotionTime % 3.2f) / 3.2f
                targetGazeX = when {
                    phase < 0.4f -> -0.85f
                    phase >= 0.5f && phase < 0.9f -> 0.85f
                    else -> 0f
                }
                targetGazeY = sin(emotionTime * 1.3f) * 0.15f
            }
            Emotion.CONFUSED -> if (Random.nextFloat() < 0.03f) {
                targetGazeX = Random.nextFloat() * 0.6f - 0.3f
                targetGazeY = Random.nextFloat() * 0.3f - 0.15f
            }
            // Inquisitive peeking around when nobody is there to look at.
            Emotion.CURIOUS, Emotion.IDLE -> if (!externalFresh && elapsed > nextGlanceAt) {
                val back = Random.nextFloat() < 0.4f
                targetGazeX = if (back) 0f else Random.nextFloat() * 1.4f - 0.7f
                targetGazeY = if (back) 0f else Random.nextFloat() * 0.6f - 0.3f
                nextGlanceAt = elapsed + (if (emotion == Emotion.CURIOUS) 0.6f else 1.2f) + Random.nextFloat() * 2f
            }
            Emotion.HUNGRY -> if (!externalFresh) { targetGazeX = 0f; targetGazeY = -0.15f }
            Emotion.POWER_UP, Emotion.ALERT -> { targetGazeX = 0f; targetGazeY = 0f }
            Emotion.DIZZY -> { targetGazeX = sin(emotionTime * 4f) * 0.35f; targetGazeY = cos(emotionTime * 3.3f) * 0.2f }
            Emotion.LOW_BATTERY -> if (!externalFresh) { targetGazeX = 0f; targetGazeY = -0.25f }
            else -> Unit
        }
    }

    private fun updateBlink(dt: Float) {
        if (blinkT >= 0f) {
            val duration = when {
                emotion == Emotion.SLEEPY -> 0.6f
                slowBlink -> 0.5f
                else -> 0.16f
            }
            blinkT += dt / duration
            if (blinkT >= 1f) {
                blinkT = if (pendingDoubleBlink) { pendingDoubleBlink = false; 0f } else -1f
            }
            return
        }
        if (elapsed >= nextBlinkAt) {
            val canBlink = emotion !in NO_AUTO_BLINK
            if (canBlink) blink(double = Random.nextFloat() < 0.2f)
            nextBlinkAt = elapsed + when (emotion) {
                Emotion.SLEEPY -> 1.2f + Random.nextFloat() * 1.5f
                Emotion.BORED -> 1.5f + Random.nextFloat() * 2f
                Emotion.EXCITED, Emotion.LOVESTRUCK, Emotion.PARTY -> 3f + Random.nextFloat() * 3f
                else -> 2f + Random.nextFloat() * 4f
            }
        }
    }

    private fun blinkClosure(): Float =
        if (blinkT < 0f) 0f else sin(blinkT.coerceIn(0f, 1f) * PI_F)

    /** Extra lid closure for scripted actions (sneeze squeeze, yawn). 0 = open, 1 = shut. */
    private fun actionClosure(): Float {
        val t = emotionTime
        return when (emotion) {
            Emotion.SNEEZE -> when {
                t < 0.9f -> 0.35f * (t / 0.9f) + 0.06f * sin(t * 55f)   // "ah... ah..."
                t < 1.25f -> 0.92f                                     // "CHOO!"
                t < 1.6f -> 0.92f * (1f - (t - 1.25f) / 0.35f)
                else -> 0f
            }
            Emotion.YAWN -> when {
                t < 0.8f -> 0.75f * (t / 0.8f)
                t < 1.9f -> 0.75f + 0.05f * sin(t * 6f)
                t < 2.5f -> 0.75f * (1f - (t - 1.9f) / 0.6f)
                else -> 0f
            }
            else -> 0f
        }
    }

    private fun heartbeat(): Float {
        val t = elapsed % 1f
        fun pulse(c: Float): Float { val x = (t - c) / 0.06f; return exp(-(x * x)) }
        return pulse(0.1f) + pulse(0.3f) * 0.6f
    }

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    private fun drawFace(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val baseW = min(w * 0.22f, h * 0.42f) * eyeScale
        val baseH = baseW * 1.15f
        val spacing = baseW * 1.45f
        val gazeShiftX = gazeX * w * 0.16f
        val gazeShiftY = gazeY * h * 0.14f

        var faceBob = 0f
        var faceShakeX = 0f
        var breathe = 1f
        var squash = 1f      // vertical eye squash (chomping)
        var tilt = 0f        // whole-face rotation, degrees
        val t = emotionTime
        when (emotion) {
            Emotion.SLEEPY -> breathe = 1f + sin(t * 1.4f) * 0.06f
            Emotion.BORED -> { val s = max(0f, sin(t * 1.25f)); faceBob = s * s * s * baseH * 0.08f }
            Emotion.EXCITED -> faceBob = -abs(sin(t * 9f)) * baseH * 0.1f
            Emotion.LOVESTRUCK -> faceBob = sin(t * 2f) * baseH * 0.04f
            Emotion.HAPPY -> faceBob = sin(t * 3f) * baseH * 0.02f
            Emotion.CONTENT -> { faceBob = sin(t * 2.2f) * baseH * 0.03f; tilt = sin(t * 1.6f) * 4f }
            Emotion.HUNGRY -> { faceShakeX = sin(t * 18f) * baseW * 0.012f; breathe = 1f + sin(t * 2.5f) * 0.04f }
            Emotion.EATING -> { squash = 1f - 0.28f * max(0f, sin(t * 13f)); faceBob = max(0f, sin(t * 13f)) * baseH * 0.05f }
            Emotion.SAD -> faceBob = baseH * 0.06f + sin(t * 1.2f) * baseH * 0.015f
            Emotion.PARTY -> { faceBob = -abs(sin(t * 7f)) * baseH * 0.14f; tilt = sin(t * 3.5f) * 7f }
            Emotion.SNEEZE -> when {
                t < 0.9f -> faceBob = -baseH * 0.08f * (t / 0.9f)
                t < 1.25f -> { faceBob = baseH * 0.16f; faceShakeX = sin(t * 70f) * baseW * 0.05f }
                else -> faceBob = baseH * 0.16f * max(0f, 1f - (t - 1.25f) / 0.4f)
            }
            Emotion.YAWN -> { faceBob = -sin(min(t / 2.5f, 1f) * PI_F) * baseH * 0.06f; breathe = 1f + 0.08f * sin(min(t / 2.5f, 1f) * PI_F) }
            Emotion.GRUMPY -> faceShakeX = if (t < 0.5f) sin(t * 40f) * baseW * 0.03f else 0f
            Emotion.ALERT -> faceShakeX = sin(t * 50f) * baseW * 0.02f
            Emotion.DIZZY -> {   // slow drunken sway that settles down
                val fade = (1f - t / 4f).coerceIn(0.35f, 1f)
                tilt = sin(t * 3.1f) * 12f * fade
                faceShakeX = sin(t * 2.3f) * baseW * 0.12f * fade
                faceBob = cos(t * 2.9f) * baseH * 0.06f * fade
            }
            Emotion.HOT -> { breathe = 1f + sin(t * 3f) * 0.05f; faceBob = baseH * 0.04f }
            Emotion.POWER_UP -> {
                val charge = (t / POWER_CHARGE_S).coerceIn(0f, 1f)
                if (t < POWER_CHARGE_S) faceShakeX = sin(t * 60f) * baseW * 0.04f * charge
                faceBob = -baseH * 0.05f * charge + if (t < POWER_CHARGE_S) sin(t * 47f) * baseH * 0.02f * charge else 0f
            }
            Emotion.LOW_BATTERY -> { faceBob = -baseH * 0.12f + sin(t * 1.3f) * baseH * 0.015f; breathe = 1f - 0.05f * (0.5f + 0.5f * sin(t * 1.1f)) }
            else -> Unit
        }
        if (partyMode && emotion != Emotion.PARTY) faceBob -= abs(sin(elapsed * 7f)) * baseH * 0.08f

        val cx = w / 2f + gazeShiftX + faceShakeX
        val cy = h / 2f + gazeShiftY + faceBob
        val closure = max(blinkClosure(), actionClosure())
        val leftScale = 1f - gazeX * 0.08f
        val rightScale = 1f + gazeX * 0.08f
        // In one-eye mode the decorations (Z z z, sweat, icons, aura) sit around the single eye.
        val leftX = if (oneEye) cx - spacing * 0.75f else cx - spacing / 2f
        val rightX = if (oneEye) cx + spacing * 0.75f else cx + spacing / 2f

        val (leftColor, rightColor) = eyeColors()

        if (emotion == Emotion.POWER_UP) drawPowerAura(canvas, leftX, rightX, cy, baseW, baseH)

        // Ampera: the eyes are drawn into a low-resolution bitmap and scaled up without
        // smoothing, so every shape (blink, lids, heart, spiral, glow) becomes 8-bit pixels.
        val ec = if (pixelEyes) beginPixels(w, h) else canvas
        ec.save()
        if (tilt != 0f) ec.rotate(tilt, w / 2f, h / 2f)
        if (oneEye) {
            // One big eye: the average of the two eye shapes, 1.7x the size.
            soloEye.blend(curLeft, curRight)
            val sw = baseW * SOLO_SCALE; val sh = baseH * SOLO_SCALE
            drawEye(ec, soloEye, cx, cy, sw, sh, 1f, closure, breathe * squash, 1f, leftColor, braveCore = !partyMode)
            if (emotion == Emotion.DIZZY) drawDizzyRing(ec, soloEye, cx, cy, sw, sh, 1f, 1f)
        } else {
            drawEye(ec, curLeft, leftX, cy, baseW, baseH, leftScale, closure, breathe * squash, 1f, leftColor)
            drawEye(ec, curRight, rightX, cy, baseW, baseH, rightScale, closure, breathe * squash, -1f, rightColor)
            if (emotion == Emotion.DIZZY) {
                drawDizzyRing(ec, curLeft, leftX, cy, baseW, baseH, leftScale, 1f)
                drawDizzyRing(ec, curRight, rightX, cy, baseW, baseH, rightScale, -1f)
            }
        }
        ec.restore()
        if (pixelEyes) endPixels(canvas)

        if (emotion == Emotion.SLEEPY && emotionTime > 1.5f) {
            drawZzz(canvas, rightX + baseW * 0.55f, cy - baseH * 0.55f, baseH, rightColor)
        }
        if (emotion == Emotion.DIZZY) drawDizzyStars(canvas, cx, cy - baseH * 0.95f, spacing, baseH, leftColor)
        if (emotion == Emotion.HOT) drawSweat(canvas, rightX + baseW * 0.62f, cy - baseH * 0.45f, baseH)
        if (emotion == Emotion.LOW_BATTERY) drawChargeCard(canvas, cx, cy + baseH * 0.95f, baseW, leftColor)
        if (emotion == Emotion.POWER_UP && emotionTime in POWER_CHARGE_S..(POWER_CHARGE_S + 0.45f)) {
            fxPaint.color = Color.WHITE
            fxPaint.alpha = (255 * (1f - (emotionTime - POWER_CHARGE_S) / 0.45f)).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, fxPaint)                       // the burst flash
        }
        icon?.let { ic ->
            val age = elapsed - iconShownAt
            if (age > iconSeconds) icon = null
            else drawIcon(canvas, ic, leftX - baseW * 1.35f, cy - baseH * 0.2f, baseH * 0.9f, age, leftColor)
        }
        bubbleText?.let { drawBubble(canvas, it, w / 2f, cy - baseH * (if (oneEye) 1.15f else 0.95f), w, baseH, leftColor) }

        if (dimCurrent > 0.01f) {
            dimPaint.alpha = (255 * dimCurrent).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, dimPaint)
        }
    }

    private fun eyeColors(): Pair<Int, Int> {
        // One-eye mode is always brave red (colour cards only tint the two-eye face).
        val base = if (oneEye) BRAVE_RED else currentEyeColor
        if (emotion == Emotion.POWER_UP) {
            val k = (emotionTime / POWER_CHARGE_S).coerceIn(0f, 1f)
            val gold = ColorUtils.blendARGB(base, GOLD, k)
            return gold to gold
        }
        if (emotion == Emotion.ALERT) {
            val to = if (oneEye) BRAVE_FLASH else ALERT_RED
            val flash = ColorUtils.blendARGB(base, to, 0.5f + 0.5f * sin(elapsed * 14f))
            return flash to flash
        }
        if (!partyMode) return base to base
        val hue = (elapsed * 160f) % 360f
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.95f, 0.6f)) to
            ColorUtils.HSLToColor(floatArrayOf((hue + 90f) % 360f, 0.95f, 0.6f))
    }

    private fun drawEye(
        canvas: Canvas, eye: MutableEye, centerX: Float, centerY: Float,
        baseW: Float, baseH: Float, perspective: Float, blinkClosure: Float,
        breathe: Float, spinDir: Float, baseColor: Int,
        braveCore: Boolean = false,
    ) {
        val ew = eye.width * baseW * perspective
        val eh = eye.height * baseH * perspective * breathe * (1f - 0.92f * blinkClosure)
        val ex = centerX + eye.offsetX * baseW
        val ey = centerY + eye.offsetY * baseH
        val radius = min(ew, eh) * 0.28f
        val heart = eye.heart.coerceIn(0f, 1f)
        val color = ColorUtils.blendARGB(baseColor, heartColor, heart)
        eyePaint.color = color
        glowPaint.color = color

        canvas.save()
        canvas.rotate(eye.rotation, ex, ey)

        val roundAlpha = 1f - heart
        if (roundAlpha > 0.01f) {
            rect.set(ex - ew / 2f, ey - eh / 2f, ex + ew / 2f, ey + eh / 2f)
            // Soft glow halo
            val rings = if (braveCore) 3 else 2
            for (i in rings downTo 1) {
                val g = i * baseW * (if (braveCore) 0.045f else 0.035f)
                glowPaint.alpha = ((rings + 1 - i) * (if (braveCore) 34 else 28) * roundAlpha).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g, radius + g, radius + g, glowPaint)
            }
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            eyePaint.alpha = (255 * roundAlpha).toInt()
            canvas.drawRoundRect(rect, radius, radius, eyePaint)
            if (braveCore) drawBraveCore(canvas, ex, ey, ew, eh, roundAlpha)

            if (eye.spiral > 0.01f) drawSpiral(canvas, ex, ey, ew, eh, eye.spiral * roundAlpha, spinDir)

            if (eye.topLid > 0.005f) {
                canvas.save()
                canvas.rotate(eye.topLidAngle, ex, ey)
                val lidBottom = rect.top + eye.topLid * eh
                lidRect.set(rect.left - ew, rect.top - 2f * eh, rect.right + ew, lidBottom)
                canvas.drawRect(lidRect, lidPaint)
                canvas.restore()
            }
            if (eye.bottomLid > 0.005f) {
                val ovalTop = rect.bottom - eye.bottomLid * eh
                lidRect.set(ex - ew * 0.85f, ovalTop, ex + ew * 0.85f, ovalTop + eh * 1.6f)
                canvas.drawOval(lidRect, lidPaint)
            }
            canvas.restore()
            if (pixelEyes && roundAlpha > 0.5f && eh > baseH * 0.22f) drawLashes(canvas, ex, rect.top + eye.topLid * eh, ew, baseH, -spinDir, roundAlpha)
        }

        if (heart > 0.01f) {
            val grow = 0.55f + heart * 0.45f
            val beat = 1f + heartbeat() * 0.1f * heart
            val sx = ew * 1.05f * grow * beat
            val sy = eh * 1.0f * grow * beat
            canvas.save()
            canvas.translate(ex, ey)
            canvas.save()
            canvas.scale(sx * 1.12f, sy * 1.12f)
            glowPaint.alpha = (60 * heart).toInt()
            canvas.drawPath(heartPath, glowPaint)
            canvas.restore()
            canvas.scale(sx, sy)
            eyePaint.alpha = (255 * heart).toInt()
            canvas.drawPath(heartPath, eyePaint)
            canvas.restore()
        }
        canvas.restore()
    }

    /**
     * The fiery heart of the one big eye: a white-hot centre fading through gold and orange into
     * the red. It pulses steadily, like a brave heartbeat, beats harder when excited or alert,
     * and leans a little toward where the eye is looking.
     */
    private fun drawBraveCore(canvas: Canvas, ex: Float, ey: Float, ew: Float, eh: Float, alpha: Float) {
        val fast = emotion == Emotion.ALERT || emotion == Emotion.EXCITED || emotion == Emotion.GRUMPY || emotion == Emotion.POWER_UP
        val pulse = if (fast) 1f + 0.12f * abs(sin(elapsed * 7f)) else 1f + 0.06f * sin(elapsed * 2.4f)
        val r = min(ew, eh) * 0.62f * pulse
        if (r < 1f) return
        val px = ex + gazeX * ew * 0.10f
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        val edge = ColorUtils.setAlphaComponent(eyePaint.color, 0)
        corePaint.shader = RadialGradient(
            px, ey, r,
            intArrayOf(CORE_WHITE, CORE_GOLD, CORE_ORANGE, edge),
            floatArrayOf(0f, 0.16f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        corePaint.alpha = a
        canvas.drawCircle(px, ey, r, corePaint)
        corePaint.shader = null
    }

    /** Three 8-bit lashes on the outer half of the top edge, leaning outwards (one block each). */
    private fun drawLashes(canvas: Canvas, ex: Float, topY: Float, ew: Float, @Suppress("UNUSED_PARAMETER") baseH: Float, outer: Float, alpha: Float) {
        val p = pixelSize
        eyePaint.alpha = (255 * alpha).toInt()
        val yb = kotlin.math.floor(topY / p) * p
        for ((f, blocks) in LASHES) {
            val xb = kotlin.math.floor((ex + outer * ew * f) / p) * p
            for ((dx, dy) in blocks) {
                val x = xb + outer * dx * p
                val y = yb - dy * p
                canvas.drawRect(x, y, x + p, y + p, eyePaint)
            }
        }
    }

    // ---- 8-bit eyes ------------------------------------------------------------------------
    private var pixelBitmap: Bitmap? = null
    private var pixelCanvas: Canvas? = null
    private var pixelSize = 1f
    private val pixelDst = RectF()
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    /** Returns a canvas (same coordinates as the screen) that draws into the low-res bitmap. */
    private fun beginPixels(w: Float, h: Float): Canvas {
        pixelSize = max(3f, min(w, h) / PIXEL_ROWS)
        val bw = kotlin.math.ceil(w / pixelSize).toInt().coerceAtLeast(1)
        val bh = kotlin.math.ceil(h / pixelSize).toInt().coerceAtLeast(1)
        var bmp = pixelBitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            pixelBitmap = bmp
            pixelCanvas = Canvas(bmp)
        }
        bmp!!.eraseColor(Color.TRANSPARENT)
        val c = pixelCanvas!!
        c.save()
        c.scale(1f / pixelSize, 1f / pixelSize)
        setPixelPaints(true)
        return c
    }

    private fun endPixels(out: Canvas) {
        pixelCanvas?.restore()
        setPixelPaints(false)
        val bmp = pixelBitmap ?: return
        pixelDst.set(0f, 0f, bmp.width * pixelSize, bmp.height * pixelSize)
        out.drawBitmap(bmp, null, pixelDst, pixelPaint)
    }

    /** Hard edges inside the pixel pass, smooth edges everywhere else. */
    private fun setPixelPaints(pixel: Boolean) {
        val aa = !pixel
        eyePaint.isAntiAlias = aa; glowPaint.isAntiAlias = aa; lidPaint.isAntiAlias = aa
        spiralPaint.isAntiAlias = aa; fxPaint.isAntiAlias = aa; corePaint.isAntiAlias = aa
    }

    /** Golden flickering energy aura + rising sparks while charging up (EyeBot's own power-up). */
    private fun drawPowerAura(canvas: Canvas, leftX: Float, rightX: Float, cy: Float, baseW: Float, baseH: Float) {
        val t = emotionTime
        val grow = (t / POWER_CHARGE_S).coerceIn(0f, 1f)
        val fade = if (t > POWER_CHARGE_S) (1f - (t - POWER_CHARGE_S) / 1.4f).coerceIn(0f, 1f) else 1f
        val strength = (0.3f + 0.7f * grow) * fade
        if (strength <= 0.01f) return
        val midX = (leftX + rightX) / 2f
        val radiusX = (rightX - leftX) / 2f + baseW * 0.9f
        val radiusY = baseH * (0.9f + 0.5f * grow)
        // Flame tongues around the face, flickering with layered sines.
        val tongues = 26
        for (layer in 0 until 2) {
            fxPath.reset()
            for (i in 0..tongues) {
                val a = i.toFloat() / tongues * 2f * PI_F
                val flick = 0.5f + 0.5f * sin(t * (9f + layer * 4f) + i * 1.7f) * sin(t * 5.3f + i * 0.9f)
                val up = if (sin(a) < 0f) 1f + 0.6f * -sin(a) else 1f      // taller flames on top
                val r = 1f + (0.12f + 0.28f * flick * grow) * up * (1f - layer * 0.35f)
                val x = midX + cos(a) * radiusX * r
                val y = cy + sin(a) * radiusY * r - (if (sin(a) < 0f) baseH * 0.25f * grow * flick else 0f)
                if (i == 0) fxPath.moveTo(x, y) else fxPath.lineTo(x, y)
            }
            fxPath.close()
            fxPaint.color = if (layer == 0) GOLD else AURA_CORE
            fxPaint.alpha = ((if (layer == 0) 90 else 60) * strength).toInt().coerceIn(0, 255)
            canvas.drawPath(fxPath, fxPaint)
        }
        // Rising sparks.
        fxPaint.color = AURA_CORE
        for (k in 0 until 14) {
            val seed = k * 37.1f
            val p = ((t * (0.9f + (k % 5) * 0.15f) + k * 0.13f) % 1f)
            val x = midX + sin(seed) * radiusX * 1.1f
            val y = cy + radiusY * 0.9f - p * radiusY * 2.6f
            fxPaint.alpha = (220 * sin(p * PI_F) * strength).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, baseW * 0.025f, fxPaint)
        }
    }

    /** Punches a hole that rolls around inside the eye, so each eye becomes a wobbling ring. */
    private fun drawDizzyRing(canvas: Canvas, eye: MutableEye, x: Float, cy: Float, baseW: Float, baseH: Float, persp: Float, dir: Float) {
        val ew = eye.width * baseW * persp
        val eh = eye.height * baseH * persp * (1f - 0.92f * blinkClosure())
        if (eh < baseH * 0.2f) return
        val ex = x + eye.offsetX * baseW
        val ey = cy + eye.offsetY * baseH
        val a = emotionTime * 7f * dir
        val r = min(ew, eh) * 0.26f
        val orbit = min(ew, eh) * 0.12f
        fxPaint.color = bgColor
        fxPaint.alpha = 255
        canvas.drawCircle(ex + cos(a) * orbit, ey + sin(a) * orbit, r, fxPaint)
    }

    /** Little stars circling above the head, cartoon-style. */
    private fun drawDizzyStars(canvas: Canvas, cx: Float, cy: Float, spacing: Float, baseH: Float, color: Int) {
        val rx = spacing * 0.75f
        val ry = baseH * 0.14f
        fxPaint.color = color
        for (k in 0 until 3) {
            val a = emotionTime * 3.2f + k * (2f * PI_F / 3f)
            val x = cx + cos(a) * rx
            val y = cy + sin(a) * ry
            val front = sin(a) > 0f                         // stars in front are bigger and brighter
            val size = baseH * (if (front) 0.09f else 0.06f)
            fxPaint.alpha = if (front) 255 else 140
            fxPath.reset()
            for (i in 0 until 10) {
                val ang = i * PI_F / 5f - PI_F / 2f + emotionTime * 2f
                val rr = if (i % 2 == 0) size else size * 0.45f
                val px = x + cos(ang) * rr; val py = y + sin(ang) * rr
                if (i == 0) fxPath.moveTo(px, py) else fxPath.lineTo(px, py)
            }
            fxPath.close()
            canvas.drawPath(fxPath, fxPaint)
        }
    }

    private fun drawSweat(canvas: Canvas, x: Float, y0: Float, baseH: Float) {
        val p = (emotionTime % 2.2f) / 2.2f
        val y = y0 + p * baseH * 0.6f
        val r = baseH * 0.07f
        fxPaint.color = SWEAT_BLUE
        fxPaint.alpha = (230 * (1f - p * 0.7f)).toInt()
        fxPath.reset()
        fxPath.moveTo(x, y - r * 2.2f)
        fxPath.quadTo(x + r * 1.2f, y - r * 0.2f, x, y + r)
        fxPath.quadTo(x - r * 1.2f, y - r * 0.2f, x, y - r * 2.2f)
        fxPath.close()
        canvas.drawPath(fxPath, fxPaint)
    }

    /** The little "Charge me pls" card, held up with two tiny nubs and trembling a bit. */
    private fun drawChargeCard(canvas: Canvas, cx: Float, top: Float, baseW: Float, color: Int) {
        val t = emotionTime
        val cw = baseW * 2.3f
        val ch = baseW * 0.62f
        canvas.save()
        canvas.rotate(-4f + sin(t * 2.2f) * 2f, cx, top + ch / 2f)
        val wobbleY = sin(t * 9f) * baseW * 0.01f
        fxRect.set(cx - cw / 2f, top + wobbleY, cx + cw / 2f, top + ch + wobbleY)
        fxPaint.color = CARD_WHITE
        fxPaint.alpha = 245
        canvas.drawRoundRect(fxRect, ch * 0.18f, ch * 0.18f, fxPaint)
        // tiny battery glyph
        val bx = fxRect.left + ch * 0.25f
        val by = fxRect.centerY()
        fxPaint.color = Color.BLACK
        fxRect.set(bx, by - ch * 0.16f, bx + ch * 0.48f, by + ch * 0.16f)
        canvas.drawRoundRect(fxRect, ch * 0.04f, ch * 0.04f, fxPaint)
        fxRect.set(bx + ch * 0.48f, by - ch * 0.07f, bx + ch * 0.55f, by + ch * 0.07f)
        canvas.drawRect(fxRect, fxPaint)
        fxPaint.color = if ((t * 2f).toInt() % 2 == 0) LOW_RED else CARD_WHITE
        fxRect.set(bx + ch * 0.04f, by - ch * 0.11f, bx + ch * 0.12f, by + ch * 0.11f)
        canvas.drawRect(fxRect, fxPaint)
        // text
        textPaint.color = Color.BLACK
        textPaint.textSize = ch * 0.36f
        canvas.drawText("Charge me pls", cx + ch * 0.32f, by + textPaint.textSize * 0.35f + wobbleY, textPaint)
        // holding nubs
        fxPaint.color = color
        fxPaint.alpha = 255
        val nub = ch * 0.22f
        canvas.drawCircle(cx - cw * 0.36f, top + ch + wobbleY, nub, fxPaint)
        canvas.drawCircle(cx + cw * 0.36f, top + ch + wobbleY, nub, fxPaint)
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, ic: PixelIcon, cx: Float, cy: Float, size: Float, age: Float, color: Int) {
        val pop = when {
            age < 0.18f -> age / 0.18f * 1.15f
            age < 0.3f -> 1.15f - (age - 0.18f) / 0.12f * 0.15f
            age > iconSeconds - 0.3f -> ((iconSeconds - age) / 0.3f).coerceIn(0f, 1f)
            else -> 1f
        }
        if (pop <= 0f) return
        val cell = size * pop / PixelIcon.SIZE
        val left = cx - cell * PixelIcon.SIZE / 2f
        val top = cy - cell * PixelIcon.SIZE / 2f + sin(age * 6f) * size * 0.03f
        fxPaint.color = color
        fxPaint.alpha = 255
        for (y in 0 until PixelIcon.SIZE) for (x in 0 until PixelIcon.SIZE) {
            if (ic.lit(x, y)) {
                fxRect.set(left + x * cell + cell * 0.08f, top + y * cell + cell * 0.08f, left + (x + 1) * cell - cell * 0.08f, top + (y + 1) * cell - cell * 0.08f)
                canvas.drawRoundRect(fxRect, cell * 0.25f, cell * 0.25f, fxPaint)
            }
        }
    }

    private fun drawBubble(canvas: Canvas, text: String, cx: Float, bottom: Float, w: Float, baseH: Float, color: Int) {
        val age = elapsed - bubbleShownAt
        val pop = (age / 0.15f).coerceIn(0f, 1f)
        textPaint.textSize = baseH * 0.30f
        val maxW = w * 0.8f
        // Split off a leading status tic like "[Servo click]" so it gets its own small line.
        var body = text
        var tic: String? = null
        if (body.startsWith("[")) {
            val end = body.indexOf(']')
            if (end > 0) { tic = body.substring(0, end + 1); body = body.substring(end + 1).trim() }
        }
        val lines = wrap(body, maxW, BUBBLE_MAX_LINES)
        val lineH = textPaint.textSize * 1.2f
        val ticSize = textPaint.textSize * 0.7f
        val ticH = if (tic != null) ticSize * 1.3f else 0f
        var tw = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        if (tic != null) { textPaint.textSize = ticSize; tw = max(tw, textPaint.measureText(tic)); textPaint.textSize = ticSize / 0.7f }
        tw = min(tw, maxW)
        val pad = textPaint.textSize * 0.6f
        val bh = ticH + lineH * lines.size + pad * 0.9f
        val top = max(pad * 0.3f, bottom - bh - baseH * 0.15f)    // never off the top edge
        fxRect.set(cx - tw / 2f - pad, top, cx + tw / 2f + pad, top + bh)
        canvas.save()
        canvas.scale(pop, pop, cx, top + bh)
        fxPaint.color = color
        fxPaint.alpha = 235
        val corner = min(bh / 2f, textPaint.textSize * 0.9f)
        canvas.drawRoundRect(fxRect, corner, corner, fxPaint)
        val tail = textPaint.textSize * 0.45f
        fxPath.reset()
        fxPath.moveTo(cx - tail, fxRect.bottom - 1f)
        fxPath.lineTo(cx + tail, fxRect.bottom - 1f)
        fxPath.lineTo(cx, fxRect.bottom + tail * 1.2f)
        fxPath.close()
        canvas.drawPath(fxPath, fxPaint)
        textPaint.color = bgColor
        var y = top + pad * 0.45f
        if (tic != null) {
            val full = textPaint.textSize
            textPaint.textSize = ticSize
            textPaint.alpha = 170
            canvas.drawText(tic, cx, y + ticSize, textPaint)
            textPaint.alpha = 255
            textPaint.textSize = full
            y += ticH
        }
        for (l in lines) {
            canvas.drawText(l, cx, y + textPaint.textSize, textPaint)
            y += lineH
        }
        canvas.restore()
    }

    /** Greedy word wrap to [maxW]; extra text becomes "…" on the last line. */
    private fun wrap(text: String, maxW: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>(maxLines)
        var line = ""
        val words = text.split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size) {
            val tryLine = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (textPaint.measureText(tryLine) <= maxW || line.isEmpty()) { line = tryLine; i++ }
            else {
                out += line; line = ""
                if (out.size == maxLines) break
            }
        }
        if (line.isNotEmpty() && out.size < maxLines) out += line
        if (i < words.size && out.isNotEmpty()) {
            var last = out.last()
            while (last.isNotEmpty() && textPaint.measureText("$last…") > maxW) last = last.dropLast(1)
            out[out.size - 1] = "$last…"
        }
        return out
    }

    private fun drawSpiral(canvas: Canvas, ex: Float, ey: Float, ew: Float, eh: Float, strength: Float, spinDir: Float) {
        val maxR = max(ew, eh) * 0.75f
        val spin = elapsed * 5f * spinDir
        spiralPath.reset()
        val steps = 140
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val th = f * 3.5f * 2f * PI_F * spinDir + spin
            val r = maxR * f
            val x = ex + cos(th) * r
            val y = ey + sin(th) * r
            if (i == 0) spiralPath.moveTo(x, y) else spiralPath.lineTo(x, y)
        }
        spiralPaint.strokeWidth = min(ew, eh) * 0.075f
        spiralPaint.alpha = (255 * strength.coerceIn(0f, 1f)).toInt()
        canvas.drawPath(spiralPath, spiralPaint)
    }

    private fun drawZzz(canvas: Canvas, x: Float, y: Float, baseH: Float, color: Int) {
        zzzPaint.color = color
        for (k in 0 until 3) {
            val p = ((emotionTime + k) % 3f) / 3f
            zzzPaint.textSize = baseH * (0.16f + 0.18f * p)
            zzzPaint.alpha = (200 * sin(PI_F * p)).toInt().coerceIn(0, 255)
            canvas.drawText("Z", x + p * baseH * 0.45f, y - p * baseH * 0.8f, zzzPaint)
        }
    }

    private fun smoothing(dt: Float, speed: Float) = 1f - exp(-speed * dt)

    private fun buildUnitHeart() = Path().apply {
        moveTo(0f, 0.45f)
        cubicTo(-0.1f, 0.35f, -0.5f, 0.1f, -0.5f, -0.15f)
        cubicTo(-0.5f, -0.35f, -0.35f, -0.47f, -0.22f, -0.47f)
        cubicTo(-0.1f, -0.47f, -0.02f, -0.4f, 0f, -0.3f)
        cubicTo(0.02f, -0.4f, 0.1f, -0.47f, 0.22f, -0.47f)
        cubicTo(0.35f, -0.47f, 0.5f, -0.35f, 0.5f, -0.15f)
        cubicTo(0.5f, 0.1f, 0.1f, 0.35f, 0f, 0.45f)
        close()
    }

    /** Current (animated) eye shape, eased towards the target pose. */
    private class MutableEye {
        var width = 1f; var height = 1f
        var offsetX = 0f; var offsetY = 0f
        var rotation = 0f
        var topLid = 0f; var topLidAngle = 0f; var bottomLid = 0f
        var heart = 0f; var spiral = 0f

        fun blend(a: MutableEye, b: MutableEye) {
            width = (a.width + b.width) / 2f; height = (a.height + b.height) / 2f
            offsetX = (a.offsetX + b.offsetX) / 2f; offsetY = (a.offsetY + b.offsetY) / 2f
            rotation = (a.rotation + b.rotation) / 2f
            topLid = (a.topLid + b.topLid) / 2f; topLidAngle = (a.topLidAngle + b.topLidAngle) / 2f
            bottomLid = (a.bottomLid + b.bottomLid) / 2f
            heart = maxOf(a.heart, b.heart); spiral = maxOf(a.spiral, b.spiral)
        }

        fun approach(t: EyeShape, k: Float) {
            width += (t.width - width) * k
            height += (t.height - height) * k
            offsetX += (t.offsetX - offsetX) * k
            offsetY += (t.offsetY - offsetY) * k
            rotation += (t.rotation - rotation) * k
            topLid += (t.topLid - topLid) * k
            topLidAngle += (t.topLidAngle - topLidAngle) * k
            bottomLid += (t.bottomLid - bottomLid) * k
            heart += (t.heart - heart) * k
            spiral += (t.spiral - spiral) * k
        }
    }

    companion object {
        private const val PI_F = Math.PI.toFloat()
        private const val LOW_POWER_FRAME_MS = 100L
        private val NO_AUTO_BLINK = setOf(Emotion.SURPRISED, Emotion.HYPNOTIZED, Emotion.SNEEZE, Emotion.YAWN, Emotion.ALERT, Emotion.POWER_UP)
        /** v4 default: eyes at 50 % of the v3 size. */
        const val DEFAULT_EYE_SCALE = 0.5f
        /** One-eye mode: the single eye is this much bigger than one of the pair. */
        const val SOLO_SCALE = 1.7f
        /** Ampera's 8-bit grid: how many pixel rows fit in the screen's short side. */
        const val PIXEL_ROWS = 54f
        /** Lash layout: (position along the top edge as a fraction of eye width, blocks (out, up)). */
        private val LASHES = listOf(
            0.42f to listOf(0 to 0, 1 to 1, 2 to 2),
            0.20f to listOf(0 to 0, 0 to 1, 1 to 2),
            -0.02f to listOf(0 to 0, 0 to 1),
        )
        private const val BUBBLE_MAX_LINES = 3
        const val POWER_CHARGE_S = 2.2f
        private const val GOLD = 0xFFFFC93C.toInt()
        private const val AURA_CORE = 0xFFFFF4B0.toInt()
        private const val ALERT_RED = 0xFFFF3030.toInt()
        private const val SWEAT_BLUE = 0xFF7FD4FF.toInt()
        private const val CARD_WHITE = 0xFFF4F4F4.toInt()
        private const val LOW_RED = 0xFFE53935.toInt()
        /** One-eye "brave" palette. */
        private const val BRAVE_RED = 0xFFE8141C.toInt()
        private const val BRAVE_FLASH = 0xFFFFB070.toInt()
        private const val CORE_WHITE = 0xFFFFFBEA.toInt()
        private const val CORE_GOLD = 0xFFFFD45A.toInt()
        private const val CORE_ORANGE = 0xFFFF5A14.toInt()
    }
}
