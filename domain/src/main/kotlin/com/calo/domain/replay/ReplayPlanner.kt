package com.calo.domain.replay

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep
import com.calo.domain.slots.SlotResolver

/**
 * The sequencing logic for replaying a taught flow, with zero Android
 * dependencies — everything that touches a real screen goes through
 * [NodeProvider], so this file is what ReplayEngine (in :app) delegates to,
 * and what gets to be unit-tested for real here.
 *
 * Rules this class exists to enforce:
 *
 * 1. The credential gate is evaluated BEFORE every single step, not once at
 *    flow start. A step list can walk into a login/payment screen midway
 *    (session timeout, "add a card" mid-checkout) that wasn't there when
 *    the flow was taught, and this loop has to catch that on every
 *    iteration or the guarantee is worthless. Unconditional, even for a
 *    step about to be skipped as a duplicate below: the "checked before
 *    EVERY step, no exceptions" guarantee must not grow a silent gap for
 *    skipped steps.
 *
 * 2. Added 2026-09-26 after a real teach-time capture bug (a Zomato flow
 *    recorded the same "Domino's Pizza" CLICK anchor 5 times in a row): a
 *    CLICK step whose action AND target are IDENTICAL to the immediately
 *    preceding step is never re-resolved or re-tapped — it's skipped. The
 *    prior assumption ("re-tapping an already-open screen is a no-op") was
 *    never actually tested and doesn't hold in general: the same
 *    resourceId/text can legitimately resolve to a DIFFERENT real element
 *    once the screen has moved on (a generic id/label reused across
 *    screens), so blindly re-resolving a duplicate risks acting on that
 *    new element as if it were still the taught step, not a no-op. One
 *    taught tap is always exactly one FlowStep in this system, so a
 *    consecutive duplicate is never a genuine second user action — it's
 *    always a capture artifact. Scoped to CLICK only: a repeated SCROLL on
 *    the same anchor (e.g. "scroll down twice") is a normal, intentional
 *    taught pattern this must not break.
 *
 * Note on scope (2026-09-28): a cross-app "semantic" replay mode was
 * prototyped on a teammate's branch (RoleMatcher/ScreenElement-based
 * grounding for running a flow on a DIFFERENT app than it was taught on).
 * Deliberately left out of this integration: cross-app generalization is
 * the project's own +4 bonus item, explicitly rejected as an architecture
 * direction in this project's `theme3-eval-criteria-status.md` (25 Sep) —
 * it costs T2's determinism guarantee and weakens T11's fail-closed
 * safety property for the least valuable scored item. The prototype isn't
 * discarded, just not merged into the tested critical path this close to
 * freeze; it remains on that branch if revisited later.
 */
object ReplayPlanner {

    fun replay(
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        provider: NodeProvider
    ): ReplayResult {
        val ordered = steps.sortedBy { it.order }
        var previousStep: FlowStep? = null

        for (step in ordered) {
            // Rule 1 — see class doc. Unconditional, before anything else
            // this iteration, including the duplicate check below.
            val verdict = CredentialGateRules.classify(provider.currentScreenSignals())
            if (verdict is GateVerdict.Blocked) {
                return ReplayResult.Halted(atStepOrder = step.order, reason = verdict.reason)
            }

            if (isDuplicateClick(step, previousStep)) {
                previousStep = step
                continue
            }

            val ok = when (step.action) {
                ActionType.WAIT -> {
                    provider.awaitIdle()
                    true
                }
                ActionType.CLICK -> {
                    // A slot value that genuinely differs from what was
                    // taught means the original anchor now names the WRONG
                    // option (e.g. taught tapping "Home", replaying with
                    // "Work") — re-resolving that anchor would silently tap
                    // the stale element instead of generalizing, so this
                    // searches by the new value instead of using findNode().
                    val searchValue = SlotResolver.resolveClickTarget(step, slotValues)
                    val node = if (searchValue != null) {
                        provider.findNodeByValue(searchValue)
                            ?: return ReplayResult.Stuck(step.order, "couldn't find an option matching '$searchValue'")
                    } else {
                        provider.findNode(step.target)
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                    }
                    provider.performClick(node)
                }
                ActionType.SET_TEXT -> {
                    val node = provider.findNode(step.target)
                        ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                    val value = SlotResolver.resolveValue(step, slotValues)
                        ?: return ReplayResult.Stuck(step.order, "no value to type (should be unreachable for SET_TEXT)")
                    provider.performSetText(node, value)
                }
                ActionType.SCROLL -> {
                    val node = provider.findNode(step.target)
                        ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                    provider.performScroll(node, forward = true)
                }
            }

            if (!ok) {
                // This is the ACTION_SET_TEXT caveat made explicit: if
                // performAction() returns false — e.g. a custom widget that
                // silently doesn't support ACTION_SET_TEXT — this step is
                // Stuck, not silently treated as success.
                return ReplayResult.Stuck(step.order, "action ${step.action} did not apply to resolved node")
            }

            previousStep = step
        }

        return ReplayResult.Completed
    }

    private fun isDuplicateClick(step: FlowStep, previous: FlowStep?): Boolean =
        step.action == ActionType.CLICK &&
            previous?.action == ActionType.CLICK &&
            previous.target == step.target
}
