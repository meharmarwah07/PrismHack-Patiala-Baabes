package com.calo.domain.replay

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
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

    // NodeProvider.awaitScreenChange's own doc says "called after every
    // performed action" — until this fix (2026-09-29), nothing here actually
    // called it: this loop went straight from a successful performClick/
    // performSetText/performScroll/performImeEnter into the NEXT step's gate
    // check and findNode(), with no wait for the tap's own screen transition
    // to even start. Confirmed on-device the same day: a real taught flow
    // (tap search bar -> type -> submit -> tap result) failed at step 2 only
    // ~145ms after step 1's tap reported success — nowhere near enough time
    // for Zomato's search interstitial to finish animating in, so findNode()
    // for step 2's field searched a screen that was still mid-transition.
    // Not an isAction call (it performs nothing on the device, just reads/
    // waits), so a timeout here surfaces as an ordinary Stuck, never
    // actionMayHaveExecuted.
    private fun awaitScreenChange(stepOrder: Int, provider: NodeProvider) {
        withStepTimeout(stepOrder, "awaitScreenChange") { provider.awaitScreenChange() }
    }

    // Confirmed on-device (2026-09-29, Zomato search-as-you-type): a screen
    // backed by a live/staggered content load can render in WAVES — an
    // early, sparser intermediate state can satisfy awaitScreenChange's own
    // stability check (two 250ms-apart reads happening to agree during a
    // brief lull between waves) well before the real target element has
    // actually appeared. README previously claimed findNode "retries for up
    // to 1.5s" — false, confirmed by reading this file: every lookup here
    // was always single-shot before this. A null result is now retried a
    // few times, spaced out, before being treated as genuinely absent —
    // bounded well inside the outer STEP_TIMEOUT_MS backstop, so a
    // genuinely-gone element still fails within the same overall budget.
    private const val FIND_RETRY_INTERVAL_MS = 250L
    private const val FIND_RETRY_WINDOW_MS = 1_500L

    private fun findNodeWithRetry(provider: NodeProvider, anchor: ElementAnchor): NodeHandle? {
        val deadline = System.currentTimeMillis() + FIND_RETRY_WINDOW_MS
        while (true) {
            provider.findNode(anchor)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(FIND_RETRY_INTERVAL_MS)
        }
    }

    private fun findNodeByValueWithRetry(provider: NodeProvider, value: String): NodeHandle? {
        val deadline = System.currentTimeMillis() + FIND_RETRY_WINDOW_MS
        while (true) {
            provider.findNodeByValue(value)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(FIND_RETRY_INTERVAL_MS)
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
                            withStepTimeout(step.order, "findNodeByValue") { findNodeByValueWithRetry(provider, searchValue) }
                                ?: return ReplayResult.Stuck(step.order, "couldn't find an option matching '$searchValue'")
                        } else {
                            withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, step.target) }
                                ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        }
                        withStepTimeout(step.order, "performClick", isAction = true) { provider.performClick(node) }
                            .also { performed -> if (performed) awaitScreenChange(step.order, provider) }
                    }
                    ActionType.SET_TEXT -> {
                        val node = withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, step.target) }
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        val value = SlotResolver.resolveValue(step, slotValues)
                            ?: return ReplayResult.Stuck(step.order, "no value to type (should be unreachable for SET_TEXT)")
                        withStepTimeout(step.order, "performSetText", isAction = true) { provider.performSetText(node, value) }
                            .also { performed -> if (performed) awaitScreenChange(step.order, provider) }
                    }
                    ActionType.SCROLL -> {
                        val node = withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, step.target) }
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        withStepTimeout(step.order, "performScroll", isAction = true) { provider.performScroll(node, forward = true) }
                            .also { performed -> if (performed) awaitScreenChange(step.order, provider) }
                    }
                    ActionType.SUBMIT_SEARCH -> {
                        // Capability gate BEFORE resolving anything — a
                        // pre-API-30 device can never perform this action, no
                        // matter what's on screen, so there's no point
                        // spending a node lookup to find that out.
                        if (!withStepTimeout(step.order, "imeEnterApiSupported") { provider.imeEnterApiSupported() }) {
                            return ReplayResult.Stuck(step.order, "IME_ENTER_UNSUPPORTED: ACTION_IME_ENTER needs API 30+, this device is below that")
                        }
                        val node = withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, step.target) }
                            ?: return ReplayResult.Stuck(step.order, "element not found: ${step.target}")
                        // Not all editable fields expose ACTION_IME_ENTER even
                        // on a supported API level — only when the resolved
                        // node is currently input-focused with an active IME
                        // session (Android's own actionList contract). A
                        // deliberately diagnosable Stuck, never a fallback tap
                        // at a guessed coordinate — see class doc.
                        if (!withStepTimeout(step.order, "nodeSupportsImeEnter") { provider.nodeSupportsImeEnter(node) }) {
                            return ReplayResult.Stuck(step.order, "IME_ENTER_UNSUPPORTED: resolved field does not currently expose ACTION_IME_ENTER")
                        }
                        withStepTimeout(step.order, "performImeEnter", isAction = true) { provider.performImeEnter(node) }
                            .also { performed -> if (performed) awaitScreenChange(step.order, provider) }
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

    // Compares identity fields only (the ones NodeWalker's resolver actually
    // searches by — see ElementAnchor's own doc) — not the full ElementAnchor
    // equality. contextLabel is captured live from surrounding screen text at
    // each tap (see ContextPicker) and hintText is documented as "not used to
    // re-find the element"; both can legitimately differ between two taps on
    // the SAME element captured moments apart during a UI transition (e.g. a
    // search bar mid-open), which silently defeated this check for exactly
    // the capture-artifact case it exists to catch (2026-09-28, on-device).
    private fun isDuplicateClick(step: FlowStep, previous: FlowStep?): Boolean =
        step.action == ActionType.CLICK &&
            previous?.action == ActionType.CLICK &&
            previous.target.resourceId == step.target.resourceId &&
            previous.target.text == step.target.text &&
            previous.target.contentDescription == step.target.contentDescription &&
            previous.target.className == step.target.className &&
            previous.target.indexInParent == step.target.indexInParent
}
