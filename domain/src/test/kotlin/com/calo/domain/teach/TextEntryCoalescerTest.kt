package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEntryCoalescerTest {

    private val fieldById = ElementAnchor(resourceId = "id/item_search", text = "M")
    private val sameFieldLaterKeystroke = ElementAnchor(resourceId = "id/item_search", text = "Ma")
    private val differentFieldById = ElementAnchor(resourceId = "id/quantity", text = "M")

    private val fieldByPosition = ElementAnchor(className = "android.widget.EditText", indexInParent = 2, text = "A")
    private val samePositionLaterKeystroke =
        ElementAnchor(className = "android.widget.EditText", indexInParent = 2, text = "Al")
    private val differentPositionSameClass =
        ElementAnchor(className = "android.widget.EditText", indexInParent = 3, text = "A")

    @Test
    fun `no prior text entry never coalesces`() {
        assertFalse(TextEntryCoalescer.isSameFieldEntry(fieldById, lastTextEntryAnchor = null))
    }

    @Test
    fun `same resourceId coalesces even though text differs (another keystroke into the same field)`() {
        assertTrue(TextEntryCoalescer.isSameFieldEntry(sameFieldLaterKeystroke, lastTextEntryAnchor = fieldById))
    }

    @Test
    fun `different resourceId does not coalesce`() {
        assertFalse(TextEntryCoalescer.isSameFieldEntry(differentFieldById, lastTextEntryAnchor = fieldById))
    }

    @Test
    fun `no resourceId falls back to className+indexInParent, same position coalesces`() {
        assertTrue(
            TextEntryCoalescer.isSameFieldEntry(samePositionLaterKeystroke, lastTextEntryAnchor = fieldByPosition)
        )
    }

    @Test
    fun `no resourceId, different indexInParent does not coalesce`() {
        assertFalse(
            TextEntryCoalescer.isSameFieldEntry(differentPositionSameClass, lastTextEntryAnchor = fieldByPosition)
        )
    }

    @Test
    fun `identical anchors except text still coalesce regardless of how long the text has grown`() {
        val firstKeystroke = ElementAnchor(resourceId = "id/notes", text = "")
        val tenthKeystroke = ElementAnchor(resourceId = "id/notes", text = "hello worl")
        assertTrue(TextEntryCoalescer.isSameFieldEntry(tenthKeystroke, lastTextEntryAnchor = firstKeystroke))
    }
}
