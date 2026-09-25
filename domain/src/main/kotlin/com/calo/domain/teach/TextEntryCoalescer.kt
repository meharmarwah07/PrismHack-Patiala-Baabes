package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor

/**
 * Decides whether a TYPE_VIEW_TEXT_CHANGED event belongs to the SAME field
 * entry as the immediately-preceding SET_TEXT step (should UPDATE that
 * step's recordedValue in place), or starts a new one.
 *
 * Deliberately NOT time-based, unlike [ScrollCoalescer]: a real pause
 * mid-word (the user stops to think, or just types slowly) can easily
 * exceed any fixed debounce window while still being the same field
 * entry, so a fixed-time cutoff would wrongly split one entry into
 * several steps. The reliable signal here is NODE IDENTITY instead — same
 * resourceId/contentDescription/className+indexInParent as the field
 * currently being typed into — which doesn't degrade no matter how long
 * the user pauses between keystrokes.
 *
 * `text` is deliberately excluded from the identity comparison: it's the
 * one [ElementAnchor] field guaranteed to change on every keystroke
 * (that's the whole point of a text-entry burst), so comparing it would
 * make every single event look like a "different node."
 */
object TextEntryCoalescer {

    /**
     * [newAnchor] is a fresh anchor for the node the incoming event
     * targets (built the normal way — its `text` field is ignored here,
     * so callers don't need to special-case it). [lastTextEntryAnchor] is
     * the anchor of the field the immediately-preceding SET_TEXT step
     * updated, or null if there isn't one (no prior text entry, or a
     * CLICK/SCROLL/different node broke the burst since).
     */
    fun isSameFieldEntry(newAnchor: ElementAnchor, lastTextEntryAnchor: ElementAnchor?): Boolean {
        if (lastTextEntryAnchor == null) return false
        return newAnchor.copy(text = null) == lastTextEntryAnchor.copy(text = null)
    }
}
