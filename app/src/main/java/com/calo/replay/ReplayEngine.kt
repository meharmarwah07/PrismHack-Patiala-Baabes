package com.calo.replay

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.accessibility.CaloAccessibilityService
import com.calo.accessibility.CredentialGate
import com.calo.accessibility.NodeWalker
import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.replay.NodeHandle
import com.calo.domain.replay.NodeProvider
import com.calo.domain.replay.ReplayPlanner
import com.calo.domain.replay.ReplayResult

private class AndroidNodeHandle(val node: AccessibilityNodeInfo) : NodeHandle

/**
 * :app's implementation of the domain [NodeProvider] interface — the one
 * seam ReplayPlanner (fully unit-tested in :domain, see ReplayPlannerTest)
 * delegates to for anything that touches the live screen. This class has
 * NO sequencing or gate-decision logic of its own; it only resolves nodes
 * and dispatches AccessibilityAction calls. If this class starts growing
 * "if step N then..." branching, that logic belongs in ReplayPlanner, not
 * here — keeping that split is what makes the sequencing testable at all.
 *
 * Scope note (2026-09-28): does NOT implement the semantic-layer members
 * of NodeProvider (screenElements/nodeForElement/submitCurrentInput) —
 * those have safe empty/false/no-op defaults on the interface, which is
 * exactly correct here: cross-app "semantic" replay was left out of this
 * integration (see ReplayPlanner's class doc for why), so this provider
 * only ever needs to support ReplayPlanner's EXACT-mode path.
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
        const val MIN_SETTLE_MS = 300L
        const val SETTLE_POLL_MS = 250L
        const val MAX_SETTLE_MS = 4000L

        // After an action, how long to wait for the screen to start
        // changing before assuming the action doesn't navigate anywhere.
        const val MAX_CHANGE_WAIT_MS = 2000L
    }

    // Screen fingerprint just before the latest action, so the wait after
    // it can tell "already settled" from "hasn't started changing yet".
    private var fingerprintBeforeAction: Int? = null

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

    fun replay(steps: List<FlowStep>, slotValues: Map<String, String> = emptyMap()): ReplayResult {
        stepCounter = 0
        cachedRoot?.recycle()
        cachedRoot = null
        service.setReplaying(true)
        Log.i(TAG, "Replay starting: steps=${steps.size}")
        return try {
            // The app may have just been launched (splash screen, feed still
            // loading): let it settle before looking for step 1.
            waitForStableScreen()
            ReplayPlanner.replay(steps, slotValues, this)
        } finally {
            service.setReplaying(false)
        }
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
        val node = nodeWalker.resolve(takeCachedRootOrFetch(), anchor) ?: return null
        return AndroidNodeHandle(node)
    }

    override fun findNodeByValue(value: String): NodeHandle? {
        val node = nodeWalker.findBySlotValue(takeCachedRootOrFetch(), value) ?: return null
        return AndroidNodeHandle(node)
    }

    override fun awaitScreenChange() = waitForStableScreen()

    override fun performClick(node: NodeHandle): Boolean {
        val info = (node as AndroidNodeHandle).node
        stepCounter++
        fingerprintBeforeAction = screenFingerprint()
        Log.i(TAG, "Replay step $stepCounter: CLICK target=${describe(info)}")
        var ok = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Replay step $stepCounter: CLICK result=$ok")
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
            val changeDeadline = System.currentTimeMillis() + MAX_CHANGE_WAIT_MS
            while (System.currentTimeMillis() < changeDeadline && screenFingerprint() == before) {
                Thread.sleep(SETTLE_POLL_MS / 2)
            }
        }
        Thread.sleep(MIN_SETTLE_MS)
        val deadline = System.currentTimeMillis() + MAX_SETTLE_MS
        var previous = screenFingerprint()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(SETTLE_POLL_MS)
            val current = screenFingerprint()
            if (current == previous) return
            previous = current
        }
        Log.w(TAG, "Screen still changing after ${MAX_SETTLE_MS}ms — continuing anyway")
    }

    /** Which app, and all visible text: identical twice in a row = settled. */
    private fun screenFingerprint(): Int {
        val root = service.currentRoot()
        return (root?.packageName?.toString() to nodeWalker.collectAllText(root)).hashCode()
    }
}
