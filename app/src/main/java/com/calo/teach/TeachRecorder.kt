package com.calo.teach

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep

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
 */
class TeachRecorder {
    private val steps = mutableListOf<FlowStep>()
    private var nextOrder = 1
    private var targetPackage: String? = null

    fun onAccessibilityEvent(event: AccessibilityEvent, currentRoot: AccessibilityNodeInfo?) {
        val source = event.source ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (targetPackage == null) targetPackage = event.packageName?.toString()
                    steps += FlowStep(order = nextOrder++, action = ActionType.CLICK, target = anchorFor(source))
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (targetPackage == null) targetPackage = event.packageName?.toString()
                    val typed = event.text?.joinToString(separator = "") ?: ""
                    steps += FlowStep(
                        order = nextOrder++,
                        action = ActionType.SET_TEXT,
                        target = anchorFor(source),
                        recordedValue = typed
                    )
                }

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    if (targetPackage == null) targetPackage = event.packageName?.toString()
                    steps += FlowStep(order = nextOrder++, action = ActionType.SCROLL, target = anchorFor(source))
                }

                else -> Unit // TYPE_WINDOW_STATE_CHANGED etc. — not a recordable user action
            }
        } finally {
            source.recycle()
        }
    }

    /**
     * indexInParent is the one field here worth real care: it's the
     * last-resort tier NodeWalker falls back to when an element has no
     * resourceId/text/contentDescription at all (a bare icon button, say),
     * so it's worth computing properly rather than leaving it always null.
     * AccessibilityNodeInfo.equals() compares the underlying node, not
     * instance identity, so `child == node` below correctly identifies
     * which of the parent's children this node is.
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

    /** Called by a (future, UI-driven) review step; no-op if stepOrder doesn't exist. */
    fun promoteToSlot(stepOrder: Int, slotName: String) {
        val index = steps.indexOfFirst { it.order == stepOrder }
        if (index == -1) return
        steps[index] = steps[index].copy(slotName = slotName)
    }

    fun currentSteps(): List<FlowStep> = steps.toList()

    fun currentTargetPackage(): String? = targetPackage
}
