package com.calo.teach

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.accessibility.NodeWalker
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.teach.PostTypingTapResolver
import com.calo.domain.teach.RawTouchCaptureGate
import com.calo.domain.teach.ScrollCoalescer
import com.calo.domain.teach.TargetPackageTracker
import com.calo.domain.teach.TeachPackageFilter
import com.calo.domain.teach.TextEntryCoalescer

/**
 * Builds a FlowStep list from raw AccessibilityEvents while
 * CaloAccessibilityService.mode == TEACHING. Deliberately dumb: it records
 * literal taps/typing/scrolls as they happen, nothing more.
 *
 * Slot promotion (marking "Margherita" as a value the {item} slot should
 * own) is NOT inferred automatically here — that would mean silently
 * guessing which literal values are "generic" vs. one-off, which is the
 * opposite of this project's approach elsewhere (CredentialGateRules fails
 * closed rather than guessing; this should too). [promoteToSlot] is the
 * explicit hook a review step calls after teaching finishes.
 *
 * [ownPackageName] exists to solve a real self-recording bug: the tap on
 * Calo's own "Teach a Flow" button (and any of Calo's own UI updates while
 * teaching, e.g. the pill's label changing) generates AccessibilityEvents
 * same as any app under test. Without filtering those out, step 1 of every
 * taught flow could end up being a tap on Calo's own UI instead of the
 * first real action in the target app — and targetPackage below would get
 * latched to "com.calo" instead of the actual app being taught, since it's
 * only ever set once. CaloAccessibilityService passes its own `packageName`
 * (real, install-time-accurate) rather than a hardcoded string.
 *
 * [launcherPackageName] closes a sibling bug found on a real device
 * (2026-09-24, first-ever live human teach session): the class doc above
 * already explains why Calo's own package is excluded, but the home/
 * launcher screen was NOT — and since Calo → home screen → target app is
 * the only way a real person leaves Calo to go open the app they're
 * teaching (there's no in-app app-switcher path here), the launcher's own
 * events were the first non-Calo events seen on essentially every real
 * session, latching targetPackage to the launcher instead of the actual
 * target app. Nullable/optional (unlike ownPackageName) because resolving
 * it can fail on a given device — a failed resolution degrades to "no
 * extra exclusion" rather than crashing teaching outright.
 */
