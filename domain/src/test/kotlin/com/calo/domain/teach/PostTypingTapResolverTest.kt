package com.calo.domain.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTypingTapResolverTest {

    @Test
    fun `exactly one pre-tap candidate appears on the new screen - matched`() {
        val newScreenTexts = setOf("Domino's Pizza", "4.6", "1 km", "Menu")
        val preTapCandidates = listOf("Burger King", "Domino's Pizza", "KFC")

        val outcome = PostTypingTapResolver.resolve(newScreenTexts, preTapCandidates)

        assertEquals(PostTypingTapResolver.Outcome.Matched("Domino's Pizza"), outcome)
    }

    @Test
    fun `no pre-tap candidate appears on the new screen - fails closed as NoMatch`() {
        val newScreenTexts = setOf("Some Unrelated Screen", "Back")
        val preTapCandidates = listOf("Burger King", "Domino's Pizza", "KFC")

        val outcome = PostTypingTapResolver.resolve(newScreenTexts, preTapCandidates)

        assertEquals(PostTypingTapResolver.Outcome.NoMatch, outcome)
    }

    @Test
    fun `two pre-tap nodes share the tapped text - fails closed as Ambiguous, does not guess`() {
        // Two different branches of the same restaurant both showed
        // "Domino's Pizza" in the pre-tap results list — this must NOT
        // silently resolve to either one.
        val newScreenTexts = setOf("Domino's Pizza", "Closed right now")
        val preTapCandidates = listOf("Domino's Pizza", "Burger King", "Domino's Pizza")

        val outcome = PostTypingTapResolver.resolve(newScreenTexts, preTapCandidates)

        assertTrue(outcome is PostTypingTapResolver.Outcome.Ambiguous)
        assertEquals(listOf("Domino's Pizza", "Domino's Pizza"), (outcome as PostTypingTapResolver.Outcome.Ambiguous).matchedTexts)
    }
}
