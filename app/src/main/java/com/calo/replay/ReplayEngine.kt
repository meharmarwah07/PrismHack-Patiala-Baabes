package com.calo.replay

import android.os.Bundle
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
 */
class ReplayEngine(
    private val service: CaloAccessibilityService,
    private val nodeWalker: NodeWalker = NodeWalker(),
    private val credentialGate: CredentialGate = CredentialGate({ service.currentPackageName() })
) : NodeProvider {

    fun replay(steps: List<FlowStep>, slotValues: Map<String, String> = emptyMap()): ReplayResult {
        service.setReplaying(true)
        return try {
            ReplayPlanner.replay(steps, slotValues, this)
        } finally {
            service.setReplaying(false)
        }
    }

    override fun currentScreenSignals(): ScreenSignals =
        credentialGate.extractSignals(service.currentRoot())

    override fun findNode(anchor: ElementAnchor): NodeHandle? {
        val node = nodeWalker.resolve(service.currentRoot(), anchor) ?: return null
        return AndroidNodeHandle(node)
    }

    override fun performClick(node: NodeHandle): Boolean {
        val info = (node as AndroidNodeHandle).node
        val ok = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        info.recycle()
        return ok
    }

    override fun performSetText(node: NodeHandle, value: String): Boolean {
        val info = (node as AndroidNodeHandle).node
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        info.recycle()
        return ok
    }

    override fun performScroll(node: NodeHandle, forward: Boolean): Boolean {
        val info = (node as AndroidNodeHandle).node
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = info.performAction(action)
        info.recycle()
        return ok
    }

    override fun awaitIdle() {
        // Simplification for this build pass: a fixed settle delay rather
        // than a real window-content-changed listener. Not verified
        // against a real device — if a screen transition is slower than
        // this, the next step's findNode() will fail to resolve and the
        // planner reports Stuck; it will not silently act on a stale
        // screen, but the flow will incorrectly stop on a slow-loading
        // screen that would otherwise have succeeded. Tune this constant,
        // or replace with a real idle-detection listener, once tested on
        // an actual device against real app transition times.
        Thread.sleep(400)
    }
}
