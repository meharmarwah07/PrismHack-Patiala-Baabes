package com.calo.domain.replay

/** What the caller should do next after a Stuck question was asked (Task 2, Lane B). */
sealed class StuckAction {
    /** Ask the same question again — the one allowed repeat after an unrecognized/failed answer. */
    data object Repeat : StuckAction()

    /** Abort the replay cleanly. Never auto-taps anything — same guarantee ReplayPlanner already enforces. */
    data object Stop : StuckAction()

    /** Retry the stuck step using [newValue] as the text/label to match instead of what was taught. */
    data class Retry(val newValue: String) : StuckAction()
}

/**
 * Pure decision logic for "what happens after the user answers the Stuck
 * question" — no SpeechRecognizer/TextToSpeech here, so this is fully
 * unit-testable (the Android voice I/O is a thin adapter in :app's
 * CaloOrchestrator, same split as NodeProvider/ReplayEngine elsewhere in
 * this project).
 *
 * [attempt] is 1 for the first time this question was asked, 2 for the one
 * allowed repeat — the caller tracks and passes this in rather than this
 * object holding state itself, keeping it a pure function like every other
 * domain decision here.
 */
object StuckAnswerHandler {

    const val MAX_ATTEMPTS = 2

    sealed class VoiceOutcome {
        data class Recognized(val utterance: String) : VoiceOutcome()
        data object Failed : VoiceOutcome()
    }

    // Matched case-insensitively against the whole (trimmed) utterance, not
    // a substring — "I'd like to stop by the store" must not abort a flow.
    private val STOP_WORDS = setOf("stop", "cancel", "abort", "never mind", "nevermind")

    fun handle(outcome: VoiceOutcome, attempt: Int): StuckAction = when (outcome) {
        is VoiceOutcome.Recognized -> {
            val trimmed = outcome.utterance.trim()
            when {
                trimmed.isEmpty() -> repeatOrStop(attempt)
                trimmed.lowercase() in STOP_WORDS -> StuckAction.Stop
                else -> StuckAction.Retry(trimmed)
            }
        }
        is VoiceOutcome.Failed -> repeatOrStop(attempt)
    }

    private fun repeatOrStop(attempt: Int): StuckAction =
        if (attempt < MAX_ATTEMPTS) StuckAction.Repeat else StuckAction.Stop
}
