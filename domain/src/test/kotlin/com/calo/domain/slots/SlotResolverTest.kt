package com.calo.domain.slots

import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotResolverTest {

    private fun step(action: ActionType, recorded: String?, slotName: String?) = FlowStep(
        order = 1,
        action = action,
        target = ElementAnchor(resourceId = "id/x"),
        recordedValue = recorded,
        slotName = slotName
    )

    @Test
    fun `non-SET_TEXT actions never carry a value`() {
        assertNull(SlotResolver.resolveValue(step(ActionType.CLICK, "irrelevant", null), emptyMap()))
        assertNull(SlotResolver.resolveValue(step(ActionType.SCROLL, null, null), emptyMap()))
        assertNull(SlotResolver.resolveValue(step(ActionType.WAIT, null, null), emptyMap()))
    }

    @Test
    fun `no slotName always uses the recorded literal`() {
        val s = step(ActionType.SET_TEXT, "Margherita", slotName = null)
        assertEquals("Margherita", SlotResolver.resolveValue(s, mapOf("item" to "Pepperoni")))
    }

    @Test
    fun `slotName present and value supplied substitutes it (generalized replay, T4-T6)`() {
        val s = step(ActionType.SET_TEXT, "Margherita", slotName = "item")
        assertEquals("Pepperoni", SlotResolver.resolveValue(s, mapOf("item" to "Pepperoni")))
    }

    @Test
    fun `slotName present but no value supplied falls back to recorded literal (T2, exact replay)`() {
        val s = step(ActionType.SET_TEXT, "Margherita", slotName = "item")
        assertEquals("Margherita", SlotResolver.resolveValue(s, emptyMap()))
    }

    @Test
    fun `empty string is a legitimate substituted value, not treated as missing`() {
        val s = step(ActionType.SET_TEXT, "Margherita", slotName = "item")
        assertEquals("", SlotResolver.resolveValue(s, mapOf("item" to "")))
    }
}
