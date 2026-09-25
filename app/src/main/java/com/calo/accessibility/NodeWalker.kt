package com.calo.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.domain.model.ElementAnchor
import com.calo.domain.teach.BoundsAtPointResolver

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
     * Finds an element by text/contentDescription equal to [value] —
     * deliberately NOT keyed off a taught ElementAnchor at all, unlike
     * [resolve]. Called only when a CLICK step's slot value differs from
     * what was recorded at teach time (see ReplayPlanner /
     * SlotResolver.resolveClickTarget): the anchor still describes the OLD
     * option ("Home"), so resolving it would find the wrong element; this
     * searches for the NEW one ("Work") on whatever's on screen right now.
     * No resourceId/className tier here — unlike a taught anchor, there's
     * no recorded resourceId for a value nobody ever tapped during
     * teaching, so text/contentDescription (what a person would actually
     * read to find "Work" themselves) is all there is to search by.
     *
     * Built on [findAllByValue] — first match in traversal order wins,
     * everything else found along the way is recycled. TeachRecorder's
     * null-source CLICK recovery (see its class doc) uses the same
     * [findAllByValue] but applies its own disambiguation instead of
     * blindly taking the first match, since a wrong pick there corrupts a
     * taught flow with no later "Stuck" safety net the way replay has here.
     */
    fun findByValue(root: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        val matches = findAllByValue(root, value)
        val chosen = matches.firstOrNull()
        for (match in matches) if (match !== chosen) match.recycle()
        return chosen
    }

    /**
     * Every element under [root] (root included) whose text OR
     * contentDescription equals [value], in depth-first traversal order —
     * NOT ranked, since this function has no signal beyond "this label
     * equals [value]" to prefer one match over another; callers that need
     * to disambiguate (multiple similar rows, e.g. a list) must do so
     * themselves. Each result is resolved to its nearest clickable
     * ancestor via [nearestClickableAncestor] first: the text/
     * contentDescription that matches is very often a label INSIDE the
     * actionable row (a Preference row's title TextView, say) rather than
     * the actionable element itself, and ACTION_CLICK silently fails on a
     * non-clickable node — confirmed on a real Settings preference row
     * during on-device verification (see README's capture-time known-gap
     * section).
     *
     * Every node in the returned list is owned by the caller and must be
     * recycled; every node visited but not returned is recycled here.
     */
    fun findAllByValue(root: AccessibilityNodeInfo?, value: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val direct = collectAll(root) { it.text?.toString() == value }
            .ifEmpty { collectAll(root) { it.contentDescription?.toString() == value } }

        val resolved = mutableListOf<AccessibilityNodeInfo>()
        for (node in direct) {
            val clickable = nearestClickableAncestor(node)
            val alreadyHave = resolved.any { it == clickable }
            if (alreadyHave) {
                // Two different labels under the same clickable row (e.g.
                // title + a duplicated content-desc) climbed to the same
                // ancestor — keep one, recycle the redundant climb.
                clickable.recycle()
            } else {
                resolved += clickable
            }
        }
        return resolved
    }

    /**
     * Finding 6 fallback (2026-09-26): resolves a raw touch-down coordinate
     * (TouchInteractionController delivers these, not a node) to a real,
     * anchor-worthy element — needed because some target apps (confirmed:
     * Zomato's search bar and search-result taps) never fire a
     * TYPE_VIEW_CLICKED at all for certain taps, so there is no
     * AccessibilityEvent.source to build an anchor from. [root] must be a
     * snapshot taken at touch-down time (not a later, possibly-navigated-
     * away screen) — see TeachRecorder's pending-raw-touch doc for why.
     *
     * Finds the DEEPEST node whose bounds contain ([x],[y]) — mirroring how
     * a real tap actually resolves in a real Android touch dispatch (the
     * most specific/innermost view under the finger, not some ancestor
     * container) — then climbs to its [nearestClickableAncestor], same as
     * every other anchor this class ever hands back. First-child-wins on
     * overlapping bounds (no z-order signal available here); a node with
     * empty bounds (`[0,0][0,0]`, common for off-screen/measured-but-
     * unlaid-out Compose nodes) can never contain a real touch point and is
     * skipped rather than falsely matched at the origin.
     */
    fun findClickableAtPoint(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val deepest = deepestNodeAt(root, x, y) ?: return null
        return nearestClickableAncestor(deepest)
    }

    private fun deepestNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = BoundsAtPointResolver.Bounds(rect.left, rect.top, rect.right, rect.bottom)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val deeper = deepestNodeAt(child, x, y)
            if (deeper != null) {
                if (deeper !== child) child.recycle()
                return deeper
            }
            child.recycle()
        }
        return node
    }

    /**
     * Finding 6 fallback refinement (2026-09-26): confirmed on-device that
     * the clickable ancestor [findClickableAtPoint] resolves to is very
     * often a bare container (a result row's FrameLayout, say) with no
     * text of its own — the actual label ("Domino's Pizza") lives on a
     * descendant TextView. Without this, the saved anchor's text/
     * contentDescription were both null, leaving only className+
     * indexInParent to re-find it at replay time — the weakest tier.
     * First non-blank text or contentDescription found in a depth-first
     * walk, or null if [node] and every descendant are genuinely textless
     * (an icon-only button with no contentDescription either). [node]
     * itself is never recycled here (borrowed, same as every NodeWalker
     * entry point); every descendant visited is.
     */
    fun firstDescendantText(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.let { if (it.isNotBlank()) return it }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = firstDescendantText(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Finding 6 fallback, part 2 (2026-09-26): every non-blank text/
     * contentDescription value under (and including) [root], in traversal
     * order, ONE ENTRY PER NODE — duplicates are kept deliberately (unlike
     * [findAllByValue]'s de-duplication-by-clickable-ancestor), since
     * [PostTypingTapResolver] needs to know when two different nodes share
     * the same text to refuse to guess between them. Not restricted to
     * clickable nodes: whichever text comes back matched is re-resolved
     * through [findByValue] afterwards, which already climbs to the
     * nearest clickable ancestor. Same borrowed-root, recycle-the-rest
     * contract as every other function here.
     */
    fun collectAllText(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val texts = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let { if (it.isNotBlank()) texts += it }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts += it }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }
        walk(root)
        return texts
    }

    /**
     * Walks up from [node] to the nearest ancestor with isClickable ==
     * true. Returns [node] itself unchanged if it's already clickable, or
     * if no clickable ancestor exists before the window root. Every
     * intermediate ancestor visited but not chosen is recycled; [node] is
     * recycled too if a better (different) ancestor is returned instead.
     */
    private fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var previous: AccessibilityNodeInfo? = null
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.isClickable) {
                previous?.recycle()
                node.recycle()
                return ancestor
            }
            previous?.recycle()
            previous = ancestor
            ancestor = ancestor.parent
        }
        previous?.recycle()
        return node
    }

    /**
     * Collects every descendant of [root] (root included) matching
     * [predicate], instead of stopping at the first hit like [find]/
     * [searchChildren] do. Matches are returned to the caller unrecycled;
     * everything else visited is recycled on the way back out. [root]
     * itself is never recycled here regardless of match, same borrowed-not-
     * owned contract as the rest of this class.
     */
    private fun collectAll(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        collectInto(root, isRoot = true, predicate, found)
        return found
    }

    private fun collectInto(
        node: AccessibilityNodeInfo,
        isRoot: Boolean,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        found: MutableList<AccessibilityNodeInfo>
    ) {
        val isMatch = predicate(node)
        if (isMatch) found += node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInto(child, isRoot = false, predicate, found)
        }
        if (!isMatch && !isRoot) node.recycle()
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
