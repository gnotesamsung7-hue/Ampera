package com.example.eyebot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** How EyeBot talks (Settings ▸ Voice). */
enum class VoiceMode(val label: String) {
    BEEPS_BUBBLE("Beeps + speech bubble"),
    BEEPS("Beeps only"),
    BEEPS_THEN_WORDS("Beeps, then words"),
    WORDS("Words (robot voice)"),
}

/**
 * EyeBot's mouth. Ordinary chatter comes out as robot-language beeps ([BeepSpeech]); important
 * safety messages (drowsiness, back-seat reminder, heat) always use clear spoken words.
 */
class VoiceBox(
    private val words: RobotVoice,
    /** Show (text) or hide (null) the speech bubble. Main thread. */
    private val onBubble: (String?) -> Unit,
) {
    var enabled = true
    var mode = VoiceMode.BEEPS_BUBBLE
    var tone = VoiceTone()
    /** False while driving: no text to read on the dashboard. */
    var bubblesAllowed = true
    /** True when the online voice is used (adds network time before she starts talking). */
    var cloudVoice = false

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var track: AudioTrack? = null
    private val hideBubble = Runnable { onBubble(null) }
    private var generation = 0

    /**
     * @param text may contain Ampera status tics like "[Servo click]": they show in the bubble
     * but are never spoken or beeped.
     */
    fun say(text: String, important: Boolean = false) {
        if (text.isBlank()) return
        val spoken = AmperaPersona.spoken(text)
        val extra = (text.length * 45L).coerceAtMost(3500L)     // longer lines stay up longer
        if (important) {                       // safety first: always words, even when muted beeps
            if (spoken.isNotBlank()) words.say(spoken)
            bubble(text, 4000 + extra, force = true)
            return
        }
        if (!enabled) return
        when (mode) {
            VoiceMode.WORDS -> { if (spoken.isNotBlank()) words.say(spoken); bubble(text, 2500 + extra) }
            VoiceMode.BEEPS -> beep(spoken, thenWords = false)
            VoiceMode.BEEPS_BUBBLE -> { beep(spoken, thenWords = false); bubble(text, 2200 + extra) }
            VoiceMode.BEEPS_THEN_WORDS -> { beep(spoken, thenWords = true); bubble(text, 3000 + extra) }
        }
    }

    /**
     * A conversational reply: a quick chirp, then clear words, so jokes and riddles can be
     * understood. "Beeps only" mode stays beeps; with voice off only the bubble shows.
     */
    /** Ampera: a tiny "thinking" pause before she answers, like a real person listening. */
    var thinkPauseMs = 0L

    fun chat(text: String) {
        if (text.isBlank()) return
        if (thinkPauseMs > 0) { main.postDelayed({ chatNow(text) }, thinkPauseMs); return }
        chatNow(text)
    }

    private fun chatNow(text: String) {
        val spoken = AmperaPersona.spoken(text)
        val extra = (text.length * 45L).coerceAtMost(4500L)
        if (!enabled) { bubble(text, 3000 + extra, force = true); return }
        when (mode) {
            VoiceMode.BEEPS -> { beep(spoken, thenWords = false); bubble(text, 2200 + extra, force = true) }
            VoiceMode.WORDS -> { if (spoken.isNotBlank()) words.say(spoken); bubble(text, 2500 + extra) }
            else -> { beep(spoken.take(14), thenWords = false, wordsAfter = spoken); bubble(text, 3000 + extra) }
        }
    }

    /** Rough time until Ampera has finished saying [text] in a chat reply (for reopening the mic). */
    fun estimateChatMs(text: String): Long {
        val n = AmperaPersona.spoken(text).length
        if (!enabled) return 600L + thinkPauseMs
        return when (mode) {
            VoiceMode.BEEPS -> n * 50L + 400
            VoiceMode.WORDS -> n * 75L + 700
            else -> 14 * 50L + n * 75L + 900
        } + thinkPauseMs + (if (cloudVoice) CLOUD_LATENCY_MS else 0L)
    }

    /** Play a raw clip (e.g. a person's signature melody). */
    fun play(clip: FloatArray) {
        if (!enabled || worker.isShutdown) return
        val gen = ++generation
        worker.execute { playNow(clip, gen) }
    }

    private fun beep(text: String, thenWords: Boolean, wordsAfter: String? = null) {
        if (worker.isShutdown || text.isBlank()) return
        val gen = ++generation
        val t = tone
        val follow = wordsAfter ?: if (thenWords) text else null
        worker.execute {
            val clip = BeepSpeech.render(text, t)
            val ms = playNow(clip, gen)
            if (follow != null && gen == generation) main.postDelayed({ if (gen == generation) words.say(follow) }, ms + 150L)
        }
    }

    /** Worker thread. Returns the clip length in ms. */
    private fun playNow(clip: FloatArray, gen: Int): Long {
        if (gen != generation) return 0
        val pcm = ShortArray(clip.size) { (clip[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val ms = clip.size * 1000L / ToneSynth.SR
        try {
            stopTrack()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ToneSynth.SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            synchronized(this) { track = t }
            main.postDelayed({ synchronized(this) { if (track === t) { track = null; t.release() } } }, ms + 300L)
        } catch (e: Exception) {
            Log.w(TAG, "Beep playback failed", e)
        }
        return ms
    }

    private fun stopTrack() {
        synchronized(this) {
            track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            track = null
        }
    }

    private fun bubble(text: String, ms: Long, force: Boolean = false) {
        main.post {
            main.removeCallbacks(hideBubble)
            if (!bubblesAllowed || (mode == VoiceMode.BEEPS && !force)) { onBubble(null); return@post }
            onBubble(text)
            main.postDelayed(hideBubble, ms)
        }
    }

    fun shutdown() {
        generation++
        worker.shutdownNow()
        stopTrack()
    }

    companion object {
        private const val TAG = "VoiceBox"
        private const val CLOUD_LATENCY_MS = 1200L
    }
}
