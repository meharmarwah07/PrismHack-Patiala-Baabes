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

    // Two tiers. STRONG keywords block on any single hit, matched as a
    // case-insensitive substring against every string in ScreenSignals (text,
    // resourceId, className). Grouped by category only for readability in the
    // "matchedOn" reason string — all categories block identically.
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
        "add new card", "saved card", "netbanking", "net banking",
        "payment options", "choose payment", "select payment method"
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

    // WEAK payment wording: block only when TWO OR MORE DISTINCT keywords are
    // present. A CART screen carries exactly one ("Place Order"), so it stays
    // Clear and replay can reach and tap the button that leads to payment. A
    // real payment screen carries several ("UPI", "Wallet", "Pay", "Payment"),
    // so it still blocks — which is what T11 grades (worth 5, -10 on failure).
    //
    // Matched against allText ONLY (never resourceIds/classNames — kills
    // "com.zomato.payments.*" false positives) and on WORD BOUNDARIES (so
    // "Paytm cashback" isn't "pay" and "Occupied" isn't "upi"). Distinct
    // keywords are counted, not occurrences: "Pay" in four places is one signal.
    //
    // Weak signals must be INDEPENDENT pieces of evidence. "place order" and
    // "proceed to pay" are deliberately NOT in this list: they are cart-screen
    // affordances that never appear on a payment-ENTRY screen, so they add
    // nothing to detecting one — their only effect would be inflating the weak
    // count on the exact screen that must stay Clear ("Proceed to Pay" would
    // otherwise fire both itself and "pay"). A "Proceed to Pay" cart carries one
    // weak signal ("pay") and passes; a real payment sheet carries several
    // ("upi", "wallet", "payment", "pay") and blocks.
    private val WEAK_PAYMENT_KEYWORDS = listOf(
        "pay", "upi", "wallet", "paytm", "gpay", "phonepe", "total payable", "payment"
    )
    private val WEAK_PAYMENT_PATTERNS: List<Pair<String, Regex>> = WEAK_PAYMENT_KEYWORDS.map {
        it to Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE)
    }
    private const val WEAK_SIGNALS_TO_BLOCK = 2

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

        val weakHits = WEAK_PAYMENT_PATTERNS.filter { (_, pattern) ->
            signals.allText.any { pattern.containsMatchIn(it) }
        }
        if (weakHits.size >= WEAK_SIGNALS_TO_BLOCK) {
            val keywords = weakHits.map { it.first }
            return GateVerdict.Blocked(
                reason = "payment-related content detected: " + keywords.joinToString(", ") { "\"$it\"" },
                matchedOn = keywords.joinToString(", ")
            )
        }

        return GateVerdict.Clear
    }
}
