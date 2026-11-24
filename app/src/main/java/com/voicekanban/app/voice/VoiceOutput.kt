package com.voicekanban.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Speaks assistant replies. Uses an ElevenLabs voice (human-sounding) and
 * falls back to the phone's built-in voice when there's no key, no network
 * or the ElevenLabs quota is used up. Publishes a 0..1 [level] while
 * speaking so the voice orb can move with the audio.
 */
class VoiceOutput(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var onDone: (() -> Unit)? = null

    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _lastEngine = MutableStateFlow("")
    /** "ElevenLabs · Jessica" or "Phone voice" - shown in settings. */
    val lastEngine: StateFlow<String> = _lastEngine.asStateFlow()

    private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) { tts.language = Locale.getDefault(); tts.setSpeechRate(1.02f) }
    }.also { t ->
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { finished(id) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { scope.launch { finished(id) } }
        })
    }

    /**
     * Speaks [text] in [languageTag] with the user's ElevenLabs [voiceId]. The ElevenLabs model is
     * chosen per language so Bangla and other languages are pronounced natively. Without a key the
     * phone voice is used only if it actually has that language installed - never an English
     * voice reading another language.
     */
    fun speak(
        id: String, text: String, voiceId: String, voiceName: String, apiKey: String = "", languageTag: String = "",
        then: (() -> Unit)? = null, model: String = "",
    ) {
        stop()
        onDone = then
        _speakingId.value = id
        val clean = text.replace("•", "").replace("“", "\"").replace("”", "\"").replace(Regex("""[*#`]+"""), "").replace(Regex("""\s+"""), " ").trim()
        val key = apiKey.trim()
        val locale = if (languageTag.isNotBlank()) Locale.forLanguageTag(languageTag) else Locale.getDefault()
        // No ElevenLabs key: the phone's own voice, and only if it speaks this language.
        if (key.isBlank()) { speakDevice(id, clean, locale); return }
        if (voiceId.isBlank()) { fail(id, "No ${locale.displayLanguage} ElevenLabs voice chosen yet - pick one in Settings → Voice."); return }
        job = scope.launch {
            _lastEngine.value = "ElevenLabs · $voiceName"
            val result = runCatching { withContext(Dispatchers.IO) { synthesize(clean, voiceId, key, locale.language, model) } }
            if (_speakingId.value != id) return@launch // stopped meanwhile
            result.onSuccess { _lastError.value = null; play(id, it, voiceName, locale) }
                // Never swap in a different (phone) voice: say what went wrong instead.
                .onFailure { fail(id, friendlyError(it.message.orEmpty(), voiceName)) }
        }
    }

    private fun fail(id: String, message: String) {
        Log.w(TAG, message)
        _lastError.value = message
        finished(id)
    }

    private val _lastError = MutableStateFlow<String?>(null)
    /** Last ElevenLabs problem (e.g. paid-only voice, quota used up) - shown to the user. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun clearError() { _lastError.value = null }

    private fun synthesize(text: String, voiceId: String, key: String, iso: String, preferred: String): File {
        val models = (listOf(preferred.ifBlank { modelFor(iso) }) + listOf(MODEL_V4, MODEL_V3)).distinct()
        var last: Exception? = null
        for (model in models) {
            try { return synthesizeWith(text, voiceId, key, iso, model) } catch (e: Exception) {
                last = e
                // Only retry with another model when the model itself was the problem.
                if (!(e.message.orEmpty().contains("model", true))) throw e
            }
        }
        throw last ?: IllegalStateException("ElevenLabs failed")
    }

    private fun synthesizeWith(text: String, voiceId: String, key: String, iso: String, model: String): File {
        val cacheFile = File(app.cacheDir, "tts-${(voiceId + model + text).hashCode()}.mp3")
        if (cacheFile.exists() && cacheFile.length() > 1000) return cacheFile
        val body = buildJsonObject {
            put("text", text.take(if (model == MODEL_V3) 4800 else 2000))
            put("model_id", model)
            // Flash v2.5 accepts a language hint, which stops accents drifting to English.
            if (model == MODEL && iso.isNotBlank()) put("language_code", iso)
            putJsonObject("voice_settings") { put("stability", 0.5); put("similarity_boost", 0.8); put("style", 0.15); put("use_speaker_boost", true) }
        }
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .header("xi-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}: ${res.body.string().take(300)}")
            val tmp = File(app.cacheDir, cacheFile.name + ".part")
            tmp.outputStream().use { out -> res.body.byteStream().copyTo(out) }
            tmp.renameTo(cacheFile)
        }
        return cacheFile
    }

    /** Plays a voice's sample clip (Voice Library preview), so each voice can be heard before choosing it. */
    fun playSample(id: String, url: String) {
        stop()
        _speakingId.value = id
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(url)
                setOnPreparedListener { if (_speakingId.value == id) it.start() }
                setOnCompletionListener { finished(id) }
                setOnErrorListener { _, _, _ -> finished(id); true }
                prepareAsync()
            }
            player = mp
        } catch (e: Exception) { finished(id) }
    }

    private fun play(id: String, file: File, voiceName: String, locale: Locale = Locale.getDefault()) {
        _lastEngine.value = "ElevenLabs · $voiceName"
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                setOnCompletionListener { finished(id) }
                setOnErrorListener { _, _, _ -> finished(id); true }
                prepare()
            }
            player = mp
            attachVisualizer(mp.audioSessionId)
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Playback failed", e)
            speakDevice(id, "", locale)
        }
    }

    /** Real audio level for the orb (needs RECORD_AUDIO, which voice input already asks for). */
    private fun attachVisualizer(session: Int) {
        visualizer = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                        wave ?: return
                        var sum = 0
                        for (b in wave) sum += abs((b.toInt() and 0xFF) - 128)
                        _level.value = (sum.toFloat() / wave.size / 48f).coerceIn(0f, 1f)
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        }.getOrNull()
    }

    private fun speakDevice(id: String, text: String, locale: Locale = Locale.getDefault()) {
        _lastEngine.value = "Phone voice"
        if (!ttsReady || text.isBlank()) { finished(id); return }
        // Never let an English voice read another language: stay silent if the phone lacks it.
        val supported = runCatching { tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        if (!supported) { _lastEngine.value = "No ${locale.displayLanguage} voice"; finished(id); return }
        runCatching { tts.language = locale }
        // Simulated level so the orb still breathes with the phone voice.
        job = scope.launch {
            var t = 0f
            while (_speakingId.value == id) {
                t += 0.35f
                _level.value = (0.35f + 0.3f * kotlin.math.sin(t) * kotlin.math.sin(t * 0.37f)).coerceIn(0f, 1f)
                kotlinx.coroutines.delay(60)
            }
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun finished(id: String?) {
        if (id != null && _speakingId.value != id) return
        releasePlayer()
        _speakingId.value = null
        _level.value = 0f
        val cb = onDone; onDone = null
        cb?.invoke()
    }

    private fun releasePlayer() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }; visualizer = null
        runCatching { player?.stop(); player?.release() }; player = null
    }

    fun stop() {
        onDone = null
        job?.cancel()
        tts.stop()
        releasePlayer()
        _speakingId.value = null
        _level.value = 0f
    }

    fun shutdown() { stop(); tts.shutdown() }

    data class Voice(
        val id: String, val name: String, val description: String,
        /** Set for Voice Library voices that must be added to the account before use. */
        val publicOwnerId: String? = null,
        val language: String = "", val accent: String = "", val previewUrl: String? = null,
        /** False for Voice Library voices that need a paid ElevenLabs plan. */
        val freeAllowed: Boolean = true,
        /** Models this voice is verified for in [language] (e.g. eleven_v4_turbo). */
        val models: List<String> = emptyList(),
    ) {
        fun bestModel(iso: String): String = modelFor(iso, models)
    }

    companion object {
        private const val TAG = "VoiceOutput"
        /** Fast, natural, and half the character cost of the multilingual models. */
        const val MODEL = "eleven_flash_v2_5"
        /** v4 / v3 speak 70+ languages, including Bengali, which Flash v2.5 lacks. Bangla Voice Library voices are verified for v4. */
        const val MODEL_V4_TURBO = "eleven_v4_turbo"
        const val MODEL_V4 = "eleven_v4"
        const val MODEL_V3 = "eleven_v3"
        private val PREFERRED = listOf(MODEL_V4_TURBO, "eleven_flash_v2_5", MODEL_V4, MODEL_V3, "eleven_multilingual_v2", "eleven_turbo_v2_5")
        private val FLASH_LANGS = setOf(
            "en", "ja", "zh", "de", "hi", "fr", "ko", "pt", "it", "es", "id", "nl", "tr", "fil", "pl", "sv", "bg", "ro",
            "ar", "cs", "el", "fi", "hr", "ms", "sk", "da", "ta", "uk", "ru", "hu", "no", "vi",
        )
        /** Best model for a language, preferring one the chosen voice is verified for. */
        fun modelFor(iso: String, verified: List<String> = emptyList()): String =
            PREFERRED.firstOrNull { it in verified } ?: if (iso.isBlank() || iso in FLASH_LANGS) MODEL else MODEL_V4_TURBO

        /** Turns ElevenLabs error bodies into a sentence a user can act on. */
        fun friendlyError(raw: String, voice: String): String = when {
            raw.contains("free_users_not_allowed", true) || raw.contains("not available for free", true) || (raw.contains("free", true) && raw.contains("library", true)) ->
                "$voice needs a paid ElevenLabs plan. Pick a voice marked “Free” in Settings → Voice."
            raw.contains("quota_exceeded", true) || raw.contains("quota", true) -> "Your ElevenLabs character quota is used up for this month."
            raw.contains("voice_not_found", true) || raw.contains("HTTP 404") -> "ElevenLabs can’t find $voice any more - choose another voice in Settings → Voice."
            raw.contains("HTTP 401") -> "ElevenLabs rejected the API key - check it in Settings → Voice."
            raw.contains("Unable to resolve host", true) || raw.contains("timeout", true) -> "Couldn’t reach ElevenLabs - check your internet connection."
            else -> "ElevenLabs couldn’t speak: ${raw.take(140)}"
        }

        /**
         * Native speakers for [iso] from the ElevenLabs Voice Library (e.g. Bangla voices for "bn"),
         * most used first.
         */
        suspend fun listLanguageVoices(apiKey: String, iso: String): List<Voice> = withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            require(key.isNotBlank()) { "No ElevenLabs key" }
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/shared-voices?page_size=40&language=$iso&sort=usage_character_count_1y")
                .header("xi-api-key", key).build()
            http.newCall(req).execute().use { res ->
                val text = res.body.string()
                if (res.code == 401) throw IllegalStateException("ElevenLabs rejected this key - check it and try again.")
                if (!res.isSuccessful) throw IllegalStateException("ElevenLabs HTTP ${res.code}")
                json.parseToJsonElement(text).jsonObject["voices"]!!.jsonArray.mapNotNull { v ->
                    val o = v.jsonObject
                    val lang = o["language"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // (language, model, preview) for each language the voice is verified in.
                    val verified = (o["verified_languages"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { x ->
                        val vo = x as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        Triple(vo["language"]?.jsonPrimitive?.contentOrNull.orEmpty(), vo["model_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), vo["preview_url"]?.jsonPrimitive?.contentOrNull)
                    }.orEmpty()
                    if (lang.isNotBlank() && !lang.equals(iso, true)) return@mapNotNull null
                    Voice(
                        id = o["voice_id"]!!.jsonPrimitive.content,
                        name = o["name"]!!.jsonPrimitive.content.substringBefore(" -").trim(),
                        description = listOfNotNull(
                            o["gender"]?.jsonPrimitive?.contentOrNull, o["age"]?.jsonPrimitive?.contentOrNull?.replace('_', ' '),
                            o["accent"]?.jsonPrimitive?.contentOrNull, o["descriptive"]?.jsonPrimitive?.contentOrNull,
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        publicOwnerId = o["public_owner_id"]?.jsonPrimitive?.contentOrNull,
                        language = lang, accent = o["accent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        previewUrl = verified.firstOrNull { it.first.equals(iso, true) && it.third != null }?.third ?: o["preview_url"]?.jsonPrimitive?.contentOrNull,
                        freeAllowed = o["free_users_allowed"]?.jsonPrimitive?.contentOrNull != "false",
                        models = verified.filter { it.first.equals(iso, true) }.map { it.second },
                    )
                }.sortedByDescending { it.freeAllowed }
            }
        }

        /** Adds a Voice Library voice to the user's account (needed before the API can speak with it); returns its usable id. */
        suspend fun addLibraryVoice(apiKey: String, voice: Voice): String = withContext(Dispatchers.IO) {
            val owner = voice.publicOwnerId ?: return@withContext voice.id
            val body = buildJsonObject { put("new_name", voice.name) }
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/voices/add/$owner/${voice.id}")
                .header("xi-api-key", apiKey.trim())
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body.string()
                if (res.isSuccessful) return@withContext json.parseToJsonElement(text).jsonObject["voice_id"]?.jsonPrimitive?.contentOrNull ?: voice.id
                // Already in the account → the library id works as-is.
                if (text.contains("already", true)) return@withContext voice.id
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["detail"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw IllegalStateException(msg ?: "Couldn’t add ${voice.name} (HTTP ${res.code})")
            }
        }
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        private val json = Json { ignoreUnknownKeys = true }

        /** Voices available to the key (premade + the user's own). */
        suspend fun listVoices(apiKey: String): List<Voice> = withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            require(key.isNotBlank()) { "No ElevenLabs key" }
            val req = Request.Builder().url("https://api.elevenlabs.io/v2/voices?page_size=60").header("xi-api-key", key).build()
            http.newCall(req).execute().use { res ->
                val text = res.body.string()
                if (res.code == 401) throw IllegalStateException("ElevenLabs rejected this key - check it and try again.")
                if (!res.isSuccessful) throw IllegalStateException("ElevenLabs HTTP ${res.code}")
                json.parseToJsonElement(text).jsonObject["voices"]!!.jsonArray.map { v ->
                    val o = v.jsonObject
                    val name = o["name"]!!.jsonPrimitive.content
                    Voice(o["voice_id"]!!.jsonPrimitive.content, name.substringBefore(" -").trim(), name.substringAfter("- ", "").trim())
                }
            }
        }

        /** Characters used / allowed this month, e.g. "5,548 / 10,000". */
        suspend fun quota(apiKey: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isBlank()) return@withContext null
            runCatching {
                val req = Request.Builder().url("https://api.elevenlabs.io/v1/user/subscription").header("xi-api-key", key).build()
                http.newCall(req).execute().use { res ->
                    val o = json.parseToJsonElement(res.body.string()).jsonObject
                    o["character_count"]!!.jsonPrimitive.content.toInt() to o["character_limit"]!!.jsonPrimitive.content.toInt()
                }
            }.getOrNull()
        }
    }
}
