package com.calo.domain.replay

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.semantic.PopupRules
import com.calo.domain.semantic.RoleContext
import com.calo.domain.semantic.RoleMatch
import com.calo.domain.semantic.RoleMatcher
import com.calo.domain.semantic.SemanticRole
import com.calo.domain.slots.SlotResolver
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 * Rules this class exists to enforce:
 *
 * 1. The credential gate is evaluated BEFORE every single step, not once at
 *    flow start — and again after anything that changes the screen mid-step
 *    (dismissing a pop-up). A step list can walk into a login/payment screen
 *    midway (session timeout, "add a card" mid-checkout) that wasn't there
 *    when the flow was taught, and this loop has to catch that on every
 *    iteration or the guarantee is worthless. That holds in both modes, and
 *    unconditionally even for a step about to be skipped as a duplicate
 *    capture below — the "checked before EVERY step, no exceptions"
 *    guarantee must not grow a silent gap for skipped steps.
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
 * Merged 2026-09-29: this file used to fork into two incompatible lines —
 * one adding cross-app SEMANTIC replay (RoleMatcher-based grounding, popup
 * dismissal, keyboard-submit-via-role), the other adding the timeout/retry/
 * settle-wait reliability fixes below (see README's dated sections for what
 * each closed and how it was verified on-device). Both are real and both are
 * needed, so this is now one implementation: SEMANTIC mode's step-location
 * logic (locate/locateByRole/dismissPopup) runs underneath the same
 * withStepTimeout/retry/awaitScreenChange infrastructure every step already
 * needed for EXACT mode.
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
    // (performClick/performSetText/performScroll/performImeEnter/
    // submitCurrentInput) as opposed to one that only reads state
    // (currentScreenSignals/findNode/findNodeByValue/awaitIdle/
    // awaitScreenChange/screenElements/nodeForElement). A timed-out read is
    // simply abandoned — nothing happened. A timed-out action is different:
    // future.cancel(true) below is best-effort against a blocked Binder call
    // (may not actually stop it), so the real tap/type/scroll can still land
    // on the device later, unsupervised, after this function has already
    // told the caller Stuck. See ReplayResult.Stuck.actionMayHaveExecuted's
    // doc for how callers must treat that.
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

        return try {
            var previousStep: FlowStep? = null
            for ((i, step) in plan.withIndex()) {
                // Gate check happens first, before resolving or touching any
                // node for this step — a Blocked verdict means this step (and
                // everything after it) is never attempted. Unconditional, even
                // for a step about to be skipped as a duplicate below: the
                // "checked before EVERY step, no exceptions" guarantee must not
                // grow a silent gap for skipped steps.
                gateCheck(step, provider)?.let { return it }

                if (isDuplicateCapture(step, previousStep)) {
                    // Teaching sometimes records one physical tap twice (a real
                    // CLICK event and the raw-touch fallback both committing —
                    // seen on-device in Zomato, 27 Sep). Checked BEFORE calling
                    // runStep, not after a failed resolve: the same resourceId/
                    // text can legitimately resolve to a DIFFERENT real element
                    // once the screen has moved on (a generic id/label reused
                    // across screens), so a duplicate step must never even
                    // attempt to resolve — only "never touched" is actually
                    // safe, not "touched but happened to fail" (2026-09-26,
                    // on-device; this exact risk is why isDuplicateClick was
                    // moved ahead of resolution in the first place).
                    previousStep = step
                    continue
                }

                val result = runStep(step, plan.getOrNull(i + 1), slotValues, query, provider, mode)
                result?.let { return it }
                previousStep = step
            }
            ReplayResult.Completed
        } catch (e: StepTimeoutException) {
            ReplayResult.Stuck(e.stepOrder, e.message ?: "step timed out", actionMayHaveExecuted = e.isAction)
        }
    }

    private fun gateCheck(step: FlowStep, provider: NodeProvider): ReplayResult? {
        val verdict = CredentialGateRules.classify(
            withStepTimeout(step.order, "currentScreenSignals") { provider.currentScreenSignals() }
        )
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
            withStepTimeout(step.order, "awaitIdle") { provider.awaitIdle() }
            return null
        }

        // Capability gate BEFORE resolving anything — a pre-API-30 device
        // can never perform ACTION_IME_ENTER, no matter what's on screen, so
        // there's no point spending a node lookup to find that out.
        if (step.action == ActionType.SUBMIT_SEARCH &&
            !withStepTimeout(step.order, "imeEnterApiSupported") { provider.imeEnterApiSupported() }
        ) {
            return ReplayResult.Stuck(step.order, "IME_ENTER_UNSUPPORTED: ACTION_IME_ENTER needs API 30+, this device is below that")
        }

        var target = locate(step, slotValues, query, provider, mode)

        // One attempt at clearing an interrupting pop-up, then look again.
        // The dismiss tap changes the screen, so the gate runs again before
        // anything else is touched.
        if (target is Target.Missing && dismissPopup(step.order, provider)) {
            gateCheck(step, provider)?.let { return it }
            target = locate(step, slotValues, query, provider, mode)
        }

        val node = when (target) {
            is Target.Found -> target.node
            Target.AlreadySatisfied -> return null
            Target.SubmitViaKeyboard -> {
                if (withStepTimeout(step.order, "submitCurrentInput", isAction = true) { provider.submitCurrentInput() }) {
                    awaitScreenChange(step.order, provider)
                    return null
                }
                // Keyboard submit unsupported (Android < 11) or nothing
                // focused: last resort, a search button.
                val elements = withStepTimeout(step.order, "screenElements") { provider.screenElements() }
                val button = RoleMatcher.match(SemanticRole.OPEN_SEARCH, elements)
                (button as? RoleMatch.Found)?.let {
                    withStepTimeout(step.order, "nodeForElement") { provider.nodeForElement(it.element) }
                } ?: return ReplayResult.Stuck(step.order, "couldn't submit the search")
            }
            is Target.Missing -> return ReplayResult.Stuck(step.order, target.reason)
            is Target.Unsure -> return ReplayResult.Stuck(step.order, "not sure what to tap: ${target.reason} — please do this step yourself")
        }

        // Not all editable fields expose ACTION_IME_ENTER even on a
        // supported API level — only when the resolved node is currently
        // input-focused with an active IME session (Android's own
        // actionList contract). A deliberately diagnosable Stuck, never a
        // fallback tap at a guessed coordinate — see class doc.
        if (step.action == ActionType.SUBMIT_SEARCH &&
            !withStepTimeout(step.order, "nodeSupportsImeEnter") { provider.nodeSupportsImeEnter(node) }
        ) {
            return ReplayResult.Stuck(step.order, "IME_ENTER_UNSUPPORTED: resolved field does not currently expose ACTION_IME_ENTER")
        }

        val ok = when (step.action) {
            ActionType.CLICK -> withStepTimeout(step.order, "performClick", isAction = true) { provider.performClick(node) }
            ActionType.SET_TEXT -> {
                val value = SlotResolver.resolveValue(step, slotValues)
                    ?: return ReplayResult.Stuck(step.order, "no value to type (should be unreachable for SET_TEXT)")
                withStepTimeout(step.order, "performSetText", isAction = true) { provider.performSetText(node, value) }
            }
            ActionType.SCROLL -> withStepTimeout(step.order, "performScroll", isAction = true) { provider.performScroll(node, forward = true) }
            ActionType.SUBMIT_SEARCH -> withStepTimeout(step.order, "performImeEnter", isAction = true) { provider.performImeEnter(node) }
            ActionType.WAIT -> true // handled above, unreachable
        }

        if (!ok && step.action == ActionType.SCROLL) {
            // The list couldn't move (already at the end, or shorter than
            // when taught — confirmed on-device, Zomato 27 Sep). A scroll
            // only exists to reveal the next step's element; whether that
            // element is there is the next step's check, not this one's.
            awaitScreenChange(step.order, provider)
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
            withStepTimeout(step.order, "submitCurrentInput", isAction = true) { provider.submitCurrentInput() }
        }
        awaitScreenChange(step.order, provider)
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
                return withStepTimeout(step.order, "findNodeByValue") { findNodeByValueWithRetry(provider, searchValue) }
                    ?.let { Target.Found(it) }
                    ?: Target.Missing("couldn't find an option matching '$searchValue'")
            }
        }

        val notFound = "element not found: ${step.target}"
        return when (mode) {
            ReplayMode.EXACT -> {
                withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, step.target) }
                    ?.let { return Target.Found(it) }
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
                        withStepTimeout(step.order, "findNode") { findNodeWithRetry(provider, readable) }
                            ?.let { Target.Found(it) } ?: Target.Missing(notFound)
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
        val elements = withStepTimeout(step.order, "screenElements") { provider.screenElements() }

        return when (val match = RoleMatcher.match(role, elements, ctx)) {
            is RoleMatch.Found -> withStepTimeout(step.order, "nodeForElement") { provider.nodeForElement(match.element) }
                ?.let { Target.Found(it) }
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

    private fun dismissPopup(stepOrder: Int, provider: NodeProvider): Boolean {
        val elements = withStepTimeout(stepOrder, "screenElements") { provider.screenElements() }
        val button = PopupRules.findDismissButton(elements) ?: return false
        val node = withStepTimeout(stepOrder, "nodeForElement") { provider.nodeForElement(button) } ?: return false
        if (!withStepTimeout(stepOrder, "performClick", isAction = true) { provider.performClick(node) }) return false
        awaitScreenChange(stepOrder, provider)
        return true
    }

    // Compares identity fields only (the ones NodeWalker's resolver actually
    // searches by — see ElementAnchor's own doc) — not the full ElementAnchor
    // equality. contextLabel is captured live from surrounding screen text at
    // each tap (see ContextPicker) and hintText is documented as "not used to
    // re-find the element"; both can legitimately differ between two taps on
    // the SAME element captured moments apart during a UI transition (e.g. a
    // search bar mid-open), which silently defeated this check for exactly
    // the capture-artifact case it exists to catch (2026-09-28, on-device —
    // confirmed the full-anchor-equality version this replaces missed it).
    // slotName == null: a slot-driven CLICK's real target can legitimately
    // differ from its taught anchor (see locate()'s searchValue branch
    // above), so two consecutive slot steps sharing a literal taught anchor
    // are not the same "capture artifact" this exists to catch.
    private fun isDuplicateCapture(step: FlowStep, previous: FlowStep?): Boolean =
        step.action == ActionType.CLICK &&
            previous?.action == ActionType.CLICK &&
            step.slotName == null &&
            previous.target.resourceId == step.target.resourceId &&
            previous.target.text == step.target.text &&
            previous.target.contentDescription == step.target.contentDescription &&
            previous.target.className == step.target.className &&
            previous.target.indexInParent == step.target.indexInParent
}
