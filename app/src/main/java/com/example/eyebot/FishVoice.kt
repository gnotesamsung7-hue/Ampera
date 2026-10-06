package com.example.eyebot

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Ampera's natural voice through the Fish Audio text-to-speech API (online, pay-as-you-go).
 *
 *  POST https://api.fish.audio/v1/tts   (Authorization: Bearer <key>, header "model")
 *  body: { text, reference_id (voice model id), format: "mp3", mp3_bitrate, latency, prosody }
 *
 * Every rendered line is cached on the phone (keyed by voice + text), so repeated lines are free
 * and instant. If there's no key, no internet, or the API fails, [say] calls the fallback so the
 * phone's own voice speaks instead.
 */
class FishVoice(context: Context) {

    var apiKey: String = ""
    var voiceId: String = ""
    var model: String = DEFAULT_MODEL
    var enabled = false

    val isActive get() = enabled && apiKey.isNotBlank()

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val dir = File(app.cacheDir, "fish").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var generation = 0

    /** Speak [text]; on any failure run [fallback] on the main thread. Main thread. */
    fun say(text: String, fallback: () -> Unit) {
        if (text.isBlank()) return
        if (!isActive) { fallback(); return }
        val gen = ++generation
        stop(keepGeneration = true)
        val file = cacheFile(text)
        if (file.exists() && file.length() > 0) { play(file, gen, fallback); return }
        val key = apiKey; val voice = voiceId; val mdl = model
        net.execute {
            val ok = try { render(text, key, voice, mdl, file) } catch (e: Exception) { Log.w(TAG, "Fish TTS failed", e); false }
            main.post {
                if (gen != generation) return@post          // a newer line replaced this one
                if (ok) play(file, gen, fallback) else fallback()
            }
        }
    }

    fun stop(keepGeneration: Boolean = false) {
        if (!keepGeneration) generation++
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    fun shutdown() { stop(); net.shutdownNow() }

    /** Search Fish Audio's public voice library. Results (title, id) arrive on the main thread. */
    fun searchVoices(query: String, done: (List<Pair<String, String>>?) -> Unit) {
        val key = apiKey
        net.execute {
            val result = try {
                val q = URLEncoder.encode(query, "UTF-8")
                val c = URL("$BASE/model?title=$q&page_size=15&sort_by=score").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val items: JSONArray = JSONObject(body).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { i ->
                    val o = items.getJSONObject(i)
                    val langs = o.optJSONArray("languages")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } } ?: ""
                    "${o.optString("title")}${if (langs.isNotEmpty()) "  ($langs)" else ""}" to o.optString("_id")
                }.filter { it.second.isNotBlank() }
            } catch (e: Exception) { Log.w(TAG, "Voice search failed", e); null }
            main.post { done(result) }
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun render(text: String, key: String, voice: String, mdl: String, out: File): Boolean {
        val body = JSONObject().apply {
            put("text", text)
            if (voice.isNotBlank()) put("reference_id", voice)
            put("format", "mp3")
            put("mp3_bitrate", 64)
            put("latency", "balanced")
            put("prosody", JSONObject().put("speed", 1.05).put("volume", 0))
        }.toString().toByteArray()
        val c = URL("$BASE/v1/tts").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        if (mdl.isNotBlank()) c.setRequestProperty("model", mdl)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            Log.w(TAG, "Fish TTS HTTP $code: ${err?.take(200)}")
            c.disconnect()
            return false
        }
        val tmp = File(out.path + ".part")
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        c.disconnect()
        if (tmp.length() < 200) { tmp.delete(); return false }
        trimCache()
        return tmp.renameTo(out)
    }

    private fun play(f: File, gen: Int, fallback: () -> Unit) {
        if (gen != generation) return
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
            Log.w(TAG, "Fish playback failed", e)
            f.delete()
            player = null
            fallback()
        }
    }

    private fun cacheFile(text: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest("$model|$voiceId|$text".toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".mp3")
    }

    /** Keep the cache under ~40 MB (oldest files go first). */
    private fun trimCache() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < MAX_CACHE_BYTES) break; total -= f.length(); f.delete() }
    }

    companion object {
        private const val TAG = "FishVoice"
        private const val BASE = "https://api.fish.audio"
        const val DEFAULT_MODEL = "s2.1-pro"
        private const val MAX_CACHE_BYTES = 40L * 1024 * 1024
    }
}
