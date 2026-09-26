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

    // Zomato menu, 27 Sep 2026: a big container earlier in the tree (the
    // search container / section header) also covers the ADD button.
    private val bigContainer = BoundsAtPointResolver.Candidate(Bounds(0, 0, 1080, 2400), visibleToUser = true)
    private val hiddenOverlay = BoundsAtPointResolver.Candidate(Bounds(600, 800, 1000, 950), visibleToUser = false)
    private val menuRow = BoundsAtPointResolver.Candidate(Bounds(0, 400, 1080, 1200), visibleToUser = true)
    private val addButton = BoundsAtPointResolver.Candidate(Bounds(640, 830, 1000, 950), visibleToUser = true)

    @Test
    fun `tap on ADD resolves to the ADD button, not the big container that comes first`() {
        val tree = listOf(bigContainer, menuRow, addButton)
        assertEquals(2, BoundsAtPointResolver.smallestVisibleContaining(tree, x = 800, y = 890))
    }

    @Test
    fun `invisible nodes are ignored even when they're the smallest`() {
        val tree = listOf(bigContainer, menuRow, hiddenOverlay, addButton)
        assertEquals(3, BoundsAtPointResolver.smallestVisibleContaining(tree, x = 800, y = 890))
    }

    @Test
    fun `equal size overlap goes to the later node, which is drawn on top`() {
        val a = BoundsAtPointResolver.Candidate(Bounds(0, 0, 100, 100), visibleToUser = true)
        val b = BoundsAtPointResolver.Candidate(Bounds(0, 0, 100, 100), visibleToUser = true)
        assertEquals(1, BoundsAtPointResolver.smallestVisibleContaining(listOf(a, b), x = 50, y = 50))
    }

    @Test
    fun `nothing visible under the finger resolves to nothing`() {
        assertNull(BoundsAtPointResolver.smallestVisibleContaining(listOf(hiddenOverlay), x = 800, y = 890))
    }
}
