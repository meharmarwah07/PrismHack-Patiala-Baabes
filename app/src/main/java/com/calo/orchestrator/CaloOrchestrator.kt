package com.calo.orchestrator

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.accessibility.CaloAccessibilityService
import com.calo.data.FlowRepository
import com.calo.data.LearnedFlow
import com.calo.domain.agent.AgentTaskBuilder
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import com.calo.domain.nlu.AmbiguityResolver
import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchResult
import com.calo.domain.nlu.MatchStatus
import com.calo.domain.replay.ReplayMode
import com.calo.domain.replay.ReplayResult
import com.calo.domain.replay.StuckAction
import com.calo.domain.replay.StuckAnswerHandler
import com.calo.domain.replay.StuckQuestion
import com.calo.domain.semantic.RoleLabeler
import com.calo.nlu.NLUClient
import com.calo.replay.ReplayEngine
import com.calo.voice.TextToSpeechManager
import com.calo.voice.VoiceInputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Asks the user a yes/no question (e.g. "Did you mean ...?") and reports the
 * answer. The UI supplies this; callers without one (adb debug paths) get
 * the conservative behaviour of not proceeding.
 */
typealias ConfirmPrompt = (question: String, answer: (Boolean) -> Unit) -> Unit

class CaloOrchestrator(context: Context) {

    private companion object {
        const val AI_HELPER_ENABLED = false

        // Safety net added submission night (2026-09-30): ReplayEngine.replay
        // is a plain blocking call (Thread.sleep-based settle polling + raw
        // AccessibilityNodeInfo tree walks) with NO per-step timeout of its
        // own. STEP_TIMEOUT_MS/withStepTimeout exist only on ReplayPlanner
        // (:domain) -- confirmed tonight that class is never constructed
        // anywhere in the app, so it bounds nothing at runtime. Confirmed
        // on-device: a node-tree walk right after "Screen still changing...
        // continuing anyway" hung indefinitely -- zero further log output,
        // no ANR (not a main-thread/system-level freeze, just a wedged
        // background coroutine). This does NOT fix why the walk stalls
        // (most likely: walking a tree Zomato is still actively re-rendering)
        // -- it only guarantees a hang degrades to a spoken "stopped, not sure
        // what happened" instead of hanging forever. Generous on purpose:
        // observed legitimate multi-step runs (settle waits + CredentialGate
        // walks) comfortably run past 30s.
        const val REPLAY_WATCHDOG_MS = 90_000L

        // Below this, the AI's pick is confirmed with the user before
        // anything is tapped. The parser reports 0.0 when the reply had no
        // confidence at all, so a missing value also asks. Superseded in
        // practice by NluMatchEvaluator.MATCH_THRESHOLD (0.8) gating status
        // to MATCHED before this is ever reached — kept as a defensive
        // floor, not the active gate.
        const val CONFIDENCE_THRESHOLD = 0.6

        // launchAndWaitForForeground: how many text/contentDescription-
        // bearing nodes a screen needs before it counts as "actually
        // loaded", not just "the right package is in front". Confirmed
        // on-device (2026-09-29, Zomato, cold start via FLAG_ACTIVITY_
        // CLEAR_TASK): a splash/loading frame reports the correct
        // currentPackageName() and can read as "stable" across two polls
        // well before real content renders (observed nodeCount=9 total,
        // most bare containers with no text at all) — replay proceeded
        // against that stub tree and went Stuck on step 1 immediately,
        // surfaced only as a spoken question easy to miss mid-demo. 5 is
        // comfortably below any real app screen's content-bearing node
        // count while safely above a bare splash/logo frame's.
        const val MIN_CONTENT_NODES_FOR_ARRIVED = 5

        // Fires after the observed median (~1.2s) so a normal-latency call never shows
        // it — only calls that are actually running long get the repeated reassurance.
        const val THINKING_CUE_INTERVAL_MS = 3000L
    }

    private val appContext = context.applicationContext
    private val voice = VoiceInputManager(appContext)
    private val tts = TextToSpeechManager(appContext)
    private val repository = FlowRepository(appContext)
    private val nluClient = NLUClient()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    fun startVoiceCommand(onStatus: (String) -> Unit, onConfirm: ConfirmPrompt? = null) {
        voice.startListening(
            onResult = { utterance -> handleUtterance(utterance, onStatus, onConfirm) },
            onFailure = { reason -> onStatus("Didn't catch that: $reason") }
        )
    }

