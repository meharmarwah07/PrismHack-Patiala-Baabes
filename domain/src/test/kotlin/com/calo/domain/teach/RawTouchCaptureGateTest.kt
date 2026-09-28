package com.calo.domain.teach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTouchCaptureGateTest {

    @Test
    fun `clear screen, touch not on the keyboard - capture allowed`() {
        assertTrue(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = true, touchOnKeyboard = false))
    }

    @Test
    fun `CredentialGate-blocked screen - no coordinates recorded, on the keyboard or not`() {
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = false, touchOnKeyboard = false))
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = false, touchOnKeyboard = true))
    }

    @Test
    fun `touch on the keyboard - never recorded, even on an otherwise-clear screen`() {
        assertFalse(RawTouchCaptureGate.isCaptureAllowed(credentialGateClear = true, touchOnKeyboard = true))
    }
}
