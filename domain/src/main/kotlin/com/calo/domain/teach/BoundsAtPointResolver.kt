package com.calo.domain.teach

/**
 * The actual disambiguation logic behind [NodeWalker.findClickableAtPoint]
 * (:app) — pulled out as pure geometry so it can be unit-tested without a
 * real AccessibilityNodeInfo tree (not constructible in a plain JUnit test
 * — see this project's other Android-dependent classes for why). This is
 * exactly what makes two sibling rows sharing the same resourceId resolve
 * correctly: the decision is purely positional, resourceId never enters
 * into it at all.
 */
object BoundsAtPointResolver {

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = left >= right || top >= bottom

        /** Half-open on the right/bottom edge, matching android.graphics.Rect.contains. */
        fun contains(x: Int, y: Int): Boolean =
            !isEmpty && x in left until right && y in top until bottom
    }

    /**
     * Index of the first of [candidates] (in traversal order) whose bounds
     * contain ([x], [y]), or null if none do. First-match-wins on
     * overlapping bounds — there's no z-order signal available at the
     * accessibility-tree level to prefer one over another.
     */
    fun firstContaining(candidates: List<Bounds>, x: Int, y: Int): Int? {
        val index = candidates.indexOfFirst { it.contains(x, y) }
        return if (index >= 0) index else null
    }

    /** One node of the whole tree, flattened in traversal order. */
    data class Candidate(val bounds: Bounds, val visibleToUser: Boolean)

    /**
     * Index of the element the finger actually landed on: of all
     * VISIBLE [candidates] containing ([x], [y]), the one with the smallest
     * area; on a tie, the later one in traversal order (drawn on top).
     *
     * Replaces a "first child whose bounds contain the point, then descend"
     * walk that was confirmed wrong on-device (Zomato, 27 Sep 2026): taps on
     * a menu item's ADD button were recorded as `search_container` and as
     * the "Recommended for you" section header, because those large
     * containers (one of them not even visible) came earlier in the tree
     * and their bounds also covered the point. The smallest visible element
     * under the finger is the most specific thing that was touched; the
     * caller then climbs to its nearest clickable ancestor.
     */
    fun smallestVisibleContaining(candidates: List<Candidate>, x: Int, y: Int): Int? {
        var best: Int? = null
        var bestArea = Long.MAX_VALUE
        candidates.forEachIndexed { i, c ->
            if (!c.visibleToUser || !c.bounds.contains(x, y)) return@forEachIndexed
            val area = (c.bounds.right - c.bounds.left).toLong() * (c.bounds.bottom - c.bounds.top)
            if (area <= bestArea) {
                best = i
                bestArea = area
            }
        }
        return best
    }
}
