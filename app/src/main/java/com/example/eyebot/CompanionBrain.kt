package com.example.eyebot

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** High-level modes. NORMAL is v2 behaviour; the rest come from QR game cards / idling. */
enum class BrainMode { NORMAL, FEED_ME, PARTY, SLEEP, SCREENSAVER }

/** Touch / button reactions (screen gestures, Bluetooth / USB buttons, ESP32 touch pads). */
enum class TouchKind { PET, BOOP, POKE, POKE_SPAM, FLING }

/** One-off visual actions the brain asks the face to do. */
enum class IdleAction { HEAVY_BLINK }

/** Everything the brain wants the outside world to do. All calls happen on the main thread. */
interface BrainListener {
    /** Called on every evaluation (frame or tick) with the current emotion. */
    fun onState(emotion: Emotion, obs: FaceObservation) {}
    fun onModeChanged(mode: BrainMode) {}
    /** null = default eye colour. */
    fun onEyeColorChanged(argb: Int?) {}
    /** [important] = safety message: always spoken in clear words. */
    fun onSpeak(text: String, important: Boolean) {}
    fun onSound(sfx: Sfx) {}
    fun onIdleAction(action: IdleAction) {}
    // ---- v4 ----
    fun onShowIcon(icon: PixelIcon) {}
    /** Play this household member's signature melody. */
    fun onPlayMelody(person: FaceRegistry.Person) {}
    fun onEnroll(progress: EnrollProgress) {}
    fun onDrivingChanged(driving: Boolean) {}
    /** Too hot: stop the camera until it cools (true) / resume (false). */
    fun onCoolDown(active: Boolean) {}
    // ---- Ampera talk-back ----
    /** A conversational reply (spoken in words even in beep modes). */
    fun onChat(text: String) = onSpeak(text, false)
    /** Ampera asked something: open the mic once she has finished saying [afterText]. */
    fun onWantListen(afterText: String) {}
}

/** How often Ampera starts a conversation by herself (Settings ▸ Talk & chat). */
enum class Chattiness(val label: String, val minMs: Long, val maxMs: Long, val quietMs: Long) {
    QUIET("Quiet (only when spoken to)", Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4),
    NORMAL("Normal (every few minutes)", 4 * 60_000L, 8 * 60_000L, 2 * 60_000L),
    CHATTY("Chatty", 90_000L, 3 * 60_000L, 60_000L),
}

/**
 * Rules engine: turns camera observations, QR cards, colour cards, touches and time into an
 * emotion (and a mode). v2's face rules are unchanged; v3 adds:
 *   - QR emotion override + mini-game cards (Feed Me, Sleep/Wake, Party, colours, SAY:)
 *   - colour-card eye colour
 *   - touch reactions (pet / boop / poke / fling)
 *   - autonomous idle behaviours (look around, heavy blink, yawn, sneeze, peek) and a
 *     low-power screensaver mode.
 *
 * All thresholds are named constants in the companion object.
 */
