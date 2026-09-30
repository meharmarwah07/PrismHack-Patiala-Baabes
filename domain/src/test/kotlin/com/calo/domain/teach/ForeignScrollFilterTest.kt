package com.calo.domain.teach

import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ForeignScrollFilterTest {

    private val zomato = "com.application.zomato"

    private fun step(order: Int, action: ActionType, resourceId: String?) =
        FlowStep(order = order, action = action, target = ElementAnchor(resourceId = resourceId))

    @Test
    fun `leading scroll from another app is dropped and steps renumbered`() {
        val steps = listOf(
            step(1, ActionType.SCROLL, "com.oneplus.deskclock:id/world_city_list"),
            step(2, ActionType.CLICK, "$zomato:id/search_bar"),
            step(3, ActionType.SET_TEXT, "$zomato:id/edittext")
        )
        val out = ForeignScrollFilter.filter(steps, zomato)
        assertEquals(listOf(1, 2), out.map { it.order }.take(2))
        assertEquals(listOf(ActionType.CLICK, ActionType.SET_TEXT), out.map { it.action })
    }

    @Test
    fun `scroll in the target app is kept`() {
        val steps = listOf(step(1, ActionType.SCROLL, "$zomato:id/list"))
        assertSame(steps, ForeignScrollFilter.filter(steps, zomato))
    }

    @Test
    fun `scroll with no resourceId is kept`() {
        val steps = listOf(step(1, ActionType.SCROLL, null))
        assertSame(steps, ForeignScrollFilter.filter(steps, zomato))
    }

    @Test
    fun `non-scroll step from another package is never touched`() {
        val steps = listOf(step(1, ActionType.CLICK, "com.other:id/x"))
        assertSame(steps, ForeignScrollFilter.filter(steps, zomato))
    }

    @Test
    fun `null target package filters nothing`() {
        val steps = listOf(step(1, ActionType.SCROLL, "com.oneplus.deskclock:id/world_city_list"))
        assertSame(steps, ForeignScrollFilter.filter(steps, null))
    }
}
