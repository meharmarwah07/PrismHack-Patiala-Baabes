package com.calo.domain.gate

/** Why replay stopped — surfaced to the UI as "Stuck: <reason>", and to logs/T11 grading. */
sealed class GateVerdict {
    data class Blocked(val reason: String, val matchedOn: String) : GateVerdict()
    data object Clear : GateVerdict()
}

/**
 * Deterministic, rule-based sensitive-screen detector. NOT an LLM call —
 * on purpose. A gate that can be wrong in either direction is bad, but a
 * gate that can be *talked out of blocking* by adversarial screen text is
 * worse, so this stays plain keyword/flag matching that anyone can audit
 * by reading it, and it always fails closed (see [classify]'s doc).
 *
 * ReplayEngine (in :app) is required to call [classify] before executing
 * EVERY step, not just once at the start of a flow — a flow can navigate
 * into a login/payment screen midway (e.g. session timeout, "add a card"
 * mid-checkout) that didn't exist when the flow was taught.
 */
object CredentialGateRules {

    // Matched as a case-insensitive substring against every string in
    // ScreenSignals (text, resourceId, className). Grouped by category only
    // for readability in the "matchedOn" reason string — all categories
    // block identically.
    private val PASSWORD_KEYWORDS = listOf(
        "password", "passwd", "pwd", "passcode"
    )
    private val OTP_KEYWORDS = listOf(
        "otp", "one-time code", "one time code", "one-time passcode",
        "verification code", "security code", "auth code", "2fa", "mfa",
        "two-factor", "two factor"
    )
    private val PAYMENT_KEYWORDS = listOf(
        "card number", "cardnumber", "card_number", "cvv", "cvc",
        "expiry", "exp date", "expiration date", "billing address",
        "upi pin", "bank account", "ifsc", "routing number", "swift code",
        // Added for T11 (2026-09-26): checkout-flow wording confirmed missing
        // from the original keyword set — "pay"/"upi" alone (not just "upi
        // pin") are what real checkout screens (Zomato, Dominos) actually
        // show on the final confirm-and-pay button/page.
        "pay", "proceed to pay", "place order", "upi", "pay ₹", "total payable"
    )
    private val LOGIN_KEYWORDS = listOf(
        "sign in", "signin", "log in", "login", "authenticate",
        "biometric", "fingerprint", "face unlock", "enter your pin",
        "confirm your identity"
    )

    private val ALL_KEYWORDS: List<Pair<String, String>> =
        PASSWORD_KEYWORDS.map { it to "password" } +
        OTP_KEYWORDS.map { it to "otp" } +
        PAYMENT_KEYWORDS.map { it to "payment" } +
        LOGIN_KEYWORDS.map { it to "login" }

    /**
     * Fails closed: if signals are ambiguous or the caller passed nothing
     * useful (e.g. resolution failed and allText is empty), that is NOT
     * treated as "safe" — callers must decide separately whether "no
     * signal" means "couldn't inspect the screen" (which ReplayEngine
     * should treat as Stuck, never as Clear-to-proceed) vs. "genuinely
     * clear" (an empty screen with a password field would still have
     * hasPasswordField=true regardless of text, so this holds).
     */
    fun classify(signals: ScreenSignals): GateVerdict {
        if (!signals.readable) {
            return GateVerdict.Blocked(
                reason = "could not read screen content to check for sensitive fields",
                matchedOn = "readable=false"
            )
        }
        if (signals.hasPasswordField) {
            return GateVerdict.Blocked(
                reason = "password input field detected on screen",
                matchedOn = "AccessibilityNodeInfo.isPassword"
            )
        }

        val haystacks = signals.allText + signals.resourceIds + signals.classNames
        for (haystack in haystacks) {
            val normalized = haystack.lowercase()
            for ((keyword, category) in ALL_KEYWORDS) {
                if (normalized.contains(keyword)) {
                    return GateVerdict.Blocked(
                        reason = "$category-related content detected: \"$keyword\"",
                        matchedOn = haystack
                    )
                }
            }
        }

        return GateVerdict.Clear
    }
}
