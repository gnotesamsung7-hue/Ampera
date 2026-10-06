package com.example.eyebot

/**
 * Every expression EyeBot can show. v3 adds CONTENT (being petted), HUNGRY / EATING / SAD
 * (Feed-Me game), PARTY, and the two idle "actions" SNEEZE and YAWN.
 *
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 */
enum class Emotion {
    IDLE,
    TRACKING,
    HAPPY,
    CURIOUS,
    EXCITED,
    SEARCHING,
    CONFUSED,
    BORED,
    SLEEPY,
    SURPRISED,
    LOVESTRUCK,
    HYPNOTIZED,
    // ---- v3 ----
    CONTENT,
    HUNGRY,
    EATING,
    SAD,
    PARTY,
    SNEEZE,
    YAWN,
    // ---- v4 ----
    GRUMPY,
    ALERT,
    HOT,
    POWER_UP,
    LOW_BATTERY,
    DIZZY;

    /** Next emotion in declaration order (used by demo mode). */
    fun next(): Emotion = entries[(ordinal + 1) % entries.size]

    companion object {
        private val ALIASES = mapOf(
            "LOVE" to LOVESTRUCK,
            "HEART" to LOVESTRUCK,
            "HYPNO" to HYPNOTIZED,
            "SPIRAL" to HYPNOTIZED,
            "SHOCKED" to SURPRISED,
            "SCARED" to SURPRISED,
            "STARTLED" to SURPRISED,
            "JOY" to HAPPY,
            "SMILE" to HAPPY,
            "PETTED" to CONTENT,
            "CALM" to CONTENT,
            "TIRED" to SLEEPY,
            "ACHOO" to SNEEZE,
            "LOOK" to SEARCHING,
            "ANGRY" to GRUMPY,
            "HMPH" to GRUMPY,
            "WAKE_ALERT" to ALERT,
            "SWEATY" to HOT,
            "CHARGE" to POWER_UP,
            "POWERUP" to POWER_UP,
            "LOW_BATT" to LOW_BATTERY,
            "WOOZY" to DIZZY,
            "SPINNING" to DIZZY,
        )

        /** Case-insensitive lookup by name or friendly alias; null if unknown. */
        fun fromName(raw: String): Emotion? {
            val key = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name == key } ?: ALIASES[key]
        }
    }
}

/**
 * Shape of one eye, all values relative to the base eye size.
 *  - topLid / bottomLid: 0 = open, 1 = fully covered from that side.
 *  - topLidAngle: degrees; tilts the upper lid (brow) for curious / sad looks.
 *  - heart / spiral: 0..1 morph amount into heart eyes / spiral overlay.
 */
data class EyeShape(
    val width: Float = 1f,
    val height: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val topLid: Float = 0f,
    val topLidAngle: Float = 0f,
    val bottomLid: Float = 0f,
    val heart: Float = 0f,
    val spiral: Float = 0f,
)

data class FacePose(val left: EyeShape, val right: EyeShape) {
    constructor(both: EyeShape) : this(both, both)
}

