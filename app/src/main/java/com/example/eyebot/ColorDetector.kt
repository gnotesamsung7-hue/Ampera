package com.example.eyebot

import android.graphics.RectF
import androidx.camera.core.ImageProxy

/**
 * Lightweight colour-card detector that runs on the same YUV_420_888 frame as face detection.
 * No OpenCV and no Bitmap conversion: it samples a [cols] x [rows] grid straight from the Y/U/V
 * planes inside a centre target region, converts each sample to HSV and lets
 * [ColorClassifier] decide whether one saturated colour fills the region.
 *
 * Cost: ~430 samples per frame, well under 1 ms on a mid-range phone.
 */
class ColorDetector(
    private val cols: Int = 24,
    private val rows: Int = 18,
    /** Target region in upright, normalised frame coordinates. Hold the card here. */
    private val target: RectF = RectF(0.2f, 0.15f, 0.8f, 0.85f),
) {
    private val classifier = ColorClassifier()
    private val stabilizer = ColorStabilizer()

    /** Most recent stable detection (may be null). */
    val current: ColorStabilizer.Detection? get() = stabilizer.current

    /**
     * @param exclude face box (upright, normalised) to skip, so a face isn't read as a colour.
     * @return the stable detected colour, or null when no card is in view.
     */
    fun process(image: ImageProxy, rotation: Int, exclude: RectF?): ColorStabilizer.Detection? {
        val planes = image.planes
        if (planes.size < 3) return stabilizer.update(null)
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        val w = image.width; val h = image.height
        val uLimit = uBuf.limit(); val vLimit = vBuf.limit(); val yLimit = yBuf.limit()

        classifier.reset()
        for (gy in 0 until rows) {
            val uy = target.top + (gy + 0.5f) / rows * target.height()
            for (gx in 0 until cols) {
                val ux = target.left + (gx + 0.5f) / cols * target.width()
                if (exclude != null && exclude.contains(ux, uy)) continue

                // Upright (ux, uy) -> sensor (u, v): inverse of MotionDetector's mapping.
                val u: Float
                val v: Float
                when (rotation) {
                    90 -> { u = uy; v = 1f - ux }
                    180 -> { u = 1f - ux; v = 1f - uy }
                    270 -> { u = 1f - uy; v = ux }
                    else -> { u = ux; v = uy }
                }
                val px = (u * w).toInt().coerceIn(0, w - 1)
                val py = (v * h).toInt().coerceIn(0, h - 1)

                val yi = py * yRow + px * yPix
                val ui = (py / 2) * uRow + (px / 2) * uPix
                val vi = (py / 2) * vRow + (px / 2) * vPix
                if (yi >= yLimit || ui >= uLimit || vi >= vLimit) continue

                val rgb = ColorMath.yuvToRgb(
                    yBuf.get(yi).toInt() and 0xFF,
                    uBuf.get(ui).toInt() and 0xFF,
                    vBuf.get(vi).toInt() and 0xFF,
                )
                classifier.addRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            }
        }
        return stabilizer.update(classifier.result())
    }

    fun reset() = stabilizer.reset()
}
