package com.calo.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around the platform SpeechRecognizer for the voice trigger.
 * One call in, one callback out — CaloOrchestrator doesn't need to know
 * anything about RecognitionListener's dozen lifecycle callbacks.
 *
 * UNVERIFIED: not exercised on a device/emulator with a microphone in this
 * build pass (none available in this environment). The RECORD_AUDIO
 * runtime permission request itself is also NOT handled here — that's an
 * Activity/UI concern (permission dialogs) deliberately out of scope for
 * this logic-only pass; wire ActivityCompat.requestPermissions before
 * calling startListening from real UI code.
 */
class VoiceInputManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun startListening(onResult: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFailure("Speech recognition is not available on this device")
            return
        }

        destroy() // in case a previous session's recognizer is still around

        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = newRecognizer

        newRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text != null) onResult(text) else onFailure("no speech recognized")
            }

            override fun onError(error: Int) {
                onFailure("speech recognizer error code $error")
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        newRecognizer.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
