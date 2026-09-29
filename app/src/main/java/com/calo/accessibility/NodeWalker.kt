package com.calo.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.domain.model.ElementAnchor
import com.calo.domain.replay.ClickValueMatcher
import com.calo.domain.replay.ContextPicker
import com.calo.domain.replay.FuzzyLabel
import com.calo.domain.semantic.ScreenElement
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
/**
 * Diagnostic-only (27 Sep 2026): one mutable record per tap, filled in as
 * resolution proceeds through NodeWalker/TeachRecorder/CaloAccessibilityService,
 * then formatted and logged ONCE after the tap fully resolves — see the tap-
 * freeze investigation this was added for. Never allocated/read off the
 * single touchWorker thread that owns one tap's resolution at a time, so
 * plain vars are fine (no synchronization needed).
 */
class TapTiming {
    var boundsCalls = 0
    var boundsTotalNanos = 0L
    var traversalNanos = 0L
    var semanticNanos = 0L
}

class NodeWalker {

    private companion object {
        // Caps so a huge feed can't make a single replay step slow.
        const val MAX_SNAPSHOT_NODES = 1500
        const val MAX_LABEL_PARTS = 6
        const val MAX_LABEL_CHARS = 300

        // findClickableAtPoint only prunes RECURSION depth (skips descending
        // into a child whose bounds don't contain the touch point) — it still
        // pays one getChild() Binder round-trip per SIBLING at every level on
        // the containment path, to read that sibling's own bounds first. On a
        // wide container (a flattened list/grid — confirmed on Zomato's home
        // screen, same shape as the countVisibleTextNodes ANR fixed 26 Sep),
        // that's hundreds of Binder calls for one touch. Caps total getChild()
        // calls across the whole walk so one touch can't run away; a real
        // touch's containment path only ever needs a small fraction of this
        // in practice. Trades a theoretical miss (an overlapping sibling
        // found only after the budget runs out) for a bounded worst case —
        // acceptable since Compose views overlapping at the exact same point
        // as another clickable sibling are rare, and unresolved is a smaller
        // failure than multi-second-and-climbing input lag.
        const val MAX_CHILD_LOOKUPS_PER_TOUCH = 200
    }

    /**
     * [requiredClimb]: true when the node NodeWalker actually matched
     * (by resourceId/text/contentDescription/className) was not itself
     * clickable/editable/scrollable, so [actionable] climbed to a clickable
     * ANCESTOR instead — a guess about which container owns the real click
     * behavior, distinct from a direct hit. See [ReplayEngine.performClick]
     * for why this matters: `ACTION_CLICK` can report success on a climbed
     * ancestor without doing anything (confirmed on-device, 2026-09-29,
     * Zomato — see that function's own comment), and that's only worth
     * verifying for the climbed case, not every click.
     */
    data class ResolvedMatch(val node: AccessibilityNodeInfo, val requiredClimb: Boolean)

    private fun matched(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): ResolvedMatch {
        val requiredClimb = !(node === root || node.isClickable || node.isEditable || node.isScrollable)
        return ResolvedMatch(actionable(node, root), requiredClimb)
    }

