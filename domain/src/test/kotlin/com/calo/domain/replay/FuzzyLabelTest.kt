package com.calo.domain.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyLabelTest {

    // Exactly as recorded on-device, 27 Sep 2026.
    private val taughtBurgerKing =
        "Restaurant Name is Burger King 50% OFF select items delivers in 48 minutesSwipe up or down for more actions"

    @Test
    fun `same restaurant with a different delivery time still matches`() {
        val now = "Restaurant Name is Burger King 50% OFF select items delivers in 35 minutesSwipe up or down for more actions"
        assertEquals(1.0, FuzzyLabel.similarity(taughtBurgerKing, now), 0.0001)
    }

    @Test
    fun `a different restaurant with the same offer text does not match`() {
        val kfc = "Restaurant Name is KFC 50% OFF select items delivers in 25 minutesSwipe up or down for more actions"
        assertTrue(FuzzyLabel.similarity(taughtBurgerKing, kfc) < FuzzyLabel.THRESHOLD)
    }

    @Test
    fun `picks the right card from a feed`() {
        val feed = listOf(
            "Restaurant Name is KFC 50% OFF select items delivers in 25 minutesSwipe up or down for more actions",
            "Restaurant Name is Burger King 50% OFF select items delivers in 31 minutesSwipe up or down for more actions",
            "Restaurant Name is Domino's Pizza 40% OFF delivers in 20 minutes"
        )
        assertEquals(1, FuzzyLabel.bestUnique(taughtBurgerKing, feed))
    }

    @Test
    fun `two equally good matches is no match`() {
        val feed = listOf(
            "Restaurant Name is Burger King 50% OFF select items delivers in 31 minutes",
            "Restaurant Name is Burger King 50% OFF select items delivers in 44 minutes"
        )
        assertNull(FuzzyLabel.bestUnique(taughtBurgerKing, feed))
    }

    @Test
    fun `screen-reader hint glued onto a word is stripped`() {
        assertEquals(setOf("beverages"), FuzzyLabel.tokens("BeveragesSwipe up or down for more actions"))
    }

    @Test
    fun `a short label does not fuzzily match a longer different one`() {
        assertNull(FuzzyLabel.bestUnique("Paneer", listOf("Paneer Tikka Masala")))
    }
}
