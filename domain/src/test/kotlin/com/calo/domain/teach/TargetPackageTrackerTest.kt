package com.calo.domain.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetPackageTrackerTest {

    @Test
    fun `first action latches targetPackage, no warning`() {
        val outcome = TargetPackageTracker.record(currentTargetPackage = null, actionPackage = "com.zomato")
        assertEquals("com.zomato", outcome.targetPackage)
        assertNull(outcome.warning)
    }

    @Test
    fun `later action in the same package confirms targetPackage, no warning`() {
        val outcome = TargetPackageTracker.record(currentTargetPackage = "com.zomato", actionPackage = "com.zomato")
        assertEquals("com.zomato", outcome.targetPackage)
        assertNull(outcome.warning)
    }

    @Test
    fun `later action in a different package keeps the first, warns instead of merging`() {
        val outcome = TargetPackageTracker.record(currentTargetPackage = "com.zomato", actionPackage = "com.dominos")
        assertEquals("com.zomato", outcome.targetPackage)
        assertEquals(
            "Teach session action package \"com.dominos\" differs from already-latched " +
                "targetPackage \"com.zomato\" — keeping \"com.zomato\", not merging.",
            outcome.warning
        )
    }
}
