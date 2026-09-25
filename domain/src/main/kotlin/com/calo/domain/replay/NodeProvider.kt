package com.calo.domain.replay

import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ElementAnchor

/** Opaque handle to a resolved on-screen element. Real impl wraps AccessibilityNodeInfo. */
interface NodeHandle

/**
 * Everything ReplayPlanner needs from "the live screen", abstracted so the
 * planner's sequencing/gate/retry logic is unit-testable with a fake here,
 * without an Android SDK or a device. :app's ReplayEngine implements this
 * over real AccessibilityNodeInfo (see NodeWalker) — that adapter is NOT
 * covered by these tests, since AccessibilityNodeInfo can't run outside an
 * actual Android process. Everything on THIS side of the interface is.
 */
interface NodeProvider {
    /** Signals for whatever is on screen right now — must be re-read before every step. */
    fun currentScreenSignals(): ScreenSignals

    /** Priority-order resolution: resourceId > text > contentDescription > className+index. Null = not found. */
    fun findNode(anchor: ElementAnchor): NodeHandle?

    /**
     * Finds an on-screen element by text/contentDescription equal to
     * [value] — NOT the step's own anchor. Used only for a CLICK step whose
     * slot value differs from what was recorded at teach time (see
     * SlotResolver.resolveClickTarget): the taught anchor describes the OLD
     * option (e.g. "Home"), so re-resolving it would tap the wrong element;
     * this searches for the NEW one (e.g. "Work") instead. Null = no
     * matching option currently on screen — callers must treat that as
     * Stuck, never fall back to [findNode].
     */
    fun findNodeByValue(value: String): NodeHandle?

    fun performClick(node: NodeHandle): Boolean
    fun performSetText(node: NodeHandle, value: String): Boolean
    fun performScroll(node: NodeHandle, forward: Boolean): Boolean

    /** Blocks/suspends until the screen has settled. No return value — WAIT can't itself fail. */
    fun awaitIdle()
}
