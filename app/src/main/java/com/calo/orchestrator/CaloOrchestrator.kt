package com.calo.orchestrator

import android.content.Context
import android.content.Intent
import com.calo.accessibility.CaloAccessibilityService
import com.calo.data.FlowRepository
import com.calo.data.LearnedFlow
import com.calo.domain.agent.AgentTaskBuilder
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.replay.ReplayMode
import com.calo.domain.replay.ReplayResult
import com.calo.domain.semantic.RoleLabeler
import com.calo.nlu.NLUClient
import com.calo.replay.ReplayEngine
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
            val mode = if (targetPackage == taughtPackage) ReplayMode.EXACT else ReplayMode.SEMANTIC

            // Fresh labels under the current rules, so flows saved before a
            // labelling fix (or before roles existed) replay correctly.
            val steps = RoleLabeler.relabel(matchedFlow.steps)
            if (mode == ReplayMode.SEMANTIC && steps.none { it.role != null }) {
                onStatus("I learned \"${matchedFlow.description}\" on ${appLabel(taughtPackage)}, but it isn't a task I know how to carry over to ${appLabel(targetPackage)}.")
                return@launch
            }

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

            onStatus(
                if (mode == ReplayMode.SEMANTIC) {
                    "Doing \"${matchedFlow.description}\" on ${appLabel(targetPackage)} (learned on ${appLabel(taughtPackage)})..."
                } else {
                    "Replaying: ${matchedFlow.description}"
                }
            )
            // Counts as "used" here, not only on a Completed result: the
            // Saved Workflows screen's usage stat is about how many times
            // the user actually invoked the flow by voice, the same way a
            // Halted/Stuck attempt is still a real attempt worth surfacing —
            // not a claim that it always finished successfully.
            repository.recordUsage(matchedFlow.id)

            val engine = ReplayEngine(service)
            // Off the main thread: replay sleeps while screens settle.
            val replayed = withContext(Dispatchers.Default) { engine.replay(steps, match.slotValues, mode) }

            // Exact replay is free, so it always goes first; only when it
            // gets Stuck does the AI helper take over from the current
            // screen, with the taught steps as hints. Never after Halted —
            // a safety stop is final.
            val result = if (replayed !is ReplayResult.Stuck) replayed else {
                val stuck: ReplayResult.Stuck = replayed
                android.util.Log.i("Calo", "Replay stuck at step ${stuck.atStepOrder} (${stuck.reason}) — handing over to AI helper")
                onStatus("Step ${stuck.atStepOrder} didn't match — letting AI finish it...")
                val task = AgentTaskBuilder.from(
                    spokenCommand = utterance,
                    triggerUtterance = matchedFlow.triggerUtterance,
                    description = matchedFlow.description,
                    appName = appLabel(targetPackage),
                    steps = steps,
                    slotValues = match.slotValues,
                    stuckAtOrder = stuck.atStepOrder
                )
                withContext(Dispatchers.Default) {
                    engine.finishWithAgent(task, stuck.atStepOrder) { prompt -> nluClient.complete(prompt) }
                }
            }
            announceResult(describeResult(result), onStatus)
        }
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
    }
}