    fun resolve(root: AccessibilityNodeInfo?, anchor: ElementAnchor): ResolvedMatch? {
        if (root == null) return null

        anchor.resourceId?.let { rid ->
            when (val p = pickAmong(root, anchor) { it.viewIdResourceName == rid }) {
                is Pick.One -> return matched(p.node, root)
                Pick.Refused -> return null
                Pick.None -> Unit
            }
        }
        anchor.text?.let { text ->
            when (val p = pickAmong(root, anchor) { it.text?.toString() == text }) {
                is Pick.One -> return matched(p.node, root)
                Pick.Refused -> return null
                Pick.None -> Unit
            }
        }
        anchor.contentDescription?.let { cd ->
            when (val p = pickAmong(root, anchor) { it.contentDescription?.toString() == cd }) {
                is Pick.One -> return matched(p.node, root)
                Pick.Refused -> return null
                Pick.None -> Unit
            }
        }

        // Same element, label with changed live data ("delivers in 48
        // minutes" → "35 minutes"). See FuzzyLabel.
        val taughtLabel = anchor.contentDescription?.takeIf { it.isNotBlank() }
            ?: anchor.text?.takeIf { it.isNotBlank() }
        if (taughtLabel != null) {
            findFuzzy(root, taughtLabel)?.let { return it }
            // The element had a readable label and nothing on screen
            // resembles it: it isn't here. Falling through to "first
            // <className> at index N" would tap some unrelated element
            // (confirmed on-device, Zomato 27 Sep: step 1 matched a random
            // android.view.View at index 0), so stop and report Stuck.
            return null
        }

        val anchorClassName = anchor.className
        if (anchorClassName != null) {
            // Confirmed on-device (2026-09-29, Zomato): this tier only ever
            // runs for a bare anchor with NO resourceId/text/contentDescription
            // at all (every stronger tier above already returned or fell
            // through to the taughtLabel != null early-return, which itself
            // exists for exactly this "don't guess" reason — see its own
            // comment). Unlike every tier above, this used to grab the FIRST
            // className+index match via `find()` with zero ambiguity check —
            // a generic combination like FrameLayout+index 0 exists many
            // times over in a typical screen, so "first in traversal order"
            // is not "the taught element", it's whichever unrelated node
            // happens to come first. Confirmed root cause of a real mis-tap:
            // a taught FAB anchor (layout_holder_menu_fab, no text/
            // contentDescription) resolved to an unrelated FrameLayout on the
            // replay-time screen and, after performClick failed on it, the
            // coordinate-fallback tap landed on the search bar instead.
            // Routed through the same pickAmong the tiers above use so
            // several matches fail closed (Stuck) instead of guessing —
            // matches this project's fail-closed philosophy everywhere else
            // (CredentialGate, dedup, click-value ambiguity, post-typing-tap
            // resolution).
            val matches = collectAllByClassNameAndIndex(root, anchorClassName, anchor.indexInParent)
            when (val p = pickAmong(anchor, matches, root)) {
                is Pick.One -> return matched(p.node, root)
                Pick.Refused -> return null
                Pick.None -> Unit
            }
        }
        return null
    }

    private sealed class Pick {
        data class One(val node: AccessibilityNodeInfo) : Pick()
        /** Several matched and none is the taught row: stop, don't guess. */
        data object Refused : Pick()
        data object None : Pick()
    }