    /**
     * Matches against flows from EVERY app the user has taught, not just
     * whatever's in front right now — the user may open Calo, speak a
     * command, and expect it to jump to the right app itself. Each
     * LearnedFlow already carries its own targetPackage, so the LLM only
     * has to pick the right flow by meaning; getting to that app's screen
     * is this function's job, via launchAndWaitForForeground below.
     *
     * Known limitation, stated plainly rather than papered over: this
     * launches the target app's default entry point (whatever tapping its
     * icon would open), not the specific screen the flow was taught from.
     * If that screen isn't reachable from the app's default open state,
     * replay will correctly report Stuck (NodeWalker won't find the
     * anchors) rather than silently misfiring — but it won't succeed
     * either. Not yet solved; flagging so it isn't mistaken for "works in
     * all cases."
     */
    internal fun handleUtterance(utterance: String, onStatus: (String) -> Unit, onConfirm: ConfirmPrompt? = null) {
        val service = CaloAccessibilityService.instance
        if (service == null) {
            onStatus("Calo's accessibility service isn't running — enable it in Settings.")
            return
        }

        scope.launch {
            val flows = repository.all()
            if (flows.isEmpty()) {
                onStatus("No flows learned yet.")
                return@launch
            }

            val candidates = flows.map { flow ->
                CandidateFlow(
                    id = flow.id,
                    triggerUtterance = flow.triggerUtterance,
                    description = flow.description,
                    slotNames = flow.slots.map { it.name },
                    appName = appLabel(flow.targetPackage),
                    slotExampleValues = flow.slots.associate { it.name to it.exampleValue }
                )
            }

            // NLUClient's own live latency ranges ~0.5-15s (Groq free-tier queuing) --
            // withThinkingCue re-fires the "thinking" cue onto onStatus every few
            // seconds for as long as the call is actually still in flight, so a
            // long-but-legitimate wait reads as "still working," not silence a judge
            // would call broken. The TTS cue is spoken once up front only (shorter
            // than the shown status text on purpose: the TTS engine's own startup
            // latency eats into the exact window this is meant to cover).
            tts.speak("Thinking...")
            val match = withThinkingCue(onStatus) { nluClient.match(utterance, candidates) }
            // Logged separately from the user-facing status below on
            // purpose: this is the ONLY place the raw NLU decision
            // (matchedFlowId/confidence/slotValues) is visible at all — a
            // failure downstream (wrong flow, wrong slot value, or replay
            // itself going Stuck/Halted) is otherwise undiagnosable from
            // logs alone, since describeResult() only ever shows the FINAL
            // outcome, never what Groq actually returned.
            android.util.Log.d("Calo", "NLU match: matchedFlowId=${match.matchedFlowId} confidence=${match.confidence} slotValues=${match.slotValues} targetApp=${match.targetApp} candidates=${candidates.map { it.id to it.triggerUtterance } }")

            // Every non-MATCHED/NEEDS_SLOT status must dead-end here — replay never
            // starts on an NLU error, an unrecognized command (T12), or an unresolved
            // ambiguity (T13). NEEDS_SLOT and MATCHED both proceed to proceedAsMatched,
            // which does the missing-slot check itself (T14).
            when (match.status) {
                MatchStatus.ERROR -> {
                    onStatus(NLUClient.ERROR_MESSAGE)
                    return@launch
                }
                MatchStatus.NO_MATCH -> {
                    onStatus("I don't know how to do that yet. Want to teach me?")
                    return@launch
                }
                MatchStatus.AMBIGUOUS -> {
                    resolveAmbiguity(service, match, flows, candidates, onStatus)
                    return@launch
                }
                MatchStatus.NEEDS_SLOT, MatchStatus.MATCHED -> Unit // handled below
            }

            proceedAsMatched(service, flows, candidates, match, utterance, onStatus)
        }
    }

