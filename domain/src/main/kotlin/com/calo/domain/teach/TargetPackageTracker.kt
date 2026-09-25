package com.calo.domain.teach

/**
 * Decides TeachRecorder's targetPackage as recordable actions arrive.
 *
 * Only ever fed CLICK/SET_TEXT actions whose package already passed
 * [TeachPackageFilter.isExcluded] — a real teach session always goes Calo
 * -> home screen -> target app, so the first CLICK/SET_TEXT seen after
 * exclusion is the real target. SCROLL is deliberately never fed here:
 * a scroll observed before the first tap (a stray notification-shade
 * drag, an incidental list scroll while the person is still getting their
 * bearings on the target screen) must never latch targetPackage on its
 * own — only a genuine tap or text entry should.
 *
 * Once latched, targetPackage is never overwritten — see [record]'s doc
 * on why a later action in a different package produces a warning instead
 * of silently merging or replacing it.
 */
object TargetPackageTracker {

    /**
     * [targetPackage] is always [currentTargetPackage] if it was already
     * non-null (latching is one-way), or [actionPackage] if this is the
     * first action seen this session.
     *
     * [warning] is non-null exactly when [actionPackage] differs from an
     * already-latched [currentTargetPackage] — i.e. the session's actions
     * span two or more non-excluded packages. The caller (TeachRecorder)
     * logs this rather than silently merging the two apps' steps into one
     * flow, or silently discarding the second app's steps without a trace
     * — per this project's fail-closed-and-visible approach elsewhere
     * (CredentialGateRules, the null-source CLICK recovery's logging).
     * targetPackage itself is NOT changed by a warning: the first
     * non-excluded package stays authoritative.
     */
    data class Outcome(val targetPackage: String, val warning: String?)

    fun record(currentTargetPackage: String?, actionPackage: String): Outcome {
        if (currentTargetPackage == null) {
            return Outcome(targetPackage = actionPackage, warning = null)
        }
        if (actionPackage == currentTargetPackage) {
            return Outcome(targetPackage = currentTargetPackage, warning = null)
        }
        return Outcome(
            targetPackage = currentTargetPackage,
            warning = "Teach session action package \"$actionPackage\" differs from already-latched " +
                "targetPackage \"$currentTargetPackage\" — keeping \"$currentTargetPackage\", not merging."
        )
    }
}