class CompanionBrain(
    private val face: VectorFaceView,
    private val listener: BrainListener,
    private val registry: FaceRegistry = FaceRegistry(),
    /** Lasting mood (restored from storage by MainActivity). */
    var mood: Mood = Mood(),
    /** Story progress and running gags for the conversation brain. */
    chatMemory: ChatMemory = MapChatMemory(),
) {
    /** Ampera's conversation brain (jokes, riddles, games, banter). */
    val chat = ChatBrain(chatMemory)
    var chattiness = Chattiness.NORMAL
    /** Mic is open for a sentence. */
    private var listening = false
    private var personName: String? = null
    private var personNameAt = 0L

    /** Demo mode: emotions are stepped manually; rules are paused. */
    var manualMode = false

    var mode = BrainMode.NORMAL
        private set

    // --- v2 tracking state ---
    private var lastActivityAt = System.currentTimeMillis()
    private var faceVisible = false
    private var smoothX = 0f
    private var smoothY = 0f
    private var centeredSince = 0L
    private var multiSince = 0L
    private var lastMultiAt = 0L
    var lastObs = FaceObservation(false)
        private set

    // --- timed emotion sequences (QR holds, reactions, idle acts) ---
    private val sequence = ArrayDeque<Pair<Emotion, Long>>()
    private var stepEndsAt = 0L

    // --- v3 state ---
    private var nextIdleActAt = 0L
    private var lastSpokeAt = 0L
    private var nextMuseAt = 0L
    private var feedStartedAt = 0L
    private var partyUntil = 0L
    private var baseColor: Int? = null      // set by COLOR: QR cards
    private var liveColor: Int? = null      // colour card currently in front of the camera
    private var liveBucket: ColorBucket? = null
    private var emittedColor: Int? = null
    private var colorEmittedOnce = false
    private var lastColorSpeechAt = 0L
    private var lastQrText: String? = null
    private var lastQrAt = 0L
    private var lastQrEmotion: Emotion? = null
    private var lastGreetSpeechAt = 0L
    private var lastTickleSpeechAt = 0L
    private val pendingSpeech = mutableListOf<Pair<Long, String>>()

    // --- v4 state ---
    val trip = TripTracker()
    val power = PowerWatch()
    private val drowsiness = DrowsinessMonitor()
    /** Car Mode says we are moving: calm, no games, eyes on the road. */
    var driving = false
        private set
    var enrolling = false
        private set
    var coolingDown = false
        private set
    private var personColor: Int? = null
    private var lastPersonSeenAt = 0L
    private val lastGreeted = HashMap<Long, Long>()
    private val birthdaySungOn = HashMap<Long, Int>()
    private var strangerSightings = 0
    private var lastLearnMePromptAt = -LEARN_ME_GAP_MS
    private val lastAnimalAt = HashMap<String, Long>()
    private var lookingSince = 0L
    private var alertLevel = 0
    private var lastAlertAt = 0L
    private var backSeatUntil = 0L
    private var lastBackSeatNagAt = 0L
    private var lastRoadAt = 0L
    private var lastMoodTickAt = System.currentTimeMillis()
    private var lastLowBatterySpeechAt = -LOW_BATTERY_SPEECH_GAP_MS
    private var lastDistCm = 999
    private val pendingSounds = mutableListOf<Pair<Long, Sfx>>()

    // =========================================================================================
    // Inputs
    // =========================================================================================

    /** One analysed camera frame (main thread). */
    fun onFrame(frame: VisionFrame, now: Long = System.currentTimeMillis()) {
        frame.enroll?.let { onEnrollProgress(it, now) }
        if (!driving) {
            handleColor(frame.color, now)
            for (payload in frame.qrPayloads.distinct()) onQrPayload(payload, now)
        }
        if (!enrolling && !coolingDown) {
            for (p in frame.people) if (p.newlyConfirmed) onPersonConfirmed(p, now)
            if (frame.animalsChecked) onAnimals(frame.animals, now)
        }
        if (driving) onDrivingFrame(frame.face, now)
        onFace(frame.face, now)
    }

    fun onFace(obs: FaceObservation, now: Long = System.currentTimeMillis()) {
        lastObs = obs

        if (mode == BrainMode.SLEEP) { evaluate(now); return }   // only the WAKE card / a tap wakes it
        if (mode == BrainMode.SCREENSAVER && (obs.present || obs.waving || obs.circling)) {
            wake(now, fromSleepCard = false)
        }

        if (!manualMode) {
            if (obs.circling) {
                markActivity(now)
                play(now, Emotion.HYPNOTIZED to HYPNOTIZED_MS)
            } else if (obs.waving) {
                markActivity(now)
                play(now, Emotion.EXCITED to WAVE_EXCITED_MS, Emotion.HAPPY to WAVE_HAPPY_MS)
            }
        }

        if (obs.present) {
            val wasAlone = !faceVisible && now - lastActivityAt > SEARCH_AFTER_MS
            faceVisible = true
            markActivity(now)
            smoothX += (obs.offsetX - smoothX) * 0.5f
            smoothY += (obs.offsetY - smoothY) * 0.5f
            if (driving) {
                // Eyes mostly on the road; never reward a long glance at the dashboard.
                if (obs.lookingAtMe) { if (lookingSince == 0L) lookingSince = now } else lookingSince = 0L
                if (lookingSince != 0L && now - lookingSince > GLANCE_LIMIT_MS) face.lookAt(0f, -0.85f)
                else face.lookAt(smoothX * 0.3f, -0.35f)
            } else {
                face.lookAt(smoothX, smoothY)
            }

            if (obs.faceCount >= 2) {
                if (multiSince == 0L) multiSince = now
                lastMultiAt = now
            } else {
                multiSince = 0L
            }

            if (wasAlone && !manualMode && !driving && !enrolling && mode == BrainMode.NORMAL && sequence.isEmpty() && now >= stepEndsAt) {
                if (mood.isGrumpy) {
                    // Ignored for too long: a little "hmph" first, then it forgives you.
                    play(now, Emotion.GRUMPY to 1300, Emotion.EXCITED to GREET_MS)
                    speak(AmperaPersona.grumpyGreeting())
                    mood.apply(Mood.Event.KNOWN_PERSON)
                    evaluate(now)
                    return
                }
                play(now, Emotion.EXCITED to GREET_MS)
                listener.onSound(Sfx.GREET)
                // With a household registered, the named greeting follows once the face is recognised.
                if (registry.size() == 0 && now - lastGreetSpeechAt > GREET_SPEECH_GAP_MS) {
                    lastGreetSpeechAt = now
                    speak(AmperaPersona.greeting())
                }
            }
        } else {
            faceVisible = false
            multiSince = 0L
        }
        evaluate(now)
    }

    /** QR code text seen by the camera. Instantly overrides the current emotion. */
    fun onQrPayload(text: String, now: Long = System.currentTimeMillis()) {
        // The same card stays in view for many frames: only act once, but keep the hold alive.
        if (text == lastQrText && now - lastQrAt < QR_REPEAT_MS) {
            lastQrAt = now
            markActivity(now)
            val held = lastQrEmotion
            if (held != null && face.emotion == held && sequence.isEmpty()) {
                stepEndsAt = maxOf(stepEndsAt, now + QR_EMOTION_HOLD_MS)
            }
            return
        }
        lastQrText = text
        lastQrAt = now
        lastQrEmotion = null
        handleCommand(QrCommand.parse(text), now, fromQr = true)
    }

    /**
     * Text command from the microcontroller (serial / BLE) or a keyboard shortcut.
     * TOUCH:* / BTN:* lines become touches; anything else goes through [QrCommand].
     */
    fun onExternalLine(line: String, now: Long = System.currentTimeMillis()) {
        val l = line.trim().uppercase()
        when {
            l.isEmpty() || l.startsWith("HELLO") || l.startsWith("OK") || l.startsWith("#") -> return
            l == "TOUCH:HEAD" || l == "TOUCH:PET" || l == "PET" || l == "BTN:1" -> onTouch(TouchKind.PET, now)
            l == "TOUCH:BOOP" || l == "BOOP" || l == "BTN:2" -> onTouch(TouchKind.BOOP, now)
            l == "TOUCH:POKE" || l == "TOUCH:TAP" || l == "POKE" || l == "TAP" || l == "BTN:3" -> onTouch(TouchKind.POKE, now)
            l == "TOUCH:SHAKE" || l == "BTN:4" -> onTouch(TouchKind.FLING, now)
            l.startsWith("DIST:") -> onDistance(l.removePrefix("DIST:").toIntOrNull() ?: return, now)
            else -> QrCommand.parse(line)?.let { handleCommand(it, now, fromQr = false) }
        }
    }

    fun onTouch(kind: TouchKind, now: Long = System.currentTimeMillis()) {
        if (manualMode) return
        if (backSeatUntil > now) {            // any touch acknowledges the back-seat reminder
            backSeatUntil = 0L
            play(now, Emotion.HAPPY to 1500)
            speak(AmperaPersona.thanks())
            return
        }
        if (driving || coolingDown) return    // no touch play while moving
        markActivity(now)
        mood.apply(
            when (kind) {
                TouchKind.PET -> Mood.Event.PETTED
                TouchKind.BOOP -> Mood.Event.BOOPED
                TouchKind.POKE -> Mood.Event.POKED
                TouchKind.POKE_SPAM -> Mood.Event.POKE_SPAM
                TouchKind.FLING -> Mood.Event.PLAYED
            }
        )
        when (mode) {
            BrainMode.SCREENSAVER -> { wake(now, fromSleepCard = false); return }
            BrainMode.SLEEP -> {
                if (kind == TouchKind.PET) listener.onSound(Sfx.PURR)   // contented snore, stays asleep
                else wake(now, fromSleepCard = true)
                return
            }
            else -> Unit
        }
        when (kind) {
            TouchKind.PET -> {
                if (face.emotion == Emotion.CONTENT && sequence.isEmpty()) {
                    stepEndsAt = maxOf(stepEndsAt, now + PET_MS)   // keep purring while stroked
                } else {
                    play(now, Emotion.CONTENT to PET_MS)
                }
            }
            TouchKind.BOOP -> {
                listener.onSound(Sfx.BOOP)
                play(now, Emotion.HAPPY to BOOP_MS)
            }
            TouchKind.POKE -> play(now, Emotion.SURPRISED to POKE_STARTLE_MS, Emotion.CURIOUS to POKE_CURIOUS_MS)
            TouchKind.POKE_SPAM -> {
                play(now, Emotion.CONFUSED to POKE_SPAM_MS)
                if (now - lastTickleSpeechAt > 8000) {
                    lastTickleSpeechAt = now
                    speak(AmperaPersona.poked())
                }
            }
            TouchKind.FLING -> play(now, Emotion.EXCITED to FLING_MS)
        }
    }

    fun tick(now: Long = System.currentTimeMillis()) = evaluate(now)

    /** Show [emotion] for [durationMs], then return to the rules. */
    fun lockEmotion(emotion: Emotion, durationMs: Long, now: Long = System.currentTimeMillis()) {
        play(now, emotion to durationMs)
    }

    // =========================================================================================
    // Commands (QR cards, serial lines)
    // =========================================================================================

    private fun handleCommand(cmd: QrCommand?, now: Long, fromQr: Boolean) {
        markActivity(now)
        if (mode == BrainMode.SCREENSAVER) wake(now, fromSleepCard = false, quiet = true)
        if (mode == BrainMode.SLEEP && cmd != QrCommand.Wake) return   // asleep: only WAKE works

        when (cmd) {
            null -> if (fromQr) {
                listener.onSound(Sfx.QR_ACK)
                play(now, Emotion.CURIOUS to 2500)
                speak(AmperaPersona.mysteryCode())
            }
            is QrCommand.SetEmotion -> {
                lastQrEmotion = if (fromQr) cmd.emotion else null
                play(now, cmd.emotion to QR_EMOTION_HOLD_MS)
                AmperaPersona.emotionPhrases[cmd.emotion]?.let { speak(it.random()) }
            }
            QrCommand.FeedMe -> startFeedMe(now)
            is QrCommand.Food -> feed(cmd.item, now)
            QrCommand.Sleep -> goToSleep()
            QrCommand.Wake -> {
                if (mode == BrainMode.SLEEP) wake(now, fromSleepCard = true)
                else { play(now, Emotion.EXCITED to 1500); speak(AmperaPersona.alreadyAwake()) }
            }
            QrCommand.Party -> if (driving) speak(AmperaPersona.partyLater()) else startParty(now)
            is QrCommand.SetColor -> {
                baseColor = cmd.argb
                emitColor()
                listener.onSound(Sfx.QR_ACK)
                play(now, Emotion.HAPPY to 1400)
                speak(if (cmd.argb == null) AmperaPersona.colourReset() else if (cmd.label.startsWith("#")) AmperaPersona.colourNew() else AmperaPersona.colourNamed(cmd.label))
            }
            is QrCommand.Say -> {
                play(now, Emotion.HAPPY to 2000)
                speak(cmd.text)
            }
        }
    }

    private fun startFeedMe(now: Long) {
        setMode(BrainMode.FEED_ME)
        feedStartedAt = now
        clearSequence()
        setEmotion(Emotion.HUNGRY)
        speak(AmperaPersona.hungry())
    }

    private fun feed(item: String, now: Long) {
        val pretty = item.replace('_', ' ')
        if (mode == BrainMode.FEED_ME) {
            setMode(BrainMode.NORMAL)
            mood.apply(Mood.Event.FED)
            play(now, Emotion.EATING to EATING_MS, Emotion.HAPPY to FED_HAPPY_MS)
            speakLater(now + EATING_MS, AmperaPersona.yum(pretty))
        } else {
            play(now, Emotion.CURIOUS to 1200, Emotion.CONTENT to 1600)
            speak(AmperaPersona.full())
        }
    }

    private fun goToSleep() {
        speak(AmperaPersona.goodNight())
        listener.onSound(Sfx.POWER_DOWN)
        clearSequence()
        setMode(BrainMode.SLEEP)
        setEmotion(Emotion.SLEEPY)
    }

    private fun wake(now: Long, fromSleepCard: Boolean, quiet: Boolean = false) {
        val was = mode
        setMode(BrainMode.NORMAL)
        markActivity(now)
        if (quiet) return
        if (was == BrainMode.SLEEP || fromSleepCard) {
            listener.onSound(Sfx.POWER_UP)
            play(now, Emotion.SURPRISED to 500, Emotion.EXCITED to 1500)
            speak(AmperaPersona.wakeUp())
        } else {
            // Woken from the screensaver by a face / wave / touch: greet.
            play(now, Emotion.SURPRISED to 400, Emotion.EXCITED to 1200)
            listener.onSound(Sfx.GREET)
            if (lastObs.present && now - lastGreetSpeechAt > GREET_SPEECH_GAP_MS) {
                lastGreetSpeechAt = now
                speak(AmperaPersona.greeting())
            }
        }
    }

    private fun startParty(now: Long) {
        if (mode == BrainMode.PARTY) {
            partyUntil = maxOf(partyUntil, now) + PARTY_EXTEND_MS
            return
        }
        setMode(BrainMode.PARTY)
        mood.apply(Mood.Event.PARTY)
        partyUntil = now + PARTY_MS
        clearSequence()
        setEmotion(Emotion.PARTY)
        speak(AmperaPersona.partyStart())
    }

    // =========================================================================================
    // Colour cards
    // =========================================================================================

    private fun handleColor(det: ColorStabilizer.Detection?, now: Long) {
        if (mode == BrainMode.SLEEP) return
        liveColor = det?.argb
        if (det != null && det.bucket != liveBucket) {
            // A new colour card appeared: counts as interaction, and say its name now and then.
            markActivity(now)
            if (now - lastColorSpeechAt > COLOR_SPEECH_GAP_MS) {
                lastColorSpeechAt = now
                speak(AmperaPersona.colourNamed(det.bucket.label))
            }
        }
        liveBucket = det?.bucket
        emitColor()
    }

    private fun emitColor() {
        val effective = liveColor ?: personColor ?: baseColor
        if (!colorEmittedOnce || effective != emittedColor) {
            colorEmittedOnce = true
            emittedColor = effective
            listener.onEyeColorChanged(effective)
        }
    }

    // =========================================================================================
    // Rules
    // =========================================================================================

    private fun evaluate(now: Long) {
        flushSpeech(now)
        flushSounds(now)
        tickMood(now)
        tickCar(now)
        if (manualMode) return
        if (enrolling) { if (now >= stepEndsAt) setEmotion(Emotion.CURIOUS); return }
        if (coolingDown) { setEmotion(Emotion.HOT); return }
        if (now < stepEndsAt) return
        if (advanceSequence(now)) return

        when (mode) {
            BrainMode.SLEEP, BrainMode.SCREENSAVER -> { setEmotion(Emotion.SLEEPY); return }
            BrainMode.PARTY -> {
                if (now >= partyUntil) {
                    setMode(BrainMode.NORMAL)
                    play(now, Emotion.HAPPY to 2000)
                    speak(AmperaPersona.partyDone())
                } else {
                    setEmotion(Emotion.PARTY)
                }
                return
            }
            BrainMode.FEED_ME -> {
                if (now - feedStartedAt > FEED_TIMEOUT_MS) {
                    setMode(BrainMode.NORMAL)
                    mood.apply(Mood.Event.STARVED)
                    play(now, Emotion.SAD to SAD_MS)
                    speak(AmperaPersona.nobodyFedMe())
                } else {
                    setEmotion(Emotion.HUNGRY)
                }
                return
            }
            BrainMode.NORMAL -> Unit
        }

        // v4 overrides of the resting state
        if (power.heat == PowerWatch.Heat.HOT) { setEmotion(Emotion.HOT); return }
        if (power.isLow && !driving) { setEmotion(Emotion.LOW_BATTERY); return }
        if (!faceVisible && now - lastPersonSeenAt > PERSON_COLOR_FORGET_MS && personColor != null) { personColor = null; emitColor() }
        if (driving) { setEmotion(if (faceVisible) Emotion.TRACKING else Emotion.IDLE); return }

        val alone = now - lastActivityAt
        val lovestruck = (multiSince != 0L && now - multiSince >= LOVE_ENTER_MS) ||
            (face.emotion == Emotion.LOVESTRUCK && now - lastMultiAt < LOVE_EXIT_MS)

        val next: Emotion = when {
            lovestruck -> Emotion.LOVESTRUCK
            faceVisible -> {
                val centered = hypot(smoothX, smoothY) < CENTERED_RADIUS
                if (!centered) centeredSince = 0L else if (centeredSince == 0L) centeredSince = now
                when {
                    lastObs.sizeRatio > TOO_CLOSE_RATIO -> Emotion.SURPRISED
                    (lastObs.smiling ?: 0f) > SMILE_THRESHOLD -> Emotion.HAPPY
                    abs(lastObs.headRoll) > TILT_THRESHOLD_DEG -> Emotion.CURIOUS
                    centered && now - centeredSince > HAPPY_WHEN_CENTERED_MS -> Emotion.HAPPY
                    else -> Emotion.TRACKING
                }
            }
            else -> {
                centeredSince = 0L
                if (alone >= SCREENSAVER_AFTER_MS) {
                    setMode(BrainMode.SCREENSAVER)
                    setEmotion(Emotion.SLEEPY)
                    return
                }
                // Autonomous curiosity: a random idle act every 5-10 s while nobody is around.
                if (nextIdleActAt == 0L) nextIdleActAt = lastActivityAt + randomIdleGap()
                if (alone >= SEARCH_AFTER_MS && now >= nextIdleActAt) {
                    nextIdleActAt = now + randomIdleGap()
                    playIdleAct(alone, now)
                    return
                }
                when {
                    alone >= SLEEP_AFTER_MS -> Emotion.SLEEPY
                    alone >= BORED_AFTER_MS -> Emotion.BORED
                    alone >= SEARCH_AFTER_MS -> {
                        val t = (alone - SEARCH_AFTER_MS) % CONFUSED_EVERY_MS
                        if (t >= CONFUSED_EVERY_MS - CONFUSED_DURATION_MS) Emotion.CONFUSED else Emotion.SEARCHING
                    }
                    else -> when (face.emotion) {
                        Emotion.SLEEPY, Emotion.BORED, Emotion.HYPNOTIZED, Emotion.LOVESTRUCK,
                        Emotion.PARTY, Emotion.HUNGRY, Emotion.EATING, Emotion.SAD,
                        Emotion.SNEEZE, Emotion.YAWN -> Emotion.IDLE
                        else -> face.emotion
                    }
                }
            }
        }
        if (faceVisible && next != Emotion.LOVESTRUCK && next != Emotion.SURPRISED) maybeMuse(now)
        setEmotion(next)
    }

    private enum class IdleAct { LOOK_AROUND, PEEK, HEAVY_BLINK, YAWN, SNEEZE }

    private fun playIdleAct(alone: Long, now: Long) {
        val sleepy = alone >= SLEEP_AFTER_MS
        val bored = alone >= BORED_AFTER_MS
        val weights: List<Pair<IdleAct, Float>> = if (sleepy) {
            listOf(IdleAct.YAWN to 3f, IdleAct.HEAVY_BLINK to 2f, IdleAct.SNEEZE to 0.5f)
        } else {
            listOf(
                IdleAct.LOOK_AROUND to 3f,
                IdleAct.PEEK to 2f,
                IdleAct.HEAVY_BLINK to 2f,
                IdleAct.SNEEZE to 1.2f,
                IdleAct.YAWN to if (bored || mood.isTired) 2f else 0.6f,
            )
        }
        var r = Random.nextFloat() * weights.sumOf { it.second.toDouble() }.toFloat()
        var act = weights.last().first
        for ((a, w) in weights) { if (r < w) { act = a; break }; r -= w }

        when (act) {
            IdleAct.LOOK_AROUND -> play(now, Emotion.SEARCHING to 3200)
            IdleAct.PEEK -> play(now, Emotion.CURIOUS to 1600, Emotion.SURPRISED to 350, Emotion.CURIOUS to 1200)
            IdleAct.HEAVY_BLINK -> {
                listener.onIdleAction(IdleAction.HEAVY_BLINK)
                play(now, (if (sleepy) Emotion.SLEEPY else Emotion.BORED) to 2000)
            }
            IdleAct.YAWN -> play(now, Emotion.YAWN to YAWN_MS)
            IdleAct.SNEEZE -> play(now, Emotion.SNEEZE to SNEEZE_MS, Emotion.CONFUSED to 900)
        }
    }


    // =========================================================================================
    // v4: people, animals, enrolment
    // =========================================================================================

    fun startEnrollment(now: Long = System.currentTimeMillis()) {
        enrolling = true
        clearSequence()
        markActivity(now)
        setEmotion(Emotion.CURIOUS)
        speak(AmperaPersona.enrollStart())
    }

    fun cancelEnrollment() { enrolling = false }

    private fun onEnrollProgress(p: EnrollProgress, now: Long) {
        if (!enrolling) return
        listener.onEnroll(p)
        if (p.done) {
            enrolling = false
            play(now, Emotion.EXCITED to 1500, Emotion.HAPPY to 1500)
            listener.onSound(Sfx.CHIME)
        } else if (p.failed) {
            enrolling = false
            play(now, Emotion.SAD to 1500)
        }
    }

    /** Call after a person was added so the welcome feels personal. */
    fun welcomeNewPerson(person: FaceRegistry.Person, now: Long = System.currentTimeMillis()) {
        lastGreeted[person.id] = now
        listener.onPlayMelody(person)
        speakLater(now + 900, AmperaPersona.newPerson(person.name))
        play(now, Emotion.EXCITED to 1500, Emotion.HAPPY to 2000)
        mood.apply(Mood.Event.KNOWN_PERSON)
    }

    private fun onPersonConfirmed(p: SeenPerson, now: Long) {
        lastPersonSeenAt = now
        if (p.stranger) {
            mood.apply(Mood.Event.STRANGER)
            if (driving || manualMode) return
            strangerSightings++
            listener.onSound(Sfx.WHO)
            if (sequence.isEmpty() && now >= stepEndsAt) play(now, Emotion.CURIOUS to 1500)
            if (strangerSightings >= 3 && registry.size() < FaceRegistry.MAX_PEOPLE && now - lastLearnMePromptAt > LEARN_ME_GAP_MS) {
                lastLearnMePromptAt = now
                speakLater(now + 600, AmperaPersona.learnMePrompt())
            }
            return
        }
        val person = p.personId?.let { registry.find(it) } ?: return
        personName = person.name
        personNameAt = now
        person.favoriteColor?.let { personColor = it; emitColor() }
        val last = lastGreeted[person.id] ?: 0L
        if (now - last < REGREET_MS) return
        lastGreeted[person.id] = now
        mood.apply(Mood.Event.KNOWN_PERSON)
        if (driving) trip.passengersSeen = trip.passengersSeen || lastObs.faceCount >= 2

        listener.onPlayMelody(person)
        val today = todayMonthDay()
        if (person.birthMonth * 100 + person.birthDay == today && birthdaySungOn[person.id] != today) {
            birthdaySungOn[person.id] = today
            if (!driving) {
                play(now, Emotion.PARTY to 6000, Emotion.HAPPY to 2000)
                listener.onSound(Sfx.PARTY)
            }
            speakLater(now + 900, AmperaPersona.birthday(person.name))
            return
        }
        if (driving) return                       // melody only; no show while driving
        if (mood.isGrumpy) {
            play(now, Emotion.GRUMPY to 1200, Emotion.HAPPY to 2000)
            speakLater(now + 900, AmperaPersona.personGrumpy(person.name))
        } else {
            play(now, Emotion.EXCITED to 1400, Emotion.HAPPY to 2200)
            speakLater(now + 900, AmperaPersona.personHi(person.name))
        }
    }

    private fun onAnimals(animals: List<SeenAnimal>, now: Long) {
        if (animals.isEmpty()) return
        val best = animals.maxBy { it.score }
        val reaction = AnimalReactions.forLabel(best.label) ?: return
        if (reaction.isPet && trip.inTrip) trip.animalsSeen = true
        // Follow the animal with the eyes when no human is in view.
        if (!faceVisible && !driving) {
            val ax = -(best.box.centerX() * 2f - 1f)
            val ay = best.box.centerY() * 2f - 1f
            face.lookAt(ax.coerceIn(-1f, 1f), ay.coerceIn(-1f, 1f))
        }
        val last = lastAnimalAt[best.label] ?: -ANIMAL_REACT_GAP_MS
        if (now - last < ANIMAL_REACT_GAP_MS) return
        lastAnimalAt[best.label] = now
        markActivity(now)
        mood.apply(Mood.Event.ANIMAL)
        if (driving || manualMode || mode == BrainMode.SLEEP) return     // remember it, but no show
        listener.onShowIcon(reaction.icon)
        listener.onSound(reaction.sfx)
        if (sequence.isEmpty() && now >= stepEndsAt || face.emotion == Emotion.TRACKING || face.emotion == Emotion.IDLE) {
            play(now, reaction.emotion to 2500)
        }
        speakLater(now + 500, AmperaPersona.animal(reaction.icon, best.label))
    }

    private fun onDistance(cm: Int, now: Long) {
        val was = lastDistCm
        lastDistCm = cm
        if (driving || mode != BrainMode.NORMAL) return
        if (cm in 1..12 && was > 20) {
            markActivity(now)
            play(now, Emotion.SURPRISED to 600, Emotion.CURIOUS to 1500)
            listener.onSound(Sfx.WHOA)
        }
    }

    // =========================================================================================
    // v4: Car Mode
    // =========================================================================================

    /** GPS speed from CarModeController (null = no fix). */
    fun onSpeed(kmh: Float?, now: Long = System.currentTimeMillis()) {
        handleTripEvents(trip.update(now, kmh), now)
    }

    /** Settings ▸ Car mode "Always driving" (testing) or back to automatic. */
    fun forceDriving(on: Boolean, now: Long = System.currentTimeMillis()) {
        val was = trip.driving
        trip.forceDriving(on, now)
        if (was != trip.driving) handleTripEvents(listOf(if (on) TripTracker.Event.STARTED_DRIVING else TripTracker.Event.PARKED), now)
    }

    private fun handleTripEvents(events: List<TripTracker.Event>, now: Long) {
        for (e in events) when (e) {
            TripTracker.Event.STARTED_DRIVING -> {
                setDriving(true)
                drowsiness.reset()
                listener.onSound(Sfx.CHIME)
                speak(AmperaPersona.tripStart())
            }
            TripTracker.Event.PARKED -> {
                setDriving(false)
                markActivity(now)
                play(now, Emotion.HAPPY to 2000)
                speak(AmperaPersona.arrived())
            }
            TripTracker.Event.TRIP_ENDED -> checkBackSeat(now)
            TripTracker.Event.BREAK_DUE -> {
                play(now, Emotion.SLEEPY to 3000)
                speak(AmperaPersona.breakDue(), important = true)
            }
        }
    }

    private fun setDriving(on: Boolean) {
        if (driving == on) return
        driving = on
        lookingSince = 0L
        if (on && mode == BrainMode.PARTY) setMode(BrainMode.NORMAL)
        if (on && (mode == BrainMode.SCREENSAVER || mode == BrainMode.FEED_ME)) setMode(BrainMode.NORMAL)
        listener.onDrivingChanged(on)
    }

    private fun checkBackSeat(now: Long) {
        if (trip.passengersSeen || trip.animalsSeen) {
            backSeatUntil = now + BACK_SEAT_REMINDER_MS
            lastBackSeatNagAt = 0L
        }
        trip.passengersSeen = false
        trip.animalsSeen = false
    }

    private fun onDrivingFrame(obs: FaceObservation, now: Long) {
        if (obs.faceCount >= 2) trip.passengersSeen = true
        for (e in drowsiness.update(now, obs.eyesOpen, obs.mouthOpen)) when (e) {
            DrowsinessMonitor.Event.MICROSLEEP -> {
                alertLevel = if (now - lastAlertAt < 60_000) alertLevel + 1 else 1
                lastAlertAt = now
                play(now, Emotion.ALERT to 2500)
                listener.onSound(Sfx.ALARM)
                if (alertLevel >= 2) speak(AmperaPersona.wakeUpDriver(), important = true)
            }
            DrowsinessMonitor.Event.DROWSY -> {
                play(now, Emotion.ALERT to 1500, Emotion.SLEEPY to 1500)
                speak(AmperaPersona.driverSleepy(), important = true)
            }
            DrowsinessMonitor.Event.YAWN -> listener.onSound(Sfx.YAWN)
        }
    }

    /** Phone shaken by hand (desk only): dizzy! */
    fun onShake(now: Long = System.currentTimeMillis()) {
        if (driving || manualMode || coolingDown || mode == BrainMode.SLEEP) return
        if (mode == BrainMode.SCREENSAVER) wake(now, fromSleepCard = false, quiet = true)
        markActivity(now)
        mood.apply(Mood.Event.POKED)
        play(now, Emotion.DIZZY to DIZZY_MS, Emotion.CONFUSED to 900)
        speakLater(now + 1200, AmperaPersona.dizzy())
    }

    /** Accelerometer event from CarModeController. */
    fun onRoad(e: RoadFeel.Event, now: Long = System.currentTimeMillis()) {
        if (!driving || face.emotion == Emotion.ALERT || now - lastRoadAt < 3000) return
        lastRoadAt = now
        when (e) {
            RoadFeel.Event.BUMP -> { listener.onSound(Sfx.OOF); play(now, Emotion.SURPRISED to 400) }
            RoadFeel.Event.HARD_MANEUVER -> { listener.onSound(Sfx.WHOA); play(now, Emotion.SURPRISED to 700) }
        }
    }

    private fun tickCar(now: Long) {
        if (backSeatUntil > now && now - lastBackSeatNagAt > BACK_SEAT_NAG_EVERY_MS) {
            lastBackSeatNagAt = now
            play(now, Emotion.ALERT to 2000)
            listener.onSound(Sfx.CHIME)
            speak(AmperaPersona.backSeat(), important = true)
        }
    }

    // =========================================================================================
    // v4: power, heat, mood
    // =========================================================================================

    fun onPower(percent: Int, charging: Boolean, tempC: Float?, now: Long = System.currentTimeMillis()) {
        for (e in power.update(percent, charging, tempC)) when (e) {
            PowerWatch.Event.PLUGGED_IN -> {
                if (coolingDown) continue
                if (driving) { listener.onSound(Sfx.CHIME); continue }
                if (trip.inTrip.not()) markActivity(now)
                if (mode == BrainMode.SCREENSAVER) setMode(BrainMode.NORMAL)
                listener.onShowIcon(PixelIcon.BOLT)
                play(now, Emotion.POWER_UP to POWER_UP_MS, Emotion.EXCITED to 1500)
                listener.onSound(Sfx.CHARGE_UP)
                soundLater(now + (VectorFaceView.POWER_CHARGE_S * 1000).toLong(), Sfx.CHARGE_BURST)
                speakLater(now + POWER_UP_MS, AmperaPersona.powerUp())
            }
            PowerWatch.Event.UNPLUGGED -> if (trip.inTrip && !driving) { trip.endTrip(); checkBackSeat(now) }
            PowerWatch.Event.LOW -> if (!driving) {
                lastLowBatterySpeechAt = now
                speak(AmperaPersona.batteryLow())
            }
            PowerWatch.Event.CRITICAL -> speak(AmperaPersona.batteryCritical(), important = true)
            PowerWatch.Event.HOT -> {
                play(now, Emotion.HOT to 2500)
                speak(AmperaPersona.gettingHot(), important = true)
            }
            PowerWatch.Event.COOL_DOWN -> {
                coolingDown = true
                listener.onCoolDown(true)
                speak(AmperaPersona.coolDown(), important = true)
            }
            PowerWatch.Event.COOLED -> if (coolingDown) {
                coolingDown = false
                listener.onCoolDown(false)
                play(now, Emotion.HAPPY to 1500)
                speak(AmperaPersona.cooled())
            }
        }
        // Reminder every few minutes while low and not charging.
        if (power.isLow && !driving && now - lastLowBatterySpeechAt > LOW_BATTERY_SPEECH_GAP_MS) {
            lastLowBatterySpeechAt = now
            listener.onSound(Sfx.TUMMY)
            speak(AmperaPersona.chargeReminder(power.isCritical))
        }
    }

    private fun tickMood(now: Long) {
        val dt = (now - lastMoodTickAt) / 1000f
        if (dt < 1f) return
        lastMoodTickAt = now
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f
        mood.tick(dt, hour, alone = !faceVisible)
    }

    private fun todayMonthDay(): Int {
        val c = java.util.Calendar.getInstance()
        return (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private fun markActivity(now: Long) {
        lastActivityAt = now
        nextIdleActAt = 0L
    }

    private fun randomIdleGap() = Random.nextLong(IDLE_ACT_MIN_MS, IDLE_ACT_MAX_MS + 1)

    private fun setMode(m: BrainMode) {
        if (mode != m) {
            mode = m
            listener.onModeChanged(m)
        }
    }

    private fun play(now: Long, vararg steps: Pair<Emotion, Long>) {
        sequence.clear()
        sequence.addAll(steps)
        stepEndsAt = 0L
        advanceSequence(now)
    }

    private fun clearSequence() {
        sequence.clear()
        stepEndsAt = 0L
    }

    private fun advanceSequence(now: Long): Boolean {
        val step = sequence.removeFirstOrNull() ?: return false
        stepEndsAt = now + step.second
        setEmotion(step.first)
        return true
    }

    private fun setEmotion(e: Emotion) {
        if (face.emotion != e) face.emotion = e
        listener.onState(e, lastObs)
    }

    private fun speak(text: String, important: Boolean = false) {
        lastSpokeAt = System.currentTimeMillis()
        listener.onSpeak(text, important)
    }

    /** Ampera's quiet remarks: only while you're with her, calm, parked, and she's been silent a while. */
    private fun maybeMuse(now: Long) {
        if (driving || mode != BrainMode.NORMAL || mood.isGrumpy || enrolling || listening) return
        if (chattiness == Chattiness.QUIET) return
        val c = chattiness
        if (nextMuseAt == 0L) { nextMuseAt = now + Random.nextLong(c.minMs, c.maxMs); return }
        if (now < nextMuseAt || now - lastSpokeAt < c.quietMs) return
        nextMuseAt = now + Random.nextLong(c.minMs, c.maxMs)
        chat.clearContext()
        deliver(chat.proactive(chatContext(now)), now)
    }

    // =========================================================================================
    // Ampera talk-back
    // =========================================================================================

    fun chatContext(now: Long = System.currentTimeMillis()): ChatContext {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return ChatContext(
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK),
            dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR),
            person = personName?.takeIf { now - personNameAt < PERSON_NAME_FRESH_MS },
            battery = power.percent,
            charging = power.charging,
            grumpy = mood.isGrumpy,
            tired = mood.isTired,
            driving = driving,
        )
    }

    /** The mic opened (true) / closed (false). */
    fun onListening(active: Boolean, now: Long = System.currentTimeMillis()) {
        listening = active
        if (active) {
            markActivity(now)
            if (mode == BrainMode.SCREENSAVER) wake(now, fromSleepCard = false, quiet = true)
            if (mode != BrainMode.SLEEP) play(now, Emotion.CURIOUS to LISTEN_MAX_MS)
        } else if (face.emotion == Emotion.CURIOUS) {
            clearSequence()
        }
    }

    /** Something was said to Ampera (best recogniser guess first). */
    fun onHeard(texts: List<String>, now: Long = System.currentTimeMillis()) {
        listening = false
        markActivity(now)
        mood.apply(Mood.Event.PLAYED)
        val reply = chat.reply(texts, chatContext(now))
        if (mode == BrainMode.SLEEP && reply.action != QrCommand.Wake) {
            speak(AmperaPersona.sleepTalk())
            return
        }
        if (mode == BrainMode.SCREENSAVER) wake(now, fromSleepCard = false, quiet = true)
        deliver(reply, now)
    }

    /** The mic was open but nothing was understood. */
    fun onHeardNothing(now: Long = System.currentTimeMillis()) {
        listening = false
        if (chat.expectsAnswer) {
            chat.clearContext()
            play(now, Emotion.CONFUSED to 1200)
            listener.onChat("[Static feedback] I didn't hear anything. Talk to me anytime!")
        }
    }

    private fun deliver(r: ChatReply, now: Long) {
        r.action?.let { handleCommand(it, now, fromQr = false) }
        if (r.action == null) r.emotion?.let { play(now, it to CHAT_FACE_MS) }
        r.text?.let {
            lastSpokeAt = System.currentTimeMillis()
            listener.onChat(it)
        }
        if (r.listenAgain) listener.onWantListen(r.text ?: "")
    }

    private fun speakLater(at: Long, text: String) { pendingSpeech += at to text }

    private fun flushSpeech(now: Long) {
        if (pendingSpeech.isEmpty()) return
        val due = pendingSpeech.filter { it.first <= now }
        if (due.isEmpty()) return
        pendingSpeech.removeAll(due)
        due.forEach { speak(it.second) }
    }

    private fun soundLater(at: Long, sfx: Sfx) { pendingSounds += at to sfx }

    private fun flushSounds(now: Long) {
        if (pendingSounds.isEmpty()) return
        val due = pendingSounds.filter { it.first <= now }
        pendingSounds.removeAll(due)
        due.forEach { listener.onSound(it.second) }
    }

    companion object {
        // Ampera talk-back
        const val PERSON_NAME_FRESH_MS = 60_000L
        const val LISTEN_MAX_MS = 8_000L
        const val CHAT_FACE_MS = 2_200L
        // ---- v2 face rules ----
        const val SEARCH_AFTER_MS = 3_000L
        const val CONFUSED_EVERY_MS = 8_000L
        const val CONFUSED_DURATION_MS = 1_600L
        const val GREET_MS = 1_500L
        const val HYPNOTIZED_MS = 3_000L      // was 4.5 s
        const val DIZZY_MS = 3_500L
        const val WAVE_EXCITED_MS = 2_000L
        const val WAVE_HAPPY_MS = 2_500L
        const val HAPPY_WHEN_CENTERED_MS = 2_000L
        const val LOVE_ENTER_MS = 500L
        const val LOVE_EXIT_MS = 1_500L
        const val CENTERED_RADIUS = 0.18f
        const val TOO_CLOSE_RATIO = 0.55f
        const val SMILE_THRESHOLD = 0.7f
        const val TILT_THRESHOLD_DEG = 15f

        // ---- v3 idle timeline (no face / QR / touch) ----
        const val IDLE_ACT_MIN_MS = 5_000L       // random idle act every 5-10 s
        const val IDLE_ACT_MAX_MS = 10_000L
        const val BORED_AFTER_MS = 15_000L
        const val SLEEP_AFTER_MS = 35_000L
        const val SCREENSAVER_AFTER_MS = 60_000L // then dim, low-power screensaver
        const val YAWN_MS = 2_600L
        const val SNEEZE_MS = 1_800L

        // ---- v3 QR / games ----
        const val QR_EMOTION_HOLD_MS = 4_000L
        const val QR_REPEAT_MS = 2_500L          // same card must leave view this long to re-trigger
        const val FEED_TIMEOUT_MS = 45_000L
        const val EATING_MS = 2_600L
        const val FED_HAPPY_MS = 2_500L
        const val SAD_MS = 3_500L
        const val PARTY_MS = 20_000L
        const val PARTY_EXTEND_MS = 10_000L
        const val COLOR_SPEECH_GAP_MS = 8_000L
        const val GREET_SPEECH_GAP_MS = 45_000L

        // ---- v3 touch ----
        const val PET_MS = 2_200L
        const val BOOP_MS = 1_400L
        const val POKE_STARTLE_MS = 900L
        const val POKE_CURIOUS_MS = 1_100L
        const val POKE_SPAM_MS = 2_200L
        const val FLING_MS = 1_500L

        // ---- v4 ----
        const val REGREET_MS = 10 * 60_000L
        const val LEARN_ME_GAP_MS = 10 * 60_000L
        const val ANIMAL_REACT_GAP_MS = 60_000L
        const val GLANCE_LIMIT_MS = 2_000L
        const val BACK_SEAT_REMINDER_MS = 2 * 60_000L
        const val BACK_SEAT_NAG_EVERY_MS = 20_000L
        const val PERSON_COLOR_FORGET_MS = 10_000L
        const val POWER_UP_MS = 3_600L
        const val LOW_BATTERY_SPEECH_GAP_MS = 5 * 60_000L

    }
}
