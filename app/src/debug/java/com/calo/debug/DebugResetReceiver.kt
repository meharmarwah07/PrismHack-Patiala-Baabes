package com.calo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.calo.data.FlowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wipes every LearnedFlow so test/rehearsal flows never show up during
 * judging. Lives entirely under src/debug — not just exported="false" — so
 * a release build doesn't contain this class or its manifest entry at all
 * (see src/debug/AndroidManifest.xml, merged only into debug builds).
 *
 * Trigger: adb -s <device> shell am broadcast -a com.calo.DEBUG_RESET -p com.calo
 */
class DebugResetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CaloDebugReset"
        const val ACTION_RESET = "com.calo.DEBUG_RESET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESET) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = FlowRepository(appContext)
                val before = repository.all().size
                repository.deleteAll()
                val after = repository.all().size
                Log.i(TAG, "learned_flows reset: $before row(s) -> $after row(s)")
            } catch (e: Exception) {
                Log.e(TAG, "DB reset failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
