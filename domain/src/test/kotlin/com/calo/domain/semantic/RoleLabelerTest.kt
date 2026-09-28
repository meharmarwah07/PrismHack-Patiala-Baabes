package com.calo.domain.semantic

import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleLabelerTest {

    private fun click(order: Int, text: String? = null, cd: String? = null, resId: String? = null) =
        FlowStep(order, ActionType.CLICK, ElementAnchor(resourceId = resId, text = text, contentDescription = cd))

    private fun type(order: Int, value: String, hint: String? = null, resId: String? = null) =
        FlowStep(order, ActionType.SET_TEXT, ElementAnchor(resourceId = resId, hintText = hint), recordedValue = value)

    private fun scroll(order: Int) = FlowStep(order, ActionType.SCROLL, ElementAnchor(resourceId = "list"))

    @Test
    fun `amazon search-and-add flow becomes a semantic workflow`() {
        val labelled = RoleLabeler.label(
            listOf(
                click(1, text = "Search Amazon.in"),
                type(2, "wireless earbuds", hint = "Search Amazon.in"),
                click(3, text = "wireless earbuds"),
                scroll(4),
                click(5, text = "boAt Airdopes 141 Bluetooth TWS Earbuds"),
                click(6, text = "Add to Cart")
            )
        )
        assertEquals(
            listOf(
                SemanticRole.OPEN_SEARCH, SemanticRole.SEARCH_INPUT, SemanticRole.SUBMIT_SEARCH,
                null, SemanticRole.SELECT_RESULT, SemanticRole.ADD_TO_CART
            ),
            labelled.map { it.role }
        )
        assertEquals(1, labelled[4].roleIndex)
    }

    @Test
    fun `zomato style flow - suggestion, dish, and a bare ADD button`() {
        val labelled = RoleLabeler.label(
            listOf(
                click(1, cd = "Search"),
                type(2, "domino's"),
                click(3, text = "Domino's Pizza"),
                click(4, text = "Margherita"),
                click(5, text = "ADD")
            )
        )
        assertEquals(
            listOf(
                SemanticRole.OPEN_SEARCH, SemanticRole.SEARCH_INPUT, SemanticRole.SUBMIT_SEARCH,
                SemanticRole.SELECT_RESULT, SemanticRole.ADD_TO_CART
            ),
            labelled.map { it.role }
        )
    }

    @Test
    fun `typing into a field right after opening search is the search input even with no search cue`() {
        val labelled = RoleLabeler.label(listOf(click(1, cd = "Search"), type(2, "earbuds")))
        assertEquals(SemanticRole.SEARCH_INPUT, labelled[1].role)
    }

    @Test
    fun `only the first result tap is SELECT_RESULT - a size chip after it stays unlabelled`() {
        val labelled = RoleLabeler.label(
            listOf(
                type(1, "shirt", hint = "Search for brands and products"),
                click(2, text = "Roadster Men Checked Shirt"),
                click(3, text = "M"),
                click(4, text = "ADD TO BAG")
            )
        )
        assertEquals(listOf(SemanticRole.SEARCH_INPUT, SemanticRole.SELECT_RESULT, null, SemanticRole.ADD_TO_CART), labelled.map { it.role })
    }

    @Test
    fun `add to wishlist is not add to cart`() {
        val labelled = RoleLabeler.label(listOf(click(1, text = "Add to Wishlist")))
        assertNull(labelled[0].role)
    }

    @Test
    fun `a long product title containing bag is not the cart button`() {
        val labelled = RoleLabeler.label(listOf(click(1, text = "Leather laptop bag for men 15 inch")))
        assertNull(labelled[0].role)
    }

    @Test
    fun `cart icon found by content description or resource id`() {
        val labelled = RoleLabeler.label(listOf(click(1, cd = "Cart"), click(2, resId = "com.myntra.android:id/bag_icon")))
        assertEquals(listOf(SemanticRole.GO_TO_CART, SemanticRole.GO_TO_CART), labelled.map { it.role })
    }

    @Test
    fun `a non-shopping flow gets no roles at all`() {
        val labelled = RoleLabeler.label(listOf(click(1, text = "Battery"), click(2, text = "Battery Saver")))
        assertTrue(labelled.all { it.role == null })
    }

    @Test
    fun `labelling is idempotent and keeps existing roles`() {
        val once = RoleLabeler.label(listOf(click(1, text = "Search"), type(2, "x"), click(3, text = "Buy Now")))
        assertEquals(once, RoleLabeler.label(once))
        val preset = listOf(click(1, text = "Something").copy(role = SemanticRole.CHECKOUT))
        assertEquals(SemanticRole.CHECKOUT, RoleLabeler.label(preset)[0].role)
    }

    @Test
    fun `labelling never changes anything except role fields`() {
        val steps = listOf(type(1, "earbuds", hint = "Search").copy(slotName = "query"), click(2, text = "Add to Cart"))
        val labelled = RoleLabeler.label(steps)
        assertEquals(steps.map { it.copy(role = null, roleIndex = null) }, labelled.map { it.copy(role = null, roleIndex = null) })
    }

    @Test
    fun `a collapsible menu heading tapped after searching is not SELECT_RESULT`() {
        val labelled = RoleLabeler.label(
            listOf(
                type(1, "Burger King", hint = "Search"),
                click(2, text = "Burger King"),
                click(3, cd = "Mix'N'Match- Duos Your Way double tap to expand"),
                click(4, cd = "Crispy Veg Burger")
            )
        )
        assertEquals(listOf(SemanticRole.SEARCH_INPUT, SemanticRole.SUBMIT_SEARCH, null, SemanticRole.SELECT_RESULT), labelled.map { it.role })
    }
}
