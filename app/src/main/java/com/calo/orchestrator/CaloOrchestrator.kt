package com.calo.orchestrator

import android.content.Context
import android.content.Intent
import com.calo.accessibility.CaloAccessibilityService
import com.calo.data.FlowRepository
import com.calo.data.LearnedFlow
import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.replay.ReplayResult
import com.calo.nlu.NLUClient
import com.calo.replay.ReplayEngine
import com.calo.voice.VoiceInputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CaloOrchestrator(context: Context) {

    private val appContext = context.applicationContext
    private val voice = VoiceInputManager(appContext)
    private val repository = FlowRepository(appContext)
    private val nluClient = NLUClient()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    fun startVoiceCommand(onStatus: (String) -> Unit) {
        voice.startListening(
            onResult = { utterance -> handleUtterance(utterance, onStatus) },
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
    internal fun handleUtterance(utterance: String, onStatus: (String) -> Unit) {
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
                    slotNames = flow.slots.map { it.name }
                )
            }

            val match = nluClient.match(utterance, candidates)
            val matchedFlow = flows.find { it.id == match.matchedFlowId }
            if (matchedFlow == null) {
                onStatus("Didn't recognize \"$utterance\" as any learned flow.")
                return@launch
            }

            val targetPackage = matchedFlow.targetPackage
            if (service.currentPackageName() != targetPackage) {
                onStatus("Opening $targetPackage...")
                val arrived = launchAndWaitForForeground(service, targetPackage)
                if (!arrived) {
                    onStatus("Couldn't bring $targetPackage to the foreground — is it installed?")
                    return@launch
                }
            }

            onStatus("Replaying: ${matchedFlow.description}")
            val engine = ReplayEngine(service)
            val result = engine.replay(matchedFlow.steps, match.slotValues)
            onStatus(describeResult(result))
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
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.currentPackageName() == targetPackage) return true
            delay(pollIntervalMs)
        }
        return service.currentPackageName() == targetPackage
    }

    private fun describeResult(result: ReplayResult): String = when (result) {
        ReplayResult.Completed -> "Done."
        is ReplayResult.Halted -> "Stopped for your safety at step ${result.atStepOrder}: ${result.reason}"
        is ReplayResult.Stuck -> "Got stuck at step ${result.atStepOrder}: ${result.reason}"
    }

    fun startTeaching() {
        CaloAccessibilityService.instance?.startTeaching()
    }

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

        scope.launch {
            repository.save(
                LearnedFlow(
                    targetPackage = targetPackage,
                    triggerUtterance = triggerUtterance,
                    description = description,
                    steps = steps,
                    slots = emptyList()
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
            val result = engine.replay(latest.steps, emptyMap())
            onStatus(describeResult(result))
        }
    }

    fun shutdown() {
        job.cancel()
        voice.destroy()
    }
}
