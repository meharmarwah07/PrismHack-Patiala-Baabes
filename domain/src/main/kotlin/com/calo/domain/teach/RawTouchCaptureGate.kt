package com.calo.domain.teach

/**
 * Decides whether Finding 6's raw-touch-coordinate fallback (see
 * TeachRecorder.onRawTouchDown) is allowed to run at all for a given
 * touch-down, independent of whether an anchor can actually be resolved.
 * Two hard refusals, matching this project's fail-closed approach
 * elsewhere (CredentialGateRules, the null-source CLICK recovery):
 *
 *  - the screen is CredentialGate-blocked (payment/OTP/login) — recording
 *    ANY coordinate off a screen the gate itself refuses to describe in
 *    detail would be recording exactly the kind of sensitive screen this
 *    project already goes out of its way not to touch.
 *  - the keyboard/IME window is visible — a touch-down while the keyboard
 *    is up is far more likely to be a keystroke than a tap on the
 *    underlying screen, and TouchInteractionController's raw coordinates
 *    carry no signal to tell those apart.
 *
 * Zero Android dependency, same reasoning as TeachPackageFilter/
 * TargetPackageTracker: CaloAccessibilityService reads the real
 * GateVerdict/AccessibilityWindowInfo state and reduces it to the two
 * booleans this pure function needs.
 */
object RawTouchCaptureGate {
    /**
     * [touchOnKeyboard]: the touch landed inside the on-screen keyboard's
     * window. Those coordinates are key presses — recording them would
     * amount to logging what the user types — so they're always refused.
     *
     * Changed 27 Sep 2026 from "refuse every touch while the keyboard is
     * visible": that also dropped the tap on a search suggestion/result
     * made with the keyboard still up (confirmed on-device, Zomato: the
     * Burger King result tap was never recorded), and the reason it was
     * added — real key taps being dropped while touch capture was on — is
     * believed to have been the since-fixed delegation delay (touches were
     * held back while the whole screen was read on the main thread).
     */
    fun isCaptureAllowed(credentialGateClear: Boolean, touchOnKeyboard: Boolean): Boolean =
        credentialGateClear && !touchOnKeyboard
}
