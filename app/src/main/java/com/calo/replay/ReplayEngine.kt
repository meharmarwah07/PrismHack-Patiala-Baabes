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
 *   3. findNodeByValue() / NodeWalker.findByValue() (added to fix a
 *      silent-wrong-tap bug: a CLICK step whose slot value differs from
 *      what was recorded now searches by the NEW text/contentDescription
 *      instead of re-tapping the taught anchor). ReplayPlannerTest proves
 *      the SEQUENCING decision — when to search by value vs. re-tap the
 *      anchor, and that a miss is Stuck, never a fallback tap — against a
 *      fake screen; it does not prove NodeWalker's real tree walk finds
 *      the right real AccessibilityNodeInfo by text/contentDescription.
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

        // How long findNode keeps looking for an anchor that isn't on
        // screen yet (a list still loading after the screen "settled").
        // Web pages in a browser routinely take longer than 1.5s to draw
        // their buttons after navigation (skribbl.io's "Play!" was missed
        // at 1.5s, 27 Sep 2026), so this is generous; a genuinely missing
        // element just reports Stuck a few seconds later.
        const val FIND_RETRY_MS = 6000L

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
        service.setReplaying(true)
        Log.i(TAG, "Replay starting: mode=$mode steps=${steps.size} roles=${steps.map { it.role }}")
        return try {
            // The app may have just been launched (splash screen, feed still
            // loading): let it settle before looking for step 1.
            waitForStableScreen()
            ReplayPlanner.replay(steps, slotValues, this, mode)
        } finally {
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
        service.setReplaying(true)
        Log.i(TAG, "AI helper starting at step $stuckAtOrder: goal=${task.goal} hints=${task.hints}")
        return try {
            AgentLoop.run(task, stuckAtOrder, this, llm, log = { Log.i(TAG, it) })
        } finally {
            lastSnapshot?.release()
            lastSnapshot = null
            service.setReplaying(false)
        }
    }

    override fun screenTexts(): List<String> = nodeWalker.collectAllText(service.currentRoot())

    // No retry loop, unlike findNodeByValue: the AI is looking at the screen
    // as it is right now, and a miss is reported straight back to it.
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

    override fun currentScreenSignals(): ScreenSignals =
        credentialGate.extractSignals(service.currentRoot())

    override fun findNode(anchor: ElementAnchor): NodeHandle? =
        pollFor { nodeWalker.resolve(service.currentRoot(), anchor) }?.let { AndroidNodeHandle(it) }

    override fun findNodeByValue(value: String): NodeHandle? =
        pollFor { nodeWalker.findByValue(service.currentRoot(), value) }?.let { AndroidNodeHandle(it) }

    private fun <T : Any> pollFor(find: () -> T?): T? {
        val deadline = System.currentTimeMillis() + FIND_RETRY_MS
        while (true) {
            find()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(SETTLE_POLL_MS)
        }
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
        val info = (node as AndroidNodeHandle).node
        stepCounter++
        fingerprintBeforeAction = screenFingerprint()
        Log.i(TAG, "Replay step $stepCounter: CLICK target=${describe(info)}")
        // Web content (a browser page, Google's search results) often
        // reports ACTION_CLICK as successful without following the link, so
        // the finger-tap fallback below never ran and replay carried on
        // from the wrong page (skribbl.io result on Google, 27 Sep 2026).
        // Inside a web page, tap it like a finger instead.
        if (isInsideWebView(info)) {
            val bounds = android.graphics.Rect()
            info.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && info.isVisibleToUser &&
                service.tapAt(bounds.exactCenterX(), bounds.exactCenterY())
            ) {
                Log.i(TAG, "Replay step $stepCounter: web content, finger-tap at (${bounds.centerX()},${bounds.centerY()})")
                info.recycle()
                return true
            }
        }
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

    /** True if [node] is, or sits inside, a WebView (browsers expose page content this way). [node] is borrowed. */
    private fun isInsideWebView(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.toString() == "android.webkit.WebView") return true
        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth < 40) {
            if (ancestor.className?.toString() == "android.webkit.WebView") {
                ancestor.recycle()
                return true
            }
            val next = ancestor.parent
            ancestor.recycle()
            ancestor = next
            depth++
        }
        ancestor?.recycle()
        return false
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
