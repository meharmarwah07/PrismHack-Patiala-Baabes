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

    // ---- resolveClickTarget --------------------------------------------

    private fun clickStep(recordedText: String?, slotName: String?) = FlowStep(
        order = 1,
        action = ActionType.CLICK,
        target = ElementAnchor(resourceId = "id/row", text = recordedText),
        slotName = slotName
    )

    @Test
    fun `resolveClickTarget is null for non-CLICK actions`() {
        val s = step(ActionType.SET_TEXT, "Margherita", slotName = "item")
        assertNull(SlotResolver.resolveClickTarget(s, mapOf("item" to "Pepperoni")))
    }

    @Test
    fun `resolveClickTarget is null when the step has no slotName`() {
        val s = clickStep(recordedText = "Home", slotName = null)
        assertNull(SlotResolver.resolveClickTarget(s, mapOf("address" to "Work")))
    }

    @Test
    fun `resolveClickTarget is null when slotName present but no value supplied (T2, exact replay)`() {
        val s = clickStep(recordedText = "Home", slotName = "address")
        assertNull(SlotResolver.resolveClickTarget(s, emptyMap()))
    }

    @Test
    fun `resolveClickTarget is null when the supplied value matches what was recorded`() {
        val s = clickStep(recordedText = "Home", slotName = "address")
        assertNull(SlotResolver.resolveClickTarget(s, mapOf("address" to "Home")))
    }

    @Test
    fun `resolveClickTarget returns the new value when it differs from what was recorded (the fix)`() {
        val s = clickStep(recordedText = "Home", slotName = "address")
        assertEquals("Work", SlotResolver.resolveClickTarget(s, mapOf("address" to "Work")))
    }

    @Test
    fun `resolveClickTarget falls back to contentDescription when the anchor has no text`() {
        val s = FlowStep(
            order = 1,
            action = ActionType.CLICK,
            target = ElementAnchor(resourceId = "id/row", contentDescription = "Home address"),
            slotName = "address"
        )
        assertEquals("Work address", SlotResolver.resolveClickTarget(s, mapOf("address" to "Work address")))
        assertNull(SlotResolver.resolveClickTarget(s, mapOf("address" to "Home address")))
    }

    @Test
    fun `resolveClickTarget falls back to recordedValue when the anchor has neither text nor contentDescription`() {
        // Matches TeachRecorder's null-source CLICK recovery: the anchor is
        // built from the nearest CLICKABLE ancestor (often a bare container),
        // so both text and contentDescription can be null even though the
        // step DOES carry a meaningful recorded label.
        val s = FlowStep(
            order = 1,
            action = ActionType.CLICK,
            target = ElementAnchor(className = "android.widget.LinearLayout", indexInParent = 4),
            recordedValue = "Battery",
            slotName = "section"
        )
        assertEquals("Network & internet", SlotResolver.resolveClickTarget(s, mapOf("section" to "Network & internet")))
        assertNull(SlotResolver.resolveClickTarget(s, mapOf("section" to "Battery")))
    }
}
