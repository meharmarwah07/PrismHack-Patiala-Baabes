package com.calo.domain.teach

/**
 * One finger touch → at most one recorded CLICK.
 *
 * Confirmed on-device (Zomato, 27 Sep 2026): the app fires
 * TYPE_VIEW_CLICKED events nobody tapped — "Recommended for you double tap
 * to collapse" as a restaurant menu loads — and TeachRecorder saved them as
 * steps (one was even labelled SELECT_RESULT). Replaying a phantom tap
 * collapses the very menu section the next step needs.
 *
 * While raw touch capture is live (Android 13+, keyboard hidden), every
 * real tap is seen as a touch-down first, so a real CLICK event is only
 * recorded if it can claim a recent touch-down that no other step has
 * claimed yet. When capture isn't live (keyboard up, older Android) there
 * is no touch signal to check against, and every CLICK is accepted as
 * before.
 */
object TouchClaim {

    /** A CLICK event this long after the touch-down can still belong to it (finger up + app latency). */
    const val WINDOW_MS = 1500L

    fun canClaim(captureLive: Boolean, touchAlreadyClaimed: Boolean, lastTouchDownAtMs: Long?, nowMs: Long): Boolean {
        if (!captureLive) return true
        if (touchAlreadyClaimed || lastTouchDownAtMs == null) return false
        return nowMs - lastTouchDownAtMs <= WINDOW_MS
    }
}