    /**
     * Runs [block] while re-firing a "thinking" cue onto [onStatus] every
     * THINKING_CUE_INTERVAL_MS for as long as [block] is still running --
     * not just once before it starts. A single upfront cue only covers the
     * front edge of NLUClient's observed ~0.5-15s live latency spread; past
     * the first interval, silence during a long-but-legitimate wait reads
     * as "broken" rather than "still working" to anyone watching (e.g. a
     * demo judge). The ticker is cancelled the moment [block] returns or
     * throws, via coroutineScope's structured cancellation -- it never
     * outlives the call it's narrating.
     */
    private suspend fun <T> withThinkingCue(onStatus: (String) -> Unit, block: suspend () -> T): T = coroutineScope {
        onStatus("Thinking about that...")
        val ticker = launch {
            while (isActive) {
                delay(THINKING_CUE_INTERVAL_MS)
                onStatus("Still thinking...")
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }

    /**
     * The one path from "we have a MATCHED result" to replay — reached both
     * directly (a confident single match) and after ambiguity resolves to a
     * choice (via AmbiguityResolver, which turns the AMBIGUOUS result into a
     * MATCHED one for its chosen flow). Deliberately the same function for
     * both: keeping a second, simplified copy of the missing-slot check for
     * the post-ambiguity case is exactly how slotValues got silently dropped
     * there before — one path can't drift out of sync with itself.
     */
    private suspend fun proceedAsMatched(
        service: CaloAccessibilityService,
        flows: List<LearnedFlow>,
        candidates: List<CandidateFlow>,
        match: MatchResult,
        utterance: String,
        onStatus: (String) -> Unit
    ) {
        val matchedFlow = flows.find { it.id == match.matchedFlowId }
        if (matchedFlow == null) {
            onStatus("Didn't recognize \"$utterance\" as any learned flow.")
            return
        }

        val matchedCandidate = candidates.find { it.id == matchedFlow.id }
        val missingSlot = matchedCandidate?.slotNames?.firstOrNull { it !in match.slotValues.keys }
        if (missingSlot != null) {
            askForMissingSlot(service, matchedFlow, missingSlot, match.slotValues, onStatus, targetApp = match.targetApp)
            return
        }

        launchAndReplay(service, matchedFlow, match.slotValues, onStatus, targetApp = match.targetApp)
    }

    private fun askForMissingSlot(
        service: CaloAccessibilityService,
        flow: LearnedFlow,
        slotName: String,
        knownSlotValues: Map<String, String>,
        onStatus: (String) -> Unit,
        targetApp: String? = null
    ) {
        onStatus("Which $slotName?")
        voice.startListening(
            onResult = { answer ->
                scope.launch {
                    launchAndReplay(service, flow, knownSlotValues + (slotName to answer), onStatus, targetApp = targetApp)
                }
            },
            onFailure = { reason -> onStatus("Didn't catch that: $reason") }
        )
    }

    private fun resolveAmbiguity(
        service: CaloAccessibilityService,
        match: MatchResult,
        flows: List<LearnedFlow>,
        candidates: List<CandidateFlow>,
        onStatus: (String) -> Unit
    ) {
        val options = match.alternatives.mapNotNull { alt -> flows.find { it.id == alt.flowId } }
        if (options.size < 2) {
            onStatus("That could match more than one learned flow, but I lost track of which — try again.")
            return
        }
        onStatus("Did you mean " + options.joinToString(" or ") { "\"${it.description}\"" } + "?")

        voice.startListening(
            onResult = { answer -> handleAmbiguityAnswer(service, answer, options, match, flows, candidates, onStatus) },
            onFailure = { reason -> onStatus("Didn't catch that: $reason") }
        )
    }

    private fun handleAmbiguityAnswer(
        service: CaloAccessibilityService,
        answer: String,
        options: List<LearnedFlow>,
        originalMatch: MatchResult,
        flows: List<LearnedFlow>,
        candidates: List<CandidateFlow>,
        onStatus: (String) -> Unit
    ) {
        val lower = answer.lowercase()
        val chosen = options.find { opt ->
            lower.contains(opt.description.lowercase()) || lower.contains(opt.triggerUtterance.lowercase())
        } ?: options.find { opt ->
            opt.description.lowercase().split(" ").any { word -> word.length > 3 && lower.contains(word) }
        }

        if (chosen == null) {
            onStatus("Still not sure which one you meant — try naming the app directly.")
            return
        }

        val resolved = AmbiguityResolver.resolve(originalMatch, chosen.id)
        scope.launch {
            proceedAsMatched(service, flows, candidates, resolved, answer, onStatus)
        }
    }

    /**
     * Runs [ReplayEngine.replay] (a blocking call -- see REPLAY_WATCHDOG_MS)
     * off the main thread with a hard ceiling. runInterruptible sends a
     * thread interrupt on timeout, which the engine's Thread.sleep-based
     * settle polling will observe promptly; a raw AccessibilityNodeInfo/
     * binder call mid-flight may or may not honor the interrupt, so this
     * bounds the wait, it doesn't guarantee the underlying call itself
     * stops running. On timeout, actionMayHaveExecuted=true forces the
     * existing "don't retry, just say so" path in handleReplayResult --
     * correct here since we genuinely don't know what step it froze on
     * or whether an action already landed.
     */
    private suspend fun replayWithWatchdog(
        engine: ReplayEngine,
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        mode: ReplayMode = ReplayMode.EXACT
    ): ReplayResult = try {
        withTimeout(REPLAY_WATCHDOG_MS) {
            runInterruptible(Dispatchers.Default) { engine.replay(steps, slotValues, mode) }
        }
    } catch (e: TimeoutCancellationException) {
        android.util.Log.w("Calo", "Replay watchdog: engine.replay didn't return within ${REPLAY_WATCHDOG_MS}ms -- forcing a safe stop")
        ReplayResult.Stuck(
            atStepOrder = steps.lastOrNull()?.order ?: 0,
            reason = "replay watchdog timed out after ${REPLAY_WATCHDOG_MS}ms -- unknown state",
            actionMayHaveExecuted = true
        )
    }

    private suspend fun launchAndReplay(
        service: CaloAccessibilityService,
        flow: LearnedFlow,
        slotValues: Map<String, String>,
        onStatus: (String) -> Unit,
        targetApp: String? = null,
        // What the user actually said, for the AI helper's goal if replay
        // gets stuck. Falls back to the flow's own trigger phrase for call
        // sites (askForMissingSlot's "which X?" answer) that don't have the
        // original command handy — AgentTaskBuilder reads the same either
        // way when the two already match.
        spokenCommand: String = flow.triggerUtterance
    ) {
        val taughtPackage = flow.targetPackage
        val targetPackage = if (targetApp != null) {
            resolvePackageForAppName(targetApp) ?: run {
                onStatus("I couldn't find an app called \"$targetApp\" on this phone.")
                return
            }
        } else {
            taughtPackage
        }
        val mode = if (targetPackage == taughtPackage) ReplayMode.EXACT else ReplayMode.SEMANTIC

        val steps = RoleLabeler.relabel(flow.steps)
        if (mode == ReplayMode.SEMANTIC && steps.none { it.role != null }) {
            onStatus("I learned \"${flow.description}\" on ${appLabel(taughtPackage)}, but it isn't a task I know how to carry over to ${appLabel(targetPackage)}.")
            return
        }

        onStatus("Opening ${appLabel(targetPackage)}...")
        val arrived = launchAndWaitForForeground(service, targetPackage)
        if (!arrived) {
            onStatus("Couldn't bring $targetPackage to the foreground — is it installed?")
            return
        }

        onStatus(
            if (mode == ReplayMode.SEMANTIC) {
                "Doing \"${flow.description}\" on ${appLabel(targetPackage)} (learned on ${appLabel(taughtPackage)})..."
            } else {
                "Replaying: ${flow.description}"
            }
        )
        repository.recordUsage(flow.id)

        val engine = ReplayEngine(service)
        val result = replayWithWatchdog(engine, steps, slotValues, mode)
        handleReplayResult(
            engine, steps, slotValues, result, onStatus, mode = mode,
            flow = flow, spokenCommand = spokenCommand, targetPackage = targetPackage
        )
    }

    /**
     * A Stuck result is never surfaced as a bare "Replay failed": first the
     * AI helper (AgentLoop, :domain) gets ONE silent attempt to finish the
     * task itself from whatever's on screen, taking the taught steps as
     * hints rather than a script it has to match exactly — this is what
     * actually recovers from a flow that drifted somewhere the recorded
     * anchors don't describe (e.g. a web search landing on a results page
     * instead of the taught site), which a same-step retry can't. Gated by
     * [agentEligible] to exactly once per voice command: if the helper also
     * ends up Stuck, control falls through to the existing human flow below
     * rather than trying the AI again.
     *
     * That human flow (Task 2, Lane B) builds a specific question
     * (StuckQuestion, :domain), shows AND speaks it, then takes the answer
     * by voice: "stop" (or a synonym) aborts cleanly, anything else
     * recognized is retried as the new text/label to match for the exact
     * step that got stuck (steps after it then continue normally). A
     * voice-recognition failure or an empty utterance repeats the question
     * once before giving up — StuckAnswerHandler (:domain, unit-tested)
     * makes that decision; this function is just the Android-side glue
     * (TTS + SpeechRecognizer) around it. Completed/Halted results are
     * unaffected either way — Halted (the credential gate) is a final
     * safety stop, never a question, and never handed to the AI helper.
     *
     * Exception (2026-09-28): a Stuck whose [ReplayResult.Stuck.
     * actionMayHaveExecuted] is true came from a timed-out tap/type/scroll
     * that may still land on the device later, unsupervised — see that
     * field's doc. Retrying here (by AI or by voice) would risk a second,
     * uncontrolled action stacking on top of one that might already be in
     * flight, and "should I pick something else, or stop?" is actively
     * misleading when the honest answer is "I don't know what just
     * happened." This case skips both recovery paths entirely and goes
     * straight to a stop, with the uncertainty said out loud rather than
     * papered over.
     */
    private suspend fun handleReplayResult(
        engine: ReplayEngine,
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        result: ReplayResult,
        onStatus: (String) -> Unit,
        attempt: Int = 1,
        mode: ReplayMode = ReplayMode.EXACT,
        flow: LearnedFlow? = null,
        spokenCommand: String? = null,
        targetPackage: String? = null,
        agentEligible: Boolean = true
    ) {
        if (result is ReplayResult.Halted) {
            // Credential-gate halt (T11): final, no retry/question/recovery path.
            // Spoken sentence stays short and free of resourceIds; the technical
            // detail goes to the UI status and logcat only.
            android.util.Log.i("Calo", "Replay halted by credential gate at step ${result.atStepOrder}: ${result.reason}")
            onStatus(describeResult(result))
            tts.speak("This screen is asking for something private, so I'm stopping here and handing control back to you. Your turn — I won't tap anything on this screen.")
            return
        }
        if (result !is ReplayResult.Stuck) {
            onStatus(describeResult(result))
            return
        }

        if (result.actionMayHaveExecuted) {
            val message = "Stopped — the last action may not have completed cleanly, so I'm not retrying automatically."
            onStatus(message)
            tts.speak(message)
            return
        }

        // Off for submission (2026-09-30): on-device the helper ended in a false "done", Groq
        // 429/400/truncation, or a wandering scroll in every run observed, and a wrong tap
        // it made left the app further off course. A stuck step goes straight to the spoken
        // question below instead. Flip to true to re-enable.
        if (AI_HELPER_ENABLED && agentEligible && flow != null && spokenCommand != null && targetPackage != null) {
            onStatus("Step ${result.atStepOrder} didn't match — letting AI finish it...")
            val task = AgentTaskBuilder.from(
                spokenCommand = spokenCommand,
                triggerUtterance = flow.triggerUtterance,
                description = flow.description,
                appName = appLabel(targetPackage),
                steps = steps,
                slotValues = slotValues,
                stuckAtOrder = result.atStepOrder
            )
            val agentResult = withContext(Dispatchers.Default) {
                engine.finishWithAgent(task, result.atStepOrder) { prompt -> nluClient.complete(prompt) }
            }
            handleReplayResult(
                engine, steps, slotValues, agentResult, onStatus, mode = mode,
                flow = flow, spokenCommand = spokenCommand, targetPackage = targetPackage,
                agentEligible = false // one AI attempt per voice command, win or lose
            )
            return
        }

        val stuckStep = steps.find { it.order == result.atStepOrder }
        val question = StuckQuestion.build(stuckStep?.target ?: ElementAnchor(), result.atStepOrder)
        onStatus(question)
        tts.speak(question)

        val outcome = listenForStuckAnswer()
        when (val action = StuckAnswerHandler.handle(outcome, attempt)) {
            StuckAction.Stop -> onStatus("Stopped.")
            StuckAction.Repeat -> handleReplayResult(
                engine, steps, slotValues, result, onStatus, attempt = attempt + 1, mode = mode,
                flow = flow, spokenCommand = spokenCommand, targetPackage = targetPackage, agentEligible = false
            )
            is StuckAction.Retry -> {
                val remaining = steps.filter { it.order >= result.atStepOrder }
                val target = remaining.first()
                // Steps not already carrying a slotName get a synthetic one
                // just for this retry — forces the search-by-value path
                // (SlotResolver.resolveClickTarget / resolveValue) even for
                // a step that was taught as a plain literal.
                val retrySlotName = target.slotName ?: "__stuck_retry_${target.order}"
                val retryStep = if (target.slotName == null) target.copy(slotName = retrySlotName) else target
                val retrySteps = listOf(retryStep) + remaining.drop(1)
                val retrySlotValues = slotValues + (retrySlotName to action.newValue)

                onStatus("Trying \"${action.newValue}\" instead...")
                val retryResult = replayWithWatchdog(engine, retrySteps, retrySlotValues, mode)
                handleReplayResult(
                    engine, retrySteps, retrySlotValues, retryResult, onStatus, attempt = 1, mode = mode,
                    flow = flow, spokenCommand = spokenCommand, targetPackage = targetPackage, agentEligible = false
                )
            }
        }
    }

    private suspend fun listenForStuckAnswer(): StuckAnswerHandler.VoiceOutcome =
        suspendCancellableCoroutine { cont ->
            voice.startListening(
                onResult = { text -> if (cont.isActive) cont.resume(StuckAnswerHandler.VoiceOutcome.Recognized(text)) },
                onFailure = { if (cont.isActive) cont.resume(StuckAnswerHandler.VoiceOutcome.Failed) }
            )
        }

    /**
     * Fires an explicit launch Intent for targetPackage, then polls the
     * accessibility service's own view of the foreground app rather than
     * trusting the launch call succeeded instantly — app cold-starts are
     * not instantaneous, and proceeding to replay against the WRONG
     * foreground app (whatever was in front before the launch finishes)
     * would be exactly the silent-wrong-action failure mode this whole
     * project is built to avoid.
     *
     * Package-name match alone is NOT enough (2026-09-29, confirmed
     * on-device): FLAG_ACTIVITY_CLEAR_TASK forces a genuine cold start,
     * and a cold-started app's first frame (splash/loading skeleton)
     * already reports the correct currentPackageName() well before real
     * content renders — replay's own waitForStableScreen() doesn't catch
     * this either, since two 250ms-apart polls of the same near-empty
     * splash frame look "stable" too. Both layers agreeing "looks fine"
     * on a screen that was never the real one is exactly how step 1 went
     * Stuck immediately after a voice-triggered replay, reported only as
     * a spoken question. See hasSubstantiveContent's doc for the added
     * check.
     */
    private suspend fun launchAndWaitForForeground(
        service: CaloAccessibilityService,
        targetPackage: String,
        timeoutMs: Long = 5000,
        pollIntervalMs: Long = 200
    ): Boolean {
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return false
        // CLEAR_TASK drops the app's existing back stack so it opens at its
        // home screen, like the start of a teaching session, instead of
        // wherever the user last left it.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        appContext.startActivity(launchIntent)

        fun hasArrived(): Boolean {
            if (service.currentPackageName() != targetPackage) return false
            val root = service.currentRoot() ?: return false
            return hasSubstantiveContent(root, MIN_CONTENT_NODES_FOR_ARRIVED) >= MIN_CONTENT_NODES_FOR_ARRIVED
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasArrived()) return true
            delay(pollIntervalMs)
        }
        return hasArrived()
    }

    /**
     * Counts nodes under (and including) [node] with non-blank text or
     * contentDescription, up to [limit] — never more, even if the real
     * count is much higher. Same bounded-traversal shape as TeachRecorder's
     * countVisibleTextNodes for the same reason: this project already got
     * burned twice (2026-09-26) by a version that only bounded recursion
     * DEPTH while still paying one getChild() IPC per remaining sibling
     * past the limit — a wide splash/loading screen ANR'd on that exact
     * mistake. Breaking the loop entirely once the limit is hit, not just
     * skipping the recursive call, is what actually fixed it there; this
     * copies that fix rather than the bug. [node] is borrowed and never
     * recycled here; every child fetched via getChild() is recycled on the
     * way back out regardless of whether it was counted.
     */
    private fun hasSubstantiveContent(node: AccessibilityNodeInfo, limit: Int): Int {
        var count = if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) 1 else 0
        for (i in 0 until node.childCount) {
            if (count >= limit) break
            val child = node.getChild(i) ?: continue
            count += hasSubstantiveContent(child, limit - count)
            child.recycle()
        }
        return count
    }

