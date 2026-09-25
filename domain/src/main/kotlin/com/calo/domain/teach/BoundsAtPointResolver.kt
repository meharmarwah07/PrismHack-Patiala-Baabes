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
}
