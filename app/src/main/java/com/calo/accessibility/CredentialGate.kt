package com.calo.accessibility

import android.view.accessibility.AccessibilityNodeInfo
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
            collect(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }

        walk(root)

        return ScreenSignals(
            packageName = packageName(),
            allText = allText,
            resourceIds = resourceIds,
            classNames = classNames,
            hasPasswordField = hasPasswordField
        )
    }
}
