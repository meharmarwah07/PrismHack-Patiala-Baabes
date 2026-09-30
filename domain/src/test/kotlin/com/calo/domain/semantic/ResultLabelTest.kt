package com.calo.domain.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultLabelTest {

    private val bakeByEcco =
        "Restaurant Name is Bake By Ecco ₹100 OFF above ₹199 delivers in 25 minutesSwipe up or down for more actions"

    @Test
    fun `zomato restaurant card keeps only the stable name`() {
        assertEquals("bake by ecco", ResultLabel.fromContentDescription(bakeByEcco))
    }

    @Test
    fun `volatile offer and delivery time do not change the label`() {
        val later = "Restaurant Name is Bake By Ecco ₹75 OFF above ₹299 delivers in 40 minutesSwipe up or down for more actions"
        assertEquals(ResultLabel.fromContentDescription(bakeByEcco), ResultLabel.fromContentDescription(later))
    }

    @Test
    fun `null blank or too-short descriptions give null`() {
        assertNull(ResultLabel.fromContentDescription(null))
        assertNull(ResultLabel.fromContentDescription("   "))
        assertNull(ResultLabel.fromContentDescription("₹100 OFF"))
        assertNull(ResultLabel.fromContentDescription("ab"))
    }

    // The 30 Sep failure: header "Cake | See all restaurants" mentions the query, the real
    // card has a null label and only a contentDescription.
    private val zomatoCakeResults = listOf(
        ScreenElement(3, label = "Cake | See all restaurants |", clickable = true, inList = true),
        ScreenElement(4, contentDescription = bakeByEcco, clickable = true, inList = true),
        ScreenElement(5, label = "Best in Cake", clickable = true, inList = true)
    )

    @Test
    fun `picks the taught restaurant card by contentDescription, not the query header`() {
        val ctx = RoleContext(
            query = "cake",
            expectedLabel = ResultLabel.fromContentDescription(bakeByEcco)
        )
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, zomatoCakeResults, ctx)
        assertTrue("expected Found, got $m", m is RoleMatch.Found)
        assertEquals(4, (m as RoleMatch.Found).element.id)
    }

    @Test
    fun `without an expected label behavior is unchanged (positional query match)`() {
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, zomatoCakeResults, RoleContext(query = "cake"))
        assertEquals(3, (m as RoleMatch.Found).element.id)
    }

    @Test
    fun `expected label absent from screen falls through to the old path`() {
        val ctx = RoleContext(query = "cake", expectedLabel = "some other place")
        val m = RoleMatcher.match(SemanticRole.SELECT_RESULT, zomatoCakeResults, ctx)
        assertEquals(3, (m as RoleMatch.Found).element.id)
    }
}