/** Per-emotion target poses. Tweak these numbers to restyle the face. */
object Poses {
    fun of(emotion: Emotion): FacePose = when (emotion) {
        Emotion.IDLE -> FacePose(EyeShape())
        Emotion.TRACKING -> FacePose(EyeShape(width = 1.02f, height = 0.95f))
        Emotion.HAPPY -> FacePose(EyeShape(width = 1.05f, height = 1.0f, offsetY = -0.06f, bottomLid = 0.5f))
        Emotion.CURIOUS -> FacePose(
            left = EyeShape(width = 1.05f, height = 1.12f, offsetY = -0.1f, rotation = -4f),
            right = EyeShape(width = 0.95f, height = 0.78f, offsetY = 0.04f, topLid = 0.22f, topLidAngle = -12f),
        )
        Emotion.EXCITED -> FacePose(EyeShape(width = 1.12f, height = 1.15f, offsetY = -0.05f, bottomLid = 0.32f))
        Emotion.SEARCHING -> FacePose(EyeShape(width = 0.95f, height = 0.88f, topLid = 0.08f))
        Emotion.CONFUSED -> FacePose(
            left = EyeShape(width = 0.95f, height = 0.9f, offsetY = 0.03f, topLid = 0.32f, topLidAngle = 16f),
            right = EyeShape(width = 1.0f, height = 1.05f, offsetY = -0.06f, rotation = 5f),
        )
        Emotion.BORED -> FacePose(EyeShape(width = 1.08f, height = 0.8f, offsetY = 0.1f, topLid = 0.45f))
        Emotion.SLEEPY -> FacePose(
            left = EyeShape(height = 0.55f, offsetY = 0.22f, topLid = 0.7f, topLidAngle = 6f),
            right = EyeShape(height = 0.55f, offsetY = 0.22f, topLid = 0.7f, topLidAngle = -6f),
        )
        Emotion.SURPRISED -> FacePose(EyeShape(width = 0.82f, height = 1.32f, offsetY = -0.04f))
        Emotion.LOVESTRUCK -> FacePose(EyeShape(width = 1.15f, height = 1.05f, offsetY = -0.04f, heart = 1f))
        Emotion.HYPNOTIZED -> FacePose(EyeShape(width = 1.12f, height = 1.12f, spiral = 1f))

        // ---- v3 ----
        // Squinty "^ ^" smile while being petted.
        Emotion.CONTENT -> FacePose(EyeShape(width = 1.12f, height = 0.9f, offsetY = -0.04f, topLid = 0.12f, bottomLid = 0.58f))
        // Big pleading eyes, outer corners drooping.
        Emotion.HUNGRY -> FacePose(
            left = EyeShape(width = 1.0f, height = 1.18f, offsetY = 0.04f, topLid = 0.22f, topLidAngle = -14f),
            right = EyeShape(width = 1.0f, height = 1.18f, offsetY = 0.04f, topLid = 0.22f, topLidAngle = 14f),
        )
        // Happy squint; VectorFaceView adds the chomping squash.
        Emotion.EATING -> FacePose(EyeShape(width = 1.1f, height = 0.95f, offsetY = -0.02f, bottomLid = 0.42f))
        Emotion.SAD -> FacePose(
            left = EyeShape(width = 0.95f, height = 0.82f, offsetY = 0.14f, topLid = 0.32f, topLidAngle = -18f),
            right = EyeShape(width = 0.95f, height = 0.82f, offsetY = 0.14f, topLid = 0.32f, topLidAngle = 18f),
        )
        // Rainbow colours + bounce are added by VectorFaceView.partyMode.
        Emotion.PARTY -> FacePose(EyeShape(width = 1.12f, height = 1.12f, offsetY = -0.05f, bottomLid = 0.3f))
        // Squeeze / "ACHOO" is animated in VectorFaceView.actionClosure().
        Emotion.SNEEZE -> FacePose(EyeShape(width = 1.04f, height = 0.96f, topLid = 0.1f))
        Emotion.YAWN -> FacePose(EyeShape(width = 1.08f, height = 0.8f, offsetY = -0.06f, topLid = 0.3f, bottomLid = 0.2f))

        // ---- v4 ----
        // Inner corners lowered: a cross little frown.
        Emotion.GRUMPY -> FacePose(
            left = EyeShape(width = 1.04f, height = 0.82f, offsetY = 0.04f, topLid = 0.34f, topLidAngle = 16f),
            right = EyeShape(width = 1.04f, height = 0.82f, offsetY = 0.04f, topLid = 0.34f, topLidAngle = -16f),
        )
        // Wide open eyes for drowsiness / left-behind alerts (the view also flashes them red).
        Emotion.ALERT -> FacePose(EyeShape(width = 0.92f, height = 1.34f, offsetY = -0.05f))
        // Droopy and tired; VectorFaceView draws a sweat drop.
        Emotion.HOT -> FacePose(EyeShape(width = 1.05f, height = 0.84f, offsetY = 0.06f, topLid = 0.26f, bottomLid = 0.1f))
        // Fierce, determined squint while the golden aura charges up (VectorFaceView animates it).
        Emotion.POWER_UP -> FacePose(
            left = EyeShape(width = 1.12f, height = 0.9f, offsetY = -0.04f, topLid = 0.22f, topLidAngle = 14f),
            right = EyeShape(width = 1.12f, height = 0.9f, offsetY = -0.04f, topLid = 0.22f, topLidAngle = -14f),
        )
        // Pleading and weak; the view adds a little "Charge me pls" card.
        // Lopsided, wobbling eyes; VectorFaceView turns them into rolling rings with orbiting stars.
        Emotion.DIZZY -> FacePose(
            left = EyeShape(width = 0.86f, height = 0.86f, offsetY = -0.06f, rotation = -10f),
            right = EyeShape(width = 1.04f, height = 1.0f, offsetY = 0.07f, rotation = 12f),
        )
        Emotion.LOW_BATTERY -> FacePose(
            left = EyeShape(width = 0.95f, height = 0.75f, offsetY = -0.12f, topLid = 0.3f, topLidAngle = -16f),
            right = EyeShape(width = 0.95f, height = 0.75f, offsetY = -0.12f, topLid = 0.3f, topLidAngle = 16f),
        )
    }
}
