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

    // --- T11 checkout-wording: two-tier scheme ---------------------------
    // A single weak keyword (Pay / Place order / Total payable) is what the
    // CART screen shows and must stay Clear; several together mean a real
    // payment screen and must block.

    @Test
    fun `cart screen with Proceed to Pay stays clear so replay can reach payment`() {
        val verdict = CredentialGateRules.classify(clear("Your cart", "Margherita Pizza", "449", "Proceed to Pay"))
        assertEquals(GateVerdict.Clear, verdict)
    }

    @Test
    fun `blocks on Pay via UPI`() {
        assertTrue(CredentialGateRules.classify(clear("Pay via UPI")) is GateVerdict.Blocked)
    }

    @Test
    fun `a single weak keyword alone does not block`() {
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Pay", "₹450")))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Place order")))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Proceed to pay")))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Pay ₹450")))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Total payable: ₹450")))
    }

    @Test
    fun `cart screen with Place Order stays clear so replay can reach payment`() {
        val verdict = CredentialGateRules.classify(clear("Your cart", "Margherita Pizza", "449", "Place Order"))
        assertEquals(GateVerdict.Clear, verdict)
    }

    @Test
    fun `payment screen with several weak signals blocks and names them`() {
        val verdict = CredentialGateRules.classify(clear("Payment Options", "UPI", "Wallet", "Pay 449"))
        assertTrue(verdict is GateVerdict.Blocked)
        val reason = (verdict as GateVerdict.Blocked).reason
        // "Payment Options" is also a strong keyword, so it may fire on that path
        // first; either way the verdict is Blocked. Weak-path wording is asserted below.
        assertTrue(reason.contains("payment"))
    }

    @Test
    fun `weak path reason lists every distinct weak keyword that fired`() {
        val verdict = CredentialGateRules.classify(clear("UPI", "Wallet", "Pay 449")) as GateVerdict.Blocked
        assertTrue(verdict.reason.startsWith("payment-related content detected:"))
        for (kw in listOf("upi", "wallet", "pay")) {
            assertTrue("reason should name $kw: ${verdict.reason}", verdict.reason.contains("\"$kw\""))
        }
        assertTrue(verdict.matchedOn.isNotBlank())
    }

    @Test
    fun `weak keywords are counted distinct not by occurrence`() {
        val verdict = CredentialGateRules.classify(clear("Pay", "Pay now", "Pay later", "Pay 449"))
        assertEquals(GateVerdict.Clear, verdict)
    }

    @Test
    fun `payment screen with only a card field blocks via the strong path`() {
        assertTrue(CredentialGateRules.classify(clear("Card number", "CVV")) is GateVerdict.Blocked)
    }

    @Test
    fun `new strong payment keywords block on their own`() {
        for (text in listOf("Add new card", "Saved card", "Netbanking", "Net banking",
            "Choose payment", "Select payment method")) {
            assertTrue(text, CredentialGateRules.classify(clear(text)) is GateVerdict.Blocked)
        }
    }

    @Test
    fun `payments class name on a home screen does not block`() {
        val signals = ScreenSignals(
            packageName = "com.zomato",
            allText = listOf("Delivery", "Dining"),
            classNames = listOf("com.zomato.payments.PayButton")
        )
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(signals))
    }

    @Test
    fun `weak keywords in resource ids do not count`() {
        val signals = clear("Home", resourceIds = listOf("com.zomato:id/pay_upi_wallet_payment"))
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(signals))
    }

    @Test
    fun `Paytm cashback offer alone does not block`() {
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Paytm cashback offer")))
    }

    @Test
    fun `Occupied does not substring-match upi`() {
        assertEquals(GateVerdict.Clear, CredentialGateRules.classify(clear("Occupied")))
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
