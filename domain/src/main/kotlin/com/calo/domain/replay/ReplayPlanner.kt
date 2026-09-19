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
 * The one rule this class exists to enforce: the credential gate is
 * evaluated BEFORE every single step, not once at flow start. A step list
 * can walk into a login/payment screen midway (session timeout, "add a
 * card" mid-checkout) that wasn't there when the flow was taught, and this
 * loop has to catch that on every iteration or the guarantee is worthless.
 */
object ReplayPlanner {

    fun replay(
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        provider: NodeProvider
    ): ReplayResult {
        val ordered = steps.sortedBy { it.order }

        for (step in ordered) {
            // Gate check happens first, before resolving or touching any
            // node for this step — a Blocked verdict means this step (and
            // everything after it) is never attempted.
            val verdict = CredentialGateRules.classify(provider.currentScreenSignals())
            if (verdict is GateVerdict.Blocked) {
                return ReplayResult.Halted(atStepOrder = step.order, reason = verdict.reason)
            }

            val ok = when (step.action) {
                ActionType.WAIT -> {
                    provider.awaitIdle()
                    true
                }
                ActionType.CLICK -> {
                    val node = provider.findNode(step.target)
                        ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
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
        }

        return ReplayResult.Completed
    }
}
