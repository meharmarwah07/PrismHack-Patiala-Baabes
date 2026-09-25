package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollCoalescerTest {

    private val listAnchor = ElementAnchor(resourceId = "id/menu_list")
    private val otherAnchor = ElementAnchor(resourceId = "id/other_list")

    @Test
    fun `no prior scroll step never coalesces`() {
        assertFalse(
            ScrollCoalescer.shouldCoalesce(
                newAnchor = listAnchor, lastScrollAnchor = null, lastScrollAtMs = null, nowMs = 1_000L
            )
        )
    }

    @Test
    fun `same anchor within the coalesce window coalesces`() {
        assertTrue(
            ScrollCoalescer.shouldCoalesce(
                newAnchor = listAnchor, lastScrollAnchor = listAnchor, lastScrollAtMs = 1_000L, nowMs = 1_200L
            )
        )
    }

    @Test
    fun `same anchor exactly at the window boundary still coalesces (inclusive)`() {
        val nowMs = 1_000L + ScrollCoalescer.COALESCE_WINDOW_MS
        assertTrue(
            ScrollCoalescer.shouldCoalesce(
                newAnchor = listAnchor, lastScrollAnchor = listAnchor, lastScrollAtMs = 1_000L, nowMs = nowMs
            )
        )
    }

    @Test
    fun `same anchor past the coalesce window is a new burst, does not coalesce`() {
        val nowMs = 1_000L + ScrollCoalescer.COALESCE_WINDOW_MS + 1
        assertFalse(
            ScrollCoalescer.shouldCoalesce(
                newAnchor = listAnchor, lastScrollAnchor = listAnchor, lastScrollAtMs = 1_000L, nowMs = nowMs
            )
        )
    }

    @Test
    fun `different anchor within the window is a different scrollable, does not coalesce`() {
        assertFalse(
            ScrollCoalescer.shouldCoalesce(
                newAnchor = otherAnchor, lastScrollAnchor = listAnchor, lastScrollAtMs = 1_000L, nowMs = 1_050L
            )
        )
    }
}
