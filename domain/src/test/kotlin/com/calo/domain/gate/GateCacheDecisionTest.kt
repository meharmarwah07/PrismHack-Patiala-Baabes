package com.calo.domain.gate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCacheDecisionTest {

    @Test
    fun `reuses a recent cache entry for the same app`() {
        assertTrue(GateCacheDecision.shouldReuse("com.application.zomato", 1000L, "com.application.zomato", nowMs = 1100L, ttlMs = 250L))
    }

    @Test
    fun `never reuses once the app changed, even a moment later`() {
        assertFalse(GateCacheDecision.shouldReuse("com.application.zomato", 1000L, "com.android.chrome", nowMs = 1010L, ttlMs = 250L))
    }

    @Test
    fun `expires once the TTL elapses`() {
        assertFalse(GateCacheDecision.shouldReuse("com.application.zomato", 1000L, "com.application.zomato", nowMs = 1251L, ttlMs = 250L))
        assertTrue(GateCacheDecision.shouldReuse("com.application.zomato", 1000L, "com.application.zomato", nowMs = 1249L, ttlMs = 250L))
    }

    @Test
    fun `nothing cached yet is never a reuse`() {
        assertFalse(GateCacheDecision.shouldReuse(null, null, "com.application.zomato", nowMs = 1000L, ttlMs = 250L))
    }

    @Test
    fun `a clock that appears to move backward is never trusted`() {
        assertFalse(GateCacheDecision.shouldReuse("com.application.zomato", 5000L, "com.application.zomato", nowMs = 100L, ttlMs = 250L))
    }
}
