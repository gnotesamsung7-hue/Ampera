package com.example.eyebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device face fingerprints with MobileFaceNet (assets/mobilefacenet.tflite, MIT licence,
 * 112x112 RGB in, 192 floats out). Everything stays on the phone; no photos are stored.
 *
 * Call from the camera thread only (not thread-safe).
 */
/** What the analyzer needs from the face model (lets it start before the model has loaded). */
interface FaceRecognizerApi {
    val available: Boolean
    fun embed(upright: Bitmap, box: Rect, rollDeg: Float): FloatArray?
}

class FaceRecognizer(context: Context) : FaceRecognizerApi {

    private val interpreter: Interpreter? = try {
        val fd = context.assets.openFd(MODEL)
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(buf, Interpreter.Options().setNumThreads(2))
    } catch (e: Exception) {
        Log.w(TAG, "Face model unavailable", e); null
    }

    override val available get() = interpreter != null

    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(FaceRegistry.EMBEDDING_SIZE) }
    private val pixels = IntArray(SIZE * SIZE)
    private val crop = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val cropCanvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /**
     * @param upright the camera frame, rotated upright (same coordinates as ML Kit's boxes)
     * @param box the face bounding box
     * @param rollDeg ML Kit headEulerAngleZ, used to level the face before cropping
     */
    override fun embed(upright: Bitmap, box: Rect, rollDeg: Float): FloatArray? {
        val tfl = interpreter ?: return null
        if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) return null

        // Square crop around the face (+15 %), levelled by the head roll, scaled to 112x112.
        val side = maxOf(box.width(), box.height()) * 1.15f
        val cx = box.exactCenterX(); val cy = box.exactCenterY()
        matrix.reset()
        matrix.postTranslate(-cx, -cy)
        matrix.postRotate(rollDeg)
        matrix.postScale(SIZE / side, SIZE / side)
        matrix.postTranslate(SIZE / 2f, SIZE / 2f)
        crop.eraseColor(0)
        cropCanvas.drawBitmap(upright, matrix, paint)
        crop.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        input.rewind()
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        return try {
            tfl.run(input, output)
            FaceRegistry.normalize(output[0])
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed", e); null
        }
    }

    fun close() { interpreter?.close() }

    companion object {
        private const val TAG = "FaceRecognizer"
        const val MODEL = "mobilefacenet.tflite"
        const val SIZE = 112
        /** Faces smaller than this (in frame pixels) are too blurry to recognise reliably. */
        const val MIN_FACE_PX = 60
    }
}
