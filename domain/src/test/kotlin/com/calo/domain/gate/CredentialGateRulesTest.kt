package com.calo.domain.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the T11 test case made concrete: a negative score in the rubric
 * means "acted on a screen it should have refused to touch." Every test
 * here is either "must block" or "must not block a normal screen" — there
 * is no partial credit, so these assert the verdict TYPE, not just that
 * classify() returned something.
 */
class CredentialGateRulesTest {

    private fun clear(vararg text: String, resourceIds: List<String> = emptyList()) =
        ScreenSignals(packageName = "com.example.app", allText = text.toList(), resourceIds = resourceIds)

    // --- must block ---------------------------------------------------

    @Test
    fun `blocks when a password field is present regardless of text`() {
        val signals = ScreenSignals(
            packageName = "com.example.app",
            allText = listOf("Continue"), // deliberately bland/adversarial text
            hasPasswordField = true
        )
        val verdict = CredentialGateRules.classify(signals)
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on OTP screen text`() {
        val verdict = CredentialGateRules.classify(clear("Enter the 6-digit verification code sent to your phone"))
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on payment card entry via resourceId even with bland label text`() {
        val signals = clear("Add", resourceIds = listOf("com.dominos.app:id/card_number_input"))
        val verdict = CredentialGateRules.classify(signals)
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on CVV field`() {
        val verdict = CredentialGateRules.classify(clear("CVV", "3 digits on back of card"))
        assertTrue(verdict is GateVerdict.Blocked)
    }

    // --- T11 checkout-wording keywords (2026-09-26) --------------------

    @Test
    fun `blocks on a bare Pay button`() {
        assertTrue(CredentialGateRules.classify(clear("Pay", "₹450")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on Proceed to pay`() {
        assertTrue(CredentialGateRules.classify(clear("Proceed to pay")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on Place order`() {
        assertTrue(CredentialGateRules.classify(clear("Place order")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on UPI (not just UPI PIN)`() {
        assertTrue(CredentialGateRules.classify(clear("Pay via UPI")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on a rupee-denominated pay amount`() {
        assertTrue(CredentialGateRules.classify(clear("Pay ₹450")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on Total payable`() {
        assertTrue(CredentialGateRules.classify(clear("Total payable: ₹450")) is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on generic login screen wording`() {
        val verdict = CredentialGateRules.classify(clear("Sign in to continue", "Forgot password?"))
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `blocks on biometric confirm prompt`() {
        val verdict = CredentialGateRules.classify(clear("Confirm your identity", "Use fingerprint to continue"))
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `case and punctuation do not evade the gate`() {
        val verdict = CredentialGateRules.classify(clear("PLEASE ENTER YOUR OTP NOW!!"))
        assertTrue(verdict is GateVerdict.Blocked)
    }

    @Test
    fun `blocked verdict carries a human-readable reason`() {
        val verdict = CredentialGateRules.classify(clear("Enter password")) as GateVerdict.Blocked
        assertTrue(verdict.reason.contains("password"))
    }

    @Test
    fun `an unreadable screen fails closed -- blocked, never treated as clear`() {
        val unreadable = ScreenSignals(packageName = "com.example.app", readable = false)
        val verdict = CredentialGateRules.classify(unreadable)
        assertTrue(verdict is GateVerdict.Blocked)
    }

    // --- must NOT block (false positives are also a real cost) --------

    @Test
    fun `does not block an ordinary menu screen`() {
        val verdict = CredentialGateRules.classify(clear("Home", "Search", "Cart", "Profile"))
        assertEquals(GateVerdict.Clear, verdict)
    }

    @Test
    fun `does not block a food ordering step that just says add to cart`() {
        val signals = clear(
            "Margherita Pizza", "Add to cart", "Quantity",
            resourceIds = listOf("com.dominos.app:id/btn_add_to_cart")
        )
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(signals))
    }

    @Test
    fun `a plain numeric quantity field without isPassword is not treated as a PIN`() {
        // Regression guard: "quantity" or plain digit entry must not trip the
        // payment/PIN keyword list just because it's numeric input.
        val signals = clear("Quantity", "2", resourceIds = listOf("com.example.app:id/quantity_stepper"))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(signals))
    }
}
