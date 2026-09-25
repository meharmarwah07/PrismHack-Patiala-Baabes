package com.calo.domain.teach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTouchCaptureGateTest {

    @Test
    fun `clear screen, no keyboard - capture allowed`() {
        assertTrue(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = true, keyboardVisible = false))
    }

    @Test
    fun `CredentialGate-blocked screen - no coordinates recorded, keyboard hidden or not`() {
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = false, keyboardVisible = false))
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = false, keyboardVisible = true))
    }

    @Test
    fun `keyboard visible - no coordinates recorded even on an otherwise-clear screen`() {
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = true, keyboardVisible = true))
    }
}
