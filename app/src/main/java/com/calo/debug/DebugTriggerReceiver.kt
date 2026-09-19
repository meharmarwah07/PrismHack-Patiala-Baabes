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

            else -> Log.w(TAG, "Unknown debug action: ${intent.action}")
        }
    }
}
