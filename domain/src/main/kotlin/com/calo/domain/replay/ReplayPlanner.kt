package com.calo.domain.replay

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep
import com.calo.domain.semantic.PopupRules
import com.calo.domain.semantic.RoleContext
import com.calo.domain.semantic.RoleMatch
import com.calo.domain.semantic.RoleMatcher
import com.calo.domain.semantic.SemanticRole
import com.calo.domain.slots.SlotResolver

/**
 * How a flow is grounded on the screen.
 *
 * EXACT — the flow runs on the app it was taught on. Each step's recorded
 * anchor is tried first (the precise, proven path); only if that element
 * can't be found does a step with a role fall back to finding "whatever
 * plays that role" (an app update renamed a button id, say).
 *
 * SEMANTIC — the flow runs on a DIFFERENT app. Recorded anchors describe
 * the other app's UI and mean nothing here, so only steps with a role (or a
 * user slot) are run, each grounded by RoleMatcher; steps with no known
 * meaning (dismissing that app's onboarding, an incidental scroll) are
 * skipped.
 */
enum class ReplayMode { EXACT, SEMANTIC }

/**
 * The sequencing logic for replaying a taught flow, with zero Android
 * dependencies — everything that touches a real screen goes through
 * [NodeProvider], so this file is what ReplayEngine (in :app) delegates to,
 * and what gets to be unit-tested for real here.
 *
 * The one rule this class exists to enforce: the credential gate is
 * evaluated BEFORE every single step, not once at flow start — and again
 * after anything that changes the screen mid-step (dismissing a pop-up). A
 * step list can walk into a login/payment screen midway (session timeout,
 * "add a card" mid-checkout) that wasn't there when the flow was taught,
 * and this loop has to catch that on every iteration or the guarantee is
 * worthless. That holds in both modes.
 */
object ReplayPlanner {

    fun replay(
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        provider: NodeProvider,
        mode: ReplayMode = ReplayMode.EXACT
    ): ReplayResult {
        val ordered = steps.sortedBy { it.order }
        val plan = when (mode) {
            ReplayMode.EXACT -> ordered
            ReplayMode.SEMANTIC -> ordered.filter { it.role != null || it.slotName != null }
        }
        if (mode == ReplayMode.SEMANTIC && plan.none { it.role != null }) {
            return ReplayResult.Stuck(
                ordered.firstOrNull()?.order ?: 0,
                "none of this flow's steps are ones Calo knows how to carry over to another app"
            )
        }

        val query = plan.firstOrNull { it.role == SemanticRole.SEARCH_INPUT }
            ?.let { SlotResolver.resolveValue(it, slotValues) }

        for ((i, step) in plan.withIndex()) {
            // Gate check happens first, before resolving or touching any
            // node for this step — a Blocked verdict means this step (and
            // everything after it) is never attempted.
            gateCheck(step, provider)?.let { return it }
            val result = runStep(step, plan.getOrNull(i + 1), slotValues, query, provider, mode)
            if (result is ReplayResult.Stuck && isDuplicateCapture(step, plan.getOrNull(i - 1))) {
                // Teaching sometimes records one physical tap twice (a real
                // CLICK event and the raw-touch fallback both committing —
                // seen on-device in Zomato, 27 Sep). The first copy already
                // navigated away, so the second can't be found: skip it.
                continue
            }
            result?.let { return it }
        }

        return ReplayResult.Completed
    }

    /** Same tap on the same element as the step just before it. */
    private fun isDuplicateCapture(step: FlowStep, previous: FlowStep?): Boolean =
        previous != null && step.action == ActionType.CLICK && previous.action == ActionType.CLICK &&
            step.target == previous.target && step.slotName == null

    private fun gateCheck(step: FlowStep, provider: NodeProvider): ReplayResult? {
        val verdict = CredentialGateRules.classify(provider.currentScreenSignals())
        return if (verdict is GateVerdict.Blocked) {
            ReplayResult.Halted(atStepOrder = step.order, reason = verdict.reason)
        } else {
            null
        }
    }

