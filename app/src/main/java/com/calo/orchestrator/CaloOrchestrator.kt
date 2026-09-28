package com.calo.orchestrator

import android.content.Context
import android.content.Intent
import com.calo.accessibility.CaloAccessibilityService
import com.calo.data.FlowRepository
import com.calo.data.LearnedFlow
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import com.calo.domain.nlu.CandidateFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Asks the user a yes/no question (e.g. "Did you mean ...?") and reports the
 * answer. The UI supplies this; callers without one (adb debug paths) get
 * the conservative behaviour of not proceeding.
 */
typealias ConfirmPrompt = (question: String, answer: (Boolean) -> Unit) -> Unit

class CaloOrchestrator(context: Context) {

    private companion object {
        // Below this, the AI's pick is confirmed with the user before
        // anything is tapped. The parser reports 0.0 when the reply had no
        // confidence at all, so a missing value also asks.
        const val CONFIDENCE_THRESHOLD = 0.6
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
                    appName = appLabel(flow.targetPackage)
                )
            }

            // Closes the dead-air gap during the NLU round-trip (observed
            // 0.5-12s) — silence for that long reads as broken on camera
            // for the demo video. Mirrors the onStatus("Thinking about
            // that...") cue Lane C added in their own CaloOrchestrator on
            // lane-c-nlu (that branch has otherwise diverged significantly
            // here — ambiguity resolution, missing-slot prompts — so this
            // is a same-purpose addition on this branch, not a merge of
            // theirs; whoever reconciles the branches will hit this same
            // line twice). The spoken cue is shorter than the shown status
            // text on purpose: the TTS engine's own startup latency eats
            // into the exact window this is meant to cover.
            onStatus("Thinking about that...")
            tts.speak("Thinking...")

            val match = nluClient.match(utterance, candidates)
            // Logged separately from the user-facing status below on
            // purpose: this is the ONLY place the raw NLU decision
            // (matchedFlowId/confidence/slotValues) is visible at all — a
            // failure downstream (wrong flow, wrong slot value, or replay
            // itself going Stuck/Halted) is otherwise undiagnosable from
            // logs alone, since describeResult() only ever shows the FINAL
            // outcome, never what Groq actually returned.
            android.util.Log.d("Calo", "NLU match: matchedFlowId=${match.matchedFlowId} confidence=${match.confidence} slotValues=${match.slotValues} targetApp=${match.targetApp} candidates=${candidates.map { it.id to it.triggerUtterance }}")
            val matchedFlow = flows.find { it.id == match.matchedFlowId }
            if (matchedFlow == null) {
                onStatus("Didn't recognize \"$utterance\" as any learned flow.")
                return@launch
            }

            // Unsure which flow was meant: ask before touching anything.
            if (match.confidence < CONFIDENCE_THRESHOLD) {
                val question = "Did you mean \"${matchedFlow.description}\"?"
                if (onConfirm == null) {
                    onStatus("Not sure you meant \"${matchedFlow.description}\" — try saying it more clearly.")
                    return@launch
                }
                val yes = suspendCancellableCoroutine { cont -> onConfirm(question) { cont.resume(it) } }
                if (!yes) {
                    onStatus("Okay, not running it.")
                    return@launch
                }
            }

            // Which app to run on: the one the user named, else the one it
            // was taught on. A different app means grounding the flow by
            // what each step means, not by the taught app's buttons.
            val taughtPackage = matchedFlow.targetPackage
            val namedApp = match.targetApp
            val targetPackage = if (namedApp != null) {
                resolvePackageForAppName(namedApp) ?: run {
                    onStatus("I couldn't find an app called \"$namedApp\" on this phone.")
                    return@launch
                }
            } else {
                taughtPackage
            }
            // Note (2026-09-28): a "SEMANTIC" cross-app replay mode was
            // prototyped on a teammate's branch (grounding a flow on a
            // DIFFERENT app than it was taught on) but deliberately left
            // out of this integration — see ReplayPlanner's class doc for
            // why (rejected architecture direction, +4 bonus item, real
            // safety/determinism cost). If targetPackage differs from
            // taughtPackage here (the user named a different app by
            // voice), replay still runs in the only mode that exists —
            // the taught anchors won't resolve on a different app's
            // screen, so this correctly surfaces as Stuck rather than
            // silently misfiring; it just won't succeed. Documented, not
            // a regression from anything that worked before.

            // Always a FRESH start at the app's home screen: re-opening a
            // running app otherwise resumes whatever screen it was last on
            // (confirmed on-device 27 Sep: Zomato came back on a restaurant
            // menu, so the flow's first step — a card on the home feed —
            // wasn't there).
            onStatus("Opening ${appLabel(targetPackage)}...")
            val arrived = launchAndWaitForForeground(service, targetPackage)
            if (!arrived) {
                onStatus("Couldn't bring $targetPackage to the foreground — is it installed?")
                return@launch
            }

            onStatus("Replaying: ${matchedFlow.description}")
            val engine = ReplayEngine(service)
            // Off the main thread: replay sleeps while screens settle.
            val result = withContext(Dispatchers.Default) { engine.replay(matchedFlow.steps, match.slotValues) }
            handleReplayResult(engine, matchedFlow.steps, match.slotValues, result, onStatus)
        }
    }

    /**
     * Task 2 (Lane B): a Stuck result is never surfaced as a bare "Replay
     * failed" — this builds a specific question (StuckQuestion, :domain),
     * shows AND speaks it, then takes the answer by voice: "stop" (or a
     * synonym) aborts cleanly, anything else recognized is retried as the
     * new text/label to match for the exact step that got stuck (steps
     * after it then continue normally). A voice-recognition failure or an
     * empty utterance repeats the question once before giving up —
     * StuckAnswerHandler (:domain, unit-tested) makes that decision; this
     * function is just the Android-side glue (TTS + SpeechRecognizer)
     * around it. Completed/Halted results are unaffected — Halted (the
     * credential gate) is a final safety stop, never a question.
     *
     * Exception (2026-09-28): a Stuck whose [ReplayResult.Stuck.
     * actionMayHaveExecuted] is true came from a timed-out tap/type/scroll
     * that may still land on the device later, unsupervised — see that
     * field's doc. Retrying here would risk a second, uncontrolled action
     * stacking on top of one that might already be in flight, and "should I
     * pick something else, or stop?" is actively misleading when the honest
     * answer is "I don't know what just happened." This case skips the
     * question entirely and goes straight to a stop, with the uncertainty
     * said out loud rather than papered over.
     */
    private suspend fun handleReplayResult(
        engine: ReplayEngine,
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        result: ReplayResult,
        onStatus: (String) -> Unit,
        attempt: Int = 1
    ) {
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

        val stuckStep = steps.find { it.order == result.atStepOrder }
        val question = StuckQuestion.build(stuckStep?.target ?: ElementAnchor(), result.atStepOrder)
        onStatus(question)
        tts.speak(question)

        val outcome = listenForStuckAnswer()
        when (val action = StuckAnswerHandler.handle(outcome, attempt)) {
            StuckAction.Stop -> onStatus("Stopped.")
            StuckAction.Repeat -> handleReplayResult(engine, steps, slotValues, result, onStatus, attempt = attempt + 1)
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
                val retryResult = engine.replay(retrySteps, retrySlotValues)
                handleReplayResult(engine, retrySteps, retrySlotValues, retryResult, onStatus, attempt = 1)
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

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.currentPackageName() == targetPackage) return true
            delay(pollIntervalMs)
        }
        return service.currentPackageName() == targetPackage
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
            val result = withContext(Dispatchers.Default) { engine.replay(RoleLabeler.relabel(latest.steps), emptyMap()) }
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
