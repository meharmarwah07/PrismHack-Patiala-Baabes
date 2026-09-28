package com.calo.domain.replay

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep
import com.calo.domain.slots.SlotResolver
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 *
 * Second rule, added 2026-09-26 after a real teach-time capture bug (a
 * Zomato flow recorded the same "Domino's Pizza" CLICK anchor 5 times in a
 * row): a CLICK step whose action AND target are IDENTICAL to the
 * immediately preceding step is never re-resolved or re-tapped — it's
 * skipped. The prior assumption ("re-tapping an already-open screen is a
 * no-op") was never actually tested and doesn't hold in general: the same
 * resourceId/text can legitimately resolve to a DIFFERENT real element
 * once the screen has moved on (a generic id/label reused across screens),
 * so blindly re-resolving a duplicate risks acting on that new element as
 * if it were still the taught step, not a no-op. One taught tap is always
 * exactly one FlowStep in this system, so a consecutive duplicate is never
 * a genuine second user action — it's always a capture artifact. Scoped to
 * CLICK only: a repeated SCROLL on the same anchor (e.g. "scroll down
 * twice") is a normal, intentional taught pattern this must not break.
 */
object ReplayPlanner {

    // Every NodeProvider call is, in the real :app adapter, a synchronous
    // AccessibilityNodeInfo/Binder call into the target app's process — and
    // that IPC has no OS-level timeout of its own. Two confirmed on-device
    // hangs (2026-09-28) showed the step loop can block forever with zero
    // further log output: once on a killed target task (the window the
    // Binder call was querying simply stopped answering), once between two
    // otherwise-instant steps with no distinguishing signal. Every provider
    // call here is run on a worker thread and bounded by STEP_TIMEOUT_MS so
    // a stuck call surfaces as Stuck instead of dead air.
    //
    // Value chosen against tonight's real latency data: normal steps
    // complete near-instantly, and CredentialGate.check() — the most
    // expensive per-step call — takes up to ~350ms on a complex screen.
    // 5000ms is >10x that worst-case normal cost (room for a legitimately
    // slow-but-fine screen transition) while still resolving well inside a
    // live demo's patience, and since a timeout aborts the step immediately
    // (no further provider calls are attempted for that step), 5000ms is
    // also the worst-case total added latency per replay() call, not a
    // multiplier per step.
    private const val STEP_TIMEOUT_MS = 5_000L

    // Binder calls into a dead/killed window are not guaranteed to honor
    // Thread.interrupt() — the underlying IPC can stay blocked in native
    // code indefinitely even after we give up waiting on it here. Running
    // each call on its own worker thread means a genuinely stuck call
    // leaks that one thread rather than the whole replay loop; daemon
    // threads keep that from ever blocking JVM/process shutdown (including
    // the JUnit run itself).
    private val timeoutExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ReplayStepTimeout").apply { isDaemon = true }
    }

    private class StepTimeoutException(
        val stepOrder: Int,
        val isAction: Boolean,
        message: String
    ) : RuntimeException(message)

    // [isAction] marks a call that DOES something on the real device
    // (performClick/performSetText/performScroll) as opposed to one that
    // only reads state (currentScreenSignals/findNode/findNodeByValue/
    // awaitIdle). A timed-out read is simply abandoned — nothing happened.
    // A timed-out action is different: future.cancel(true) below is
    // best-effort against a blocked Binder call (may not actually stop it),
    // so the real tap/type/scroll can still land on the device later,
    // unsupervised, after this function has already told the caller Stuck.
    // See ReplayResult.Stuck.actionMayHaveExecuted's doc for how callers
    // must treat that.
    private fun <T> withStepTimeout(stepOrder: Int, opName: String, isAction: Boolean = false, block: () -> T): T {
        val future = timeoutExecutor.submit(Callable(block))
        return try {
            future.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true) // best-effort; may not actually unblock a stuck Binder call
            throw StepTimeoutException(
                stepOrder,
                isAction,
                "step $stepOrder timed out after ${STEP_TIMEOUT_MS}ms waiting for $opName — target screen may be gone"
            )
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    fun replay(
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        provider: NodeProvider
    ): ReplayResult {
        val ordered = steps.sortedBy { it.order }
        var previousStep: FlowStep? = null

        try {
            for (step in ordered) {
                // Gate check happens first, before resolving or touching any
                // node for this step — a Blocked verdict means this step (and
                // everything after it) is never attempted. Unconditional, even
                // for a step about to be skipped as a duplicate below: the
                // "checked before EVERY step, no exceptions" guarantee must not
                // grow a silent gap for skipped steps.
                val verdict = CredentialGateRules.classify(
                    withStepTimeout(step.order, "currentScreenSignals") { provider.currentScreenSignals() }
                )
                if (verdict is GateVerdict.Blocked) {
                    return ReplayResult.Halted(atStepOrder = step.order, reason = verdict.reason)
                }

                if (isDuplicateClick(step, previousStep)) {
                    previousStep = step
                    continue
                }

                val ok = when (step.action) {
                    ActionType.WAIT -> {
                        withStepTimeout(step.order, "awaitIdle") { provider.awaitIdle() }
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
                            withStepTimeout(step.order, "findNodeByValue") { provider.findNodeByValue(searchValue) }
                                ?: return ReplayResult.Stuck(step.order, "couldn't find an option matching '$searchValue'")
                        } else {
                            withStepTimeout(step.order, "findNode") { provider.findNode(step.target) }
                                ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        }
                        withStepTimeout(step.order, "performClick", isAction = true) { provider.performClick(node) }
                    }
                    ActionType.SET_TEXT -> {
                        val node = withStepTimeout(step.order, "findNode") { provider.findNode(step.target) }
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        val value = SlotResolver.resolveValue(step, slotValues)
                            ?: return ReplayResult.Stuck(step.order, "no value to type (should be unreachable for SET_TEXT)")
                        withStepTimeout(step.order, "performSetText", isAction = true) { provider.performSetText(node, value) }
                    }
                    ActionType.SCROLL -> {
                        val node = withStepTimeout(step.order, "findNode") { provider.findNode(step.target) }
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        withStepTimeout(step.order, "performScroll", isAction = true) { provider.performScroll(node, forward = true) }
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
        } catch (e: StepTimeoutException) {
            return ReplayResult.Stuck(e.stepOrder, e.message ?: "step timed out", actionMayHaveExecuted = e.isAction)
        }
    }

    // Identity-field comparison, not ElementAnchor's data-class == : an
    // anchor can carry fields (e.g. a live-captured surrounding-text label)
    // that legitimately differ between two taps on the same element moments
    // apart, or that are documented as not used to re-find the element at
    // all. Comparing every field would let a real duplicate slip through as
    // "different" on exactly those fields. Spelled out explicitly here
    // rather than relying on ElementAnchor's own equals() so this stays
    // correct regardless of what non-identity fields ElementAnchor picks up
    // later (e.g. from lane-a-teach) — this branch doesn't have those yet,
    // but integration-test will, and this must survive that merge unchanged.
    private fun isDuplicateClick(step: FlowStep, previous: FlowStep?): Boolean =
        step.action == ActionType.CLICK &&
            previous?.action == ActionType.CLICK &&
            previous.target.resourceId == step.target.resourceId &&
            previous.target.text == step.target.text &&
            previous.target.contentDescription == step.target.contentDescription &&
            previous.target.className == step.target.className &&
            previous.target.indexInParent == step.target.indexInParent
}
