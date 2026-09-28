package com.calo.domain.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapGestureTest {

    private val slop = 48f
    private val longPress = 400L

    @Test
    fun `quick touch that barely moves is a tap`() {
        assertTrue(TapGesture.isTap(500f, 1600f, maxDistancePx = 6f, heldMs = 120, slopPx = slop, longPressMs = longPress))
    }

    @Test
    fun `a swipe through a results list is not a tap`() {
        // Zomato 27 Sep: flicking the list started on "Thalaiva Biryani".
        assertFalse(TapGesture.isTap(423f, 1652f, maxDistancePx = 300f, heldMs = 150, slopPx = slop, longPressMs = longPress))
    }

    @Test
    fun `a touch held down is a long-press, not a tap`() {
        assertFalse(TapGesture.isTap(500f, 1600f, maxDistancePx = 2f, heldMs = 700, slopPx = slop, longPressMs = longPress))
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5f, TapGesture.distance(0f, 0f, 3f, 4f), 0.001f)
    }
}
