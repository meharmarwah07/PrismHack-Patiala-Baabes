package com.calo.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.calo.orchestrator.CaloOrchestrator
import com.calo.teach.TeachRecorder

class CaloAccessibilityService : AccessibilityService() {

    enum class Mode { IDLE, TEACHING, REPLAYING }

    companion object {
        var instance: CaloAccessibilityService? = null
            private set
    }

    var mode: Mode = Mode.IDLE
        private set

    private var teachRecorder: TeachRecorder? = null

    lateinit var orchestrator: CaloOrchestrator
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        orchestrator = CaloOrchestrator(applicationContext)
    }

    override fun onDestroy() {
        if (::orchestrator.isInitialized) {
            orchestrator.shutdown()
        }
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (mode == Mode.TEACHING) {
            teachRecorder?.onAccessibilityEvent(event, currentRoot())
        }
    }

    override fun onInterrupt() {
    }

    fun startTeaching(): TeachRecorder {
        val recorder = TeachRecorder()
        teachRecorder = recorder
        mode = Mode.TEACHING
        return recorder
    }

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
