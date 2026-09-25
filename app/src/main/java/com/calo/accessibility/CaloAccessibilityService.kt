package com.calo.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.calo.BuildConfig
import com.calo.domain.gate.GateVerdict
import com.calo.domain.teach.RawTouchCaptureGate
import com.calo.orchestrator.CaloOrchestrator
import com.calo.teach.TeachRecorder

class CaloAccessibilityService : AccessibilityService() {

    enum class Mode { IDLE, TEACHING, REPLAYING }

    companion object {
        var instance: CaloAccessibilityService? = null
            private set

        // Finding 6 fallback tuning (2026-09-26):
        //
        // How long a raw touch-down candidate waits for a real CLICK to
        // arrive and pre-empt it (see TeachRecorder.onRawTouchDown's doc).
        // Generous relative to normal event latency (the existing null-
        // source CLICK recovery's own cache window is 2000ms) but still far
        // shorter than human tap-to-tap pacing, so it never risks reaching
        // into the NEXT tap's territory.
        private const val RAW_TOUCH_COMMIT_DELAY_MS = 300L

        // Safety net for touch capture's lifecycle (condition 1): if
        // stopTeaching()/onDestroy()/onInterrupt() are somehow all missed
        // (a wedged UI, a crash mid-teardown), this guarantees touch
        // exploration capability is relinquished on its own rather than
        // silently staying on indefinitely.
        private const val TOUCH_CAPTURE_WATCHDOG_MS = 5 * 60 * 1000L

        // Confirmed on-device (2026-09-26, real touches): with the toggle
        // OFF entirely, TouchInteractionController fired for 11/11 real
        // taps — the mechanism itself is reliable. With it ON, real search
        // typing + a post-keyboard result tap both worked with zero
        // dropped keystrokes and zero missed taps, PROVIDED the callback is
        // re-registered every time the capability re-engages (see
        // registerTouchCaptureCallback's doc) — the original registration
        // goes stale across a capability off/on cycle.
        private const val KEYBOARD_RELEASE_TOGGLE_ENABLED = true
    }

    var mode: Mode = Mode.IDLE
        private set

    private var teachRecorder: TeachRecorder? = null

    private val handler = Handler(Looper.getMainLooper())
    private val credentialGate = CredentialGate(packageName = { currentPackageName() })
    private val accessibilityManager: AccessibilityManager
        get() = getSystemService(AccessibilityManager::class.java)
    private var touchInteractionController: TouchInteractionController? = null
    private var touchCaptureCallback: TouchInteractionController.Callback? = null
    private var keyboardVisible = false
    private val touchCaptureWatchdog = Runnable {
        android.util.Log.w("Calo", "Touch capture watchdog fired after ${TOUCH_CAPTURE_WATCHDOG_MS}ms — force-stopping raw touch capture.")
        stopRawTouchCapture()
    }

