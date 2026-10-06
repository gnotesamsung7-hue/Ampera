package com.example.eyebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** One animal (or teddy bear) in the frame, box normalised 0..1 in upright coordinates. */
data class SeenAnimal(val label: String, val score: Float, val box: RectF)

/**
 * Spots animals with MediaPipe's EfficientDet-Lite0 (COCO, 80 classes; we keep only animals
 * and teddy bears). The model file is downloaded into assets by Gradle at build time; if it is
 * missing the detector simply reports nothing.
 *
 * Call from the camera thread only.
 */
interface AnimalDetectorApi {
    val available: Boolean
    fun detect(upright: Bitmap): List<SeenAnimal>
}

class AnimalDetector(context: Context) : AnimalDetectorApi {

    private val detector: ObjectDetector? = try {
        if (context.assets.list("")?.contains(MODEL) != true) {
            Log.w(TAG, "$MODEL not bundled - animal detection disabled"); null
        } else {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(4)
                .setScoreThreshold(MIN_SCORE)
                .setCategoryAllowlist(LABELS)
                .build()
            ObjectDetector.createFromOptions(context, options)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Animal detector unavailable", e); null
    }

    override val available get() = detector != null

    override fun detect(upright: Bitmap): List<SeenAnimal> {
        val d = detector ?: return emptyList()
        return try {
            val result = d.detect(BitmapImageBuilder(upright).build())
            val w = upright.width.toFloat(); val h = upright.height.toFloat()
            result.detections().mapNotNull { det ->
                val cat = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val b = det.boundingBox()
                SeenAnimal(cat.categoryName(), cat.score(), RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Animal detection failed", e); emptyList()
        }
    }

    fun close() { detector?.close() }

    companion object {
        private const val TAG = "AnimalDetector"
        const val MODEL = "efficientdet_lite0.tflite"
        const val MIN_SCORE = 0.45f
        val LABELS = listOf("dog", "cat", "bird", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "teddy bear")
    }
}
