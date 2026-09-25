package com.calo.domain.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClickValueMatcherTest {

    private fun resolve(candidates: List<String>, value: String) =
        ClickValueMatcher.resolve(candidates, value) { it }

    @Test
    fun `exact match, single candidate`() {
        assertEquals("Farmhouse", resolve(listOf("Margherita", "Farmhouse"), "Farmhouse"))
    }

    @Test
    fun `exact match is case-insensitive`() {
        assertEquals("FARMHOUSE", resolve(listOf("FARMHOUSE"), "farmhouse"))
    }

    @Test
    fun `exact match is trimmed on both sides`() {
        assertEquals(" Farmhouse ", resolve(listOf(" Farmhouse "), "farmhouse"))
        assertEquals("Farmhouse", resolve(listOf("Farmhouse"), "  farmhouse  "))
    }

    @Test
    fun `zero candidates -- Stuck (no exact, no contains)`() {
        assertNull(resolve(listOf("Margherita", "Pepperoni"), "Farmhouse"))
    }

    @Test
    fun `zero candidates on an empty screen`() {
        assertNull(resolve(emptyList(), "Farmhouse"))
    }

    @Test
    fun `two exact candidates -- Stuck, never guesses between them`() {
        // Two distinct on-screen elements happen to carry the identical label.
        assertNull(resolve(listOf("Farmhouse", "Farmhouse"), "Farmhouse"))
    }

    @Test
    fun `two exact candidates never falls through to a contains tiebreak`() {
        // "Farmhouse" also contains-matches nothing else here, but even if a
        // contains candidate existed, 2+ exact matches must short-circuit.
        assertNull(resolve(listOf("Farmhouse", "Farmhouse", "Farmhouse Deluxe"), "Farmhouse"))
    }

    @Test
    fun `contains fallback used only when exact match fails, and only if unique`() {
        assertEquals("Farmhouse Deluxe", resolve(listOf("Margherita", "Farmhouse Deluxe"), "Farmhouse"))
    }

    @Test
    fun `contains fallback is case-insensitive and trimmed too`() {
        assertEquals("FARMHOUSE DELUXE", resolve(listOf("FARMHOUSE DELUXE"), " farmhouse "))
    }

    @Test
    fun `contains fallback with two candidates -- Stuck, don't guess`() {
        assertNull(resolve(listOf("Farmhouse Deluxe", "Farmhouse Feast"), "Farmhouse"))
    }

    @Test
    fun `contains fallback with zero candidates -- Stuck`() {
        assertNull(resolve(listOf("Margherita", "Pepperoni"), "Farmhouse"))
    }
}
