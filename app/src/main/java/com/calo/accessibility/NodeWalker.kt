package com.calo.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.calo.domain.model.ElementAnchor

/**
 * Re-finds a taught element on the live screen from its ElementAnchor, in
 * the priority order the anchor's own doc promises: resourceId (most
 * stable) > text > contentDescription > className + indexInParent (last
 * resort). Also does the extraction pass CredentialGate needs (walking
 * every node for text/resourceId/isPassword signals).
 *
 * Recycling contract, since AccessibilityNodeInfo is a finite per-app pool
 * and this runs before every single replay step: every node fetched via
 * getChild() that is NOT the match (or an ancestor still needed on the
 * return path) gets recycled on the way back out of the recursion,
 * regardless of whether a match was found. The `root` passed in is never
 * recycled here — it's owned by whoever fetched rootInActiveWindow, and
 * this function only ever borrows it.
 */
class NodeWalker {

    fun resolve(root: AccessibilityNodeInfo?, anchor: ElementAnchor): AccessibilityNodeInfo? {
        if (root == null) return null

        anchor.resourceId?.let { rid ->
            find(root) { node, _ -> node.viewIdResourceName == rid }?.let { return it }
        }
        anchor.text?.let { text ->
            find(root) { node, _ -> node.text?.toString() == text }?.let { return it }
        }
        anchor.contentDescription?.let { cd ->
            find(root) { node, _ -> node.contentDescription?.toString() == cd }?.let { return it }
        }
        if (anchor.className != null) {
            find(root) { node, indexInParent ->
                node.className?.toString() == anchor.className &&
                    (anchor.indexInParent == null || indexInParent == anchor.indexInParent)
            }?.let { return it }
        }
        return null
    }

    /**
     * Checks `root` itself first (it can legitimately be the match — e.g. a
     * single-node screen, or an anchor recorded on a container), then walks
     * children depth-first. `indexInParent` passed to [matches] is -1 for
     * root itself, since a window root has no parent to be indexed under.
     */
    private fun find(
        root: AccessibilityNodeInfo,
        matches: (node: AccessibilityNodeInfo, indexInParent: Int) -> Boolean
    ): AccessibilityNodeInfo? {
        if (matches(root, -1)) return root
        return searchChildren(root, matches)
    }

    private fun searchChildren(
        node: AccessibilityNodeInfo,
        matches: (node: AccessibilityNodeInfo, indexInParent: Int) -> Boolean
    ): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            if (matches(child, i)) {
                return child // caller now owns `child`; do not recycle
            }

            val deeper = searchChildren(child, matches)
            if (deeper != null) {
                // child is an ancestor of the real match, not the match
                // itself (matches(child,...) already returned false above)
                // — we're done reading it, so recycle it on the way out.
                child.recycle()
                return deeper
            }

            child.recycle()
        }
        return null
    }
}
