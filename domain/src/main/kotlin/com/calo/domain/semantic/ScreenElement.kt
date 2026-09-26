package com.calo.domain.semantic

/**
 * One actionable element (clickable or editable) on the live screen,
 * flattened into plain data so [RoleMatcher] can reason about it without an
 * Android SDK. :app builds these from the real AccessibilityNodeInfo tree
 * (NodeWalker.snapshot) and keeps the real node for each [id].
 *
 * [label] is the element's own text plus the text of what's inside it — a
 * product card is a clickable container whose title/price live on child
 * TextViews, so its own text alone is usually empty.
 */
data class ScreenElement(
    val id: Int,                        // traversal order: lower = earlier on screen
    val label: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val inList: Boolean = false         // inside a RecyclerView/ListView/GridView
)
