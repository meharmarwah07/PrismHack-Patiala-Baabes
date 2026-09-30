package com.calo.nlu

import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFlowMatcherTest {

    private val pizza = CandidateFlow(
        id = "pizza",
        triggerUtterance = "order a margherita pizza from Domino's",
        description = "Orders a pizza on Domino's",
        slotNames = listOf("item")
    )
    private val flights = CandidateFlow(
        id = "flights",
        triggerUtterance = "search flights to Goa",
        description = "Searches flights on MakeMyTrip",
        slotNames = listOf("destination")
    )

    @Test
    fun exactMatchReturnsMatchedWithFullConfidenceAndNoSlots() {
        val result = OfflineFlowMatcher.match("order a margherita pizza from Domino's", listOf(pizza, flights))
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("pizza", result.matchedFlowId)
        assertEquals(1.0, result.confidence, 0.0)
        assertTrue(result.slotValues.isEmpty())
    }

    @Test
    fun matchIsCaseAndPunctuationInsensitive() {
        val result = OfflineFlowMatcher.match("  ORDER a Margherita   pizza, from dominos!! ", listOf(pizza, flights))
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("pizza", result.matchedFlowId)
    }

    @Test
    fun unrelatedUtteranceIsNoMatch() {
        val result = OfflineFlowMatcher.match("book me a cab to the airport", listOf(pizza, flights))
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedFlowId)
    }

    @Test
    fun tieBetweenSimilarFlowsIsNoMatchRatherThanAGuess() {
        val zomato = CandidateFlow(
            id = "zomato",
            triggerUtterance = "order a margherita pizza from Zomato",
            description = "Orders a pizza on Zomato",
            slotNames = emptyList()
        )
        val domino = pizza.copy(description = "Orders a pizza on Domino's")
        val result = OfflineFlowMatcher.match("order margherita pizza", listOf(domino, zomato))
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedFlowId)
    }

    @Test
    fun clearKeywordOverlapMatchesTheSingleBestFlow() {
        val result = OfflineFlowMatcher.match("please get me a margherita pizza", listOf(pizza, flights))
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("pizza", result.matchedFlowId)
    }

    @Test
    fun blankOrStopwordOnlyUtteranceIsNoMatch() {
        assertEquals(MatchStatus.NO_MATCH, OfflineFlowMatcher.match("   ", listOf(pizza)).status)
        assertEquals(MatchStatus.NO_MATCH, OfflineFlowMatcher.match("please get me a", listOf(pizza)).status)
    }
}
