package com.example.eyebot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wires everything together:
 *
 *   CameraX ──▶ FaceTrackingAnalyzer (faces + QR + colour + motion) ──▶ CompanionBrain ──▶ VectorFaceView
 *                                                                          │
 *        touch / keys / ESP32 lines ──────────────────────────────────────▶│──▶ AudioPersonality (SoundPool)
 *                                                                          │──▶ RobotVoice (TTS)
 *                                         face offset ──▶ PanTiltController ──▶ ServoLink (BLE / USB)
 */
class MainActivity : ComponentActivity(), BrainListener, ServoLinkEvents, TouchInterpreter.Callbacks,
    CarModeController.Listener, PeopleUi.Callbacks {

    private lateinit var faceView: VectorFaceView
    private lateinit var previewView: PreviewView
    private lateinit var debugText: TextView
    private lateinit var permissionText: TextView

    private lateinit var settings: Settings
    private lateinit var brain: CompanionBrain
    private lateinit var sounds: SoundBank
    private lateinit var audio: AudioPersonality
    private lateinit var voice: RobotVoice
    private lateinit var fish: FishVoice
    private lateinit var analysisExecutor: ExecutorService
    private var analyzer: FaceTrackingAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val panTilt = PanTiltController()
    private var servoLink: ServoLink? = null
    private var linkStatus = "servo: off"
    private var lastFrameAt = 0L

    private var lastQrText = "-"
    private var lastColorName = "-"

    // ---- v4 ----
    private val registry = FaceRegistry()
    private var recognizer: FaceRecognizer? = null
    private var animalDetector: AnimalDetector? = null
    private lateinit var voiceBox: VoiceBox
    private lateinit var car: CarModeController
    private lateinit var peopleUi: PeopleUi
    private lateinit var ears: VoiceListener
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reopenMic = Runnable { if (hasPermission(Manifest.permission.RECORD_AUDIO)) ears.listenOnce() }
    private var enrollRelearnId: Long? = null
    private var savedRegistryVersion = -1
    private var lastSaveAt = 0L
    private var lux = 100f
    private var darkSince = 0L
    private var lastAnimalsText = "-"
    private var lastAnimalOffset: Pair<Float, Float>? = null
    private var lastAnimalAt = 0L
    private var speedKmh: Float? = null

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) applyCarMode() else toast("Without location, Car Mode only works when set to \"Always on\"")
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { brain.manualMode = false; startCamera() } else onCameraDenied()
        applyCarMode(askPermission = true)        // only one permission prompt at a time: location comes after
    }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { ears.listenOnce(); applyWakeWord() }
        else toast("Ampera needs the microphone to hear you")
    }

    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) connectBle() else toast("Bluetooth permission is needed for the pan-tilt stand")
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        faceView = findViewById(R.id.faceView)
        previewView = findViewById(R.id.previewView)
        debugText = findViewById(R.id.debugText)
        permissionText = findViewById(R.id.permissionText)

        enterImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = Settings(this)
        sounds = SoundBank(this)
        audio = AudioPersonality(sounds)
        voice = RobotVoice(this)
        fish = FishVoice(this)
        voice.cloud = fish
        voiceBox = VoiceBox(voice) { text -> faceView.bubbleText = text }
        analysisExecutor = Executors.newSingleThreadExecutor()
        loadPeople()
        brain = CompanionBrain(faceView, this, registry, Mood.parse(settings.mood), PrefsChatMemory(this))
        ears = VoiceListener(this, object : VoiceListener.Callbacks {
            override fun onListening(active: Boolean) {
                brain.onListening(active)
                faceView.bubbleText = if (active) "[Mic on] I'm listening..." else null
            }
            override fun onHeard(texts: List<String>) {
                if (faceView.bubbleText?.startsWith("[Mic on]") == true) faceView.bubbleText = null
                brain.onHeard(texts)
            }
            override fun onHeardNothing() = brain.onHeardNothing()
        })
        peopleUi = PeopleUi(this, registry, this)
        // Load the v4 models on the camera thread (a few hundred ms) so start-up stays snappy.
        analysisExecutor.execute {
            recognizer = FaceRecognizer(applicationContext)
            animalDetector = AnimalDetector(applicationContext)
        }
        car = CarModeController(this, this)
        car.startPowerAndSensors()
        applySettings()
        applyCarMode()

        faceView.setOnTouchListener(TouchInterpreter(this, faceView, this))
        startBrainTicker()

        permissionText.setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }
        if (hasPermission(Manifest.permission.CAMERA)) { startCamera(); applyCarMode(askPermission = true) }
        else requestCamera.launch(Manifest.permission.CAMERA)

        displayManager.registerDisplayListener(displayListener, null)
        handleUsbIntent(intent)
        if (settings.servo && settings.bleAutoConnect && servoLink == null && blePermissions().all(::hasPermission)) connectBle()
        if (settings.servo && settings.wifiAutoConnect && servoLink == null) connectWifi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        analysisUseCase?.clearAnalyzer()
        analyzer?.close()
        car.stopAll()
        savePeopleAndMood(force = true)
        analysisExecutor.execute { recognizer?.close(); animalDetector?.close() }
        analysisExecutor.shutdown()
        mainHandler.removeCallbacks(reopenMic)
        ears.destroy()
        voiceBox.shutdown()
        servoLink?.close()
        voice.shutdown()
        fish.shutdown()
        sounds.release()
    }

    // =========================================================================================
    // Camera
    // =========================================================================================

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        permissionText.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val vision = FaceTrackingAnalyzer(analysisExecutor, LazyRecognizer(), LazyAnimals(), registry, ::onVisionFrame).also {
                it.qrEnabled = settings.qrCards
                it.colorEnabled = settings.colorEyes
                it.lowPower = brain.mode == BrainMode.SLEEP || brain.mode == BrainMode.SCREENSAVER
                it.recognitionEnabled = settings.recognition
                it.animalsEnabled = settings.animals
                it.driving = brain.driving
            }
            analyzer = vision

            analysisUseCase = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, vision) }

            updateTargetRotation()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds the analyzer, plus the Preview use case only while the PIP preview is visible: a
     * GONE PreviewView never provides a surface, which would stall the whole camera session.
     */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val analysis = analysisUseCase ?: return
        try {
            provider.unbindAll()
            val preview = previewUseCase
            if (previewView.visibility == View.VISIBLE && preview != null) {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } else {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            faceView.emotion = Emotion.CONFUSED
        }
    }

    /**
     * sensorLandscape + configChanges means a 180 degree flip doesn't recreate the activity, so
     * keep the analysis rotation in sync ourselves (otherwise ML Kit sees upside-down frames).
     */
    private fun updateTargetRotation() {
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        analysisUseCase?.targetRotation = rotation
        previewUseCase?.targetRotation = rotation
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateTargetRotation()
        }
    }

    private fun onCameraDenied() {
        permissionText.visibility = View.VISIBLE
        brain.manualMode = true
        faceView.emotion = Emotion.SLEEPY
    }

    /** Main thread, once per analysed frame. */
    private fun onVisionFrame(frame: VisionFrame) {
        if (frame.qrPayloads.isNotEmpty()) lastQrText = frame.qrPayloads.first().take(24)
        lastColorName = frame.color?.bucket?.label ?: "-"
        if (frame.animalsChecked) {
            lastAnimalsText = frame.animals.joinToString { "${it.label} %.0f%%".format(Locale.US, it.score * 100) }.ifEmpty { "-" }
            frame.animals.maxByOrNull { it.score }?.let { a ->
                lastAnimalOffset = (-(a.box.centerX() * 2f - 1f)) to (a.box.centerY() * 2f - 1f)
                lastAnimalAt = System.currentTimeMillis()
            }
        }
        brain.onFrame(frame)
        driveServos(frame.face)
    }

    /** The models load in the background; these thin wrappers let the analyzer start before them. */
    private inner class LazyRecognizer : FaceRecognizerApi {
        override val available get() = recognizer?.available == true
        override fun embed(upright: android.graphics.Bitmap, box: android.graphics.Rect, rollDeg: Float) = recognizer?.embed(upright, box, rollDeg)
    }
    private inner class LazyAnimals : AnimalDetectorApi {
        override val available get() = animalDetector?.available == true
        override fun detect(upright: android.graphics.Bitmap) = animalDetector?.detect(upright).orEmpty()
    }

    // =========================================================================================
    // Pan-tilt servos
    // =========================================================================================

    private fun driveServos(obs: FaceObservation) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        val link = servoLink ?: return
        if (!link.isConnected || !settings.servo) return

        val behavior = when {
            brain.manualMode -> PanTiltController.Behavior.HOLD
            brain.mode == BrainMode.SLEEP || brain.mode == BrainMode.SCREENSAVER -> PanTiltController.Behavior.SLEEP
            brain.mode == BrainMode.PARTY -> PanTiltController.Behavior.PARTY
            obs.present -> PanTiltController.Behavior.TRACK
            now - lastAnimalAt < 1500 && lastAnimalOffset != null -> PanTiltController.Behavior.TRACK
            faceView.emotion == Emotion.SEARCHING -> PanTiltController.Behavior.SEARCH
            faceView.emotion == Emotion.SLEEPY -> PanTiltController.Behavior.SLEEP
            else -> PanTiltController.Behavior.HOLD
        }
        val target = if (!obs.present && now - lastAnimalAt < 1500) lastAnimalOffset else null
        panTilt.step(behavior, target?.first ?: obs.offsetX, target?.second ?: obs.offsetY, dt)
        panTilt.pendingCommand(now)?.let(link::send)
    }

    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun connectBle() {
        val perms = blePermissions()
        if (!perms.all(::hasPermission)) { requestBle.launch(perms); return }
        servoLink?.close()
        settings.servo = true
        settings.bleAutoConnect = true
        servoLink = BleServoLink(this, this).also { it.start() }
    }

    private fun connectWifi() {
        servoLink?.close()
        settings.servo = true
        settings.wifiAutoConnect = true
        servoLink = WifiServoLink(this, this).also { it.start() }
    }

    private fun connectUsb() {
        servoLink?.close()
        settings.servo = true
        servoLink = UsbServoLink(this, this).also { it.start() }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && servoLink?.isConnected != true) connectUsb()
    }

    override fun onLinkStatus(text: String) {
        linkStatus = "servo: $text"
        Log.i(TAG, linkStatus)
    }

    override fun onLinkConnected(name: String) {
        linkStatus = "servo: $name"
        toast("Pan-tilt connected ($name)")
        audio.play(Sfx.QR_ACK, force = true)
        panTilt.recenter()
        servoLink?.send(panTilt.forceCommand(System.currentTimeMillis()))
    }

    override fun onLinkDisconnected() {
        linkStatus = "servo: disconnected"
        toast("Pan-tilt disconnected")
    }

    override fun onLinkLine(line: String) {
        Log.d(TAG, "from servo board: $line")
        brain.onExternalLine(line)
    }

    // =========================================================================================
    // Brain -> outputs
    // =========================================================================================

    override fun onState(emotion: Emotion, obs: FaceObservation) {
        audio.onEmotion(emotion)
        if (debugText.visibility == View.VISIBLE) {
            debugText.text = String.format(
                Locale.US,
                "%s  [%s]  faces=%d  x=%+.2f y=%+.2f  size=%.2f  smile=%.2f  eyes=%s  motion=%.2f%s%s\n" +
                    "qr=%s  colour=%s  animals=%s  people=%d  mood h%.2f e%.2f s%.2f\n" +
                    "car=%s %s  batt=%d%%%s %s  pan=%.0f tilt=%.0f  %s%s",
                emotion, brain.mode, obs.faceCount, obs.offsetX, obs.offsetY, obs.sizeRatio,
                obs.smiling ?: 0f, obs.eyesOpen?.let { "%.2f".format(Locale.US, it) } ?: "-", obs.motion,
                if (obs.waving) "  WAVE!" else "", if (obs.circling) "  CIRCLE!" else "",
                lastQrText, lastColorName, lastAnimalsText, registry.size(),
                brain.mood.happiness, brain.mood.energy, brain.mood.social,
                if (brain.driving) "DRIVING" else settings.carMode.name.lowercase(),
                speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "no gps",
                brain.power.percent, if (brain.power.charging) "+" else "", brain.power.heat.name.lowercase(),
                panTilt.pan, panTilt.tilt, linkStatus,
                if (sounds.enabled) "" else "  (muted)",
            )
        }
    }

    override fun onModeChanged(mode: BrainMode) = updateDisplay()

    /** One place that decides dimming, brightness, frame rate and analysis load. */
    private fun updateDisplay() {
        val mode = brain.mode
        val sleepish = mode == BrainMode.SLEEP || mode == BrainMode.SCREENSAVER
        val nightDrive = brain.driving && isNight()
        faceView.partyMode = mode == BrainMode.PARTY
        faceView.lowPower = sleepish || brain.coolingDown
        faceView.dim = when {
            brain.coolingDown -> 0.6f
            mode == BrainMode.SLEEP -> 0.55f
            mode == BrainMode.SCREENSAVER -> 0.4f
            nightDrive -> 0.5f
            else -> 0f
        }
        audio.quiet = mode == BrainMode.SCREENSAVER
        analyzer?.lowPower = sleepish || brain.power.heat != PowerWatch.Heat.OK
        analyzer?.driving = brain.driving
        voiceBox.bubblesAllowed = !brain.driving
        setScreenBrightness(
            when {
                mode == BrainMode.SLEEP -> 0.02f
                mode == BrainMode.SCREENSAVER || brain.coolingDown -> 0.06f
                nightDrive -> 0.12f                      // dashboard at night: very dim
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        )
    }

    private fun isNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val darkLong = darkSince != 0L && System.currentTimeMillis() - darkSince > 10_000
        return hour >= 19 || hour < 6 || darkLong
    }

    override fun onEyeColorChanged(argb: Int?) = faceView.setEyeColor(argb)

    override fun onSpeak(text: String, important: Boolean) {
        ears.pauseFor(voiceBox.estimateChatMs(text) + 400)
        voiceBox.say(text, important)
    }

    override fun onChat(text: String) {
        ears.pauseFor(voiceBox.estimateChatMs(text) + 400)
        voiceBox.chat(text)
    }

    override fun onWantListen(afterText: String) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) || !ears.available) return
        mainHandler.removeCallbacks(reopenMic)
        mainHandler.postDelayed(reopenMic, voiceBox.estimateChatMs(afterText) + 350)
    }

    // =========================================================================================
    // Talk-back: long-press, the T key, or "Hey Ampera"
    // =========================================================================================

    private fun startTalking() {
        if (!ears.available) { toast("This phone has no speech recognition (install Google app / Speech Services)"); return }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) { requestMic.launch(Manifest.permission.RECORD_AUDIO); return }
        mainHandler.removeCallbacks(reopenMic)
        ears.listenOnce()
    }

    private fun applyWakeWord() {
        ears.setWakeWord(settings.wakeWord && hasPermission(Manifest.permission.RECORD_AUDIO) && ears.available)
    }

    override fun onResume() {
        super.onResume()
        if (::ears.isInitialized) applyWakeWord()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(reopenMic)
        ears.setWakeWord(false)
        ears.stop()
    }

    private fun showTalkSettings() {
        val items = arrayOf(
            "Talk to Ampera now",
            "Listen for \"Hey Ampera\": ${if (settings.wakeWord) "On" else "Off"}",
            "Chattiness: ${settings.chattiness.label}",
            "Demo mode (step through faces)",
            "What can I say?",
            "Natural voice (Fish Audio): ${if (settings.fishEnabled && settings.fishKey.isNotBlank()) "On" else "Off"}…",
        )
        AlertDialog.Builder(this).setTitle("Talk & chat")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startTalking()
                    1 -> {
                        settings.wakeWord = !settings.wakeWord
                        if (settings.wakeWord && !hasPermission(Manifest.permission.RECORD_AUDIO)) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        applyWakeWord()
                        toast(if (settings.wakeWord) "Say \"Hey Ampera\" any time (uses more battery)" else "Wake word off. Long-press to talk.")
                    }
                    2 -> pickChattiness()
                    3 -> toggleDemoMode()
                    4 -> AlertDialog.Builder(this).setTitle("Things to say")
                        .setMessage(TALK_HELP).setPositiveButton("OK", null).show()
                    5 -> showFishSettings()
                }
            }
            .setPositiveButton("Done", null).show()
    }

    // =========================================================================================
    // Ampera: natural voice through Fish Audio (online, needs your own API key)
    // =========================================================================================

    private fun showFishSettings() {
        val voiceLabel = settings.fishVoice.ifBlank { "not set" }
        val keyLabel = if (settings.fishKey.isBlank()) "not set" else "••••" + settings.fishKey.takeLast(4)
        val items = arrayOf(
            "Use Fish Audio voice: ${if (settings.fishEnabled) "On" else "Off"}",
            "API key: $keyLabel",
            "Voice ID: $voiceLabel",
            "Search voices…",
            "Model: ${settings.fishModel}",
            "Test voice",
        )
        AlertDialog.Builder(this).setTitle("Natural voice (Fish Audio)")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        settings.fishEnabled = !settings.fishEnabled
                        applySettings()
                        if (settings.fishEnabled && settings.fishKey.isBlank()) toast("Add your Fish Audio API key first")
                        showFishSettings()
                    }
                    1 -> askText("Fish Audio API key", settings.fishKey, "From fish.audio ▸ API Keys. Stored on this phone only.") {
                        settings.fishKey = it; applySettings(); showFishSettings()
                    }
                    2 -> askText("Voice ID", settings.fishVoice, "Paste a voice model ID from fish.audio, or use Search voices.") {
                        settings.fishVoice = it; applySettings(); showFishSettings()
                    }
                    3 -> askText("Search voices", "teen girl", "Words to search the Fish Audio voice library") { q ->
                        toast("Searching…")
                        fish.searchVoices(q) { found ->
                            if (found.isNullOrEmpty()) { toast("No voices found (check internet / key)"); return@searchVoices }
                            AlertDialog.Builder(this).setTitle("Pick a voice")
                                .setItems(found.map { it.first }.toTypedArray()) { _, i ->
                                    settings.fishVoice = found[i].second
                                    applySettings()
                                    voiceBox.chat(AmperaPersona.voiceTest())
                                }.setNegativeButton("Cancel", null).show()
                        }
                    }
                    4 -> {
                        val models = FISH_MODELS
                        AlertDialog.Builder(this).setTitle("Fish Audio model")
                            .setSingleChoiceItems(models, models.indexOf(settings.fishModel)) { d, i ->
                                settings.fishModel = models[i]; applySettings(); d.dismiss(); showFishSettings()
                            }.show()
                    }
                    5 -> voiceBox.chat(AmperaPersona.voiceTest())
                }
            }
            .setPositiveButton("Done", null).show()
    }

    private fun askText(title: String, initial: String, hint: String, done: (String) -> Unit) {
        val input = android.widget.EditText(this).apply { setText(initial); this.hint = hint; setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(input)
            .setPositiveButton("OK") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickChattiness() {
        val all = Chattiness.entries
        AlertDialog.Builder(this).setTitle("How chatty?")
            .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(settings.chattiness)) { d, which ->
                settings.chattiness = all[which]; applySettings(); d.dismiss()
            }.show()
    }

    // ---- v4 brain outputs ----
    override fun onShowIcon(icon: PixelIcon) = faceView.showIcon(icon)

    override fun onPlayMelody(person: FaceRegistry.Person) = voiceBox.play(BeepSpeech.signature(person.name, person.melodyVariant))

    override fun onEnroll(progress: EnrollProgress) {
        when {
            progress.done -> { faceView.bubbleText = null; peopleUi.finishEnrollment(progress.samples, enrollRelearnId); enrollRelearnId = null }
            progress.failed -> { faceView.bubbleText = null; toast(progress.hint); enrollRelearnId = null }
            else -> faceView.bubbleText = "${progress.hint}  ${progress.collected}/${progress.target}"
        }
    }

    override fun onDrivingChanged(driving: Boolean) {
        updateDisplay()
        if (driving) { faceView.bubbleText = null; analyzer?.cancelEnrollment(); brain.cancelEnrollment() }
    }

    override fun onCoolDown(active: Boolean) {
        if (active) cameraProvider?.unbindAll() else bindCamera()
        updateDisplay()
    }

    // ---- CarModeController.Listener ----
    override fun onSpeed(kmh: Float?) {
        speedKmh = kmh
        if (settings.carMode == CarModeSetting.AUTO) brain.onSpeed(kmh)
    }

    override fun onRoad(event: RoadFeel.Event) = brain.onRoad(event)

    override fun onShake() = brain.onShake()

    override fun onLight(lux: Float) {
        this.lux = lux
        val now = System.currentTimeMillis()
        if (lux < 12f) { if (darkSince == 0L) darkSince = now } else darkSince = 0L
        if (brain.driving) updateDisplay()
    }

    override fun onPower(percent: Int, charging: Boolean, tempC: Float?) {
        brain.onPower(percent, charging, tempC)
        updateGpsNeed()
        updateDisplay()
    }

    /**
     * Automatic Car mode keeps GPS off on the desk: it only runs while charging (a dashboard
     * phone lives on the car charger) or while a trip is in progress.
     */
    private fun updateGpsNeed() {
        if (settings.carMode != CarModeSetting.AUTO || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (brain.power.charging || brain.trip.inTrip) car.startGps() else car.stopGps()
    }

    private fun applyCarMode(askPermission: Boolean = false) {
        when (settings.carMode) {
            CarModeSetting.OFF -> { car.stopGps(); brain.forceDriving(false) }
            CarModeSetting.ALWAYS -> { car.stopGps(); brain.forceDriving(true) }
            CarModeSetting.AUTO -> {
                brain.forceDriving(false)
                val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) updateGpsNeed()
                else if (askPermission && !getPreferences(MODE_PRIVATE).getBoolean("asked_location", false)) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("asked_location", true).apply()
                    requestLocation.launch(perms)
                }
            }
        }
        updateDisplay()
    }

    // ---- People ----
    private val peopleFile by lazy { java.io.File(filesDir, "people.txt") }

    private fun loadPeople() {
        try { if (peopleFile.exists()) registry.load(peopleFile.readText()) } catch (e: Exception) { Log.w(TAG, "Could not load people", e) }
        registry.expireGuests(System.currentTimeMillis())
        savedRegistryVersion = registry.version
    }

    private fun savePeopleAndMood(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 60_000) return
        lastSaveAt = now
        settings.mood = brain.mood.serialize()
        if (registry.version != savedRegistryVersion) {
            val text = registry.serialize()
            savedRegistryVersion = registry.version
            try {
                val tmp = java.io.File(filesDir, "people.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(peopleFile)) { peopleFile.writeText(text); tmp.delete() }
            } catch (e: Exception) { Log.w(TAG, "Could not save people", e) }
        }
    }

    override fun startEnrollment(relearnId: Long?) {
        if (brain.driving) { toast("Let's do that when we're parked"); return }
        if (recognizer?.available != true) { toast("Face model not loaded yet - try again in a moment"); return }
        enrollRelearnId = relearnId
        brain.startEnrollment()
        analyzer?.startEnrollment()
    }

    override fun peopleChanged() {
        analyzer?.resetIdentities()
        savePeopleAndMood(force = true)
    }

    override fun playMelody(person: FaceRegistry.Person) = onPlayMelody(person)

    override fun welcome(person: FaceRegistry.Person) {
        analyzer?.resetIdentities()
        brain.welcomeNewPerson(person)
    }

    override fun onSound(sfx: Sfx) = audio.play(sfx, force = true)

    override fun onIdleAction(action: IdleAction) {
        when (action) {
            IdleAction.HEAVY_BLINK -> faceView.blink(double = true, slow = true)
        }
    }

    private fun setScreenBrightness(value: Float) {
        window.attributes = window.attributes.also { it.screenBrightness = value }
    }

    // =========================================================================================
    // Touch
    // =========================================================================================

    override fun onTouchKind(kind: TouchKind, x: Float, y: Float) {
        if (brain.manualMode) {
            // Demo mode (v2): a tap steps to the next emotion.
            if (kind == TouchKind.BOOP || kind == TouchKind.POKE) faceView.emotion = faceView.emotion.next()
            return
        }
        if (kind == TouchKind.POKE) faceView.lookAtScreenPoint(x, y)   // startled look at the finger
        brain.onTouch(kind)
    }

    override fun onFingerAt(x: Float, y: Float) {
        if (!brain.manualMode && brain.mode != BrainMode.SLEEP) faceView.lookAtScreenPoint(x, y)
    }

    override fun onDoubleTap() {
        val show = previewView.visibility != View.VISIBLE
        previewView.visibility = if (show) View.VISIBLE else View.GONE
        debugText.visibility = previewView.visibility
        bindCamera()   // add / remove the Preview use case
    }

    override fun onLongPress() = startTalking()

    private fun toggleDemoMode() {
        brain.manualMode = !brain.manualMode
        faceView.emotion = if (brain.manualMode) Emotion.IDLE.next() else Emotion.IDLE
        faceView.blink(double = true)
        toast(if (brain.manualMode) "Demo mode: tap to step through emotions" else "Demo mode off")
    }

    override fun onTwoFingerTap() {
        val mute = settings.sound || settings.voice
        settings.sound = !mute
        settings.voice = !mute
        applySettings()
        toast(if (mute) "Sounds muted" else "Sounds on")
    }

    override fun onTwoFingerLongPress() = showSettingsDialog()

    // =========================================================================================
    // Physical buttons: Bluetooth / USB keyboards, remotes, game pads, selfie-stick shutters
    // =========================================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> brain.onTouch(TouchKind.PET)

            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_B -> brain.onTouch(TouchKind.BOOP)

            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_CAMERA -> brain.onTouch(TouchKind.POKE)

            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_F -> brain.onTouch(TouchKind.FLING)

            KeyEvent.KEYCODE_DPAD_LEFT -> lookFor(-0.85f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> lookFor(0.85f, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> lookFor(0f, -0.8f)
            KeyEvent.KEYCODE_DPAD_DOWN -> lookFor(0f, 0.8f)

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                // Number keys 1..9, 0 = first ten emotions (handy for testing).
                val idx = if (keyCode == KeyEvent.KEYCODE_0) 9 else keyCode - KeyEvent.KEYCODE_1
                Emotion.entries.getOrNull(idx)?.let { brain.lockEmotion(it, 4000) }
            }
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> startTalking()
            KeyEvent.KEYCODE_S -> brain.onExternalLine("SLEEP")
            KeyEvent.KEYCODE_W -> brain.onExternalLine("WAKE")
            KeyEvent.KEYCODE_Y -> brain.onExternalLine("PARTY")
            KeyEvent.KEYCODE_H -> brain.onExternalLine("FEED_ME")
            KeyEvent.KEYCODE_E -> brain.onExternalLine("FOOD")
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun lookFor(x: Float, y: Float) {
        faceView.lookAt(x, y)
        brain.lockEmotion(Emotion.CURIOUS, 1200)
    }

    // =========================================================================================
    // Settings
    // =========================================================================================

    private fun applySettings() {
        sounds.enabled = settings.sound
        voice.enabled = settings.voice
        voice.robotFx = settings.robotVoiceFx
        fish.enabled = settings.fishEnabled
        fish.apiKey = settings.fishKey
        fish.voiceId = settings.fishVoice
        fish.model = settings.fishModel
        voiceBox.cloudVoice = fish.isActive
        voiceBox.thinkPauseMs = THINK_PAUSE_MS
        analyzer?.qrEnabled = settings.qrCards
        analyzer?.colorEnabled = settings.colorEyes
        panTilt.config = panTilt.config.copy(invertPan = settings.invertPan, invertTilt = settings.invertTilt)
        voiceBox.enabled = settings.voice || settings.sound
        voiceBox.mode = settings.voiceMode
        faceView.eyeScale = settings.eyeScale
        faceView.oneEye = false           // Ampera: two 8-bit eyes
        faceView.pixelEyes = true
        brain.chattiness = settings.chattiness
        analyzer?.recognitionEnabled = settings.recognition
        analyzer?.animalsEnabled = settings.animals
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "People Ampera knows…",
            "Voice: ${settings.voiceMode.label}",
            "Eye size: ${eyeSizeLabel(settings.eyeScale)}",
            "Car mode: ${settings.carMode.label}",
            "Talk & chat…",
            "Features…",
            "Robot link ($linkStatus)…",
        )
        AlertDialog.Builder(this)
            .setTitle("Ampera 1.0")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> peopleUi.showList()
                    1 -> pickVoiceMode()
                    2 -> pickEyeSize()
                    3 -> pickCarMode()
                    4 -> showTalkSettings()
                    5 -> showFeatures()
                    6 -> showLinks()
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun eyeSizeLabel(v: Float) = EYE_SIZES.firstOrNull { it.second == v }?.first ?: "${(v * 100).toInt()} %"

    private fun pickEyeSize() {
        val cur = EYE_SIZES.indexOfFirst { it.second == settings.eyeScale }
        AlertDialog.Builder(this).setTitle("Eye size")
            .setSingleChoiceItems(EYE_SIZES.map { it.first }.toTypedArray(), cur) { d, which ->
                settings.eyeScale = EYE_SIZES[which].second; applySettings(); d.dismiss()
            }.show()
    }

    private fun pickVoiceMode() {
        val modes = VoiceMode.entries
        AlertDialog.Builder(this).setTitle("How Ampera talks")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(settings.voiceMode)) { d, which ->
                settings.voiceMode = modes[which]; applySettings(); d.dismiss()
                voiceBox.say(AmperaPersona.voiceTest())
            }.show()
    }

    private fun pickCarMode() {
        val modes = CarModeSetting.entries
        AlertDialog.Builder(this).setTitle("Car mode (dashboard)")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), modes.indexOf(settings.carMode)) { d, which ->
                settings.carMode = modes[which]; d.dismiss()
                if (modes[which] == CarModeSetting.AUTO && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                applyCarMode()
            }.show()
    }

    private fun showFeatures() {
        val labels = arrayOf(
            "Sound effects", "Voice (beeps / words)", "Robot voice effect", "Recognise household faces",
            "Spot animals", "Colour-card eyes", "QR cards", "Pan-tilt / robot movement", "Invert pan", "Invert tilt",
        )
        val checked = booleanArrayOf(
            settings.sound, settings.voice, settings.robotVoiceFx, settings.recognition,
            settings.animals, settings.colorEyes, settings.qrCards, settings.servo, settings.invertPan, settings.invertTilt,
        )
        AlertDialog.Builder(this)
            .setTitle("Features")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                when (which) {
                    0 -> settings.sound = on
                    1 -> settings.voice = on
                    2 -> settings.robotVoiceFx = on
                    3 -> settings.recognition = on
                    4 -> settings.animals = on
                    5 -> settings.colorEyes = on
                    6 -> settings.qrCards = on
                    7 -> settings.servo = on
                    8 -> settings.invertPan = on
                    9 -> settings.invertTilt = on
                }
                applySettings()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showLinks() {
        AlertDialog.Builder(this)
            .setTitle("Robot link")
            .setMessage("Pan-tilt stand or robot body.\n\nBLE: ESP32 stand\nWi-Fi: ESP8266 / ESP32 on the same network or your phone's hotspot (e.g. the spider robot)\nUSB: OTG cable\n\nNow: $linkStatus")
            .setPositiveButton("Wi-Fi") { _, _ -> connectWifi() }
            .setNegativeButton("BLE") { _, _ -> connectBle() }
            .setNeutralButton("USB") { _, _ -> connectUsb() }
            .show()
    }

    // =========================================================================================
    // Misc
    // =========================================================================================

    private fun startBrainTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    brain.tick()
                    voiceBox.tone = brain.mood.voiceTone()
                    savePeopleAndMood()
                    delay(200)
                }
            }
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "EyeBot"
        private const val THINK_PAUSE_MS = 400L
        private val FISH_MODELS = arrayOf("s2.1-pro", "s2-pro", "s1", "s2.1-pro-free")
        private val TALK_HELP = """
Long-press the screen (or press T on a keyboard) and talk. With the wake word on, just say "Hey Ampera ..."

• Tell me a joke / a riddle / a story
• Let's play (rock paper scissors, guess my number)
• Knock knock! / Tell me a knock-knock joke
• How are you? / Kumusta?
• Who is Voltnutt? Thorne? Sparky? Unit 7? Whirr?
• Where are we going? Which way is north?
• What time is it? What day is it?
• What's 7 plus 5?
• How's your battery?
• Tell me a fact / Sing a song
• Say hello to Mama (he repeats it)
• Party! / Go to sleep / Wake up / Do you want some pizza?
• I love you / Good robot / Thank you / Salamat
""".trimIndent()
        private val EYE_SIZES = listOf("Small (v4 default)" to 0.5f, "Medium" to 0.75f, "Large (v3 size)" to 1.0f)
    }
}
