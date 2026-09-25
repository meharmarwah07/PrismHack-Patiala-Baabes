package com.calo.domain.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NluMatchEvaluatorTest {

    @Test
    fun `a confident single match with no alternatives is MATCHED`() {
        val raw = MatchResult(matchedFlowId = "flow-1", confidence = 0.95, slotValues = mapOf("item" to "Pepperoni"))
        val result = NluMatchEvaluator.evaluate(raw)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("flow-1", result.matchedFlowId)
        assertEquals(mapOf("item" to "Pepperoni"), result.slotValues)
    }

    @Test
    fun `explicit no-match from the model stays NO_MATCH`() {
        val raw = MatchResult(matchedFlowId = null, confidence = 0.0)
        val result = NluMatchEvaluator.evaluate(raw)
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedFlowId)
    }

    // T12: don't fall through to the best low-confidence candidate
    @Test
    fun `a low-confidence best guess is rejected as NO_MATCH, not replayed`() {
        val raw = MatchResult(matchedFlowId = "flow-1", confidence = 0.5)
        val result = NluMatchEvaluator.evaluate(raw)
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedFlowId)
    }

    @Test
    fun `confidence just below the match threshold is rejected`() {
        val raw = MatchResult(matchedFlowId = "flow-1", confidence = NluMatchEvaluator.MATCH_THRESHOLD - 0.01)
        assertEquals(MatchStatus.NO_MATCH, NluMatchEvaluator.evaluate(raw).status)
    }

    @Test
    fun `confidence exactly at the match threshold is accepted`() {
        val raw = MatchResult(matchedFlowId = "flow-1", confidence = NluMatchEvaluator.MATCH_THRESHOLD)
        assertEquals(MatchStatus.MATCHED, NluMatchEvaluator.evaluate(raw).status)
    }

    // T13: two candidates too close to call
    @Test
    fun `a tied top-two (the observed live pattern for a genuinely ambiguous command) is AMBIGUOUS`() {
        val raw = MatchResult(
            matchedFlowId = "dominos-order",
            confidence = 0.5,
            alternatives = listOf(Alternative("pizzahut-order", 0.5))
        )
        val result = NluMatchEvaluator.evaluate(raw)
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertNull(result.matchedFlowId)
        assertEquals(
            listOf(Alternative("dominos-order", 0.5), Alternative("pizzahut-order", 0.5)),
            result.alternatives
        )
    }

    // Regression (fixed 2026-09-26): slotValues used to be cleared here, so a resolved
    // ambiguous command ("same as before but paneer") silently replayed the taught
    // default instead of what the user actually said. See AmbiguityResolverTest for the
    // full chain through to SlotResolver.
    @Test
    fun `AMBIGUOUS preserves slotValues -- they were extracted from the utterance, not the losing candidate`() {
        val raw = MatchResult(
            matchedFlowId = "dominos-order",
            confidence = 0.5,
            slotValues = mapOf("item" to "paneer"),
            alternatives = listOf(Alternative("pizzahut-order", 0.5))
        )
        assertEquals(mapOf("item" to "paneer"), NluMatchEvaluator.evaluate(raw).slotValues)
    }

    @Test
    fun `AMBIGUOUS with no slots in the utterance still has empty slotValues, not a crash`() {
        val raw = MatchResult(
            matchedFlowId = "dominos-order",
            confidence = 0.5,
            alternatives = listOf(Alternative("pizzahut-order", 0.5))
        )
        assertTrue(NluMatchEvaluator.evaluate(raw).slotValues.isEmpty())
    }

    @Test
    fun `a confident match with only a negligible alternative is MATCHED, not AMBIGUOUS`() {
        // e.g. live: "order a Margherita" -> 0.95 dominos, 0.1 pizzahut. 0.1 is below the
        // ambiguity floor -- it's a barely-considered alternative, not a real tie.
        val raw = MatchResult(
            matchedFlowId = "dominos-order",
            confidence = 0.95,
            alternatives = listOf(Alternative("pizzahut-order", 0.1))
        )
        assertEquals(MatchStatus.MATCHED, NluMatchEvaluator.evaluate(raw).status)
    }

    @Test
    fun `two low-confidence long-shots close together are NOT ambiguous -- neither is plausible`() {
        val raw = MatchResult(
            matchedFlowId = "flow-1",
            confidence = 0.15,
            alternatives = listOf(Alternative("flow-2", 0.12))
        )
        assertEquals(MatchStatus.NO_MATCH, NluMatchEvaluator.evaluate(raw).status)
    }

    @Test
    fun `ambiguity is checked before the match threshold -- a tie below MATCH_THRESHOLD is still AMBIGUOUS, not NO_MATCH`() {
        val raw = MatchResult(
            matchedFlowId = "flow-1",
            confidence = 0.5,
            alternatives = listOf(Alternative("flow-2", 0.5))
        )
        assertEquals(MatchStatus.AMBIGUOUS, NluMatchEvaluator.evaluate(raw).status)
    }
}
