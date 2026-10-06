package com.example.eyebot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Ampera's ears: Android's built-in speech recogniser (works offline on most phones once the
 * offline language pack is installed). Two ways to use it:
 *
 *  - [listenOnce]: open the mic for one sentence (long-press, the T key, or after he asks a question).
 *  - wake word: keep listening in the background and react to "Hey Ampera ..." ([setWakeWord]).
 *
 * All calls on the main thread.
 */
class VoiceListener(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** The mic opened (true) or closed (false) for a sentence meant for Ampera. */
        fun onListening(active: Boolean)
        /** Recognised text (best guess first) meant for Ampera. */
        fun onHeard(texts: List<String>)
        /** The mic opened but nothing understandable was said. */
        fun onHeardNothing()
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var busy = false
    private var oneShot = false
    private var wakeEnabled = false
    private var pausedUntil = 0L
    private var destroyed = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Open the mic for one sentence. */
    fun listenOnce() {
        if (destroyed || !available) return
        main.removeCallbacks(restartWake)
        stopRecognizer()
        oneShot = true
        start()
    }

    /** Background "Hey Ampera" listening. Uses more battery; off by default. */
    fun setWakeWord(on: Boolean) {
        wakeEnabled = on
        if (on && !busy) scheduleWake(300)
        if (!on && !oneShot) { main.removeCallbacks(restartWake); stopRecognizer() }
    }

    /** Don't listen while Ampera herself is talking (so she doesn't hear his own voice). */
    fun pauseFor(ms: Long) {
        pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + ms)
        if (busy && !oneShot) { stopRecognizer(); scheduleWake(ms) }
    }

    fun stop() {
        main.removeCallbacks(restartWake)
        if (oneShot) callbacks.onListening(false)
        oneShot = false
        stopRecognizer()
    }

    fun destroy() {
        destroyed = true
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    // ------------------------------------------------------------------------------------------

    private val restartWake = Runnable {
        if (destroyed || !wakeEnabled || busy) return@Runnable
        val wait = pausedUntil - System.currentTimeMillis()
        if (wait > 0) { scheduleWake(wait + 100); return@Runnable }
        oneShot = false
        start()
    }

    private fun scheduleWake(ms: Long) {
        main.removeCallbacks(restartWake)
        if (wakeEnabled && !destroyed) main.postDelayed(restartWake, ms)
    }

    private fun start() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            busy = true
            if (oneShot) callbacks.onListening(true)
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            finish(null)
        }
    }

    private fun stopRecognizer() {
        if (busy) try { recognizer?.cancel() } catch (_: Exception) {}
        busy = false
    }

    private fun finish(texts: List<String>?) {
        busy = false
        val wasOneShot = oneShot
        oneShot = false
        if (wasOneShot) {
            callbacks.onListening(false)
            if (texts.isNullOrEmpty()) callbacks.onHeardNothing() else callbacks.onHeard(texts)
        } else if (!texts.isNullOrEmpty()) {
            // Wake-word mode: only react when he's addressed by name.
            val rest = texts.firstNotNullOfOrNull { ChatBrain.wakeRest(it) }
            if (rest != null) {
                if (rest.isBlank()) { callbacks.onHeard(listOf("hey voltnutt")); scheduleWake(600); return }
                callbacks.onHeard(listOf(rest))
            }
        }
        scheduleWake(600)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }
            finish(list)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                stopRecognizer()
                if (oneShot) { main.postDelayed({ if (!destroyed) start() }, 400); return }
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) wakeEnabled = false
            finish(null)
        }
    }

    companion object { private const val TAG = "VoiceListener" }
}