    /** Null = step done, carry on. Non-null = the replay ends with this result. */
    private fun runStep(
        step: FlowStep,
        next: FlowStep?,
        slotValues: Map<String, String>,
        query: String?,
        provider: NodeProvider,
        mode: ReplayMode
    ): ReplayResult? {
        if (step.action == ActionType.WAIT) {
            provider.awaitIdle()
            return null
        }

        var target = locate(step, slotValues, query, provider, mode)

        // One attempt at clearing an interrupting pop-up, then look again.
        // The dismiss tap changes the screen, so the gate runs again before
        // anything else is touched.
        if (target is Target.Missing && dismissPopup(provider)) {
            gateCheck(step, provider)?.let { return it }
            target = locate(step, slotValues, query, provider, mode)
        }

        val node = when (target) {
            is Target.Found -> target.node
            Target.AlreadySatisfied -> return null
            Target.SubmitViaKeyboard -> {
                if (provider.submitCurrentInput()) {
                    provider.awaitScreenChange()
                    return null
                }
                // Keyboard submit unsupported (Android < 11) or nothing
                // focused: last resort, a search button.
                val button = RoleMatcher.match(SemanticRole.OPEN_SEARCH, provider.screenElements())
                (button as? RoleMatch.Found)?.let { provider.nodeForElement(it.element) }
                    ?: return ReplayResult.Stuck(step.order, "couldn't submit the search")
            }
            is Target.Missing -> return ReplayResult.Stuck(step.order, target.reason)
            is Target.Unsure -> return ReplayResult.Stuck(step.order, "not sure what to tap: ${target.reason} — please do this step yourself")
        }

        val ok = when (step.action) {
            ActionType.CLICK -> provider.performClick(node)
            ActionType.SET_TEXT -> {
                val value = SlotResolver.resolveValue(step, slotValues)
                    ?: return ReplayResult.Stuck(step.order, "no value to type (should be unreachable for SET_TEXT)")
                provider.performSetText(node, value)
            }
            ActionType.SCROLL -> provider.performScroll(node, forward = true)
            ActionType.WAIT -> true // handled above
        }

        if (!ok && step.action == ActionType.SCROLL) {
            // The list couldn't move (already at the end, or shorter than
            // when taught — confirmed on-device, Zomato 27 Sep). A scroll
            // only exists to reveal the next step's element; whether that
            // element is there is the next step's check, not this one's.
            provider.awaitScreenChange()
            return null
        }

        if (!ok) {
            // This is the ACTION_SET_TEXT caveat made explicit: if
            // performAction() returns false — e.g. a custom widget that
            // silently doesn't support ACTION_SET_TEXT — this step is
            // Stuck, not silently treated as success.
            return ReplayResult.Stuck(step.order, "action ${step.action} did not apply to resolved node")
        }

        // The teacher pressed the keyboard's search key (which records no
        // step) and went straight to a result: do the same.
        if (step.role == SemanticRole.SEARCH_INPUT && next?.role == SemanticRole.SELECT_RESULT) {
            provider.submitCurrentInput()
        }
        provider.awaitScreenChange()
        return null
    }

    private sealed class Target {
        data class Found(val node: NodeHandle) : Target()
        data object AlreadySatisfied : Target()
        data object SubmitViaKeyboard : Target()
        data class Missing(val reason: String) : Target()
        data class Unsure(val reason: String) : Target()
    }

    private fun locate(
        step: FlowStep,
        slotValues: Map<String, String>,
        query: String?,
        provider: NodeProvider,
        mode: ReplayMode
    ): Target {
        if (step.action == ActionType.CLICK) {
            // A slot value that genuinely differs from what was taught
            // means the original anchor now names the WRONG option (e.g.
            // taught tapping "Home", replaying with "Work") — re-resolving
            // that anchor would silently tap the stale element instead of
            // generalizing, so this searches by the new value instead.
            val searchValue = SlotResolver.resolveClickTarget(step, slotValues)
            if (searchValue != null) {
                return provider.findNodeByValue(searchValue)?.let { Target.Found(it) }
                    ?: Target.Missing("couldn't find an option matching '$searchValue'")
            }
        }

        val notFound = "element not found: ${step.target}"
        return when (mode) {
            ReplayMode.EXACT -> {
                provider.findNode(step.target)?.let { return Target.Found(it) }
                val role = step.role ?: return Target.Missing(notFound)
                locateByRole(role, step, slotValues, query, provider)
            }
            ReplayMode.SEMANTIC -> {
                val role = step.role
                if (role != null) {
                    locateByRole(role, step, slotValues, query, provider)
                } else {
                    // A slot step with no role: only what a person would
                    // read (text / content description) is meaningful on
                    // another app — never the other app's resource id or
                    // "nth child of that class".
                    val readable = step.target.copy(resourceId = null, className = null, indexInParent = null)
                    if (readable.text == null && readable.contentDescription == null) {
                        Target.Missing(notFound)
                    } else {
                        provider.findNode(readable)?.let { Target.Found(it) } ?: Target.Missing(notFound)
                    }
                }
            }
        }
    }

    private fun locateByRole(
        role: SemanticRole,
        step: FlowStep,
        slotValues: Map<String, String>,
        query: String?,
        provider: NodeProvider
    ): Target {
        val expectedLabel = if (role == SemanticRole.SELECT_RESULT) {
            step.slotName?.let { slotValues[it] } ?: step.target.text ?: step.recordedValue
        } else {
            null
        }
        val ctx = RoleContext(query = query, expectedLabel = expectedLabel, index = step.roleIndex ?: 1)
        val elements = provider.screenElements()

        return when (val match = RoleMatcher.match(role, elements, ctx)) {
            is RoleMatch.Found -> provider.nodeForElement(match.element)?.let { Target.Found(it) }
                ?: Target.Missing("the screen changed while looking for it")
            is RoleMatch.Ambiguous -> Target.Unsure(match.reason)
            is RoleMatch.NotFound -> when {
                // Some apps show the search box straight away — nothing to open.
                role == SemanticRole.OPEN_SEARCH &&
                    RoleMatcher.match(SemanticRole.SEARCH_INPUT, elements, ctx) is RoleMatch.Found ->
                    Target.AlreadySatisfied
                role == SemanticRole.SUBMIT_SEARCH -> Target.SubmitViaKeyboard
                else -> Target.Missing(match.reason)
            }
        }
    }

    private fun dismissPopup(provider: NodeProvider): Boolean {
        val button = PopupRules.findDismissButton(provider.screenElements()) ?: return false
        val node = provider.nodeForElement(button) ?: return false
        if (!provider.performClick(node)) return false
        provider.awaitScreenChange()
        return true
    }
}