class TeachRecorder(
    private val ownPackageName: String,
    private val launcherPackageName: String? = null,
    private val nodeWalker: NodeWalker = NodeWalker()
) {
    private companion object {
        const val TAG = "Calo"

        // How long a cached "good root" (see goodRootHistory below) stays
        // usable for the null-source CLICK recovery, in ms. Picked to
        // comfortably cover a real screen transition (confirmed on-device:
        // the destination window can still be an empty stub ~450ms after
        // the tap that triggered it) while staying far short of "long
        // enough to match against a screen from several taps ago" — teach
        // sessions are human-paced (taps land seconds apart), so 2s doesn't
        // risk admitting a stale, several-navigations-back screen.
        const val MAX_GOOD_ROOT_AGE_MS = 2000L

        // A root is "real" (cacheable) only if it has MORE than this many
        // visible text/contentDescription nodes — rejects the empty stub
        // FrameLayout an in-transition destination window returns (observed
        // on-device: 0 visible texts) while still accepting a genuinely
        // sparse-but-real screen (a single-button dialog, say).
        const val MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT = 1

        // How many recent "real" roots to retain for null-source CLICK
        // recovery (see goodRootHistory's doc) instead of just the single
        // most recent one. 3 is enough to survive a couple of
        // faster-than-the-click intervening events (confirmed on-device:
        // one intervening WINDOW_CONTENT_CHANGED was enough to break the
        // old single-slot cache) without retaining so much history that a
        // stale, several-navigations-back screen becomes a plausible (wrong)
        // match — MAX_GOOD_ROOT_AGE_MS is still the primary guard against
        // that, this is just a second, shallow line of defense.
        const val GOOD_ROOT_HISTORY_SIZE = 3

        // Finding 6 fallback, part 2 (2026-09-26): how long after the
        // keyboard hides a new WINDOW_STATE_CHANGED still counts as
        // "probably caused by the tap that dismissed the keyboard" rather
        // than some unrelated later navigation. 1.5s per the agreed spec —
        // generous enough to cover a real screen transition's load time,
        // short enough that it doesn't reach into a much later, unrelated
        // navigation several seconds on.
        const val POST_TYPING_TAP_WINDOW_MS = 1500L

        // Noise filter (2026-09-26): confirmed on-device that a home-screen
        // banner carousel's own auto-advance fires TYPE_VIEW_SCROLLED with
        // no real touch behind it at all — recorded as a bogus step in a
        // real T1 run. 500ms is generous relative to onRawTouchDown's own
        // near-instant delivery (confirmed: single-digit-ms latency from
        // ACTION_DOWN) while still well short of "a scroll that happens to
        // follow some earlier, unrelated tap."
        const val SCROLL_NOISE_WINDOW_MS = 500L
    }

    private val steps = mutableListOf<FlowStep>()
    private var nextOrder = 1
    private var targetPackage: String? = null

    // Best-effort HISTORY (not just a single slot) of recent screens that
    // looked "real" (see MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT), used ONLY to
    // recover a CLICK whose event.source is null — see
    // recordClickWithoutSource. Refreshed opportunistically on every
    // qualifying event (not just clicks) and seeded eagerly at teach-start
    // (see seedRoot) so even the very FIRST event of a session has something
    // to fall back on. Each entry is timestamped and package-tagged so a
    // stale or cross-app match can be refused outright rather than silently
    // matching against the wrong screen.
    //
    // A SINGLE cached slot (this class's original design) turned out to be
    // insufficient — confirmed on-device/emulator, 2026-09-25 (Chrome's
    // "Switch or close tabs" tab-switcher button): the destination screen's
    // OWN TYPE_WINDOW_CONTENT_CHANGED can be delivered to this service
    // BEFORE the null-source TYPE_VIEW_CLICKED for the tap that caused it —
    // not just possible for THIS event's own currentRoot (already guarded
    // against, see the call-order comment below), but for an entirely
    // separate, chronologically-earlier-arriving event in between. That
    // earlier event's refreshGoodRootCache call overwrites the one cache
    // slot with the destination screen before the click even arrives, so by
    // the time recordClickWithoutSource runs, "the strictly earlier cached
    // root" it reads is ALSO already the wrong (post-click) screen — the
    // single-slot design has no earlier state left to fall back to. Keeping
    // the last [GOOD_ROOT_HISTORY_SIZE] roots instead of just one gives
    // recovery a real pre-click screen to search even when the most recent
    // entry has already rolled forward past it.
    private val goodRootHistory = ArrayDeque<CachedRoot>()

    private data class CachedRoot(
        val root: AccessibilityNodeInfo,
        val atMs: Long,
        val packageName: String?
    )

    // Burst-coalescing state for TYPE_VIEW_SCROLLED — see ScrollCoalescer.
    private var lastScrollAnchor: ElementAnchor? = null
    private var lastScrollAtMs: Long? = null

    // Text-entry-coalescing state for TYPE_VIEW_TEXT_CHANGED — see
    // TextEntryCoalescer. lastTextEntryStepIndex is the `steps` index of
    // the SET_TEXT step currently absorbing keystrokes; both are cleared
    // (see CLICK/SCROLL below) whenever anything else happens in between,
    // so a later TEXT_CHANGED on the same node after an intervening tap
    // starts a genuinely new step rather than reopening the old one.
    private var lastTextEntryAnchor: ElementAnchor? = null
    private var lastTextEntryStepIndex: Int? = null

    // Finding 6 fallback state (2026-09-26) — see onRawTouchDown's doc.
    // `token` disambiguates: a later raw touch-down (or a genuine CLICK
    // arriving in between) invalidates any earlier pending one, so a
    // delayed commit that fires late can recognize it's stale and no-op
    // instead of recording the wrong tap.
    private data class PendingRawTouch(val anchor: ElementAnchor, val token: Long)
    private var pendingRawTouch: PendingRawTouch? = null
    private var nextRawTouchToken = 0L

    // Finding 6 fallback, part 2 (2026-09-26) — see onKeyboardHidden's and
    // tryResolvePostTypingTap's docs. Non-null only in the window right
    // after the keyboard hides; consumed (set back to null) the first time
    // tryResolvePostTypingTap acts on it, whether or not it finds a match —
    // one keyboard-hide gets one resolution attempt, not a retry per
    // subsequent WINDOW_STATE_CHANGED.
    private var lastKeyboardHideAtMs: Long? = null

    // Noise filter (2026-09-26) — see SCROLL_NOISE_WINDOW_MS and the
    // TYPE_VIEW_SCROLLED branch below. Set unconditionally at the top of
    // onRawTouchDown (even for an excluded/gated touch-down — the point is
    // only "was a real finger on the glass recently", not what the app did
    // with it), so this stays null (never dropping scrolls) on any device/
    // session where raw touch capture isn't running at all.
    private var lastRawTouchDownAtMs: Long? = null

    fun onAccessibilityEvent(event: AccessibilityEvent, currentRoot: AccessibilityNodeInfo?) {
        val eventPackage = event.packageName?.toString()
        // Never record OR cache Calo's own UI, the resolved launcher, or
        // systemui (notification shade / quick settings / recents — a real
        // device session can easily pick up a stray swipe-down here that was
        // never part of the taught flow). See TeachPackageFilter's doc and
        // this class's [launcherPackageName] section for why each is
        // excluded, and TargetPackageTracker for what happens to whatever
        // survives this filter.
        if (TeachPackageFilter.isExcluded(eventPackage, ownPackageName, launcherPackageName)) {
            currentRoot?.recycle()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Doesn't return early — currentRoot is only borrowed here
            // (still needed below, whether or not this resolves anything),
            // and a WINDOW_STATE_CHANGED still needs the normal null-source
            // handling afterwards (it usually IS a valid signal on its own,
            // just not one TeachRecorder otherwise acts on — see the `when`
            // block's `else -> Unit`).
            tryResolvePostTypingTap(currentRoot, eventPackage)
        }

        val source = event.source
        if (source == null) {
            // Confirmed on a real device (2026-09-23): AccessibilityEvent
            // .source is reliably null — not the occasional flake it was
            // once assumed to be — for TYPE_VIEW_CLICKED on Settings'
            // Preference/RecyclerView-based rows (and, confirmed 24 Sep,
            // Clock's alarm-row digital_clock TextView too). Previously
            // this silently dropped the tap; the only thing left to record
            // was whatever incidental SCROLL followed navigation, producing
            // a flow whose one step was a scroll nobody performed. Recovery
            // only applies to CLICK — SET_TEXT/SCROLL have never been
            // observed with a null source and still fail closed here.
            //
            // recordClickWithoutSource MUST run BEFORE refreshGoodRootCache
            // below, not after — confirmed on-device (24 Sep 2026): a click
            // that opens a dialog (Clock's alarm-time picker) can have its
            // OWN currentRoot already reflect the DIALOG's content by the
            // time this event is processed, not the pre-click screen the
            // tap actually happened on. That dialog content is easily
            // "real" enough (a clock face has plenty of visible text) to
            // pass refreshGoodRootCache's check, so refreshing first would
            // silently overwrite the one cache entry recovery needed with
            // exactly the wrong screen, corrupting the cache from within
            // the very event it exists to rescue. Recovery always reads
            // whatever was cached from a STRICTLY EARLIER event; only once
            // it's done does this event's own (possibly already-moved-on)
            // root become eligible to become the new cache, for whatever
            // event comes next.
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                recordClickWithoutSource(event)
            }
            refreshGoodRootCache(currentRoot)
            return
        }
        refreshGoodRootCache(currentRoot)
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    lastTextEntryAnchor = null // a tap ends any in-progress text entry burst
                    lastTextEntryStepIndex = null
                    pendingRawTouch = null // a real CLICK arrived — the Finding 6 fallback isn't needed for this tap
                    latchTargetPackage(eventPackage)
                    val anchor = anchorFor(source)
                    // Separate from the null-source recovery below: even
                    // with a perfectly valid source, a clickable CONTAINER
                    // (a Preference row's LinearLayout, say — confirmed
                    // on-device) often has no text of its OWN, only its
                    // children do — anchorFor(source) then produces a
                    // textless anchor here too. event.text carries the
                    // click's visible label regardless of source-nullness,
                    // so it's used as a display-only fallback (plays no
                    // part in CLICK replay resolution — see
                    // SlotResolver.resolveValue) whenever the anchor alone
                    // isn't nameable; otherwise left null to avoid
                    // duplicating data the anchor already carries.
                    val displayLabel = if (anchor.text.isNullOrBlank()) {
                        event.text.orEmpty().map { it.toString() }.firstOrNull { it.isNotBlank() }
                    } else null
                    steps += FlowStep(order = nextOrder++, action = ActionType.CLICK, target = anchor, recordedValue = displayLabel)
                    logIfLowConfidenceClick(anchor, "real CLICK event")
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // Same reasoning as the SCROLL branch's pendingRawTouch
                    // clear: SET_TEXT's own anchor is self-sufficient for
                    // replay (ACTION_SET_TEXT doesn't need a prior tap), so
                    // a pending raw-touch-down candidate surviving to typing
                    // would only add a spurious duplicate CLICK step.
                    pendingRawTouch = null
                    latchTargetPackage(eventPackage)
                    val typed = event.text?.joinToString(separator = "") ?: ""

                    // Identity-only anchor for "is this the same field as
                    // the step we're already recording" — built the normal
                    // way, then compared by TextEntryCoalescer with `text`
                    // ignored (it's exactly the field that changes every
                    // keystroke, so it can't be part of a same-node check).
                    val liveAnchor = anchorFor(source)
                    val existingIndex = lastTextEntryStepIndex

                    if (existingIndex != null && TextEntryCoalescer.isSameFieldEntry(liveAnchor, lastTextEntryAnchor)) {
                        // Another keystroke into the SAME field entry as the
                        // immediately-preceding step (no CLICK/SCROLL or
                        // different node since) — update the value in
                        // place rather than appending a near-duplicate
                        // step. The anchor (captured from the FIRST
                        // keystroke's beforeText, below) is left untouched.
                        steps[existingIndex] = steps[existingIndex].copy(recordedValue = typed)
                    } else {
                        // node.text at this point is what was just TYPED
                        // (post-edit) — using that as the anchor's `text`
                        // tier would mean NodeWalker later searches replay's
                        // live screen for a field that already contains the
                        // typed value, which is never true before this step
                        // runs. event.beforeText is the field's PRE-edit
                        // state, which IS what the live screen looks like
                        // right before this step replays — that's the
                        // stable characteristic to anchor on, and since
                        // this is the FIRST keystroke of a new entry (the
                        // coalescing branch above handles every keystroke
                        // after it), beforeText here really is the field's
                        // untouched pre-edit state, not a later partial
                        // value. A blank beforeText ("", the common case
                        // for an untouched field) isn't a useful match key
                        // on its own — likely several blank fields on one
                        // screen — so it's dropped to null instead, same as
                        // an anchor with no text at all, letting
                        // NodeWalker's existing contentDescription /
                        // className+index fallback tiers do the work
                        // instead of inventing a parallel matching rule.
                        val anchor = liveAnchor.copy(
                            text = event.beforeText?.toString()?.takeIf { it.isNotEmpty() }
                        )
                        steps += FlowStep(
                            order = nextOrder++,
                            action = ActionType.SET_TEXT,
                            target = anchor,
                            recordedValue = typed
                        )
                        lastTextEntryStepIndex = steps.lastIndex
                    }
                    lastTextEntryAnchor = liveAnchor
                }

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val recentTouchDown = lastRawTouchDownAtMs
                    if (recentTouchDown != null && System.currentTimeMillis() - recentTouchDown > SCROLL_NOISE_WINDOW_MS) {
                        // No real touch-down within the window — an auto-
                        // advancing carousel or similar animation-driven
                        // scroll, not something the person actually did.
                        // Dropped entirely, not even coalesce-tracked.
                        Log.i(TAG, "Dropped SCROLL (no touch-down within ${SCROLL_NOISE_WINDOW_MS}ms — likely auto-scroll, not user-initiated)")
                        return
                    }
                    lastTextEntryAnchor = null // a scroll ends any in-progress text entry burst
                    lastTextEntryStepIndex = null
                    // Confirmed on-device (2026-09-26, real-touch Finding 6
                    // fallback test): a drag that turns out to be a SCROLL,
                    // not a tap, never fires a CLICK — so without this, the
                    // pending raw-touch-down candidate from onRawTouchDown
                    // survived to its 300ms commit and got recorded as a
                    // bogus CLICK step for what was actually a scroll. A real
                    // SCROLL is just as much "this touch-down wasn't a tap"
                    // as a real CLICK is "here's what it actually was".
                    pendingRawTouch = null
                    // Deliberately does NOT call latchTargetPackage: a scroll
                    // observed before the first tap (a stray incidental list
                    // scroll while the person is still getting their bearings
                    // on the target screen) must never latch targetPackage on
                    // its own — only a genuine CLICK/SET_TEXT should (Finding
                    // 5 required this be "first user ACTION", not first event
                    // of any kind). See TargetPackageTracker's doc.
                    val anchor = anchorFor(source)
                    val now = System.currentTimeMillis()
                    if (ScrollCoalescer.shouldCoalesce(anchor, lastScrollAnchor, lastScrollAtMs, now)) {
                        // Same burst as the last SCROLL step: extend the
                        // window rather than adding a near-duplicate step.
                        lastScrollAtMs = now
                    } else {
                        steps += FlowStep(order = nextOrder++, action = ActionType.SCROLL, target = anchor)
                        lastScrollAnchor = anchor
                        lastScrollAtMs = now
                    }
                }

                else -> Unit // TYPE_WINDOW_STATE_CHANGED etc. — not a recordable user action
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * Latches [targetPackage] via [TargetPackageTracker] (first non-excluded
     * CLICK/SET_TEXT action wins, never overwritten after) and logs a
     * warning — without changing targetPackage — if a later action turns up
     * in a different package. [actionPackage] is only ever null if called
     * for an event whose packageName was itself null, which can't reach
     * here (onAccessibilityEvent's TeachPackageFilter.isExcluded check
     * already treats a null packageName as excluded and returns early).
     */
    private fun latchTargetPackage(actionPackage: String?) {
        if (actionPackage == null) return
        val outcome = TargetPackageTracker.record(targetPackage, actionPackage)
        targetPackage = outcome.targetPackage
        outcome.warning?.let { Log.w(TAG, it) }
    }

    /**
     * indexInParent is the one field here worth real care: it's the
     * last-resort tier NodeWalker falls back to when an element has no
     * resourceId/text/contentDescription at all (a bare icon button, say),
     * so it's worth computing properly rather than leaving it always null.
     * AccessibilityNodeInfo.equals() compares the underlying node, not
     * instance identity, so `child == node` below correctly identifies
     * which of the parent's children this node is.
     *
     * Always built from the node's own current text. Correct as-is for
     * CLICK and SCROLL, where the node's label is stable and already
     * settled at the moment of the tap/scroll. SET_TEXT's first-keystroke
     * case (above) needs a DIFFERENT text value (event.beforeText, not
     * node.text) for its stored anchor — rather than a parameter here,
     * that call site takes this function's result and `.copy(text = ...)`
     * it, since it also needs the plain, node.text-based version first
     * (as `liveAnchor`) for the same-field identity check.
     */
    private fun anchorFor(node: AccessibilityNodeInfo): ElementAnchor {
        val parent = node.parent
        val indexInParent: Int? = parent?.let { p ->
            var found: Int? = null
            for (i in 0 until p.childCount) {
                val child = p.getChild(i) ?: continue
                if (found == null && child == node) found = i
                child.recycle()
            }
            found
        }
        parent?.recycle()

        return ElementAnchor(
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            indexInParent = indexInParent
        )
    }

    /**
     * Finding 6 fallback refinement (2026-09-26): both raw-touch recovery
     * paths (coordinate-based and post-typing-tap) resolve to a CLICKABLE
     * ANCESTOR that is very often a bare container with no text of its own
     * — confirmed on-device, a real result row's anchor came back with
     * resourceId only, text=null, contentDescription=null, leaving only
     * the weakest (className+indexInParent) tier for replay to re-find it.
     * [fallbackText] lets [tryResolvePostTypingTap] hand over the exact
     * text it already matched on rather than re-deriving it (that text
     * came from the SAME node's subtree by construction); when null
     * (onRawTouchDown's case, where no such text is known upfront) this
     * falls back to [NodeWalker.firstDescendantText]'s own search.
     */
    private fun anchorWithDescendantTextFallback(node: AccessibilityNodeInfo, fallbackText: String? = null): ElementAnchor {
        val anchor = anchorFor(node)
        if (!anchor.text.isNullOrBlank() || !anchor.contentDescription.isNullOrBlank()) return anchor
        return anchor.copy(text = fallbackText ?: nodeWalker.firstDescendantText(node))
    }

    /**
     * Quality flag (2026-09-26), not a filter — a CLICK with none of
     * resourceId/text/contentDescription has only className+indexInParent
     * to re-find it at replay time, the weakest tier NodeWalker.resolve
     * falls back to. Still recorded (this project fails closed on whether
     * to ACT, not on whether to log a concern), just called out here so
     * it's visible in logcat at teach time rather than discovered only
     * when replay can't find the element.
     */
    private fun logIfLowConfidenceClick(anchor: ElementAnchor, source: String) {
        if (anchor.resourceId.isNullOrBlank() && anchor.text.isNullOrBlank() && anchor.contentDescription.isNullOrBlank()) {
            Log.w(TAG, "Low-confidence CLICK anchor (no resourceId/text/contentDescription, className=${anchor.className}) from $source")
        }
    }

    /**
     * Recovery path for the null-`event.source` CLICK case above. Re-finds
     * the tapped element on one of [goodRootHistory]'s cached roots (NOT
     * this event's own `currentRoot` — see [refreshGoodRootCache]'s doc for
     * why that's usually already the wrong, in-transition screen) by
     * matching `event.text` (or `event.contentDescription`, see below)
     * against the live tree, via [NodeWalker.findAllByValue] — the SAME
     * matcher replay's slot-value CLICK search uses
     * (SlotResolver.resolveClickTarget / NodeWalker.findByValue), so a
     * future tweak to the matching rules can't silently drift between the
     * two call sites.
     *
     * Guards run BEFORE any matching, and refuse (drop + log) rather than
     * risk a wrong match, per candidate root:
     *  - staleness: the root's cached-at time older than [MAX_GOOD_ROOT_AGE_MS].
     *  - cross-app/cross-navigation: the root's cached package must equal THIS
     *    event's own `event.packageName` (readable even with a null
     *    source/currentRoot) — otherwise that cache entry is from a different
     *    app or a screen several navigations back, and matching against it
     *    would silently record the wrong element's anchor.
     * Every entry in the (short, [GOOD_ROOT_HISTORY_SIZE]-deep) history is
     * tried, newest first, before giving up — see [goodRootHistory]'s doc for
     * why a single slot isn't enough.
     *
     * `event.text` is a List<CharSequence> of every text segment under the
     * clicked subtree, not one node's literal `.text` — a Preference row's
     * title AND summary arrive as two separate entries (e.g. "Battery",
     * "100%"). Segments are tried in order and the FIRST usable one wins:
     * for a title+summary row this is the title, normally the stable,
     * user-chosen label, while later segments are far more likely to be a
     * live/changing value (a percentage, a price, a timestamp) — see
     * README's known-gaps section (this is a DIFFERENT staleness risk than
     * the cache's: text can go stale across days/sessions between teach and
     * replay, not just within one capture moment).
     *
     * `event.text` is ALWAYS empty for an icon-only toolbar/ActionBar button
     * (confirmed on-device, 2026-09-25: Contacts' nav-drawer hamburger,
     * Chrome's tab-switcher button) — by Android convention such buttons are
     * contentDescription-only, with no visible text at all, so a
     * text-segment-only search always fell through to the "no text segment
     * resolved" drop below even though the click was perfectly recoverable.
     * `event.contentDescription` is the click's own accessibility label
     * regardless of source-nullness (same reasoning as `event.text` above),
     * so it's used as a fallback "segment" whenever `event.text` yields
     * nothing usable — tried against the SAME [nodeWalker.findAllByValue],
     * which already matches a node's text OR contentDescription, so no
     * separate matching path is needed here.
     *
     * Ambiguity (a segment matches more than one element — plausible in a
     * list of similar rows): [event]'s reported className is tried as a
     * disambiguating signal first; if that doesn't narrow it to exactly
     * one, every candidate is logged and the first in traversal order is
     * used. Traversal position of the ORIGINAL (pre-climb) source isn't
     * available here to disambiguate further — RecyclerView-sourced click
     * events don't reliably populate an item index the way AdapterView
     * ones do — so this is the best signal actually available, not a
     * silent coin-flip: the choice is always visible in logcat.
     */
    private fun recordClickWithoutSource(event: AccessibilityEvent) {
        lastTextEntryAnchor = null // a tap (recovered or not) ends any in-progress text entry burst
        lastTextEntryStepIndex = null
        pendingRawTouch = null // a real (if source-null) CLICK arrived — the Finding 6 fallback isn't needed for this tap

        val eventPackage = event.packageName?.toString()
        if (goodRootHistory.isEmpty()) {
            Log.w(TAG, "Dropped CLICK (source null, no cached screen to search): pkg=$eventPackage cls=${event.className}")
            return
        }

        val eventClassName = event.className?.toString()
        val textSegments = event.text.orEmpty().map { it.toString() }.filter { it.isNotBlank() }
        val segments = textSegments.ifEmpty {
            listOfNotNull(event.contentDescription?.toString()?.takeIf { it.isNotBlank() })
        }
        if (segments.isEmpty()) {
            Log.w(TAG, "Dropped CLICK (source null, no text or contentDescription on the event itself): pkg=$eventPackage cls=${event.className}")
            return
        }

        val now = System.currentTimeMillis()
        var triedAnyFreshSameAppRoot = false
        for (cached in goodRootHistory) {
            // goodRootHistory is newest-first (addFirst) — ageMs only grows
            // as iteration proceeds, so once one entry is too stale, every
            // entry after it is guaranteed to be too, and it's safe to stop.
            val ageMs = now - cached.atMs
            if (ageMs > MAX_GOOD_ROOT_AGE_MS) break
            if (cached.packageName != eventPackage) continue // this entry is from a different app/navigation than the click — never a valid match, but a fresher-or-older entry alongside it might still be
            triedAnyFreshSameAppRoot = true

            for (segment in segments) {
                val candidates = nodeWalker.findAllByValue(cached.root, segment)
                if (candidates.isEmpty()) continue

                val chosen = when {
                    candidates.size == 1 -> candidates.first()
                    else -> {
                        val sameClass = candidates.filter { it.className?.toString() == eventClassName }
                        if (sameClass.size == 1) {
                            Log.i(TAG, "Ambiguous CLICK text=\"$segment\" (${candidates.size} candidates) — disambiguated by className=$eventClassName")
                            sameClass.first()
                        } else {
                            Log.w(
                                TAG,
                                "Ambiguous CLICK text=\"$segment\": ${candidates.size} candidates " +
                                    "(${candidates.joinToString { c -> "cls=${c.className} res=${c.viewIdResourceName}" }}), " +
                                    "className disambiguation ${if (sameClass.isEmpty()) "matched none" else "still ambiguous (${sameClass.size})"} " +
                                    "— using the first in traversal order."
                            )
                            candidates.first()
                        }
                    }
                }

                latchTargetPackage(eventPackage)
                // recordedValue carries the matched segment (e.g. "Battery") even
                // though it plays no part in replay resolution for CLICK steps
                // (SlotResolver.resolveValue only ever returns non-null for
                // SET_TEXT) — it exists here purely so this step has a
                // human-readable label. anchorFor(chosen) reads text off the
                // CLICKABLE ANCESTOR, which is very often a container with no
                // text of its own (the actual label belongs to a descendant
                // that's no longer `chosen`), so target.text is frequently null
                // for these steps — without this, MainActivity.promotableLabel()
                // would never treat the step as nameable and the slot-review
                // dialog would silently skip it (confirmed on-device: teaching a
                // Settings row this way went straight to the save dialog, no
                // slot-promotion step offered at all).
                val chosenAnchor = anchorFor(chosen)
                steps += FlowStep(order = nextOrder++, action = ActionType.CLICK, target = chosenAnchor, recordedValue = segment)
                logIfLowConfidenceClick(chosenAnchor, "null-source CLICK recovery")
                candidates.forEach { it.recycle() }
                return
            }
        }
        if (!triedAnyFreshSameAppRoot) {
            Log.w(
                TAG,
                "Dropped CLICK (source null, no cached screen from package \"$eventPackage\" within ${MAX_GOOD_ROOT_AGE_MS}ms — " +
                    "refusing to match across apps/navigations or against stale screens): cls=${event.className}"
            )
        } else {
            Log.w(TAG, "Dropped CLICK (source null, no text or contentDescription segment resolved on any of the ${goodRootHistory.size} cached screens): pkg=$eventPackage cls=${event.className}")
        }
    }

    /**
     * Adopts [root] into [goodRootHistory] if it looks like a real, populated
     * screen (see [MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT] and
     * [countVisibleTextNodes]) — otherwise [root] is simply recycled and the
     * existing history is left alone. This is the ONLY place that writes
     * [goodRootHistory], so the "is it real" check is applied uniformly
     * whether called from a normal event or from [seedRoot].
     *
     * Real-world reason the "is it real" check exists at all: confirmed
     * on-device, a CLICK that triggers navigation can be delivered to this
     * service AFTER `rootInActiveWindow` has already flipped to the
     * destination window — which, mid-transition, can be a genuinely empty
     * stub (observed: `className=android.widget.FrameLayout`, zero visible
     * text). Caching that empty snapshot would have been strictly worse than
     * not caching at all, so it's rejected here rather than adopted.
     */
    private fun refreshGoodRootCache(root: AccessibilityNodeInfo?) {
        if (root == null) return
        if (countVisibleTextNodes(root, limit = MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT + 1) > MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT) {
            goodRootHistory.addFirst(CachedRoot(root, System.currentTimeMillis(), root.packageName?.toString()))
            while (goodRootHistory.size > GOOD_ROOT_HISTORY_SIZE) {
                goodRootHistory.removeLast().root.recycle()
            }
        } else {
            root.recycle()
        }
    }

    /**
     * Counts nodes under (and including) [node] with non-blank text or
     * contentDescription, up to [limit] — never more than that, even if the
     * real count is much higher. Recycles every child it obtains; never
     * recycles [node] itself (borrowed, same convention as NodeWalker).
     *
     * Confirmed on-device (2026-09-26, real ANR — full dropbox trace
     * pulled): the original unbounded version walked the ENTIRE tree via
     * AccessibilityNodeInfo.getChild(), which is a Binder round-trip PER
     * NODE — on Zomato's large, Compose-heavy home screen (carousels,
     * recommendation grids) this froze the main thread long enough for the
     * system to ANR-kill the whole process. The only caller ever checks
     * "is this > MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT" (a single-digit
     * threshold), so counting into the hundreds was always wasted work —
     * this stops descending the instant [limit] is reached. Still visits
     * (and recycles) every DIRECT child at whatever level it stops at, so
     * no node is ever leaked; it just stops recursing deeper.
     */
    private fun countVisibleTextNodes(node: AccessibilityNodeInfo, limit: Int): Int {
        var count = if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) 1 else 0
        for (i in 0 until node.childCount) {
            // Breaks out entirely once the limit is hit, rather than only
            // skipping the recursive descent (2026-09-26, real ANR #2): a
            // wide node — Zomato's home screen flattens large lists/grids
            // into hundreds of direct accessibility children — still paid
            // one getChild() Binder round-trip per remaining sibling even
            // past the limit, which is the same per-node IPC cost the
            // original ANR was caused by, just no longer compounded by
            // depth. Confirmed via a second on-device ANR trace, same
            // stack, after the depth-only fix was already live.
            if (count >= limit) break
            val child = node.getChild(i) ?: continue
            count += countVisibleTextNodes(child, limit - count)
            child.recycle()
        }
        return count
    }

    /**
     * Eager cache seed, called once by CaloAccessibilityService.startTeaching()
     * right as teaching begins — without this, the very FIRST event of a
     * session (which, as confirmed on-device, can itself be a null-source
     * CLICK) would have nothing in [goodRootHistory] to fall back on yet.
     * Subject to the exact same real-content check as any other refresh.
     */
    fun seedRoot(root: AccessibilityNodeInfo?) {
        refreshGoodRootCache(root)
    }

    /** Releases every cached root, if any. Call once a teaching session has ended (saved or discarded) — nothing after that point still needs it. */
    fun release() {
        goodRootHistory.forEach { it.root.recycle() }
        goodRootHistory.clear()
        pendingRawTouch = null
        lastKeyboardHideAtMs = null
    }

    /**
     * Finding 6 fallback, part 2 (2026-09-26): called by
     * CaloAccessibilityService the instant it detects the IME window has
     * gone away (TYPE_INPUT_METHOD no longer in [AccessibilityService.
     * getWindows]). Just records the timestamp — [tryResolvePostTypingTap]
     * (fed by the next TYPE_WINDOW_STATE_CHANGED, if any) decides whether
     * this keyboard-hide was actually caused by a tap worth recovering.
     */
    fun onKeyboardHidden() {
        lastKeyboardHideAtMs = System.currentTimeMillis()
    }

    /**
     * Finding 6 fallback, part 2: recovers a tap that happens WHILE THE
     * KEYBOARD IS STILL UP (a search result row tapped right after typing
     * a query) — TouchInteractionController's raw-coordinate capture is
     * deliberately relinquished for the entire time the keyboard is
     * visible (see CaloAccessibilityService's keyboard-release toggle:
     * touch exploration must NOT be active while the keyboard is up, or
     * real keystrokes get dropped — confirmed on a real device, 2026-09-26,
     * before this toggle existed), so [onRawTouchDown]'s coordinate-based
     * recovery has nothing to work with for this specific tap.
     *
     * Only attempts a resolution when BOTH hold: (1) the keyboard hid
     * recently ([lastKeyboardHideAtMs] within [POST_TYPING_TAP_WINDOW_MS])
     * — this is consumed (reset to null) on this call regardless of
     * outcome, so one keyboard-hide never triggers more than one attempt;
     * (2) a SET_TEXT is still the most recent recordable action
     * ([lastTextEntryStepIndex] non-null) — a CLICK or SCROLL since the
     * typing would already have cleared it, meaning whatever caused THIS
     * WINDOW_STATE_CHANGED is unrelated to the typing and this fallback
     * has no business guessing at it.
     *
     * [newRoot] (the destination screen, borrowed — same convention as
     * every other root this class reads) is reduced to its visible text
     * via [NodeWalker.collectAllText] and matched against the same
     * extraction run on the most recent cached pre-tap root (from
     * [goodRootHistory] — the screen the search results were showing,
     * keyboard still up) via [PostTypingTapResolver]. Exactly one match
     * resolves to a real node (re-run through [NodeWalker.findByValue],
     * which climbs to the nearest clickable ancestor) and becomes a CLICK
     * step; zero or several matches fail closed, logged, nothing recorded.
     */
    private fun tryResolvePostTypingTap(newRoot: AccessibilityNodeInfo?, actionPackage: String?) {
        val hideAtMs = lastKeyboardHideAtMs ?: return
        lastKeyboardHideAtMs = null // one attempt per keyboard-hide, regardless of outcome
        if (lastTextEntryStepIndex == null) return
        if (System.currentTimeMillis() - hideAtMs > POST_TYPING_TAP_WINDOW_MS) return

        val preTapRoot = goodRootHistory.firstOrNull()?.root ?: return
        val newScreenTexts = nodeWalker.collectAllText(newRoot).toSet()
        val preTapCandidates = nodeWalker.collectAllText(preTapRoot)

        when (val outcome = PostTypingTapResolver.resolve(newScreenTexts, preTapCandidates)) {
            is PostTypingTapResolver.Outcome.Matched -> {
                val node = nodeWalker.findByValue(preTapRoot, outcome.text)
                if (node == null) {
                    Log.w(TAG, "Post-typing tap: matched text \"${outcome.text}\" but couldn't re-resolve it to a node — dropped")
                    return
                }
                val anchor = try {
                    anchorWithDescendantTextFallback(node, fallbackText = outcome.text)
                } finally {
                    node.recycle()
                }
                lastTextEntryAnchor = null
                lastTextEntryStepIndex = null
                pendingRawTouch = null
                if (actionPackage != null) latchTargetPackage(actionPackage)
                steps += FlowStep(order = nextOrder++, action = ActionType.CLICK, target = anchor, recordedValue = null)
                logIfLowConfidenceClick(anchor, "post-typing-tap resolution")
                Log.i(TAG, "Committed Finding-6 post-typing-tap CLICK via title match: \"${outcome.text}\"")
            }
            PostTypingTapResolver.Outcome.NoMatch ->
                Log.w(TAG, "Post-typing tap: no title/header text on the new screen matched any pre-tap element — dropped, fail closed")
            is PostTypingTapResolver.Outcome.Ambiguous ->
                Log.w(TAG, "Post-typing tap: ${outcome.matchedTexts.size} pre-tap elements shared the matched text (${outcome.matchedTexts}) — dropped, fail closed")
        }
    }

    /**
     * Finding 6 fallback (2026-09-26): entry point for a raw touch-down
     * coordinate from CaloAccessibilityService's TouchInteractionController
     * capture — the recovery path for taps that never produce ANY
     * classifiable AccessibilityEvent at all (confirmed root cause on
     * Zomato's search bar/search-result taps: not even a null-source CLICK
     * like the [recordClickWithoutSource] case above, no event whatsoever).
     *
     * Does NOT immediately record a step — [root] is a snapshot taken at
     * touch-down, which is exactly when a real click event (if one is
     * coming at all) has not arrived yet, so recording here unconditionally
     * would race a genuine CLICK and could double-record one physical tap.
     * Instead this stores a pending candidate and returns a token; the
     * caller schedules [commitRawTouchIfStillPending] ~300ms later, and
     * whichever real CLICK path runs first (see the `pendingRawTouch = null`
     * lines in the CLICK branches above) invalidates this candidate so the
     * later commit is a no-op.
     *
     * [credentialGateClear] and [keyboardVisible] are computed by the
     * caller (needs CredentialGate + AccessibilityWindowInfo, both Android
     * types this class otherwise has no reason to depend on) and reduced
     * here to a single refusal via [RawTouchCaptureGate] — see its doc for
     * why both are hard refusals, not soft signals.
     *
     * Returns null (nothing pending) if [root] is null, the tap was in an
     * excluded package, gating refused it, or no clickable element resolves
     * at ([x], [y]) — any of which means there is nothing to commit later.
     *
     * Unlike [onAccessibilityEvent]'s `currentRoot` parameter (which this
     * class takes ownership of and recycles), [root] here is only BORROWED
     * — same convention as every NodeWalker function — since the caller
     * (CaloAccessibilityService) needs it for its own CredentialGate check
     * before calling this and must recycle it itself afterwards.
     */
    fun onRawTouchDown(
        x: Int,
        y: Int,
        root: AccessibilityNodeInfo?,
        credentialGateClear: Boolean,
        keyboardVisible: Boolean
    ): Long? {
        lastRawTouchDownAtMs = System.currentTimeMillis()
        if (root == null) return null
        if (TeachPackageFilter.isExcluded(root.packageName?.toString(), ownPackageName, launcherPackageName)) return null
        if (!RawTouchCaptureGate.isCaptureAllowed(credentialGateClear, keyboardVisible)) return null

        val node = nodeWalker.findClickableAtPoint(root, x, y) ?: return null
        val anchor = try {
            anchorWithDescendantTextFallback(node)
        } finally {
            node.recycle()
        }

        val token = nextRawTouchToken++
        pendingRawTouch = PendingRawTouch(anchor, token)
        return token
    }

    /**
     * Commits the pending raw touch from [onRawTouchDown] as a CLICK step,
     * unless it was already invalidated — by a real CLICK arriving since (see
     * the CLICK branches' `pendingRawTouch = null`), or by a newer raw touch
     * superseding it (`pending.token != token`). [actionPackage] is the
     * package the touch-down landed in, used the same way a real CLICK's
     * `event.packageName` is: to latch [targetPackage] if not already set.
     */
    fun commitRawTouchIfStillPending(token: Long, actionPackage: String?) {
        val pending = pendingRawTouch ?: return
        if (pending.token != token) return
        pendingRawTouch = null

        lastTextEntryAnchor = null
        lastTextEntryStepIndex = null
        if (actionPackage != null) latchTargetPackage(actionPackage)
        steps += FlowStep(order = nextOrder++, action = ActionType.CLICK, target = pending.anchor, recordedValue = null)
        logIfLowConfidenceClick(pending.anchor, "raw-touch-down resolution")
        Log.i(TAG, "Committed Finding-6 raw-touch CLICK: resourceId=${pending.anchor.resourceId} text=${pending.anchor.text} contentDescription=${pending.anchor.contentDescription}")
    }

    /** Called by a (future, UI-driven) review step; no-op if stepOrder doesn't exist. */
    fun promoteToSlot(stepOrder: Int, slotName: String) {
        val index = steps.indexOfFirst { it.order == stepOrder }
        if (index == -1) return
        steps[index] = steps[index].copy(slotName = slotName)
    }

    fun currentSteps(): List<FlowStep> = steps.toList()

    fun currentTargetPackage(): String? = targetPackage

    /**
     * Save-guard for a failure class distinct from (and not fixed by) the
     * null-source CLICK recovery above: confirmed on-device (2026-09-25,
     * DocumentsUI's search icon), a click that opens a popup/overlay can
     * fail to reach this service as a TYPE_VIEW_CLICKED event AT ALL — not
     * even with a null source — while the popup's own contents still emit
     * an incidental TYPE_VIEW_SCROLLED once it opens. Without this check,
     * that SCROLL was recorded as the session's only step and saved as if
     * it were the action the person actually performed — wrong data with
     * saved=true and no warning, silently. Root cause (OS event-delivery
     * gap vs. some fixable filtering) is still open; this exists to stop
     * the bad save regardless of which it turns out to be.
     *
     * Same fail-closed shape as the existing steps.isEmpty() checks at both
     * save call sites (MainActivity.beginFinishTeaching,
     * CaloOrchestrator.finishTeaching) — refuse rather than guess, same as
     * this class's null-source CLICK recovery and CredentialGateRules
     * elsewhere in the codebase.
     */
    fun hasRecordedUserAction(): Boolean =
        steps.any { it.action == ActionType.CLICK || it.action == ActionType.SET_TEXT }
}
