package com.example.eyebot

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Short robot phrases via Android's built-in Text-to-Speech.
 *
 *  - Pitch is raised with [TextToSpeech.setPitch] ([PITCH]) for a small-robot voice.
 *  - With [robotFx] on, each phrase is rendered to a WAV with synthesizeToFile, passed through
 *    [RobotFx] (ring-mod + tin-can echo) and played with MediaPlayer. Rendered phrases are
 *    cached, so repeats start instantly. If anything fails it falls back to plain speech.
 */
class RobotVoice(context: Context) : TextToSpeech.OnInitListener {

    var enabled = true
    var robotFx = true
    /** Ampera: optional natural online voice (Fish Audio). Falls back to the phone's voice. */
    var cloud: FishVoice? = null

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(app.cacheDir, "voice").apply { mkdirs() }
    private val tts = TextToSpeech(app, this)
    @Volatile private var ready = false

    private val pendingRaw = ConcurrentHashMap<String, Pair<String, File>>()   // utteranceId -> (text, raw wav)
    private val fxCache = HashMap<String, File>()                             // text -> processed wav (main thread)
    private var player: MediaPlayer? = null
    private var counter = 0
    private var lastText = ""
    private var lastAt = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "TTS init failed: $status"); return }
        val res = tts.setLanguage(Locale.US)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onRendered(utteranceId ?: return) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onRenderFailed(utteranceId ?: return) }
            override fun onError(utteranceId: String?, errorCode: Int) { onRenderFailed(utteranceId ?: return) }
            // Interrupted by a newer phrase (tts.stop()): just forget it.
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pendingRaw.remove(it) } }
        })
        ready = true
    }

    /** Say a short phrase. Newer phrases interrupt older ones; identical repeats within 3 s are dropped. */
    fun say(text: String) {
        if (!enabled || text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < 3000) return
        lastText = text; lastAt = now

        stopPlayback()
        cloud?.takeIf { it.isActive }?.let { c -> c.say(text) { sayLocal(text) }; return }
        sayLocal(text)
    }

    /** The phone's own TTS (with the robot effect if enabled). */
    private fun sayLocal(text: String) {
        if (!ready) return
        if (!robotFx) { speakPlain(text); return }

        fxCache[text]?.takeIf { it.exists() }?.let { playFile(it, text); return }

        val id = "fx${counter++}"
        val raw = File(dir, "raw_${text.hashCode().toUInt()}.wav")
        pendingRaw[id] = text to raw
        val r = tts.synthesizeToFile(text, Bundle(), raw, id)
        if (r != TextToSpeech.SUCCESS) { pendingRaw.remove(id); speakPlain(text) }
    }

    /** Called on a TTS binder thread once synthesizeToFile has finished. */
    private fun onRendered(id: String) {
        val (text, raw) = pendingRaw.remove(id) ?: return   // plain utterances end here too
        val processed = try { RobotFx.process(raw.readBytes()) } catch (e: Exception) { null }
        if (processed == null) { main.post { speakPlain(text) }; return }
        val out = File(dir, "fx_${text.hashCode().toUInt()}.wav")
        try { out.writeBytes(processed) } catch (e: Exception) { main.post { speakPlain(text) }; return }
        raw.delete()
        main.post {
            fxCache[text] = out
            if (text == lastText) playFile(out, text)   // skip if a newer phrase replaced it
        }
    }

    private fun onRenderFailed(id: String) {
        val (text, _) = pendingRaw.remove(id) ?: return
        main.post { speakPlain(text) }
    }

    private fun playFile(f: File, text: String) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(f.path)
                setOnCompletionListener { mp -> mp.release(); if (player === mp) player = null }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Robot voice playback failed", e)
            stopPlayback()
            speakPlain(text)
        }
    }

    private fun speakPlain(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "p${counter++}")
    }

    private fun stopPlayback() {
        cloud?.stop()
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        if (ready && tts.isSpeaking) tts.stop()
    }

    fun shutdown() {
        stopPlayback()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "RobotVoice"
        /** 1.0 = normal. Higher = smaller robot. */
        const val PITCH = 1.25f   // Ampera: a bright, natural teen voice (no chipmunk)
        const val RATE = 1.08f
    }
}
