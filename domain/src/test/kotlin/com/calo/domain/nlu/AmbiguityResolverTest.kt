package com.calo.domain.nlu

import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.slots.SlotResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbiguityResolverTest {

    @Test
    fun `resolving picks the chosen flow and marks MATCHED`() {
        val ambiguous = MatchResult(
            matchedFlowId = null,
            confidence = 0.5,
            status = MatchStatus.AMBIGUOUS,
            alternatives = listOf(Alternative("dominos-order", 0.5), Alternative("pizzahut-order", 0.5))
        )
        val resolved = AmbiguityResolver.resolve(ambiguous, "pizzahut-order")
        assertEquals("pizzahut-order", resolved.matchedFlowId)
        assertEquals(MatchStatus.MATCHED, resolved.status)
    }

    // The regression this whole file exists to guard against: NluMatchEvaluator used to
    // null out slotValues on AMBIGUOUS, so an utterance like "same as before but paneer"
    // would resolve to the right flow but replay with the taught default item instead of
    // "paneer" -- silently ignoring what the user actually said.
    @Test
    fun `resolving carries forward a slot value extracted from the original ambiguous utterance`() {
        val ambiguous = MatchResult(
            matchedFlowId = null,
            confidence = 0.5,
            slotValues = mapOf("item" to "paneer"),
            status = MatchStatus.AMBIGUOUS,
            alternatives = listOf(Alternative("dominos-order", 0.5), Alternative("pizzahut-order", 0.5))
        )
        val resolved = AmbiguityResolver.resolve(ambiguous, "dominos-order")
        assertEquals(mapOf("item" to "paneer"), resolved.slotValues)
    }

    // End-to-end (still no Android): a genuinely ambiguous, slot-bearing utterance goes
    // through the real parser + evaluator + resolver + SlotResolver (the actual function
    // ReplayEngine calls per SET_TEXT step) and the value that reaches the text field is
    // what the user said, not the taught default.
    @Test
    fun `full chain -- an ambiguous utterance's slot value reaches SlotResolver's output, not the taught default`() {
        val rawModelResponse = """
            {"matchedFlowId": "dominos-order", "slotValues": {"item": "paneer"}, "confidence": 0.55,
             "alternatives": [{"flowId": "pizzahut-order", "confidence": 0.45}]}
        """.trimIndent()

        val parsed = NluResponseParser.parse(rawModelResponse)
        val evaluated = NluMatchEvaluator.evaluate(parsed)
        assertEquals(MatchStatus.AMBIGUOUS, evaluated.status)
        assertEquals(mapOf("item" to "paneer"), evaluated.slotValues) // not discarded

        // User answers "the Pizza Hut one" -- orchestrator resolves to that flow id.
        val resolved = AmbiguityResolver.resolve(evaluated, "pizzahut-order")
        assertEquals(MatchStatus.MATCHED, resolved.status)

        val itemStep = FlowStep(
            order = 0,
            action = ActionType.SET_TEXT,
            target = ElementAnchor(resourceId = "com.pizzahut.app:id/item_field"),
            recordedValue = "Pepperoni", // the taught default for pizzahut-order
            slotName = "item"
        )
        val valueForReplay = SlotResolver.resolveValue(itemStep, resolved.slotValues)

        assertEquals("paneer", valueForReplay)
        assertTrue(valueForReplay != itemStep.recordedValue)
    }
}
