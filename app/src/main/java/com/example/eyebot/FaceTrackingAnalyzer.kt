package com.example.eyebot

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The single CameraX analyzer. For each frame, on the same image:
 *  - ML Kit face detection (tracking, smile, eyes open, head pose, mouth contour)
 *  - ML Kit QR scanning                                          (v3)
 *  - frame-difference motion -> wave / circle gestures           (v2)
 *  - YUV colour-card check                                       (v3)
 *  - MobileFaceNet recognition of household members              (v4, only while a face is unidentified)
 *  - MediaPipe animal detection, every [ANIMAL_EVERY_N_FRAMES]   (v4)
 *  - "Learn a new face" sample collection                        (v4)
 *
 * The ML Kit results are handled on the camera executor (so the heavier v4 models never touch
 * the UI thread); the combined [VisionFrame] is then posted to the main thread.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class FaceTrackingAnalyzer(
    private val executor: Executor,
    private val recognizer: FaceRecognizerApi?,
    private val animalDetector: AnimalDetectorApi?,
    private val registry: FaceRegistry,
    private val onFrame: (VisionFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val main = Handler(Looper.getMainLooper())

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)   // lips -> yawns (largest face only)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
    )

    private val qrScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )

    private val motionDetector = MotionDetector()
    private val waveDetector = WaveDetector()
    private val circleDetector = CircleDetector()
    private val colorDetector = ColorDetector()

    @Volatile private var lastFaceBox: RectF? = null
    @Volatile private var faceMovedRecently = false
    private var prevFaceCx = Float.NaN
    private var prevFaceCy = Float.NaN
    private var frameIndex = 0L

    // Identity per ML Kit tracking id (camera thread only)
    private class Track(val voter: IdentityVoter = IdentityVoter(), var lastSeen: Long = 0L, var reported: Boolean = false)
    private val tracks = HashMap<Int, Track>()

    // Enrolment (camera thread only, flag set from main)
    @Volatile private var enrollRequested = false
    private var enrolling = false
    private var enrollStarted = 0L
    private var enrollLastYaw = Float.NaN
    private val enrollSamples = ArrayList<FloatArray>()

    @Volatile private var closed = false
    @Volatile var lowPower = false
    @Volatile var qrEnabled = true
    @Volatile var colorEnabled = true
    @Volatile var recognitionEnabled = true
    @Volatile var animalsEnabled = true
    /** Car Mode while driving: fewer heavy passes. */
    @Volatile var driving = false

    /** Start collecting samples of the largest face (Settings ▸ People ▸ Learn a new face). */
    fun startEnrollment() { enrollRequested = true }
    fun cancelEnrollment() { enrollRequested = false; if (!closed) executor.execute { enrolling = false; enrollSamples.clear() } }

    /** Forget cached identities (after people are added / removed). */
    fun resetIdentities() { if (!closed) executor.execute { tracks.clear() } }

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || closed) { imageProxy.close(); return }

        frameIndex++
        if (lowPower && frameIndex % LOW_POWER_FRAME_SKIP != 0L) { imageProxy.close(); return }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val faceBox = lastFaceBox?.let(::expand)

        val motion = motionDetector.process(imageProxy, rotation, faceBox)
        val now = System.currentTimeMillis()
        val circling = circleDetector.update(now, motion, faceMovedRecently)
        val waving = waveDetector.update(now, motion, faceMovedRecently) && !circling
        val color = if (colorEnabled && !driving) colorDetector.process(imageProxy, rotation, faceBox) else null

        val input = InputImage.fromMediaImage(mediaImage, rotation)
        val uprightW = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightH = if (rotation % 180 == 0) imageProxy.height else imageProxy.width

        val faceTask: Task<List<Face>> = faceDetector.process(input)
        val scanQr = qrEnabled && !driving && (lowPower || frameIndex % QR_EVERY_N_FRAMES == 0L)
        val qrTask: Task<List<Barcode>>? = if (scanQr) qrScanner.process(input) else null

        Tasks.whenAllComplete(listOfNotNull<Task<*>>(faceTask, qrTask))
            .addOnCompleteListener(executor) {
                try {
                    val faces = if (faceTask.isSuccessful) faceTask.result else emptyList()
                    if (!faceTask.isSuccessful) faceTask.exception?.let { Log.w(TAG, "Face detection failed", it) }
                    val faceObs = buildObservation(faces, uprightW, uprightH, waving, circling, motion)
                    val qr = if (qrTask != null && qrTask.isSuccessful) {
                        qrTask.result.mapNotNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                    } else emptyList()

                    // ---- v4 heavy passes on an upright bitmap, only when needed ----
                    if (enrollRequested && !enrolling) { enrolling = true; enrollStarted = now; enrollSamples.clear(); enrollLastYaw = Float.NaN }
                    if (!enrollRequested && enrolling) { enrolling = false; enrollSamples.clear() }

                    val pending = recognitionEnabled && recognizer?.available == true && registry.size() > 0 &&
                        faces.any { f -> f.trackingId?.let { id -> tracks[id]?.voter?.verdict ?: IdentityVoter.Verdict.Pending } == IdentityVoter.Verdict.Pending }
                    val wantRecognition = (pending && frameIndex % 2 == 0L) || (enrolling && frameIndex % 2 == 0L)
                    val wantAnimals = animalsEnabled && animalDetector?.available == true &&
                        frameIndex % (if (driving || lowPower) ANIMAL_EVERY_N_FRAMES * 3 else ANIMAL_EVERY_N_FRAMES) == 0L

                    var upright: Bitmap? = null
                    if (wantRecognition || wantAnimals) upright = uprightBitmap(imageProxy, rotation)

                    val people = identify(faces, upright.takeIf { wantRecognition }, uprightW, uprightH, now)
                    val enroll = if (enrolling) enrollStep(faces, upright.takeIf { wantRecognition }, now) else null
                    val animals = if (wantAnimals && upright != null) animalDetector?.detect(upright).orEmpty() else emptyList()

                    val frame = VisionFrame(faceObs, qr, color, people, animals, wantAnimals && upright != null, enroll)
                    main.post { if (!closed) onFrame(frame) }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame analysis failed", e)
                } finally {
                    imageProxy.close()
                }
            }
    }

    private fun uprightBitmap(image: ImageProxy, rotation: Int): Bitmap? = try {
        val raw = image.toBitmap()
        if (rotation == 0) raw else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Bitmap conversion failed", e); null
    }

    private fun identify(faces: List<Face>, upright: Bitmap?, w: Int, h: Int, now: Long): List<SeenPerson> {
        // Drop tracks not seen for a while.
        tracks.entries.removeAll { now - it.value.lastSeen > TRACK_FORGET_MS }
        val noOneKnown = registry.size() == 0
        return faces.mapNotNull { f ->
            val id = f.trackingId ?: return@mapNotNull null
            val t = tracks.getOrPut(id) { Track() }
            t.lastSeen = now
            if (t.voter.verdict == IdentityVoter.Verdict.Pending) {
                if (noOneKnown) {
                    t.voter.vote(null)
                } else if (upright != null && recognizer != null) {
                    recognizer.embed(upright, f.boundingBox, f.headEulerAngleZ)?.let { emb ->
                        val m = registry.match(emb)
                        val v = t.voter.vote(m.person?.id)
                        if (v is IdentityVoter.Verdict.Known) registry.recordSighting(v.personId, emb, m.similarity, now, newVisit = true)
                    }
                }
            }
            val verdict = t.voter.verdict
            val newly = verdict != IdentityVoter.Verdict.Pending && !t.reported
            if (newly) t.reported = true
            val nx = f.boundingBox.exactCenterX() / w * 2f - 1f
            val ny = f.boundingBox.exactCenterY() / h * 2f - 1f
            SeenPerson(
                trackingId = id,
                personId = (verdict as? IdentityVoter.Verdict.Known)?.personId,
                stranger = verdict == IdentityVoter.Verdict.Stranger,
                newlyConfirmed = newly,
                offsetX = (if (MIRROR_FRONT_CAMERA) -nx else nx).coerceIn(-1f, 1f),
                offsetY = ny.coerceIn(-1f, 1f),
            )
        }
    }

    private fun enrollStep(faces: List<Face>, upright: Bitmap?, now: Long): EnrollProgress {
        val n = enrollSamples.size
        if (now - enrollStarted > ENROLL_TIMEOUT_MS) {
            enrollRequested = false; enrolling = false
            return EnrollProgress(n, ENROLL_SAMPLES, "I couldn't see you well. Let's try again later.", failed = true)
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face != null && upright != null && recognizer != null) {
            val yaw = face.headEulerAngleY
            // Ask for a few angles; accept a sample when the pose changed a bit (or every few tries).
            val diverse = enrollLastYaw.isNaN() || abs(yaw - enrollLastYaw) > 4f || n < 3
            if (diverse) recognizer.embed(upright, face.boundingBox, face.headEulerAngleZ)?.let {
                enrollSamples += it; enrollLastYaw = yaw
            }
        }
        val c = enrollSamples.size
        if (c >= ENROLL_SAMPLES) {
            val done = EnrollProgress(c, ENROLL_SAMPLES, "Got it!", done = true, samples = enrollSamples.toList())
            enrollRequested = false; enrolling = false; enrollSamples.clear()
            return done
        }
        val hint = when {
            face == null -> "Look at me..."
            c < 3 -> "Look at me..."
            c < 5 -> "Turn a little to the left..."
            c < 7 -> "...now a little to the right"
            else -> "Almost there!"
        }
        return EnrollProgress(c, ENROLL_SAMPLES, hint)
    }

    private fun buildObservation(
        faces: List<Face>, uprightW: Int, uprightH: Int,
        waving: Boolean, circling: Boolean, motion: MotionDetector.Result,
    ): FaceObservation {
        if (faces.isEmpty()) {
            lastFaceBox = null
            faceMovedRecently = false
            prevFaceCx = Float.NaN
            return FaceObservation(false, waving = waving, circling = circling, motion = motion.fraction)
        }

        val largest = faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
        val box = largest.boundingBox
        val nl = box.left.toFloat() / uprightW
        val nt = box.top.toFloat() / uprightH
        val nr = box.right.toFloat() / uprightW
        val nb = box.bottom.toFloat() / uprightH
        lastFaceBox = RectF(nl, nt, nr, nb)

        val fcx = (nl + nr) / 2f
        val fcy = (nt + nb) / 2f
        faceMovedRecently = !prevFaceCx.isNaN() && hypot(fcx - prevFaceCx, fcy - prevFaceCy) > 0.04f
        prevFaceCx = fcx; prevFaceCy = fcy

        var sumX = 0f; var sumY = 0f
        for (f in faces) {
            sumX += f.boundingBox.exactCenterX() / uprightW
            sumY += f.boundingBox.exactCenterY() / uprightH
        }
        val nx = sumX / faces.size * 2f - 1f
        val ny = sumY / faces.size * 2f - 1f
        val screenX = if (MIRROR_FRONT_CAMERA) -nx else nx

        val le = largest.leftEyeOpenProbability
        val re = largest.rightEyeOpenProbability
        val frontal = abs(largest.headEulerAngleY) < 25f && abs(largest.headEulerAngleX) < 25f
        val eyesOpen = if (le != null && re != null && frontal) minOf(le, re) else null

        return FaceObservation(
            present = true,
            faceCount = faces.size,
            offsetX = screenX.coerceIn(-1f, 1f),
            offsetY = ny.coerceIn(-1f, 1f),
            sizeRatio = box.width().toFloat() / uprightW,
            smiling = largest.smilingProbability,
            headRoll = largest.headEulerAngleZ,
            waving = waving,
            circling = circling,
            motion = motion.fraction,
            eyesOpen = eyesOpen,
            mouthOpen = mouthOpenRatio(largest),
            lookingAtMe = abs(largest.headEulerAngleY) < 12f && abs(largest.headEulerAngleX) < 12f,
        )
    }

    private fun mouthOpenRatio(face: Face): Float? {
        val upper = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
        val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return null
        if (upper.isEmpty() || lower.isEmpty()) return null
        val u: PointF = upper[upper.size / 2]
        val l: PointF = lower[lower.size / 2]
        val h = face.boundingBox.height().toFloat()
        return if (h <= 0f) null else hypot(l.x - u.x, l.y - u.y) / h
    }

    private fun expand(r: RectF): RectF {
        val w = r.width(); val h = r.height()
        return RectF(r.left - w * 0.25f, r.top - h * 0.25f, r.right + w * 0.25f, r.bottom + h * 0.6f)
    }

    fun close() {
        closed = true
        faceDetector.close()
        qrScanner.close()
    }

    companion object {
        private const val TAG = "VisionAnalyzer"
        /** If the eyes look the wrong way on your phone, set this to false. */
        const val MIRROR_FRONT_CAMERA = true
        const val QR_EVERY_N_FRAMES = 2L
        const val LOW_POWER_FRAME_SKIP = 3L
        const val ANIMAL_EVERY_N_FRAMES = 4L
        const val TRACK_FORGET_MS = 5_000L
        const val ENROLL_SAMPLES = 8
        const val ENROLL_TIMEOUT_MS = 20_000L
    }
}
