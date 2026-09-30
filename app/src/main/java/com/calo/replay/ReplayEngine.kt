package com.calo.replay

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.accessibility.CaloAccessibilityService
import com.calo.accessibility.CredentialGate
import com.calo.accessibility.NodeWalker
import com.calo.accessibility.ScreenSnapshot
import com.calo.domain.agent.AgentLoop
import com.calo.domain.agent.AgentTask
import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.replay.NodeHandle
import com.calo.domain.replay.NodeProvider
import com.calo.domain.replay.ReplayMode
import com.calo.domain.replay.ReplayPlanner
import com.calo.domain.replay.ReplayResult
import com.calo.domain.semantic.ScreenElement

// requiredClimb: true when NodeWalker had to climb from the actually-matched
// node to a clickable ANCESTOR (see NodeWalker.ResolvedMatch) — a guess
// about which container owns the real click behavior, as opposed to a
// direct hit on a node that was already clickable/editable/scrollable.
// Defaults false for handles that have no equivalent "matched vs climbed"
// distinction (findNodeByValue, screenElements/nodeForElement).
private class AndroidNodeHandle(
    val node: AccessibilityNodeInfo,
    val requiredClimb: Boolean = false,
    // The originally-matched element's bounds (see NodeWalker.ResolvedMatch).
    // Tapped in preference to the climbed ancestor's centre, which can fall
    // outside the element the user actually touched.
    val matchedBounds: android.graphics.Rect? = null
) : NodeHandle

/**
 * :app's implementation of the domain [NodeProvider] interface — the one
 * seam ReplayPlanner (fully unit-tested in :domain, see ReplayPlannerTest)
 * delegates to for anything that touches the live screen. This class has
 * NO sequencing or gate-decision logic of its own; it only resolves nodes
 * and dispatches AccessibilityAction calls. If this class starts growing
 * "if step N then..." branching, that logic belongs in ReplayPlanner, not
 * here — keeping that split is what makes the sequencing testable at all.
 *
 * Merged 2026-09-29: implements the full NodeProvider surface, including
 * the semantic-layer members (screenElements/nodeForElement/
 * submitCurrentInput) that a prior pass here deliberately left as no-ops —
 * ReplayPlanner's EXACT mode now falls back to role-based matching when a
 * step's own anchor can't be found, and SEMANTIC (cross-app) mode depends
 * on these entirely, so this provider needs to support both for real.
 *
 * UNVERIFIED beyond compilation: there is no Android SDK, emulator, or
 * device in the environment this was built in, so nothing below has run
 * against a real AccessibilityNodeInfo. Two things specifically need a
 * real-device check before this is trusted (see README):
 *   1. ACTION_SET_TEXT actually lands text in a real field — some custom
 *      widgets silently don't support it and performAction returns false,
 *      which correctly surfaces as Stuck, but that path has never fired
 *      for real.
 *   2. The credential gate actually halts replay the moment a real
 *      payment/OTP/login screen appears, with zero taps attempted from
 *      that point on. ReplayPlannerTest proves the SEQUENCING logic does
 *      this correctly against a fake screen; it does not prove
 *      CredentialGate's real node-tree-to-ScreenSignals extraction
 *      recognizes an actual payment screen's real AccessibilityNodeInfo
 *      tree the same way.
 *   3. findNodeByValue() / NodeWalker.findBySlotValue() (Task 1, Lane B: a
 *      CLICK step whose slot value differs from what was recorded now
 *      searches by the NEW text/contentDescription, case-insensitively and
 *      trimmed, with a contains-fallback and ambiguity guard, instead of
 *      re-tapping the taught anchor). ReplayPlannerTest and
 *      ClickValueMatcherTest prove the SEQUENCING decision and the actual
 *      matching/ambiguity algorithm respectively, both against fakes; it
 *      does not prove NodeWalker's real tree walk finds the right real
 *      AccessibilityNodeInfo by text/contentDescription.
 *
 * The "no device" note above predates this project's actual device testing
 * (see the many "confirmed on-device" comments elsewhere in this file and
 * class, 24–29 Sep) and is stale for the codebase generally. For
 * SUBMIT_SEARCH/imeEnterApiSupported/nodeSupportsImeEnter/performImeEnter
 * specifically (2026-09-28): verified end-to-end on a real device (API 34)
 * against Zomato's real search field — resolve, ACTION_SET_TEXT, and
 * ACTION_IME_ENTER all confirmed working for real, including the app
 * genuinely navigating to search results with no coordinate-guessing
 * involved. See CaloAccessibilityService.setIncludeNotImportantViewsRequested's
 * doc for a related, separately-discovered gap this feature depends on.
 */