    /**
     * All nodes matching [predicate]; when there's more than one (identical
     * buttons in a list), the one whose surrounding row text matches the
     * anchor's recorded contextLabel — see ContextPicker. Unchosen nodes
     * are recycled; [root] is never recycled.
     */
    private fun pickAmong(
        root: AccessibilityNodeInfo,
        anchor: ElementAnchor,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Pick = pickAmong(anchor, collectAll(root, predicate), root)

    /**
     * Same disambiguation as above, over an already-collected [matches] list
     * — the className+indexInParent tier needs per-child index while
     * collecting (see [collectAllByClassNameAndIndex]), which a plain
     * [predicate] can't express, so it collects separately and shares this.
     */
    private fun pickAmong(anchor: ElementAnchor, matches: List<AccessibilityNodeInfo>, root: AccessibilityNodeInfo): Pick {
        if (matches.isEmpty()) return Pick.None
        val contexts = if (matches.size > 1 && !anchor.contextLabel.isNullOrBlank()) {
            matches.map { contextLabelFor(it) }
        } else {
            matches.map { null }
        }
        val index = ContextPicker.pick(anchor.contextLabel, contexts)
        val chosen = index?.let { matches[it] }
        matches.forEach { if (it !== chosen && it !== root) it.recycle() }
        if (chosen == null) {
            android.util.Log.w("Calo", "${matches.size} elements match ${anchor.resourceId ?: anchor.text ?: anchor.contentDescription ?: anchor.className} but none is in the taught row \"${anchor.contextLabel}\"")
            return Pick.Refused
        }
        return Pick.One(chosen)
    }

    /**
     * Same shape as [collectAll], but tracks each node's index among its own
     * parent's children (as [find]/[searchChildren] do) so it can filter on
     * [indexInParent] the way the className+index tier needs — [collectAll]'s
     * plain predicate has no way to see that.
     */
    private fun collectAllByClassNameAndIndex(
        root: AccessibilityNodeInfo,
        className: String,
        indexInParent: Int?
    ): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, myIndex: Int, isRoot: Boolean) {
            val isMatch = node.className?.toString() == className &&
                (indexInParent == null || myIndex == indexInParent)
            if (isMatch) found += node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, i, isRoot = false)
            }
            if (!isMatch && !isRoot) node.recycle()
        }
        walk(root, -1, isRoot = true)
        return found
    }

    /**
     * Text of the row/card around [node]: the nearest ancestor (below any
     * list container, at most 4 levels up) whose text is more than [node]'s
     * own. For an ADD button that's the dish row ("Crispy Veg Burger. |
     * Highly reordered | ₹70 | ..."). Null if nothing distinguishing is
     * found. [node] is borrowed; ancestors are recycled.
     */
    fun contextLabelFor(node: AccessibilityNodeInfo): String? {
        val own = subtreeLabel(node)
        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth < 4 && !isListContainer(ancestor)) {
            val label = subtreeLabel(ancestor)
            if (label != null && label != own) {
                ancestor.recycle()
                return label
            }
            val next = ancestor.parent
            ancestor.recycle()
            ancestor = next
            depth++
        }
        ancestor?.recycle()
        return null
    }

    /**
     * The one node whose text or contentDescription clearly resembles
     * [taughtLabel] (FuzzyLabel.bestUnique), climbed to its nearest
     * clickable ancestor. Only candidates that pass the threshold are kept
     * while walking; everything else is recycled on the way.
     */
    private fun findFuzzy(root: AccessibilityNodeInfo, taughtLabel: String): ResolvedMatch? {
        val labels = mutableListOf<String>()
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo, isRoot: Boolean) {
            val label = listOfNotNull(node.contentDescription?.toString(), node.text?.toString())
                .maxByOrNull { FuzzyLabel.similarity(taughtLabel, it) }
            val keep = label != null && FuzzyLabel.similarity(taughtLabel, label) >= FuzzyLabel.THRESHOLD
            if (keep) {
                labels += label!!
                nodes += node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, isRoot = false)
            }
            if (!keep && !isRoot) node.recycle()
        }
        walk(root, isRoot = true)

        val chosen = FuzzyLabel.bestUnique(taughtLabel, labels)?.let { nodes[it] }
        nodes.forEach { if (it !== chosen && it !== root) it.recycle() }
        return chosen?.let { matched(it, root) }
    }

    /**
     * A label matched by text is often a TextView INSIDE the real button
     * (a card's title), and ACTION_CLICK on it silently fails. Climb to the
     * nearest clickable ancestor unless the node can already be acted on
     * itself (clickable, a text field, or a scrollable list). [root] is
     * borrowed and never climbed from, since climbing recycles the start.
     */
    private fun actionable(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node === root || node.isClickable || node.isEditable || node.isScrollable) return node
        return nearestClickableAncestor(node)
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
     * Task 1 (Lane B, 2026-09-26): slot-substitution CLICK search for item
     * replacement (e.g. taught tapping "Margherita" in a search-result
     * list, replayed with "Farmhouse"). Deliberately a SEPARATE method from
     * [findByValue] rather than a change to it: [findByValue] does an exact,
     * first-in-traversal-order match, and TeachRecorder's own record-time
     * disambiguation (see its class doc, `recordClickWithoutSource`) is
     * built on that exact contract — changing it out from under Lane A's
     * teach-time matching was exactly the kind of cross-lane collision this
     * project's branch split is meant to avoid.
     *
     * This one instead collects EVERY distinct clickable element's label
     * on screen and delegates the actual counting/uniqueness decision to
     * [ClickValueMatcher] (:domain, unit-tested): case-insensitive+trimmed
     * exact match first, a contains-match only when exact finds nothing and
     * only when it narrows to exactly one candidate, Stuck (null) for
     * anything else — see that object's doc for the full rule and
     * ReplayPlannerTest for the sequencing-level proof that Stuck follows
     * from a null result here rather than a silent wrong tap.
     */
    fun findBySlotValue(root: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val labeled = collectLabeledClickables(root)
        val chosen = ClickValueMatcher.resolve(labeled, value) { it.first }
        for ((_, node) in labeled) if (node !== chosen?.second) node.recycle()
        return chosen?.second
    }

    /**
     * One (label, node) entry per distinct clickable ancestor under [root]
     * (root included) — same dedup-by-clickable-ancestor rule [findAllByValue]
     * uses, so a label living on a descendant TextView still surfaces as its
     * containing row's actionable element. text is preferred over
     * contentDescription as the label when a node has both (matching the
     * priority order used everywhere else an anchor's label is chosen, e.g.
     * SlotResolver.resolveClickTarget), so a node never contributes two
     * separate "candidates" for what is really one tappable element.
     */
    private fun collectLabeledClickables(root: AccessibilityNodeInfo): List<Pair<String, AccessibilityNodeInfo>> {
        val labeledDescendants = collectAll(root) { node ->
            val text = node.text?.toString()
            val cd = node.contentDescription?.toString()
            !text.isNullOrBlank() || !cd.isNullOrBlank()
        }

        val resolved = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for (node in labeledDescendants) {
            val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (label == null) {
                node.recycle() // predicate guaranteed non-blank; defensive only
                continue
            }
            val clickable = nearestClickableAncestor(node)
            val existing = resolved.indexOfFirst { it.second == clickable }
            if (existing >= 0) {
                // Two different labels under the same clickable row climbed
                // to the same ancestor — keep the first, recycle the rest.
                clickable.recycle()
            } else {
                resolved += label to clickable
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
     * Picks the SMALLEST visible node whose bounds contain ([x],[y]) across
     * the whole tree (BoundsAtPointResolver.smallestVisibleContaining), then
     * climbs to its [nearestClickableAncestor], same as every other anchor
     * this class hands back. Not "first child containing the point, then
     * descend": confirmed on-device (Zomato, 27 Sep 2026) that a large or
     * invisible container earlier in the tree swallowed taps on an ADD
     * button that way. Empty bounds (`[0,0][0,0]`, unlaid-out Compose nodes)
     * never contain a touch point.
     */
    fun findClickableAtPoint(root: AccessibilityNodeInfo?, x: Int, y: Int, timing: TapTiming? = null): AccessibilityNodeInfo? {
        if (root == null) return null
        // Smallest VISIBLE node under the point across the whole tree (see
        // BoundsAtPointResolver.smallestVisibleContaining for the on-device
        // bug the old first-child descent caused). Only nodes containing the
        // point are kept while walking; the rest are recycled.
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<BoundsAtPointResolver.Candidate>()
        val rect = Rect()
        var childLookupBudget = MAX_CHILD_LOOKUPS_PER_TOUCH

        fun walk(node: AccessibilityNodeInfo, isRoot: Boolean) {
            if (timing != null) {
                val start = System.nanoTime()
                node.getBoundsInScreen(rect)
                timing.boundsCalls++
                timing.boundsTotalNanos += System.nanoTime() - start
            } else {
                node.getBoundsInScreen(rect)
            }
            val bounds = BoundsAtPointResolver.Bounds(rect.left, rect.top, rect.right, rect.bottom)
            val keep = bounds.contains(x, y)
            if (keep) {
                nodes += node
                candidates += BoundsAtPointResolver.Candidate(bounds, visibleToUser = isRoot || node.isVisibleToUser)
                // Only descend into boxes that contain the point: a child's
                // on-screen bounds lie inside its parent's, so nothing
                // outside can be under the finger. Walking every node
                // instead took up to 1.5s on Zomato (27 Sep 2026) — long
                // enough for the screen to change before it finished.
                for (i in 0 until node.childCount) {
                    // Depth pruning above doesn't bound WIDTH: every sibling
                    // still costs one getChild() Binder call just to read its
                    // bounds. Confirmed on-device (Mehar's phone, 27 Sep
                    // 2026) that this alone took multiple seconds on Zomato's
                    // home screen once IPC latency was elevated — see the
                    // budget's own doc.
                    if (childLookupBudget <= 0) break
                    childLookupBudget--
                    val child = node.getChild(i) ?: continue
                    walk(child, isRoot = false)
                }
            }
            if (!keep && !isRoot) node.recycle()
        }
        walk(root, isRoot = true)

        val chosen = BoundsAtPointResolver.smallestVisibleContaining(candidates, x, y)?.let { nodes[it] }
        nodes.forEach { if (it !== chosen && it !== root) it.recycle() }
        // Only the window root under the finger = nothing specific was
        // touched (and the caller recycles what it gets; root is borrowed).
        if (chosen == null || chosen === root) return null
        return nearestClickableAncestor(chosen)
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
     * The live screen as [ScreenElement]s for the semantic layer
     * (RoleMatcher / PopupRules), plus the real node behind each one.
     * Only visible, clickable-or-editable nodes become elements — those are
     * the only things replay can act on. Each element's label is its own
     * text followed by its descendants' text, since a clickable product
     * card's title and price live on child TextViews.
     *
     * The returned [ScreenSnapshot] owns every node it keeps; call
     * [ScreenSnapshot.release] when done. [root] is borrowed, never recycled.
     */
    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
        val elements = mutableListOf<ScreenElement>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        if (root == null) return ScreenSnapshot(elements, nodes)
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, inList: Boolean, isRoot: Boolean) {
            if (++visited > MAX_SNAPSHOT_NODES) {
                if (!isRoot) node.recycle()
                return
            }
            val keep = node.isVisibleToUser && (node.isClickable || node.isEditable)
            if (keep) {
                val id = elements.size
                elements += ScreenElement(
                    id = id,
                    label = subtreeLabel(node),
                    contentDescription = node.contentDescription?.toString(),
                    hintText = node.hintText?.toString(),
                    resourceId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    focused = node.isFocused,
                    inList = inList
                )
                nodes[id] = node
            }
            val childrenInList = inList || isListContainer(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, childrenInList, isRoot = false)
            }
            if (!keep && !isRoot) node.recycle()
        }
        walk(root, inList = false, isRoot = true)
        return ScreenSnapshot(elements, nodes)
    }

    /**
     * The largest visible scrollable node on screen (the main feed / page,
     * not a small horizontal carousel), for the AI helper's "scroll".
     * Caller owns and recycles the result; [root] is borrowed.
     */
    fun findMainScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val rect = Rect()
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, isRoot: Boolean) {
            if (++visited > MAX_SNAPSHOT_NODES) {
                if (!isRoot) node.recycle()
                return
            }
            var kept = false
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    if (best !== root) best?.recycle()
                    best = node
                    bestArea = area
                    kept = true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, isRoot = false)
            }
            if (!kept && !isRoot) node.recycle()
        }
        walk(root, isRoot = true)
        val chosen = best ?: return null
        @Suppress("DEPRECATION")
        return if (chosen === root) AccessibilityNodeInfo.obtain(root) else chosen
    }

    private fun isListContainer(node: AccessibilityNodeInfo): Boolean {
        if (node.collectionInfo != null) return true
        val cls = node.className?.toString() ?: return false
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || cls.endsWith("GridView")
    }

    /** Own text, then up to a few descendant texts, joined with " | ". [node] is borrowed. */
    private fun subtreeLabel(node: AccessibilityNodeInfo): String? {
        val parts = mutableListOf<String>()

        fun collect(n: AccessibilityNodeInfo) {
            if (parts.size >= MAX_LABEL_PARTS) return
            n.text?.toString()?.trim()?.let { if (it.isNotEmpty()) parts += it }
            for (i in 0 until n.childCount) {
                if (parts.size >= MAX_LABEL_PARTS) return
                val child = n.getChild(i) ?: continue
                collect(child)
                child.recycle()
            }
        }
        collect(node)
        return parts.joinToString(" | ").take(MAX_LABEL_CHARS).ifEmpty { null }
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
