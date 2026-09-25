package com.calo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.calo.accessibility.CaloAccessibilityService

class DebugTriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Calo"
        const val ACTION_START_TEACHING = "com.calo.debug.START_TEACHING"
        const val ACTION_FINISH_TEACHING = "com.calo.debug.FINISH_TEACHING"
        const val ACTION_REPLAY_LATEST = "com.calo.debug.REPLAY_LATEST"
        const val ACTION_VOICE_COMMAND = "com.calo.debug.VOICE_COMMAND"
        const val ACTION_SET_TOUCH_EXPLORATION = "com.calo.debug.SET_TOUCH_EXPLORATION"
        const val ACTION_DUMP_FLOWS = "com.calo.debug.DUMP_FLOWS"
        const val ACTION_DELETE_FLOWS_BY_ID = "com.calo.debug.DELETE_FLOWS_BY_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val service = CaloAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Debug trigger ignored (${intent.action}): accessibility service not running. Enable Calo under Settings > Accessibility first.")
            return
        }

        when (intent.action) {
            ACTION_START_TEACHING -> {
                service.orchestrator.startTeaching()
                Log.i(TAG, "Teaching started. Perform the flow on screen now, then send FINISH_TEACHING.")
            }

            ACTION_FINISH_TEACHING -> {
                val trigger = intent.getStringExtra("trigger") ?: "debug flow ${System.currentTimeMillis()}"
                val description = intent.getStringExtra("description") ?: trigger
                service.orchestrator.finishTeaching(trigger, description) { success ->
                    Log.i(TAG, "Teaching finished. saved=$success trigger=\"$trigger\"")
                }
            }

            ACTION_REPLAY_LATEST -> {
                service.orchestrator.replayLatestFlowForForegroundApp { status ->
                    Log.i(TAG, "Replay result: $status")
                }
            }

            ACTION_VOICE_COMMAND -> {
                val utterance = intent.getStringExtra("utterance")
                if (utterance.isNullOrBlank()) {
                    Log.w(TAG, "VOICE_COMMAND ignored: no --es utterance given.")
                    return
                }
                Log.i(TAG, "Simulating voice command (debug, real Groq call): \"$utterance\"")
                service.orchestrator.handleUtterance(utterance) { status ->
                    Log.i(TAG, "Voice command result: $status")
                }
            }

            ACTION_SET_TOUCH_EXPLORATION -> {
                val requested = intent.getBooleanExtra("requested", false)
                service.setTouchExplorationCapabilityRequested(requested)
                Log.i(TAG, "Touch exploration capability requested=$requested (now=${service.isTouchExplorationCapabilityRequested()})")
            }

            ACTION_DELETE_FLOWS_BY_ID -> {
                val ids = intent.getStringExtra("ids")?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
                service.orchestrator.deleteFlowsByIds(ids) { result -> Log.i(TAG, result) }
            }

            ACTION_DUMP_FLOWS -> {
                service.orchestrator.dumpAllFlows { dump ->
                    dump.lines().forEach { Log.i(TAG, "FLOWDUMP: $it") }
                }
            }

            else -> Log.w(TAG, "Unknown debug action: ${intent.action}")
        }
    }
}
