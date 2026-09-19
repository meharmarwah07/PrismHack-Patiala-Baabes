package com.calo.domain.gate

/**
 * Everything CredentialGate (the Android-side adapter in :app) extracts
 * from the current AccessibilityNodeInfo tree, flattened into plain data.
 * This is the seam between "walking real nodes" (untestable here, no
 * Android SDK in this environment) and "deciding whether to block"
 * (100% pure, 100% unit-testable — see CredentialGateRulesTest).
 *
 * allText should include, for every node in the tree: node.text,
 * node.hintText, and node.contentDescription — anything a real screen
 * would show or label a field with. resourceIds and classNames are
 * collected the same way, one entry per node that has one.
 */
data class ScreenSignals(
    val packageName: String,
    val allText: List<String> = emptyList(),
    val resourceIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    // AccessibilityNodeInfo.isPassword is a first-class Android signal —
    // the platform itself is telling us this EditText masks input. This is
    // the single strongest signal we have and is checked before any
    // keyword heuristic.
    val hasPasswordField: Boolean = false,
    // false means "couldn't inspect the screen at all" (e.g. rootInActiveWindow
    // was null) — NOT "inspected it and it's plain". CredentialGateRules
    // fails closed on this: unreadable is always Blocked, never Clear, because
    // "no signal" must never be conflated with "genuinely no sensitive signal."
    val readable: Boolean = true
)
