package com.calo.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.BuildConfig
import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.gate.ScreenSignals

/**
 * The Android-side half of the gate: walks the whole current-screen node
 * tree ONCE to build a [ScreenSignals], then hands off to the pure,
 * unit-tested [CredentialGateRules] for the actual decision. This class
 * itself has no decision logic — on purpose, so the thing that decides
 * "is this a payment/OTP/login screen" is exactly the code covered by
 * CredentialGateRulesTest, not a second untested copy of similar logic.
 *
 * ReplayEngine calls [check] before EVERY step — see ReplayPlanner's doc
 * for why once-at-flow-start is not good enough.
 */
class CredentialGate(private val packageName: () -> String) {

    fun check(root: AccessibilityNodeInfo?): GateVerdict = CredentialGateRules.classify(extractSignals(root))

    /**
     * root == null (couldn't read the screen at all) maps to
     * readable = false, not to an empty-but-readable ScreenSignals — the
     * fail-closed decision for that case lives in CredentialGateRules
     * (tested in CredentialGateRulesTest), not duplicated here.
     */
    fun extractSignals(root: AccessibilityNodeInfo?): ScreenSignals {
        if (root == null) {
            return ScreenSignals(packageName = packageName(), readable = false)
        }
        val allText = mutableListOf<String>()
        val resourceIds = mutableListOf<String>()
        val classNames = mutableListOf<String>()
        var hasPasswordField = false
        var nodeCount = 0

        fun collect(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let { if (it.isNotBlank()) allText += it }
            node.hintText?.toString()?.let { if (it.isNotBlank()) allText += it }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) allText += it }
            node.viewIdResourceName?.let { resourceIds += it }
            node.className?.toString()?.let { classNames += it }
            if (node.isPassword) hasPasswordField = true
        }

        // Same recycling contract as NodeWalker: root is never recycled
        // here (caller owns it), every child fetched via getChild() is
        // recycled on the way back out regardless of what it contained.
        fun walk(node: AccessibilityNodeInfo) {
            nodeCount++
            collect(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }

        // Debug-only perf instrumentation (2026-09-28): tonight's on-device
        // pass found check() consuming 75-87% of total tap-resolution time
        // (278-493ms) with no breakdown of WHERE inside it that time goes.
        // walkNanos isolates the tree-walk cost (dominated by getChild()'s
        // per-node IPC to the source app) from CredentialGateRules.classify()
        // (pure in-memory keyword matching over the resulting lists, called
        // separately by check()) -- classify() is O(haystacks x keywords)
        // with no IPC at all, so it is not expected to be a meaningful
        // fraction of the total, but this record makes that verifiable
        // on-device instead of assumed. nodeCount lets a real run correlate
        // cost with actual tree size on a given screen. Compiled out of
        // release builds, same as TeachRecorder's raw-event debug logging --
        // this fires on every single gate check (every replay step, every
        // raw touch-down while teaching), far too high-volume to ship even
        // suppressed.
        val walkStartNanos = if (BuildConfig.DEBUG) SystemClock.elapsedRealtimeNanos() else 0L
        walk(root)
        if (BuildConfig.DEBUG) {
            val walkMs = (SystemClock.elapsedRealtimeNanos() - walkStartNanos) / 1_000_000.0
            Log.d(TAG, "CredentialGate.extractSignals: nodeCount=$nodeCount walkMs=$walkMs")
        }

        return ScreenSignals(
            packageName = packageName(),
            allText = allText,
            resourceIds = resourceIds,
            classNames = classNames,
            hasPasswordField = hasPasswordField
        )
    }

    private companion object {
        const val TAG = "CaloCredentialGatePerf"
    }
}
