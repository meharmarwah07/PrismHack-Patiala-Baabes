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
    fun isCaptureAllowed(credentialGateClear: Boolean, keyboardVisible: Boolean): Boolean =
        credentialGateClear && !keyboardVisible
}
