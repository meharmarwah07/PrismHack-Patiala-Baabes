package com.calo.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.calo.domain.semantic.ScreenElement

/**
 * One NodeWalker.snapshot() result: the flattened elements the domain
 * matcher reads, and the real node behind each. Owns those nodes.
 */
class ScreenSnapshot(
    val elements: List<ScreenElement>,
    private val nodes: Map<Int, AccessibilityNodeInfo>
) {
    /**
     * A caller-owned COPY of the node behind [id], so the caller can
     * recycle it (ReplayEngine's perform* methods do) without
     * double-recycling the snapshot's own reference on older Android
     * versions, where that throws.
     */
    @Suppress("DEPRECATION")
    fun nodeCopy(id: Int): AccessibilityNodeInfo? = nodes[id]?.let { AccessibilityNodeInfo.obtain(it) }

    fun release() {
        nodes.values.forEach { it.recycle() }
    }
}
