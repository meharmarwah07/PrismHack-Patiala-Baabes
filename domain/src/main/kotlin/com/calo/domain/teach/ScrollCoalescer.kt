package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor

/**
 * Decides whether a TYPE_VIEW_SCROLLED AccessibilityEvent should become a
 * NEW SCROLL FlowStep, or be folded into the one just recorded.
 *
 * A single physical scroll gesture (one finger drag) fires many scroll
 * events in quick succession — without coalescing, one real scroll during
 * teaching would record a run of near-duplicate SCROLL steps that replay
 * then tries to reproduce one at a time, none of which individually
 * matches what the user actually did. Zero Android dependency: the
 * decision only needs the anchor being scrolled and two timestamps, so
 * TeachRecorder (:app) computes the ElementAnchor from the real
 * AccessibilityEvent and hands the rest off to this pure function.
 */
object ScrollCoalescer {

    /**
     * 300ms: comfortably longer than the gap between TYPE_VIEW_SCROLLED
     * events within one continuous drag (they fire far faster than a
     * finger can pause mid-gesture), but short enough that two genuinely
     * separate scrolls — scroll down, look around, scroll again — still
     * produce two distinct steps rather than getting merged into one.
     */
    const val COALESCE_WINDOW_MS = 300L

    /**
     * True if a scroll event on [newAnchor] belongs to the same burst as
     * the last-recorded SCROLL step, and should NOT become a new FlowStep.
     * [lastScrollAnchor]/[lastScrollAtMs] are null when no SCROLL step has
     * been recorded yet this session (or the last step recorded was a
     * different action entirely) — always false in that case, since
     * there's nothing to coalesce into.
     */
    fun shouldCoalesce(
        newAnchor: ElementAnchor,
        lastScrollAnchor: ElementAnchor?,
        lastScrollAtMs: Long?,
        nowMs: Long
    ): Boolean {
        if (lastScrollAnchor == null || lastScrollAtMs == null) return false
        if (newAnchor != lastScrollAnchor) return false
        return (nowMs - lastScrollAtMs) <= COALESCE_WINDOW_MS
    }
}
