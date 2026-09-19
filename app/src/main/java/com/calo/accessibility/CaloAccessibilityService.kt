package com.calo.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.teach.TeachRecorder

/**
 * The one AccessibilityService the whole app runs on. Deliberately kept
 * thin — it just tracks mode (idle/teaching/replaying) and forwards raw
 * events to whichever component owns that mode. Teaching logic lives in
 * TeachRecorder, replay logic in ReplayEngine, gate logic in
 * CredentialGate: this class wires them to the Android lifecycle and
 * nothing else.
 *
 * `instance` exists because other components (the orchestrator, the
 * teach-mode UI) run outside this service's lifecycle and have no other
 * way to get a handle to the running service — this is the standard
 * pattern for a singleton AccessibilityService. It's set null in
 * onDestroy() specifically so nothing holds a stale reference to a torn-
 * down service (accessing rootInActiveWindow on a dead service throws).
 */
class CaloAccessibilityService : AccessibilityService() {

    enum class Mode { IDLE, TEACHING, REPLAYING }

    companion object {
        var instance: CaloAccessibilityService? = null
            private set
    }

    var mode: Mode = Mode.IDLE
        private set

    private var teachRecorder: TeachRecorder? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (mode == Mode.TEACHING) {
            teachRecorder?.onAccessibilityEvent(event, currentRoot())
        }
        // REPLAYING intentionally ignores incoming events — ReplayEngine
        // drives itself step-by-step and re-reads currentRoot() on its own
        // schedule; reacting to every event here would double-drive it.
    }

    override fun onInterrupt() {
        // Required override; nothing to clean up — we hold no OS resources
        // beyond node references, which are scoped to each walk/replay call.
    }

    fun startTeaching(): TeachRecorder {
        val recorder = TeachRecorder()
        teachRecorder = recorder
        mode = Mode.TEACHING
        return recorder
    }

    /** Returns the recorder holding everything captured since startTeaching(), and resets to IDLE. */
    fun stopTeaching(): TeachRecorder? {
        val recorder = teachRecorder
        teachRecorder = null
        mode = Mode.IDLE
        return recorder
    }

    fun setReplaying(replaying: Boolean) {
        mode = if (replaying) Mode.REPLAYING else Mode.IDLE
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackageName(): String = rootInActiveWindow?.packageName?.toString() ?: ""
}
