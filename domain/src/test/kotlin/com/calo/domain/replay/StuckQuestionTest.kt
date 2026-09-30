package com.calo.domain.replay

import com.calo.domain.model.ElementAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class StuckQuestionTest {

    @Test
    fun `uses text when present`() {
        val anchor = ElementAnchor(resourceId = "id/search_result", text = "Margherita")
        assertEquals(
            "I couldn't find 'Margherita' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 2)
        )
    }

    @Test
    fun `falls back to contentDescription when text is absent`() {
        val anchor = ElementAnchor(contentDescription = "Farmhouse pizza, 12 inch")
        assertEquals(
            "I couldn't find 'Farmhouse pizza, 12 inch' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 3)
        )
    }

    @Test
    fun `verbose zomato card description is cut down to the restaurant name`() {
        val anchor = ElementAnchor(
            contentDescription = "Restaurant Name is Bake By Ecco ₹100 OFF above ₹199 delivers in 24 minutesSwipe up or down for more actions"
        )
        assertEquals(
            "I couldn't find 'Bake By Ecco' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 4)
        )
    }

    @Test
    fun `long description with no extractable name is truncated`() {
        val anchor = ElementAnchor(contentDescription = "7".repeat(200))
        val q = StuckQuestion.build(anchor, stepOrder = 4)
        assertEquals("I couldn't find '${"7".repeat(60)}' on this screen. Should I pick something else, or stop?", q)
    }

    @Test
    fun `falls back to contentDescription when text is blank`() {
        val anchor = ElementAnchor(text = "  ", contentDescription = "Confirm order")
        assertEquals(
            "I couldn't find 'Confirm order' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 5)
        )
    }

    @Test
    fun `falls back to the resourceId's last segment, underscores as spaces`() {
        val anchor = ElementAnchor(resourceId = "com.dominos.app:id/search_button")
        assertEquals(
            "I couldn't find 'search button' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 1)
        )
    }

    @Test
    fun `falls back to a step-number description when nothing else was recorded`() {
        val anchor = ElementAnchor(className = "android.widget.LinearLayout", indexInParent = 4)
        assertEquals(
            "I couldn't find the button I tapped at step 7 on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 7)
        )
    }

    @Test
    fun `priority order -- text wins over contentDescription and resourceId`() {
        val anchor = ElementAnchor(
            resourceId = "id/row",
            text = "Farmhouse",
            contentDescription = "ignored content description"
        )
        assertEquals(
            "I couldn't find 'Farmhouse' on this screen. Should I pick something else, or stop?",
            StuckQuestion.build(anchor, stepOrder = 4)
        )
    }
}
