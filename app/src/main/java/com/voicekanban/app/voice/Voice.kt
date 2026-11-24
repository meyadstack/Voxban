package com.voicekanban.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Speech-to-text with live partial results and mic level. Must be used on the main thread. */
class SpeechController(private val context: Context) {
    data class State(val listening: Boolean = false, val partial: String = "", val level: Float = 0f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String = Locale.getDefault().toLanguageTag(), onResult: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        if (!available) { onError("Speech recognition isn’t available on this phone. Install or enable Google’s speech services."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = State(listening = true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    _state.value = _state.value.copy(partial = it)
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish()
                if (!delivered && text.isNotBlank()) { delivered = true; onResult(text) }
            }
            override fun onError(error: Int) {
                val partial = _state.value.partial
                finish()
                if (!delivered && partial.isNotBlank()) { delivered = true; onResult(partial); return }
                val msg = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed - allow it in the app’s settings."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn’t catch that - tap the mic and try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone."
                    SpeechRecognizer.ERROR_AUDIO -> "Couldn’t access the microphone."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy - try again in a moment."
                    SpeechRecognizer.ERROR_CLIENT -> null // cancelled
                    else -> "Voice error ($error)."
                }
                msg?.let(onError)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val tag = languageTag.ifBlank { Locale.getDefault().toLanguageTag() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        _state.value = State(listening = true)
        r.startListening(intent)
    }

    private fun finish() {
        _state.value = State()
        recognizer?.destroy(); recognizer = null
    }

    /** Stop listening; whatever was heard so far is delivered as the result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() { recognizer?.cancel(); finish() }
}
