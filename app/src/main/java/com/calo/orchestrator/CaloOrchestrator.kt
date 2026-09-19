package com.calo.orchestrator

import android.content.Context
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
import kotlinx.coroutines.launch

class CaloOrchestrator(context: Context) {

    private val voice = VoiceInputManager(context)
    private val repository = FlowRepository(context)
    private val nluClient = NLUClient()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    fun startVoiceCommand(onStatus: (String) -> Unit) {
        voice.startListening(
            onResult = { utterance -> handleUtterance(utterance, onStatus) },
            onFailure = { reason -> onStatus("Didn't catch that: $reason") }
        )
    }

    private fun handleUtterance(utterance: String, onStatus: (String) -> Unit) {
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
            if (flows.isEmpty()) {
                onStatus("No flows learned yet for $targetPackage.")
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
                onStatus("Didn't recognize \"$utterance\" as a learned flow for $targetPackage.")
                return@launch
            }

            onStatus("Replaying: ${matchedFlow.description}")
            val engine = ReplayEngine(service)
            val result = engine.replay(matchedFlow.steps, match.slotValues)
            onStatus(describeResult(result))
        }
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
