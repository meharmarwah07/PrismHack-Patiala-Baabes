package com.calo.domain.gate

/**
 * Whether a cached credential-gate verdict from teaching's raw-touch
 * capture path is still safe to reuse instead of re-walking the whole
 * screen tree again (confirmed on-device, 29 Sep 2026, Zomato: the SAME
 * 176-node tree took anywhere from 68ms to 529ms to walk via
 * AccessibilityNodeInfo.getChild()'s cross-process IPC, and this walk ran
 * unconditionally on EVERY touch-down — including ones that turned out to
 * be scrolls, not taps — on a single-threaded queue, so one slow walk
 * delayed every touch behind it. That's what made the phone feel like it
 * had stopped responding while teaching a flow on a dense screen).
 *
 * Deliberately narrow and short-lived — this exists ONLY for teaching's
 * raw-touch capture (deciding whether a tap gets RECORDED as a step), not
 * for replay's own gate check (deciding whether Calo itself is ALLOWED to
 * tap something for real): ReplayEngine constructs its own separate
 * CredentialGate instance per replay and this cache is never wired into
 * it, so replay's "checked fresh before every single step" guarantee
 * (ReplayPlanner's whole reason for existing — see its class doc) is
 * completely untouched by this. The worst case of a stale "clear" here is
 * a tap gets recorded as a step on a screen that had just turned
 * sensitive within the cache window; replay would still correctly refuse
 * to ever perform that step for real, checked fresh at that time.
 *
 * The caller (CaloAccessibilityService) is expected to also eagerly
 * invalidate the cache on any real window-change signal (TYPE_WINDOWS_
 * CHANGED / TYPE_WINDOW_STATE_CHANGED) rather than relying on [ttlMs]
 * alone — this is the backstop for whatever that eager path misses, not
 * the primary safety mechanism.
 */
object GateCacheDecision {

    fun shouldReuse(
        cachedPackageName: String?,
        cachedAtMs: Long?,
        currentPackageName: String,
        nowMs: Long,
        ttlMs: Long
    ): Boolean {
        if (cachedPackageName == null || cachedAtMs == null) return false
        if (cachedPackageName != currentPackageName) return false
        if (nowMs < cachedAtMs) return false // clock moved backward: don't trust it
        return nowMs - cachedAtMs < ttlMs
    }
}
