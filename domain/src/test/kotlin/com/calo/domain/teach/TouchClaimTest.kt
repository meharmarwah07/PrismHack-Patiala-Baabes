package com.calo.domain.teach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchClaimTest {

    @Test
    fun `a click right after an unclaimed touch-down is the user's`() {
        assertTrue(TouchClaim.canClaim(captureLive = true, touchAlreadyClaimed = false, lastTouchDownAtMs = 1000, nowMs = 1300))
    }

    @Test
    fun `an app-generated click with no touch behind it is dropped`() {
        assertFalse(TouchClaim.canClaim(captureLive = true, touchAlreadyClaimed = false, lastTouchDownAtMs = null, nowMs = 5000))
        assertFalse(TouchClaim.canClaim(captureLive = true, touchAlreadyClaimed = false, lastTouchDownAtMs = 1000, nowMs = 4000))
    }

    @Test
    fun `a second click claiming an already-recorded touch is dropped`() {
        // Zomato 27 Sep: the "Burger King" tap was recorded, then the menu
        // page's own "Recommended for you" click arrived within the window.
        assertFalse(TouchClaim.canClaim(captureLive = true, touchAlreadyClaimed = true, lastTouchDownAtMs = 1000, nowMs = 1800))
    }

    @Test
    fun `without live touch capture every click is accepted as before`() {
        assertTrue(TouchClaim.canClaim(captureLive = false, touchAlreadyClaimed = true, lastTouchDownAtMs = null, nowMs = 9999))
    }
}
