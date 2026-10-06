package com.example.eyebot

/** What the camera saw about faces and hand motion in one frame. */
data class FaceObservation(
    val present: Boolean,
    val faceCount: Int = 0,
    /** Face centre, -1..+1 around the screen centre (X already mirrored for the front camera). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Largest face width / frame width. */
    val sizeRatio: Float = 0f,
    val smiling: Float? = null,
    val headRoll: Float = 0f,
    val waving: Boolean = false,
    val circling: Boolean = false,
    val motion: Float = 0f,
    // ---- v4 ----
    /** min(left, right) eye-open probability of the largest face, null if unknown. */
    val eyesOpen: Float? = null,
    /** Mouth opening / face height of the largest face, null if unknown. */
    val mouthOpen: Float? = null,
    /** Largest face is roughly facing the phone (looking at EyeBot). */
    val lookingAtMe: Boolean = false,
)

/** A tracked human face and who EyeBot thinks it is. */
data class SeenPerson(
    val trackingId: Int,
    /** Known household member, or null (pending / stranger). */
    val personId: Long?,
    val stranger: Boolean,
    /** True only on the frame where the identity was first confirmed. */
    val newlyConfirmed: Boolean,
    val offsetX: Float,
    val offsetY: Float,
)

/** Progress of "Learn a new face". */
data class EnrollProgress(
    val collected: Int,
    val target: Int,
    val hint: String,
    val done: Boolean = false,
    val failed: Boolean = false,
    /** Filled when done. */
    val samples: List<FloatArray> = emptyList(),
)

/** Everything one camera frame produced. */
data class VisionFrame(
    val face: FaceObservation,
    /** Raw text of every QR code in view (empty if none, or QR scanning skipped this frame). */
    val qrPayloads: List<String>,
    /** Stable colour card in the target region, or null. */
    val color: ColorStabilizer.Detection?,
    // ---- v4 ----
    val people: List<SeenPerson> = emptyList(),
    /** Animals seen this frame (only on frames where the detector ran). */
    val animals: List<SeenAnimal> = emptyList(),
    val animalsChecked: Boolean = false,
    val enroll: EnrollProgress? = null,
)
