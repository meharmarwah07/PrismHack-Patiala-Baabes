package com.calo.domain.teach

/**
 * Decides whether an AccessibilityEvent's package should be excluded from
 * teaching entirely — never turned into a FlowStep, never allowed to latch
 * [TargetPackageTracker]'s targetPackage. Three packages are excluded:
 * Calo's own UI, the device's home/launcher, and systemui (the
 * notification shade / quick settings / recents — a real teach session on
 * a physical device can easily pick up a stray swipe-down or back-gesture
 * event from systemui that was never part of the taught flow). Zero
 * Android dependency, same reasoning as ScrollCoalescer/TextEntryCoalescer:
 * TeachRecorder (:app) reads event.packageName off the real
 * AccessibilityEvent and hands the string to this pure function.
 */
object TeachPackageFilter {

    /**
     * Not resolved at runtime (unlike the launcher) because, unlike a
     * launcher, systemui's package name is a fixed part of the Android
     * platform itself, not something an OEM skin swaps out.
     */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * [launcherPackageName] is null when CaloAccessibilityService's
     * runtime resolution failed — degrades to "no extra exclusion" for the
     * launcher specifically, same as TeachRecorder's pre-existing
     * behavior, rather than treating resolution failure as "exclude
     * nothing" across the board.
     */
    /**
     * [keyboardPackageName]: the current on-screen keyboard app. Its events
     * are key/suggestion presses — never a step of the flow, and recording
     * them would amount to logging what the user types. Added 27 Sep 2026
     * when touch capture started staying on while the keyboard is up.
     */
    fun isExcluded(
        packageName: String?,
        ownPackageName: String,
        launcherPackageName: String?,
        keyboardPackageName: String? = null
    ): Boolean {
        if (packageName == null) return true
        if (keyboardPackageName != null && packageName == keyboardPackageName) return true
        if (packageName == ownPackageName) return true
        if (launcherPackageName != null && packageName == launcherPackageName) return true
        if (packageName == SYSTEM_UI_PACKAGE) return true
        return false
    }
}
