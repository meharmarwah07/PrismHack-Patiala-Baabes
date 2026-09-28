package com.calo.domain.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleMatcherTest {

    private fun found(m: RoleMatch): ScreenElement {
        assertTrue("expected Found, got $m", m is RoleMatch.Found)
        return (m as RoleMatch.Found).element
    }

    // A Myntra-like results screen: toolbar, filter chips, product cards
    // (each containing its own wishlist button), a bag icon.
    private val myntraResults = listOf(
        ScreenElement(0, contentDescription = "Navigate up", clickable = true),
        ScreenElement(1, contentDescription = "Search", clickable = true),
        ScreenElement(2, contentDescription = "Bag", clickable = true),
        ScreenElement(3, label = "Sort", clickable = true, inList = true),
        ScreenElement(4, label = "Filter", clickable = true, inList = true),
        ScreenElement(5, label = "Sponsored | Nike Running Shoes | ₹4,999", clickable = true, inList = true),
        ScreenElement(6, label = "boAt Airdopes 141 Wireless Earbuds | ₹1,299", clickable = true, inList = true),
        ScreenElement(7, contentDescription = "Add to wishlist", clickable = true, inList = true),
        ScreenElement(8, label = "Noise Buds VS104 Wireless Earbuds | ₹999", clickable = true, inList = true)
    )

    @Test
    fun `search icon found by content description`() {
        assertEquals(1, found(RoleMatcher.match(SemanticRole.OPEN_SEARCH, myntraResults)).id)
    }

    @Test
    fun `cart role matches Myntra's Bag icon`() {
        assertEquals(2, found(RoleMatcher.match(SemanticRole.GO_TO_CART, myntraResults)).id)
    }

    @Test
    fun `first result is the first list item that mentions the query, skipping chips and ads`() {
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, myntraResults, RoleContext(query = "wireless earbuds"))
        assertEquals(6, found(m).id)
    }

    @Test
    fun `second result honours roleIndex`() {
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, myntraResults, RoleContext(query = "wireless earbuds", index = 2))
        assertEquals(8, found(m).id)
    }

    @Test
    fun `the exact item picked at teach time wins when it's on this screen too`() {
        val m = RoleMatcher.match(
            SemanticRole.SELECT_RESULT, myntraResults,
            RoleContext(query = "wireless earbuds", expectedLabel = "Noise Buds VS104")
        )
        assertEquals(8, found(m).id)
    }

    @Test
    fun `no result mentioning the query is NotFound rather than tapping some random row`() {
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, myntraResults, RoleContext(query = "coffee maker"))
        assertTrue(m is RoleMatch.NotFound)
    }

    @Test
    fun `add to bag found and the button beats the card that contains it`() {
        val product = listOf(
            ScreenElement(0, label = "Noise Buds VS104 | ₹999 | ADD TO BAG | WISHLIST", clickable = true),
            ScreenElement(1, label = "WISHLIST", clickable = true),
            ScreenElement(2, label = "ADD TO BAG", clickable = true)
        )
        assertEquals(2, found(RoleMatcher.match(SemanticRole.ADD_TO_CART, product)).id)
    }

    @Test
    fun `several ADD buttons in a list is ambiguous, not a guess`() {
        val menu = listOf(
            ScreenElement(0, label = "ADD", clickable = true, inList = true),
            ScreenElement(1, label = "ADD", clickable = true, inList = true)
        )
        assertTrue(RoleMatcher.match(SemanticRole.ADD_TO_CART, menu) is RoleMatch.Ambiguous)
    }

    @Test
    fun `search input prefers a field that looks like search`() {
        val screen = listOf(
            ScreenElement(0, hintText = "Pincode", editable = true),
            ScreenElement(1, hintText = "Search for brands and products", editable = true)
        )
        assertEquals(1, found(RoleMatcher.match(SemanticRole.SEARCH_INPUT, screen)).id)
    }

    @Test
    fun `a single text box is the search input even with no search cue`() {
        val screen = listOf(ScreenElement(0, editable = true, focused = true))
        assertEquals(0, found(RoleMatcher.match(SemanticRole.SEARCH_INPUT, screen)).id)
    }

    @Test
    fun `two unlabelled text boxes with neither focused is ambiguous`() {
        val screen = listOf(ScreenElement(0, editable = true), ScreenElement(1, editable = true))
        assertTrue(RoleMatcher.match(SemanticRole.SEARCH_INPUT, screen) is RoleMatch.Ambiguous)
    }

    @Test
    fun `submit picks the suggestion repeating the query`() {
        val screen = listOf(
            ScreenElement(0, hintText = "Search", editable = true, clickable = true, label = "wireless earbuds"),
            ScreenElement(1, label = "wireless earbuds for iphone", clickable = true),
            ScreenElement(2, label = "wireless earbuds", clickable = true)
        )
        assertEquals(2, found(RoleMatcher.match(SemanticRole.SUBMIT_SEARCH, screen, RoleContext(query = "Wireless Earbuds"))).id)
    }

    @Test
    fun `nothing matching a keyword role is NotFound`() {
        assertTrue(RoleMatcher.match(SemanticRole.CHECKOUT, myntraResults) is RoleMatch.NotFound)
    }

    @Test
    fun `pop-up dismiss button found, but never Allow or Cancel`() {
        val popup = listOf(
            ScreenElement(0, label = "Allow", clickable = true),
            ScreenElement(1, label = "Cancel", clickable = true),
            ScreenElement(2, label = "Not now", clickable = true)
        )
        assertEquals(2, PopupRules.findDismissButton(popup)?.id)
        assertNull(PopupRules.findDismissButton(popup.take(2)))
    }

    @Test
    fun `pop-up rules only take whole-label matches`() {
        val screen = listOf(ScreenElement(0, label = "Close account", clickable = true), ScreenElement(1, label = "Skip delivery", clickable = true))
        assertNull(PopupRules.findDismissButton(screen))
    }
}