    /**
     * Finds an installed, launchable app by the name the user said
     * ("Myntra" -> com.myntra.android): exact label match first, then a
     * label containing the name or vice versa. Relies on the manifest's
     * <queries> launcher entry for package visibility on Android 11+.
     */
    private fun resolvePackageForAppName(name: String): String? {
        val wanted = normalizeAppName(name)
        if (wanted.isEmpty()) return null
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to normalizeAppName(it.loadLabel(pm).toString()) }
            .filter { it.first != appContext.packageName }
        return apps.firstOrNull { it.second == wanted }?.first
            ?: apps.firstOrNull { it.second.length >= 3 && (it.second.contains(wanted) || wanted.contains(it.second)) }?.first
    }

    private fun normalizeAppName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun appLabel(packageName: String): String = try {
        val pm = appContext.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        packageName
    }

    /**
     * By the time replay ends the target app is in front, hiding Calo's own
     * status line — so the outcome (especially "Got stuck at step N") is
     * also shown as a toast over whatever app is open.
     */
    private fun announceResult(message: String, onStatus: (String) -> Unit) {
        onStatus(message)
        android.util.Log.i("Calo", "Replay result: $message")
        android.widget.Toast.makeText(appContext, "Calo: $message", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun describeResult(result: ReplayResult): String = when (result) {
        ReplayResult.Completed -> "Done."
        is ReplayResult.Halted -> "Stopped for your safety at step ${result.atStepOrder}: ${result.reason}"
        is ReplayResult.Stuck -> "Got stuck at step ${result.atStepOrder}: ${result.reason}"
    }

    fun startTeaching() {
        CaloAccessibilityService.instance?.startTeaching()
    }

    /**
     * Debug-only convenience: stop + save in one call, no slot review, so
     * DebugTriggerReceiver's FINISH_TEACHING broadcast can drive an
     * end-to-end teach/replay cycle over adb without any UI. slots stays
     * empty on purpose — this is the "exact replay, literal values" path
     * SlotResolver's own comment calls out as T2, not a shortcut that
     * skips something the real UI is supposed to do.
     *
     * The real UI (MainActivity) does NOT call this. It needs a slot-review
     * step between stopping teaching and saving, and stopTeaching() can only
     * be called once per session (it nulls out the service's recorder), so
     * MainActivity calls CaloAccessibilityService.stopTeaching() itself and
     * then calls saveTaughtFlow() below once review is done. Two methods,
     * two disjoint call sites — neither one races the other to stop
     * teaching twice.
     */
    fun finishTeaching(triggerUtterance: String, description: String, onSaved: (success: Boolean) -> Unit) {
        val service = CaloAccessibilityService.instance
        val recorder = service?.stopTeaching()
        if (recorder == null) {
            onSaved(false)
            return
        }
        val steps = recorder.currentSteps()
        val targetPackage = recorder.currentTargetPackage()
        if (steps.isEmpty() || targetPackage.isNullOrBlank()) {
            onSaved(false)
            return
        }
        if (!recorder.hasRecordedUserAction()) {
            // Same guard as MainActivity.beginFinishTeaching — see
            // TeachRecorder.hasRecordedUserAction's doc. Steps here are
            // incidental-only (e.g. a popup's SCROLL with no CLICK/SET_TEXT
            // for what the person actually tapped ever recorded).
            android.util.Log.w("Calo", "Refusing to save: session captured ${steps.size} step(s), none a CLICK/SET_TEXT — likely a click event never reached the service (see TeachRecorder.hasRecordedUserAction)")
            onSaved(false)
            return
        }

        scope.launch {
            repository.save(
                LearnedFlow(
                    targetPackage = targetPackage,
                    triggerUtterance = triggerUtterance,
                    description = description,
                    steps = RoleLabeler.label(steps),
                    slots = emptyList()
                )
            )
            onSaved(true)
        }
    }

    /**
     * Saves a flow whose teaching session was already stopped by the
     * caller (MainActivity, after its slot-review dialog ran against the
     * live TeachRecorder). Takes the already-finalized steps/slots rather
     * than reaching for a recorder itself — there is no recorder left to
     * reach for, since stopTeaching() nulls it out on the service the
     * moment it's called.
     */
    fun saveTaughtFlow(
        steps: List<FlowStep>,
        targetPackage: String,
        slots: List<SlotDefinition>,
        triggerUtterance: String,
        description: String,
        onSaved: (success: Boolean) -> Unit
    ) {
        if (steps.isEmpty() || targetPackage.isBlank()) {
            onSaved(false)
            return
        }

        scope.launch {
            repository.save(
                LearnedFlow(
                    targetPackage = targetPackage,
                    triggerUtterance = triggerUtterance,
                    description = description,
                    // What each step means, so this flow can later be
                    // carried to another app. Doesn't change same-app replay.
                    steps = RoleLabeler.label(steps),
                    slots = slots
                )
            )
            onSaved(true)
        }
    }

    /**
     * Debug-only path: replay whatever flow was most recently saved for the
     * app currently in front, skipping voice capture AND the NLU match
     * entirely. Deliberately still scoped to "whatever app you're standing
     * in" (unlike handleUtterance above) — its whole purpose is exercising
     * ReplayEngine/CredentialGate via adb without a live Groq call, and
     * that's simplest when it doesn't also try to launch anything.
     */
    fun replayLatestFlowForForegroundApp(onStatus: (String) -> Unit) {
        val service = CaloAccessibilityService.instance
        if (service == null) {
            onStatus("Calo's accessibility service isn't running — enable it in Settings.")
            return
        }
        val targetPackage = service.currentPackageName()
        if (targetPackage.isBlank()) {
            onStatus("Can't tell which app is in front right now.")
            return
        }

        scope.launch {
            val flows = repository.flowsForApp(targetPackage)
            val latest = flows.maxByOrNull { it.createdAt }
            if (latest == null) {
                onStatus("No flows learned yet for $targetPackage.")
                return@launch
            }
            onStatus("Replaying (debug, no NLU): ${latest.description}")
            val engine = ReplayEngine(service)
            val result = replayWithWatchdog(engine, RoleLabeler.relabel(latest.steps), emptyMap())
            announceResult(describeResult(result), onStatus)
        }
    }

    /**
     * Task 4/T1 verification tooling (2026-09-26): dumps every saved
     * LearnedFlow's targetPackage and full step list to logcat via
     * [onResult] — a raw-file pull of the Room WAL database proved
     * unreliable to extract off-device, so this reads it the way the app
     * itself does, through the same repository every other code path uses.
     */
    fun dumpAllFlows(onResult: (String) -> Unit) {
        scope.launch {
            val flows = repository.all()
            if (flows.isEmpty()) {
                onResult("No flows saved.")
                return@launch
            }
            val dump = flows.joinToString(separator = "\n---\n") { flow ->
                buildString {
                    appendLine("id=${flow.id} targetPackage=${flow.targetPackage} trigger=\"${flow.triggerUtterance}\" description=\"${flow.description}\" createdAt=${flow.createdAt}")
                    flow.steps.forEach { step ->
                        appendLine(
                            "  step order=${step.order} action=${step.action} role=${step.role} slotName=${step.slotName} recordedValue=${step.recordedValue} " +
                                "anchor(resourceId=${step.target.resourceId}, text=${step.target.text}, contentDescription=${step.target.contentDescription}, " +
                                "className=${step.target.className}, indexInParent=${step.target.indexInParent})"
                        )
                    }
                }
            }
            onResult(dump)
        }
    }

    /** Cleanup tooling (2026-09-26): deletes saved flows by id — used to clear tonight's junk/debug teach sessions before a real re-teach. */
    fun deleteFlowsByIds(ids: Set<String>, onResult: (String) -> Unit) {
        scope.launch {
            val flows = repository.all()
            val toDelete = flows.filter { it.id in ids }
            toDelete.forEach { repository.delete(it) }
            onResult("Deleted ${toDelete.size}/${ids.size} requested flows.")
        }
    }

    /** DEBUG_RESET tooling (2026-09-26): wipes every saved flow for a clean re-teach, no stale/junk data from earlier debugging. */
    fun debugResetAllFlows(onResult: (String) -> Unit) {
        scope.launch {
            repository.deleteAll()
            onResult("All flows wiped.")
        }
    }

    fun shutdown() {
        job.cancel()
        voice.destroy()
        tts.destroy()
    }
}