    lateinit var orchestrator: CaloOrchestrator
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        orchestrator = CaloOrchestrator(applicationContext)
    }

    override fun onDestroy() {
        stopRawTouchCapture()
        if (::orchestrator.isInitialized) {
            orchestrator.shutdown()
        }
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (mode == Mode.TEACHING && event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            updateKeyboardVisibility()
        }
        if (mode == Mode.TEACHING) {
            logRawEventForTeachDebugging(event)
            teachRecorder?.onAccessibilityEvent(event, currentRoot())
        }
    }

    /**
     * Finding 6 fallback, part 2 (2026-09-26): confirmed on a real device
     * that leaving touch exploration capability engaged while the on-
     * screen keyboard is up breaks the keyboard itself — real key taps
     * stopped registering at all, even though our own capture/delegation
     * was behaving correctly (clean state transitions, no crashes). So
     * touch exploration is relinquished for the ENTIRE time the IME window
     * is visible, restored the instant it isn't — typing is captured via
     * the ordinary TYPE_VIEW_TEXT_CHANGED path regardless (see
     * TeachRecorder), which needs no touch capture at all.
     *
     * Gated on [touchInteractionController] being non-null: this only
     * matters while a teach session actually wants touch capture active;
     * outside that, toggling the capability would be pointless churn.
     * Idempotent by design (compares against [keyboardVisible] first) —
     * TYPE_WINDOWS_CHANGED can fire multiple times for one real visibility
     * change, and re-toggling on every one would just be wasted setServiceInfo
     * calls, each logged as if it mattered.
     */
    private fun updateKeyboardVisibility() {
        if (!KEYBOARD_RELEASE_TOGGLE_ENABLED) return // bisection run A: toggle fully disabled
        if (touchInteractionController == null) return
        val nowVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (nowVisible == keyboardVisible) return
        keyboardVisible = nowVisible

        val start = System.currentTimeMillis()
        setTouchExplorationCapabilityRequested(!nowVisible)
        val elapsedMs = System.currentTimeMillis() - start
        android.util.Log.i(
            "CaloTouchCapture",
            "[$start] Keyboard visible=$nowVisible -> touch exploration capability requested=${!nowVisible} (setServiceInfo took ${elapsedMs}ms) isTouchExplorationEnabled(system)=${accessibilityManager.isTouchExplorationEnabled}"
        )

        if (!nowVisible) {
            teachRecorder?.onKeyboardHidden()
            // Re-obtains a fresh controller and registers a new callback —
            // required, see registerTouchCaptureCallback's doc: the
            // original registration goes stale once
            // FLAG_REQUEST_TOUCH_EXPLORATION_MODE is dropped and re-added,
            // even though the service object and its `serviceInfo` are
            // otherwise unchanged.
            registerTouchCaptureCallback()
        }
    }

    /**
     * Finding 6 root-cause tooling (2026-09-26): logs EVERY raw
     * AccessibilityEvent the service receives while teaching, before
     * TeachRecorder's own filtering/classification runs — real human taps
     * on Zomato's search bar and on a search result were observed producing
     * only TYPE_VIEW_SCROLLED, never TYPE_VIEW_CLICKED, and this is the
     * capture point for confirming why. Debug-only (BuildConfig.DEBUG),
     * compiled out of release entirely rather than just log-level-gated —
     * this fires on every single accessibility event during teaching, which
     * is far too high-volume/verbose (and touches other apps' on-screen
     * text) to ship even at a suppressed log level.
     *
     * Fields chosen to answer exactly the (a)/(b) root-cause split: source's
     * isClickable/isFocusable/isEditable distinguish "this node just isn't
     * marked clickable" (structural, (b)) from "it's clickable but we're not
     * classifying its event correctly" (config/filter gap, (a)); windowId +
     * event time let a sequence of events from one physical tap be
     * reconstructed in order.
     */
    private fun logRawEventForTeachDebugging(event: AccessibilityEvent) {
        if (!BuildConfig.DEBUG) return
        val source = event.source
        try {
            android.util.Log.d(
                "CaloRawEvt",
                "eventType=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "pkg=${event.packageName} cls=${event.className} " +
                    "windowId=${event.windowId} eventTime=${event.eventTime} " +
                    "text=${event.text} contentDescription=${event.contentDescription} " +
                    "source.viewIdResourceName=${source?.viewIdResourceName} " +
                    "source.isClickable=${source?.isClickable} " +
                    "source.isFocusable=${source?.isFocusable} " +
                    "source.isEditable=${source?.isEditable}"
            )
        } finally {
            source?.recycle()
        }
    }

    override fun onInterrupt() {
        stopRawTouchCapture()
    }

    fun startTeaching(): TeachRecorder {
        // packageName (from Context/ContextWrapper) rather than a literal
        // "com.calo": always matches whatever this build actually installed
        // as, and is what TeachRecorder filters its own UI's events by —
        // see TeachRecorder's class doc for the self-recording bug this closes.
        //
        // launcherPackageName closes a companion bug (Finding 5, 2026-09-24
        // real-device teach session): the ONLY way a real person leaves
        // Calo to open the app they're actually teaching is via the
        // home/launcher screen, so on every real live-teach session the
        // launcher's own TYPE_WINDOW_STATE_CHANGED/CLICK events were the
        // first non-Calo events TeachRecorder saw — latching targetPackage
        // to the launcher instead of the target app, every time. Resolved
        // dynamically (PackageManager, ACTION_MAIN+CATEGORY_HOME) rather
        // than hardcoded, so this doesn't silently break on a different
        // physical device (e.g. the judges') with a different launcher
        // package than whatever this dev phone happens to ship.
        val launcherPackageName = resolveLauncherPackageName()
        val recorder = TeachRecorder(ownPackageName = packageName, launcherPackageName = launcherPackageName)
        // Eager seed for the null-source CLICK recovery cache: without
        // this, the very first event of a session — which can itself be a
        // null-source CLICK, confirmed on-device — has nothing to fall
        // back on yet. See TeachRecorder.seedRoot's doc.
        recorder.seedRoot(currentRoot())
        teachRecorder = recorder
        mode = Mode.TEACHING
        startRawTouchCapture()
        return recorder
    }

    fun stopTeaching(): TeachRecorder? {
        stopRawTouchCapture()
        val recorder = teachRecorder
        teachRecorder = null
        mode = Mode.IDLE
        recorder?.release()
        return recorder
    }

    fun setReplaying(replaying: Boolean) {
        mode = if (replaying) Mode.REPLAYING else Mode.IDLE
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackageName(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    /**
     * Resolves the device's actual home/launcher package at call time
     * rather than trusting a hardcoded name — launcher packages vary by
     * OEM/skin (Samsung's One UI launcher isn't the same package as AOSP's
     * or a Pixel's) and this must keep working on whatever device the
     * hackathon demo actually runs on. Null (not a crash) if resolution
     * ever fails — TeachRecorder treats a null launcherPackageName as "no
     * extra exclusion", falling back to its pre-existing behavior rather
     * than breaking teaching entirely over this being unresolvable.
     */
    private fun resolveLauncherPackageName(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName
    }

    /**
     * Finding 6 fallback investigation (2026-09-26): diagnostic-only toggle
     * for whether declaring FLAG_REQUEST_TOUCH_EXPLORATION_MODE changes what
     * a target app's accessibility tree looks like — checked BEFORE any
     * TouchInteractionController capture code is written, per the decision
     * that this must be verified first. Mutates the LIVE serviceInfo via
     * setServiceInfo rather than the static accessibility_service_config.xml,
     * since this needs to flip on/off at runtime (teach start/stop), not be
     * on for the service's entire lifetime.
     */
    fun setTouchExplorationCapabilityRequested(requested: Boolean) {
        val info = serviceInfo ?: return
        val before = info.flags
        info.flags = if (requested) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
        serviceInfo = info
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "CaloTouchCapture",
                "[${System.currentTimeMillis()}] setServiceInfo: requested=$requested flags $before -> ${info.flags} isTouchExplorationEnabled(system)=${accessibilityManager.isTouchExplorationEnabled}"
            )
        }
    }

    fun isTouchExplorationCapabilityRequested(): Boolean =
        (serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0

    /**
     * Finding 6 fallback (2026-09-26): starts raw touch-down capture for
     * the duration of a teach session, via TouchInteractionController —
     * required for Zomato-style taps that never produce ANY
     * AccessibilityEvent (confirmed root cause, see TeachRecorder's
     * onRawTouchDown doc). API 33+ only (TouchInteractionController itself
     * is a 33+ API); below that this silently does nothing — teaching
     * still works via the normal event path, just without this specific
     * recovery, same graceful-degradation shape as a failed launcher
     * resolution elsewhere in this class.
     *
     * requestDelegating() is called on EVERY callback, unconditionally,
     * before anything else — per the AOSP source's own recommendation
     * ("Prefer TouchInteractionController... MotionEvents ... are queued
     * ... sent ... after the transition has taken place"), the goal here
     * is to observe without ever changing what the app underneath
     * receives, and the framework only flushes the queued gesture through
     * to the app once a transition (delegating, in our case) is decided —
     * so deciding on every single callback, not just the first, means no
     * interaction is ever left undecided/stuck queued.
     */
    private fun startRawTouchCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.util.Log.w("Calo", "Raw touch capture unavailable (API ${Build.VERSION.SDK_INT} < 33) — Finding 6's no-event Zomato-style taps will not be recoverable on this device.")
            return
        }
        setTouchExplorationCapabilityRequested(true)
        registerTouchCaptureCallback()
        // Syncs against reality in case the keyboard somehow is already up
        // right as teaching starts — updateKeyboardVisibility reads the
        // live window list itself rather than trusting keyboardVisible's
        // just-reset default. No-ops entirely when the toggle is disabled
        // (bisection run A).
        updateKeyboardVisibility()
    }

    /**
     * Called once at teach-start, and again every time
     * [updateKeyboardVisibility] re-enables touch exploration capability
     * after the keyboard hides. The second call is required, not
     * defensive belt-and-suspenders: confirmed on-device (2026-09-26) that
     * the ORIGINAL registration goes silently stale across a capability
     * off/on cycle — onMotionEvent stops firing for any tap after the
     * keyboard hides unless the controller is freshly re-obtained and the
     * callback re-registered here. Unregisters whatever was previously
     * registered first, so this is always safe to call again.
     */
    private fun registerTouchCaptureCallback() {
        touchCaptureCallback?.let { touchInteractionController?.unregisterCallback(it) }

        val controller = getTouchInteractionController(Display.DEFAULT_DISPLAY)
        val callback = object : TouchInteractionController.Callback {
            override fun onMotionEvent(event: MotionEvent) {
                // Idle-based, not "5 minutes from teach start": a real
                // teach session pauses (reading a menu, deciding what to
                // tap next) far longer than 5 minutes sometimes; only
                // actual INACTIVITY should ever force-stop capture.
                handler.removeCallbacks(touchCaptureWatchdog)
                handler.postDelayed(touchCaptureWatchdog, TOUCH_CAPTURE_WATCHDOG_MS)
                if (BuildConfig.DEBUG) {
                    val isTapDown = event.actionMasked == MotionEvent.ACTION_DOWN
                    android.util.Log.d(
                        "CaloTouchCapture",
                        "[${System.currentTimeMillis()}] onMotionEvent action=${MotionEvent.actionToString(event.actionMasked)} " +
                            "x=${event.rawX} y=${event.rawY} state=${TouchInteractionController.stateToString(controller.state)}" +
                            if (isTapDown) " isTouchExplorationEnabled(system)=${accessibilityManager.isTouchExplorationEnabled} ourFlagRequested=${isTouchExplorationCapabilityRequested()}" else ""
                    )
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    handleRawTouchDown(event.rawX, event.rawY)
                }
                // Confirmed on-device (2026-09-26): requestDelegating() is
                // only a valid state TRANSITION from STATE_TOUCH_INTERACTING
                // — calling it again once already in STATE_DELEGATING throws
                // IllegalStateException ("State transition requests are not
                // allowed in STATE_DELEGATING"), which crashed this entire
                // process (SIGKILL) and got the service auto-disabled by
                // AccessibilityManagerService. onMotionEvent DOES keep firing
                // for MOVE/UP within an interaction already delegated, so
                // this guard — not "call once on ACTION_DOWN only" — is the
                // actual fix; the framework, not us, decides how many calls
                // one interaction produces.
                if (controller.state == TouchInteractionController.STATE_TOUCH_INTERACTING) {
                    controller.requestDelegating()
                }
            }

            override fun onStateChanged(state: Int) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CaloTouchCapture", "[${System.currentTimeMillis()}] state=${TouchInteractionController.stateToString(state)}")
                }
            }
        }
        controller.registerCallback(mainExecutor, callback)
        touchInteractionController = controller
        touchCaptureCallback = callback
        handler.removeCallbacks(touchCaptureWatchdog)
        handler.postDelayed(touchCaptureWatchdog, TOUCH_CAPTURE_WATCHDOG_MS)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "CaloTouchCapture",
                "[${System.currentTimeMillis()}] registered, initial state=${TouchInteractionController.stateToString(controller.state)} " +
                    "maxPointerCount=${controller.maxPointerCount} isTouchExplorationEnabled(system)=${accessibilityManager.isTouchExplorationEnabled}"
            )
        }
    }

    private fun stopRawTouchCapture() {
        handler.removeCallbacks(touchCaptureWatchdog)
        touchCaptureCallback?.let { touchInteractionController?.unregisterCallback(it) }
        touchInteractionController = null
        touchCaptureCallback = null
        keyboardVisible = false
        setTouchExplorationCapabilityRequested(false)
    }

    /**
     * Reads the current screen once (CredentialGate signals + keyboard
     * visibility) and hands the touch-down off to TeachRecorder, which
     * decides everything else (exclusion, gating, node resolution). The
     * actual FlowStep is committed later, via [commitRawTouchIfStillPending]
     * scheduled here — see TeachRecorder.onRawTouchDown's doc for why this
     * can't be a single synchronous step.
     */
    private fun handleRawTouchDown(rawX: Float, rawY: Float) {
        val recorder = teachRecorder ?: return
        val root = currentRoot()
        try {
            val credentialGateClear = credentialGate.check(root) is GateVerdict.Clear
            val keyboardVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val actionPackage = root?.packageName?.toString()
            val token = recorder.onRawTouchDown(rawX.toInt(), rawY.toInt(), root, credentialGateClear, keyboardVisible)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("CaloTouchCapture", "onRawTouchDown x=${rawX.toInt()} y=${rawY.toInt()} pkg=$actionPackage gateClear=$credentialGateClear keyboardVisible=$keyboardVisible -> token=$token")
            }
            if (token == null) return
            handler.postDelayed({ recorder.commitRawTouchIfStillPending(token, actionPackage) }, RAW_TOUCH_COMMIT_DELAY_MS)
        } finally {
            root?.recycle()
        }
    }
}
