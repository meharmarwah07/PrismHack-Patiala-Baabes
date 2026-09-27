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
import com.calo.domain.teach.TapGesture
import com.calo.orchestrator.CaloOrchestrator
import com.calo.domain.model.FlowStep
import com.calo.teach.TeachCheckpoint
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

        // The commit delay above counts from the finger coming UP (when the
        // app's real click event fires), not going down — counting from
        // DOWN recorded most Zomato taps twice (27 Sep 2026). If no UP is
        // ever seen, commit anyway this long after DOWN.
        private const val RAW_TOUCH_NO_UP_TIMEOUT_MS = 1500L

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
        //
        // 27 Sep 2026: tried OFF (capture kept on while the keyboard is up,
        // to record suggestion/result taps made with the keyboard showing).
        // Confirmed on-device that typing broke again even with the
        // delegation delay fixed, so it stays ON: touch exploration is
        // always released while the keyboard is visible. Taps made in that
        // time are recovered by TeachRecorder's post-typing-tap path.
        private const val KEYBOARD_RELEASE_TOGGLE_ENABLED = true

        // A teach session interrupted by a process kill is resumed on
        // reconnect only if it was last touched this recently; an older one
        // is abandoned rather than surprising the user with teaching mode.
        private const val RESUME_TEACHING_MAX_AGE_MS = 30 * 60 * 1000L
    }

    var mode: Mode = Mode.IDLE
        private set

    private var teachRecorder: TeachRecorder? = null
    private var pendingRawCommit: Runnable? = null

    // Touch-down work (reading the whole screen) runs here, never on the
    // main thread — see handleRawTouchDown.
    private val touchWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastTouchUpAtMs = 0L

    // Diagnostic-only (27 Sep 2026, tap-freeze investigation): tracks how
    // many tap-resolution jobs are submitted-but-not-yet-fully-finished on
    // [touchWorker] at any moment, purely for the structured timing record
    // in handleRawTouchDown — not used for any control-flow decision.
    private val touchQueueDepth = java.util.concurrent.atomic.AtomicInteger(0)
    private val tapIdCounter = java.util.concurrent.atomic.AtomicLong(0)

    // The current finger gesture, to tell a tap from a swipe/long-press.
    private var gestureToken: Long? = null
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureDownAtMs = 0L
    private var gestureMaxDistance = 0f
    private var gestureNotTap = false
    private lateinit var teachCheckpoint: TeachCheckpoint
    private var lastCheckpointed: Pair<List<FlowStep>, String?>? = null

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
        // Confirmed on-device (2026-09-26): if this process is killed while
        // touch exploration is armed (background-app killer, not a clean
        // stopTeaching()/onDestroy()), the system can leave the capability
        // flag stuck ON for the NEXT process instance, with no
        // TouchInteractionController/callback alive to ever call
        // requestDelegating() for it — every tap then falls back to raw
        // touch-exploration semantics (tap to focus, second tap to
        // activate) with no way to self-heal. The existing watchdog
        // (TOUCH_CAPTURE_WATCHDOG_MS) can't help here: it's a Handler
        // callback that dies along with the process it was scheduled on.
        // A fresh connection should never start armed, so defensively
        // clear it every time regardless of what the previous instance left
        // behind.
        setTouchExplorationCapabilityRequested(false)

        teachCheckpoint = TeachCheckpoint(this)
        // Posted so the connection is fully set up before touch capture is
        // re-armed.
        handler.post { resumeInterruptedTeaching() }
    }

    /**
     * If this process replaced one that was killed mid-teach (see
     * TeachCheckpoint's doc), carry on teaching with the steps recorded so
     * far, so the user's "Finish Teaching" still saves the whole flow.
     */
    private fun resumeInterruptedTeaching() {
        if (mode == Mode.TEACHING) return
        val saved = teachCheckpoint.load(RESUME_TEACHING_MAX_AGE_MS) ?: return
        android.util.Log.w("Calo", "Resuming a teaching session interrupted by a process restart: ${saved.steps.size} step(s) recovered, targetPackage=${saved.targetPackage}")
        startTeaching(restoreFrom = saved)
    }

    /** Writes the recorder's steps to disk if they changed since the last write. */
    private fun checkpointTeaching() {
        val recorder = teachRecorder ?: return
        val current = recorder.currentSteps() to recorder.currentTargetPackage()
        if (current == lastCheckpointed) return
        teachCheckpoint.save(current.first, current.second)
        lastCheckpointed = current
    }

    override fun onDestroy() {
        stopRawTouchCapture()
        touchWorker.shutdownNow()
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
            teachRecorder?.rawTouchCaptureLive = touchInteractionController != null && !keyboardVisible
            teachRecorder?.keyboardVisible = keyboardVisible
            teachRecorder?.onAccessibilityEvent(event, currentRoot())
            checkpointTeaching()
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

        teachRecorder?.keyboardVisible = nowVisible
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

    fun startTeaching(restoreFrom: TeachCheckpoint.Saved? = null): TeachRecorder {
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
        // Current keyboard app ("com.google.android.inputmethod.latin/.LatinIME" -> its package).
        val keyboardPackageName = android.provider.Settings.Secure
            .getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        val recorder = TeachRecorder(
            ownPackageName = packageName,
            launcherPackageName = launcherPackageName,
            keyboardPackageName = keyboardPackageName
        )
        // Eager seed for the null-source CLICK recovery cache: without
        // this, the very first event of a session — which can itself be a
        // null-source CLICK, confirmed on-device — has nothing to fall
        // back on yet. See TeachRecorder.seedRoot's doc.
        recorder.seedRoot(currentRoot())
        restoreFrom?.let { recorder.restore(it.steps, it.targetPackage) }
        teachRecorder = recorder
        mode = Mode.TEACHING
        // Written straight away, even with zero steps: the file existing is
        // what marks "teaching in progress" for a restarted process.
        lastCheckpointed = null
        checkpointTeaching()
        startRawTouchCapture()
        return recorder
    }

    fun stopTeaching(): TeachRecorder? {
        flushPendingRawCommit() // the last tap before Finish still counts
        stopRawTouchCapture()
        val recorder = teachRecorder
        teachRecorder = null
        mode = Mode.IDLE
        teachCheckpoint.clear()
        lastCheckpointed = null
        recorder?.release()
        return recorder
    }

    fun setReplaying(replaying: Boolean) {
        mode = if (replaying) Mode.REPLAYING else Mode.IDLE
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Taps the screen at ([x], [y]) like a finger. Replay's fallback for
     * elements that ignore ACTION_CLICK and only react to a real touch
     * (confirmed on-device: Zomato's search-page search box, 27 Sep 2026).
     * Blocks until the gesture finishes, so call it off the main thread.
     */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val done = java.util.concurrent.CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                completed = true
                done.countDown()
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                done.countDown()
            }
        }, handler)
        if (!dispatched) return false
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return completed
    }

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
                // FIRST, before anything else: hand the touch to the app.
                // Confirmed on-device (27 Sep 2026): doing the screen lookup
                // before this held the touch back long enough that a tap on
                // the Zomato icon arrived as a long-press (icon menu opened)
                // and buttons needed a second tap. requestDelegating() is
                // only a valid transition from STATE_TOUCH_INTERACTING —
                // calling it in STATE_DELEGATING throws (see below).
                // Diagnostic-only (27 Sep 2026): state read immediately
                // before and after the delegation request, for the tap-
                // freeze investigation's structured timing record — answers
                // whether requestDelegating() is even being called, and
                // whether the state reflects the change synchronously.
                val stateBeforeDelegating = controller.state
                if (stateBeforeDelegating == TouchInteractionController.STATE_TOUCH_INTERACTING) {
                    controller.requestDelegating()
                }
                val stateAfterDelegating = controller.state
                if (BuildConfig.DEBUG && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    android.util.Log.d(
                        "CaloTapTiming",
                        "delegation stateBefore=${TouchInteractionController.stateToString(stateBeforeDelegating)} " +
                            "stateAfter=${TouchInteractionController.stateToString(stateAfterDelegating)}"
                    )
                }
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
                    handleRawTouchDown(event.rawX, event.rawY, stateBeforeDelegating)
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    trackGestureMove(event.rawX, event.rawY)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    lastTouchUpAtMs = System.currentTimeMillis()
                    trackGestureMove(event.rawX, event.rawY)
                    if (!gestureNotTap && lastTouchUpAtMs - gestureDownAtMs >= android.view.ViewConfiguration.getLongPressTimeout()) {
                        markGestureNotTap("held ${lastTouchUpAtMs - gestureDownAtMs}ms")
                    }
                    if (gestureNotTap) return
                    pendingRawCommit?.let {
                        handler.removeCallbacks(it)
                        handler.postDelayed(it, RAW_TOUCH_COMMIT_DELAY_MS)
                    }
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
        pendingRawCommit?.let { handler.removeCallbacks(it) }
        pendingRawCommit = null
        touchCaptureCallback?.let { touchInteractionController?.unregisterCallback(it) }
        touchInteractionController = null
        touchCaptureCallback = null
        keyboardVisible = false
        setTouchExplorationCapabilityRequested(false)
    }

    /**
     * A finger went down during teaching. The touch has ALREADY been handed
     * to the app (onMotionEvent delegates first); this only records it.
     *
     * Working out which element is under the finger means reading the
     * whole screen (credential check + smallest-element lookup + the row
     * text around it), which takes long enough to hold the touch back if
     * done on the main thread — so it runs on [touchWorker], started right
     * away so it still sees the screen as it was when the finger landed.
     * The result is handed back to the main thread, where TeachRecorder
     * drops it if a real CLICK event already recorded this tap meanwhile.
     * The commit happens RAW_TOUCH_COMMIT_DELAY_MS after the finger lifts
     * (or RAW_TOUCH_NO_UP_TIMEOUT_MS after it went down, if no lift is seen).
     */
    private fun handleRawTouchDown(rawX: Float, rawY: Float, controllerStateBefore: Int) {
        val recorder = teachRecorder ?: return
        // A new touch means the previous tap is over: save it now rather
        // than drop it. Confirmed on-device (27 Sep): a checkout tap was
        // lost because the next touch arrived before its commit timer.
        flushPendingRawCommit()
        val token = recorder.markTouchDown()
        val downAtMs = System.currentTimeMillis()
        gestureToken = token
        gestureDownX = rawX
        gestureDownY = rawY
        gestureDownAtMs = downAtMs
        gestureMaxDistance = 0f
        gestureNotTap = false

        // Diagnostic-only (27 Sep 2026, tap-freeze investigation): one
        // structured record per tap, per Step 1 of the instrumentation
        // plan. All nanoTime() reads and the eventual log call are
        // unconditional-cost-free when not BuildConfig.DEBUG (a few field
        // writes), and formatting/logging happens exactly once, after
        // resolution is fully done — never inline mid-measurement, so the
        // instrumentation itself can't inflate what it's measuring.
        val tapId = tapIdCounter.incrementAndGet()
        val submittedAtNanos = System.nanoTime()
        val queueDepthAtArrival = touchQueueDepth.incrementAndGet()

        touchWorker.execute {
            val startedAtNanos = System.nanoTime()
            val queueDepthAtStart = touchQueueDepth.get()
            val threadName = Thread.currentThread().name
            // Confirmed on-device (27 Sep 2026): touchWorker is single-
            // threaded, and the element lookup below can take multiple
            // seconds under load. Tapping again while one is still queued
            // (a natural reaction when nothing seems to happen) used to
            // still pay for a full lookup on this now-superseded touch
            // before even checking it was already stale — wasting the
            // exact time budget needed to catch up to the touch that
            // actually matters. Bail out first, cheaply, before doing any
            // of that work.
            if (!recorder.isLatestTouch(token)) {
                touchQueueDepth.decrementAndGet()
                return@execute
            }
            val rootAcquireStart = System.nanoTime()
            val root = currentRoot()
            val rootAcquireNanos = System.nanoTime() - rootAcquireStart
            try {
                // Element lookup first — it's the part racing the screen
                // changing under it. The gate verdict is applied after.
                val actionPackage = root?.packageName?.toString()
                val touchOnKeyboard = isOnKeyboard(rawX.toInt(), rawY.toInt())
                val timing = if (BuildConfig.DEBUG) TapTiming() else null
                val candidate = recorder.resolveTouchAnchor(rawX.toInt(), rawY.toInt(), root, credentialGateClear = true, touchOnKeyboard = touchOnKeyboard, timing = timing)
                val credentialGateStart = System.nanoTime()
                val credentialGateClear = candidate == null || credentialGate.check(root) is GateVerdict.Clear
                val credentialGateNanos = System.nanoTime() - credentialGateStart
                val anchor = candidate.takeIf { credentialGateClear }
                if (BuildConfig.DEBUG) {
                    val totalResolutionMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                    android.util.Log.d(
                        "CaloTapTiming",
                        "tapId=$tapId thread=$threadName controllerStateBefore=${TouchInteractionController.stateToString(controllerStateBefore)} " +
                            "queueWaitMs=${(startedAtNanos - submittedAtNanos) / 1_000_000.0} queueDepthAtArrival=$queueDepthAtArrival queueDepthAtStart=$queueDepthAtStart " +
                            "rootAcquireMs=${rootAcquireNanos / 1_000_000.0} " +
                            "boundsCalls=${timing?.boundsCalls ?: -1} boundsTotalMs=${(timing?.boundsTotalNanos ?: 0) / 1_000_000.0} " +
                            "traversalMs=${(timing?.traversalNanos ?: 0) / 1_000_000.0} semanticCheckMs=${(timing?.semanticNanos ?: 0) / 1_000_000.0} " +
                            "credentialGateMs=${credentialGateNanos / 1_000_000.0} totalResolutionMs=$totalResolutionMs " +
                            "x=${rawX.toInt()} y=${rawY.toInt()} pkg=$actionPackage gateClear=$credentialGateClear onKeyboard=$touchOnKeyboard -> anchor=$anchor"
                    )
                }
                handler.post { offerRawTouch(recorder, token, anchor, actionPackage, downAtMs) }
            } catch (e: Exception) {
                android.util.Log.w("Calo", "Couldn't resolve raw touch", e)
            } finally {
                touchQueueDepth.decrementAndGet()
                root?.recycle()
            }
        }
    }

    private fun trackGestureMove(x: Float, y: Float) {
        if (gestureToken == null || gestureNotTap) return
        gestureMaxDistance = maxOf(gestureMaxDistance, TapGesture.distance(gestureDownX, gestureDownY, x, y))
        // Lenient: twice the platform slop, so a slightly shaky tap still counts.
        val slop = 2f * android.view.ViewConfiguration.get(this).scaledTouchSlop
        if (!TapGesture.isTap(gestureDownX, gestureDownY, gestureMaxDistance, 0, slop, Long.MAX_VALUE)) {
            markGestureNotTap("moved ${gestureMaxDistance.toInt()}px")
        }
    }

    private fun markGestureNotTap(why: String) {
        gestureNotTap = true
        val token = gestureToken ?: return
        pendingRawCommit?.let { handler.removeCallbacks(it) }
        pendingRawCommit = null
        teachRecorder?.discardTouch(token)
        if (BuildConfig.DEBUG) android.util.Log.d("CaloTouchCapture", "touch $token is not a tap ($why) — not recorded")
    }

    private fun flushPendingRawCommit() {
        val pending = pendingRawCommit ?: return
        handler.removeCallbacks(pending)
        pending.run()
    }

    /** Whether ([x], [y]) is inside the on-screen keyboard's window. */
    private fun isOnKeyboard(x: Int, y: Int): Boolean {
        val bounds = android.graphics.Rect()
        return windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && run {
                w.getBoundsInScreen(bounds)
                bounds.contains(x, y)
            }
        }
    }

    private fun offerRawTouch(
        recorder: TeachRecorder,
        token: Long,
        anchor: com.calo.domain.model.ElementAnchor?,
        actionPackage: String?,
        downAtMs: Long
    ) {
        if (teachRecorder !== recorder) return // teaching stopped meanwhile
        if (!recorder.offerRawTouch(token, anchor)) return
        val commit = object : Runnable {
            override fun run() {
                if (pendingRawCommit === this) pendingRawCommit = null
                recorder.commitRawTouchIfStillPending(token, actionPackage)
                checkpointTeaching()
            }
        }
        pendingRawCommit = commit
        val now = System.currentTimeMillis()
        val delay = if (lastTouchUpAtMs >= downAtMs) {
            (lastTouchUpAtMs + RAW_TOUCH_COMMIT_DELAY_MS - now).coerceAtLeast(0)
        } else {
            // Finger still down: re-scheduled to UP + RAW_TOUCH_COMMIT_DELAY_MS when it lifts.
            (downAtMs + RAW_TOUCH_NO_UP_TIMEOUT_MS - now).coerceAtLeast(0)
        }
        handler.postDelayed(commit, delay)
    }
}
