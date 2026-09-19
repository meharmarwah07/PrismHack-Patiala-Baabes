package com.calo.domain.model

import kotlinx.serialization.Serializable

/**
 * How we re-find a UI element later — deliberately NOT raw x/y coordinates.
 * Filled in from AccessibilityNodeInfo at teach time; used to re-search the
 * live screen at replay time. The resolver checks these in priority order:
 * resourceId first (most stable), then text, then contentDescription,
 * then className + indexInParent as a last resort.
 */
@Serializable
data class ElementAnchor(
    val resourceId: String? = null,        // e.g. "com.dominos.app:id/btn_add_to_cart"
    val text: String? = null,               // visible label at teach time
    val contentDescription: String? = null, // accessibility label, if the app sets one
    val className: String? = null,          // e.g. "android.widget.Button"
    val indexInParent: Int? = null          // fallback: nth child of its parent container
)
