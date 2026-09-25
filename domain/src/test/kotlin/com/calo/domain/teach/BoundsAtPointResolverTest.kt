package com.calo.domain.teach

import com.calo.domain.teach.BoundsAtPointResolver.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundsAtPointResolverTest {

    @Test
    fun `two rows sharing a resourceId - point in the first row resolves to it, not the second`() {
        // Same layout resourceId in real life (a RecyclerView row template) —
        // this resolver never sees resourceId at all, only bounds, which is
        // exactly why identical resourceIds don't confuse it.
        val row1 = Bounds(left = 0, top = 0, right = 1080, bottom = 200)
        val row2 = Bounds(left = 0, top = 200, right = 1080, bottom = 400)

        assertEquals(0, BoundsAtPointResolver.firstContaining(listOf(row1, row2), x = 500, y = 100))
    }

    @Test
    fun `two rows sharing a resourceId - point in the second row resolves to it, not the first`() {
        val row1 = Bounds(left = 0, top = 0, right = 1080, bottom = 200)
        val row2 = Bounds(left = 0, top = 200, right = 1080, bottom = 400)

        assertEquals(1, BoundsAtPointResolver.firstContaining(listOf(row1, row2), x = 500, y = 300))
    }

    @Test
    fun `point outside every candidate resolves to nothing`() {
        val row1 = Bounds(left = 0, top = 0, right = 1080, bottom = 200)
        val row2 = Bounds(left = 0, top = 200, right = 1080, bottom = 400)

        assertNull(BoundsAtPointResolver.firstContaining(listOf(row1, row2), x = 500, y = 900))
    }

    @Test
    fun `empty bounds (unlaid-out node) never match, even at the origin`() {
        val empty = Bounds(left = 0, top = 0, right = 0, bottom = 0)
        assertNull(BoundsAtPointResolver.firstContaining(listOf(empty), x = 0, y = 0))
    }

    @Test
    fun `right and bottom edges are exclusive, matching android Rect#contains`() {
        val row = Bounds(left = 0, top = 0, right = 100, bottom = 100)
        assertEquals(0, BoundsAtPointResolver.firstContaining(listOf(row), x = 99, y = 99))
        assertNull(BoundsAtPointResolver.firstContaining(listOf(row), x = 100, y = 50))
    }
}
