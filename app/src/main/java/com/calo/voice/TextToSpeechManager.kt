package com.calo.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Thin wrapper around Android's TextToSpeech for the Stuck question (Task 2,
 * Lane B) — same "one call in, no lifecycle callbacks leak out" shape as
 * [VoiceInputManager]. [speak] is a best-effort fire-and-forget: if the
 * engine failed to initialize (missing/misconfigured TTS engine on the
 * device) it silently does nothing rather than crashing the replay flow —
 * the question is still shown via onStatus regardless, so "speak" failing
 * degrades to "show", never to nothing.
 *
 * UNVERIFIED: not exercised on a device/emulator with a TTS engine in this
 * build pass (none available in this environment).
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.getDefault()
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "calo_stuck_question")
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
