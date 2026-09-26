package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor
import com.calo.domain.replay.ContextPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDedupTest {

    private val addRaw = ElementAnchor(resourceId = "z:id/ll_root", contentDescription = "Add item. customisable", indexInParent = 0)

    @Test
    fun `real click right after the raw-touch commit of the same button is the same tap`() {
        assertTrue(TapDedup.isSameTap(addRaw, 1000, addRaw.copy(text = "ADD"), 1400))
    }

    @Test
    fun `same button tapped again later is a new tap`() {
        assertFalse(TapDedup.isSameTap(addRaw, 1000, addRaw, 1000 + TapDedup.WINDOW_MS + 1))
    }

    @Test
    fun `different element is never the same tap`() {
        val viewCart = ElementAnchor(resourceId = "z:id/container", indexInParent = 0)
        assertFalse(TapDedup.isSameTap(addRaw, 1000, viewCart, 1100))
    }

    @Test
    fun `Burger King text label from both paths is the same element`() {
        val a = ElementAnchor(text = "Burger King", className = "android.view.View", indexInParent = 0)
        assertTrue(TapDedup.isSameElement(a, a.copy(indexInParent = 3)))
    }

    @Test
    fun `two unlabelled views are only the same element at the same position`() {
        val a = ElementAnchor(className = "android.view.View", indexInParent = 0)
        assertTrue(TapDedup.isSameElement(a, a))
        assertFalse(TapDedup.isSameElement(a, a.copy(indexInParent = 1)))
    }

    // ---- ContextPicker: which of several identical ADD buttons ----

    private val taught = "Crispy Veg Burger. | Highly reordered | ₹70 | Get for ₹59 | Our Best Seller - Crispy Veg Patty"

    @Test
    fun `picks the ADD next to the taught dish`() {
        val rows = listOf(
            "Veg Whopper | ₹189 | Signature flame grilled patty",
            "Crispy Veg Burger. | Highly reordered | ₹75 | Get for ₹59 | Our Best Seller - Crispy Veg Patty",
            "Chicken Wings | ₹129"
        )
        assertEquals(1, ContextPicker.pick(taught, rows))
    }

    @Test
    fun `taught dish not on screen means no pick, not the first ADD`() {
        assertNull(ContextPicker.pick(taught, listOf("Veg Whopper | ₹189", "Chicken Wings | ₹129")))
    }

    @Test
    fun `single candidate or no recorded context keeps old behaviour`() {
        assertEquals(0, ContextPicker.pick(taught, listOf("anything")))
        assertEquals(0, ContextPicker.pick(null, listOf("a", "b")))
    }
}
