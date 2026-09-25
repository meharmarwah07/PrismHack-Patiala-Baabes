package com.calo.domain.replay

import com.calo.domain.replay.StuckAnswerHandler.VoiceOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class StuckAnswerHandlerTest {

    // ---- stop path ------------------------------------------------------

    @Test
    fun `stop aborts cleanly on the first attempt`() {
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Recognized("stop"), attempt = 1))
    }

    @Test
    fun `stop is case-insensitive and trimmed`() {
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Recognized("  STOP  "), attempt = 1))
    }

    @Test
    fun `stop synonyms also abort`() {
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Recognized("cancel"), attempt = 1))
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Recognized("never mind"), attempt = 1))
    }

    @Test
    fun `a word that merely contains stop is not treated as the stop command`() {
        val result = StuckAnswerHandler.handle(VoiceOutcome.Recognized("I'd like to stop by the store"), attempt = 1)
        assertEquals(StuckAction.Retry("I'd like to stop by the store"), result)
    }

    // ---- retry-with-new-value path ---------------------------------------

    @Test
    fun `a recognized non-stop answer retries with that value`() {
        assertEquals(
            StuckAction.Retry("Farmhouse"),
            StuckAnswerHandler.handle(VoiceOutcome.Recognized("Farmhouse"), attempt = 1)
        )
    }

    @Test
    fun `retry value is trimmed`() {
        assertEquals(
            StuckAction.Retry("Farmhouse"),
            StuckAnswerHandler.handle(VoiceOutcome.Recognized("  Farmhouse  "), attempt = 1)
        )
    }

    // ---- repeat-once-then-stop path ---------------------------------------

    @Test
    fun `voice recognition failure repeats the question on the first attempt`() {
        assertEquals(StuckAction.Repeat, StuckAnswerHandler.handle(VoiceOutcome.Failed, attempt = 1))
    }

    @Test
    fun `voice recognition failure stops after the one allowed repeat`() {
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Failed, attempt = 2))
    }

    @Test
    fun `an empty recognized utterance is treated the same as a failure`() {
        assertEquals(StuckAction.Repeat, StuckAnswerHandler.handle(VoiceOutcome.Recognized("   "), attempt = 1))
        assertEquals(StuckAction.Stop, StuckAnswerHandler.handle(VoiceOutcome.Recognized("   "), attempt = 2))
    }
}