class ReplayEngine(
    private val service: CaloAccessibilityService,
    private val nodeWalker: NodeWalker = NodeWalker(),
    private val credentialGate: CredentialGate = CredentialGate({ service.currentPackageName() })
) : NodeProvider {

    private companion object {
        const val TAG = "Calo"

        // Screen-settle wait after every action (replaces the old fixed
        // 400ms sleep). A tap's effect isn't always visible instantly, so
        // always give it MIN_SETTLE_MS, then poll until two reads in a row
        // show the same screen, giving up after MAX_SETTLE_MS.
        const val MIN_SETTLE_MS = 1000L
        const val SETTLE_POLL_MS = 250L
        const val MAX_SETTLE_MS = 8000L

        // Raised 2026-09-30 after watching replay act on a half-drawn Zomato
        // screen. Zomato renders in WAVES (measured the same day: 5, then 29,
        // then 35 actionable elements across one flow), so two identical reads
        // 250ms apart can land in a lull BETWEEN waves and declare a
        // still-loading screen settled. Requiring several consecutive agreeing
        // reads makes a brief lull insufficient; a genuinely finished screen
        // still exits after STABLE_READS_REQUIRED polls.
        //
        // Budget check against ReplayPlanner.SETTLE_TIMEOUT_MS (15000ms):
        // worst case here is MAX_CHANGE_WAIT_MS 2000 + MIN_SETTLE_MS 1000 +
        // MAX_SETTLE_MS 8000 = 11000ms, leaving ~4s of headroom. Do not raise
        // MAX_SETTLE_MS past 11000 without raising SETTLE_TIMEOUT_MS too.
        const val STABLE_READS_REQUIRED = 3

        // After an action, how long to wait for the screen to start
        // changing before assuming the action doesn't navigate anywhere.
        const val MAX_CHANGE_WAIT_MS = 2000L
    }

    // Screen fingerprint just before the latest action, so the wait after
    // it can tell "already settled" from "hasn't started changing yet".
    private var fingerprintBeforeAction: Int? = null

    // Latest semantic snapshot; nodeForElement() hands out copies from it.
    private var lastSnapshot: ScreenSnapshot? = null

    // Counts actions actually PERFORMED (performClick/performSetText/
    // performScroll calls), not FlowStep.order — a step that goes Stuck
    // before an action is attempted (findNode/findNodeByValue returning
    // null) never increments this and never logs, which is deliberate:
    // this only claims "step N fired" for a step that genuinely did.
    private var stepCounter = 0

    // Perf fix (2026-09-28): ReplayPlanner calls currentScreenSignals()
    // (the gate check) and then, for the same step, findNode()/
    // findNodeByValue() — synchronously, on this thread, with nothing but a
    // pure in-memory CredentialGateRules.classify() call in between (see
    // ReplayPlanner.replay()'s loop body in :domain). There is no yield, no
    // I/O, no chance for the live screen to change between those two calls,
    // so the root fetched for the gate check is exactly as fresh for the
    // node-resolution call that immediately follows it. Caching it here
    // avoids a second rootInActiveWindow() round-trip per step — a real,
    // measured-independent cost (separate from the tree-walk cost inside
    // CredentialGate itself, see its own instrumentation) — without
    // changing what either call sees. Never reused ACROSS steps: every
    // currentScreenSignals() call (always the first call of a new step,
    // per ReplayPlanner) fetches a fresh root and drops/recycles whatever
    // was left over from the previous step first.
    private var cachedRoot: AccessibilityNodeInfo? = null

    /**
     * Blocks while the flow runs (screen waits sleep this thread), so call
     * it off the main thread.
     */
    fun replay(
        steps: List<FlowStep>,
        slotValues: Map<String, String> = emptyMap(),
        mode: ReplayMode = ReplayMode.EXACT
    ): ReplayResult {
        stepCounter = 0
        cachedRoot?.recycle()
        cachedRoot = null
        service.setReplaying(true)
        // See CaloAccessibilityService.setIncludeNotImportantViewsRequested's
        // doc. Tried scoping this to "only when a step's action/role is
        // SUBMIT_SEARCH" twice, on-device (2026-09-28, Zomato), and both
        // attempts broke on a real taught flow: a flow whose search-submit
        // step resolved via an ordinary suggestion-row CLICK never gets
        // labelled SUBMIT_SEARCH by RoleLabeler at all in some cases (goes
        // straight from SEARCH_INPUT to SELECT_RESULT), so the heuristic
        // silently misses it -- yet the EARLIER "open search" step still
        // needs the flag, because that node is also not-important-for-
        // accessibility in Zomato's UI. Teaching has no way to predict in
        // advance which anchors in a flow will turn out to need this, so
        // trying to guess it from role/action at replay time keeps failing
        // the same way. Unconditional for the whole replay() call instead --
        // matches teaching's own unconditional-for-the-whole-session scope,
        // not a per-step guess.
        service.setIncludeNotImportantViewsRequested(true)
        Log.i(TAG, "Replay starting: mode=$mode steps=${steps.size} roles=${steps.map { it.role }}")
        return try {
            // The app may have just been launched (splash screen, feed still
            // loading): let it settle before looking for step 1.
            waitForStableScreen()
            ReplayPlanner.replay(steps, slotValues, this, mode)
        } finally {
            service.setIncludeNotImportantViewsRequested(false)
            lastSnapshot?.release()
            lastSnapshot = null
            service.setReplaying(false)
        }
    }

    /**
     * Hands the rest of a Stuck replay to the AI helper (AgentLoop), from
     * whatever screen replay stopped on. Blocks like [replay]; call it off
     * the main thread. [llm] must block too (NLUClient.complete does).
     */
    fun finishWithAgent(task: AgentTask, stuckAtOrder: Int, llm: (String) -> String?): ReplayResult {
        cachedRoot?.recycle()
        cachedRoot = null
        service.setReplaying(true)
        service.setIncludeNotImportantViewsRequested(true)
        Log.i(TAG, "AI helper starting at step $stuckAtOrder: goal=${task.goal} hints=${task.hints}")
        return try {
            AgentLoop.run(task, stuckAtOrder, this, llm, log = { Log.i(TAG, it) })
        } finally {
            service.setIncludeNotImportantViewsRequested(false)
            lastSnapshot?.release()
            lastSnapshot = null
            service.setReplaying(false)
        }
    }

    override fun screenTexts(): List<String> = nodeWalker.collectAllText(service.currentRoot())

    // No retry loop, unlike findNode/findNodeByValue: the AI is looking at
    // the screen as it is right now, and a miss is reported straight back
    // to it as this turn's outcome rather than silently waited out.
    override fun tapText(text: String): Boolean {
        val node = nodeWalker.findByValue(service.currentRoot(), text) ?: return false
        return performClick(AndroidNodeHandle(node))
    }

    override fun scrollScreen(forward: Boolean): Boolean {
        val node = nodeWalker.findMainScrollable(service.currentRoot()) ?: return false
        return performScroll(AndroidNodeHandle(node), forward)
    }

    override fun pressBack(): Boolean {
        fingerprintBeforeAction = screenFingerprint()
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun describe(info: AccessibilityNodeInfo): String =
        "resId=${info.viewIdResourceName} text=${info.text} cls=${info.className}"

    override fun currentScreenSignals(): ScreenSignals {
        cachedRoot?.recycle()
        val root = service.currentRoot()
        cachedRoot = root
        return credentialGate.extractSignals(root)
    }

    // Consumes the root cached by the currentScreenSignals() call this same
    // step made (see cachedRoot's doc) instead of fetching a second one; if
    // there is no cached root — findNode()/findNodeByValue() called without
    // a preceding currentScreenSignals() this step, which never happens via
    // ReplayPlanner today but this must never silently resolve against a
    // stale/wrong tree if that assumption ever breaks — falls back to a
    // fresh fetch rather than reusing something possibly stale.
    private fun takeCachedRootOrFetch(): AccessibilityNodeInfo? {
        val root = cachedRoot
        cachedRoot = null
        return root ?: service.currentRoot()
    }

    override fun findNode(anchor: ElementAnchor): NodeHandle? {
        val match = nodeWalker.resolve(takeCachedRootOrFetch(), anchor) ?: return null
        if (coversWholeScreen(match.node)) {
            // Confirmed from a real uiautomator dump (2026-09-30, Zomato
            // search results): a taught step anchored to
            // "com.application.zomato:id/root" resolves to the WINDOW ROOT —
            // a non-clickable ViewGroup spanning [0,0][1080,2412]. That is a
            // teach-time capture failure: the anchor identifies the entire
            // screen, i.e. nothing. ACTION_CLICK fails on it, and the
            // coordinate fallback in performClick then taps its centre
            // (always 540,1206), which on a real flow silently navigates
            // somewhere unintended. Reporting "not found" is the honest
            // answer — an anchor that matches everything has matched nothing.
            Log.w(TAG, "Ignoring anchor that resolved to the whole window (${describe(match.node)}) — teach-time capture failure, not a real target")
            match.node.recycle()
            return null
        }
        return AndroidNodeHandle(match.node, match.requiredClimb, match.matchedBounds)
    }

    /** True when [node] spans (nearly) the entire display — i.e. it is the window root. */
    private fun coversWholeScreen(node: AccessibilityNodeInfo): Boolean {
        val metrics = service.resources.displayMetrics
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val nodeArea = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        return screenArea > 0 && nodeArea >= screenArea * 9 / 10
    }

    // NodeWalker.findBySlotValue, not findByValue: this is specifically the
    // CLICK slot-substitution search (SlotResolver.resolveClickTarget), and
    // NodeProvider.findNodeByValue's own contract ("no matching option ->
    // Stuck, never fall back to findNode") depends on findBySlotValue's
    // ClickValueMatcher-based disambiguation (case-insensitive/trimmed exact
    // match first, contains-match only when it narrows to one candidate,
    // ambiguous -> null) — findByValue (used by screenElements'-adjacent
    // semantic code) is a simpler first-match lookup with no such guard and
    // would silently reintroduce the "guess among several matches" risk
    // ReplayPlannerTest's ambiguity tests exist to catch.
    override fun findNodeByValue(value: String): NodeHandle? {
        val node = nodeWalker.findBySlotValue(takeCachedRootOrFetch(), value) ?: return null
        return AndroidNodeHandle(node)
    }

    override fun screenElements(): List<ScreenElement> {
        lastSnapshot?.release()
        val snapshot = nodeWalker.snapshot(service.currentRoot())
        lastSnapshot = snapshot
        Log.d(TAG, "Semantic snapshot: ${snapshot.elements.size} actionable elements")
        return snapshot.elements
    }

    override fun nodeForElement(element: ScreenElement): NodeHandle? {
        Log.i(TAG, "Semantic match: id=${element.id} label=${element.label} cd=${element.contentDescription} resId=${element.resourceId}")
        return lastSnapshot?.nodeCopy(element.id)?.let { AndroidNodeHandle(it) }
    }

    /**
     * Presses the keyboard's action key (Search/Enter) on the focused
     * input. ACTION_IME_ENTER is Android 11+. ACTION_SET_TEXT doesn't
     * always move focus, so an unfocused lone text box is focused first.
     */
    override fun submitCurrentInput(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val root = service.currentRoot() ?: return false
        val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: nodeWalker.snapshot(root).let { snap ->
                val editable = snap.elements.singleOrNull { it.editable }
                val copy = editable?.let { snap.nodeCopy(it.id) }
                snap.release()
                copy?.also { it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }
            ?: return false
        val ok = input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.i(TAG, "Replay: keyboard submit on ${describe(input)} result=$ok")
        input.recycle()
        return ok
    }

    override fun awaitScreenChange() = waitForStableScreen()

    override fun performClick(node: NodeHandle): Boolean {
        val handle = node as AndroidNodeHandle
        val info = handle.node
        stepCounter++
        val before = screenFingerprint()
        fingerprintBeforeAction = before
        Log.i(TAG, "Replay step $stepCounter: CLICK target=${describe(info)}")

        // Compose leaves get a real touch, not ACTION_CLICK (2026-09-30).
        // Zomato's search-result cards are android.view.View with NO
        // resourceId — Jetpack Compose's accessibility shape. They accept
        // ACTION_CLICK, return true, and do nothing, because Compose handles
        // the tap with its own gesture code; the accessibility layer only
        // reports that the action was dispatched, never that it took effect.
        // The detect-and-retry added earlier didn't catch it either: tapping
        // the card dismisses the keyboard, which changes the visible-text
        // fingerprint, so "the screen changed" read as success while the app
        // had not navigated at all.
        //
        // Dispatching the gesture INSTEAD of ACTION_CLICK (rather than after
        // it) is what keeps this safe: exactly one input reaches the app, so
        // there is no double-fire risk on a node that would have honoured
        // ACTION_CLICK anyway. A gesture at the element's own centre is also
        // what a person does, so it works for both kinds of view.
        val composeLeaf = info.viewIdResourceName == null &&
            info.className?.toString() == "android.view.View"
        var ok: Boolean
        if (composeLeaf) {
            // Prefer the ORIGINALLY MATCHED element's bounds over the climbed
            // ancestor's — see NodeWalker.ResolvedMatch.matchedBounds for the
            // (540,405)-vs-card-at-417 miss this fixes.
            val bounds = handle.matchedBounds?.takeIf { !it.isEmpty }
                ?: android.graphics.Rect().also { info.getBoundsInScreen(it) }
            if (!bounds.isEmpty && info.isVisibleToUser) {
                ok = service.tapAt(bounds.exactCenterX(), bounds.exactCenterY())
                Log.i(TAG, "Replay step $stepCounter: CLICK via gesture (Compose leaf) at (${bounds.centerX()},${bounds.centerY()}) result=$ok")
            } else {
                ok = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Replay step $stepCounter: CLICK result=$ok (Compose leaf not tappable by bounds)")
            }
        } else {
            ok = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Replay step $stepCounter: CLICK result=$ok")
        }
        // Widened 2026-09-30 from "only when handle.requiredClimb" to EVERY
        // click that reports success. Confirmed on-device tonight: Zomato's
        // search-result cards are Compose views that are themselves
        // clickable=true (so no climb happens), yet performAction(ACTION_CLICK)
        // returns true while the app does nothing — Compose handles the touch
        // with its own gesture code and the accessibility layer only reports
        // that the action was dispatched. Replay then carried on against a
        // screen that never changed and typed the next step's value into the
        // search bar.
        //
        // Unlike the climbed case (which fails closed), a DIRECT hit can be
        // retried safely with a real finger tap at that same element's own
        // bounds: it is the element we already resolved, not a guess, so this
        // is the same tap by another route rather than a second, different
        // action. The double-fire worry that made the climbed case fail closed
        // doesn't apply the same way here — an action that genuinely took
        // effect (a toggle, add-to-cart) changes the screen fingerprint, so
        // this branch isn't reached for it.
        if (ok && !handle.requiredClimb && !waitForFingerprintChange(before)) {
            val bounds = android.graphics.Rect()
            info.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && info.isVisibleToUser) {
                Log.w(TAG, "Replay step $stepCounter: CLICK reported success but the screen never changed — retrying as a real tap at the same element")
                ok = service.tapAt(bounds.exactCenterX(), bounds.exactCenterY())
                Log.i(TAG, "Replay step $stepCounter: gesture retry at (${bounds.centerX()},${bounds.centerY()}) result=$ok")
                if (ok) waitForFingerprintChange(screenFingerprint())
            }
            info.recycle()
            return ok
        }

        if (ok && handle.requiredClimb) {
            // Confirmed on-device (2026-09-29, Zomato): performAction(ACTION_
            // CLICK) can report true while doing nothing — some views handle
            // taps via custom touch/gesture logic instead of a real
            // OnClickListener, and Android's accessibility layer doesn't
            // reliably distinguish "dispatched" from "actually did the
            // thing". A real replay reported this step's CLICK as
            // successful, then Done. for the whole flow, while silently
            // never leaving the search-results screen — traced to exactly
            // this: NodeWalker had to climb from the matched (non-clickable)
            // text node to a clickable ANCESTOR, and THAT ancestor accepted
            // the action without it doing anything.
            //
            // Only checked when handle.requiredClimb — a DIRECT hit (the
            // matched node was already clickable/editable/scrollable) has no
            // such ambiguity and shouldn't pay this extra wait. Deliberately
            // fails closed (Stuck) rather than retrying via the coordinate-
            // tap fallback below: that fallback exists for when the action
            // is KNOWN to have failed (ok=false), but here the OS claims it
            // succeeded — retrying anyway risks a genuine double-fire on
            // some OTHER click that legitimately doesn't change the screen
            // (a toggle, add-to-cart), which this heuristic has no way to
            // tell apart from "climbed ancestor did nothing". Matches this
            // project's fail-closed philosophy everywhere else (CredentialGate,
            // dedup, click-value ambiguity, post-typing-tap resolution): a
            // click we can't verify actually worked is safer treated as
            // failed than risked as a silent wrong "success" or double-fired.
            if (!waitForFingerprintChange(before)) {
                Log.w(TAG, "Replay step $stepCounter: CLICK reported success on a climbed-to ancestor but the screen never changed — treating as failed, not retrying")
                info.recycle()
                return false
            }
        }
        if (!ok) {
            // Some elements only react to a real touch. Tap its centre.
            val bounds = android.graphics.Rect()
            info.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && info.isVisibleToUser) {
                ok = service.tapAt(bounds.exactCenterX(), bounds.exactCenterY())
                Log.i(TAG, "Replay step $stepCounter: finger-tap fallback at (${bounds.centerX()},${bounds.centerY()}) result=$ok")
            }
        }
        info.recycle()
        return ok
    }

    override fun performSetText(node: NodeHandle, value: String): Boolean {
        val info = (node as AndroidNodeHandle).node
        stepCounter++
        fingerprintBeforeAction = screenFingerprint()
        Log.i(TAG, "Replay step $stepCounter: SET_TEXT target=${describe(info)} value=\"$value\"")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "Replay step $stepCounter: SET_TEXT result=$ok")
        info.recycle()
        return ok
    }

    override fun performScroll(node: NodeHandle, forward: Boolean): Boolean {
        val info = (node as AndroidNodeHandle).node
        stepCounter++
        fingerprintBeforeAction = screenFingerprint()
        Log.i(TAG, "Replay step $stepCounter: SCROLL(forward=$forward) target=${describe(info)}")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = info.performAction(action)
        Log.i(TAG, "Replay step $stepCounter: SCROLL result=$ok")
        info.recycle()
        return ok
    }

    override fun awaitIdle() = waitForStableScreen()

    // ACTION_IME_ENTER (AccessibilityAction, not a legacy ACTION_* int
    // constant) only exists as a field on AccessibilityNodeInfo.AccessibilityAction
    // starting API 30 — referencing it is only safe on a device that's
    // actually API 30+, which is exactly what imeEnterApiSupported() is for.
    // ReplayPlanner is required to check it BEFORE calling either of the two
    // methods below (see its SUBMIT_SEARCH branch); this class doesn't
    // re-check SDK_INT itself here — the ordering contract is ReplayPlanner's
    // job, same as everywhere else it delegates a device-touching call.
    override fun imeEnterApiSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun nodeSupportsImeEnter(node: NodeHandle): Boolean {
        val info = (node as AndroidNodeHandle).node
        return info.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
    }

    override fun performImeEnter(node: NodeHandle): Boolean {
        val info = (node as AndroidNodeHandle).node
        stepCounter++
        fingerprintBeforeAction = screenFingerprint()
        Log.i(TAG, "Replay step $stepCounter: SUBMIT_SEARCH (ACTION_IME_ENTER) target=${describe(info)}")
        val ok = info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.i(TAG, "Replay step $stepCounter: SUBMIT_SEARCH result=$ok")
        info.recycle()
        return ok
    }

    /**
     * Polls until [screenFingerprint] differs from [before], up to
     * MAX_CHANGE_WAIT_MS. Returns whether it actually changed — shared by
     * [waitForStableScreen] (which doesn't otherwise care, it moves on to
     * the settle-poll either way) and [performClick]'s climbed-ancestor
     * verification (which does: no change there means CLICK's reported
     * success can't be trusted — see its own comment).
     */
    private fun waitForFingerprintChange(before: Int): Boolean {
        val changeDeadline = System.currentTimeMillis() + MAX_CHANGE_WAIT_MS
        while (System.currentTimeMillis() < changeDeadline) {
            if (screenFingerprint() != before) return true
            Thread.sleep(SETTLE_POLL_MS / 2)
        }
        return screenFingerprint() != before
    }

    /**
     * Confirmed on-device (Zomato, 27 Sep 2026): the next step started
     * 0.6s after a tap that opened a new page, because the OLD page was
     * still perfectly still for two polls before navigation began. So
     * first wait (up to MAX_CHANGE_WAIT_MS) for the screen to differ from
     * how it looked right before the action; then wait for it to settle.
     */
    private fun waitForStableScreen() {
        val before = fingerprintBeforeAction
        fingerprintBeforeAction = null
        if (before != null) {
            waitForFingerprintChange(before)
        }
        Thread.sleep(MIN_SETTLE_MS)
        val deadline = System.currentTimeMillis() + MAX_SETTLE_MS
        var previous = screenFingerprint()
        var stableReads = 1
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(SETTLE_POLL_MS)
            val current = screenFingerprint()
            if (current == previous) {
                stableReads++
                if (stableReads >= STABLE_READS_REQUIRED) return
            } else {
                stableReads = 1
                previous = current
            }
        }
        Log.w(TAG, "Screen still changing after ${MAX_SETTLE_MS}ms — continuing anyway")
    }

    /** Which app, and all visible text: identical twice in a row = settled. */
    private fun screenFingerprint(): Int {
        val root = service.currentRoot()
        return (root?.packageName?.toString() to nodeWalker.collectAllText(root)).hashCode()
    }
}
