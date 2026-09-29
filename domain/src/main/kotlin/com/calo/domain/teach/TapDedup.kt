package com.calo.domain.teach

import com.calo.domain.model.ElementAnchor

/**
 * Decides whether a real CLICK event is the SAME physical tap as the
 * raw-touch CLICK committed just before it.
 *
 * Confirmed on-device (Zomato, 27 Sep 2026): nearly every tap in a teach
 * session was saved twice. The raw-touch fallback commits a fixed delay
 * after the finger goes DOWN, but the real TYPE_VIEW_CLICKED only fires
 * when it comes UP (plus the app's own latency), so any tap held slightly
 * longer produced both. Replay then performed the phantom second tap for
 * real — on the next screen, where the same label (the restaurant's name)
 * opened something else entirely.
 *
 * The two anchors come from different paths (point lookup vs the event's
 * own source node) so they needn't be field-for-field equal; this compares
 * the identity each one does carry.
 */
object TapDedup {

    /** A real CLICK this soon after a raw-touch commit can be the same tap. */
    const val WINDOW_MS = 1500L

    fun isSameTap(rawAnchor: ElementAnchor, rawCommittedAtMs: Long, clickAnchor: ElementAnchor, nowMs: Long): Boolean {
        if (nowMs - rawCommittedAtMs > WINDOW_MS) return false
        return isSameElement(rawAnchor, clickAnchor)
    }

    fun isSameElement(a: ElementAnchor, b: ElementAnchor): Boolean {
        if (a.resourceId != null && b.resourceId != null) {
            return a.resourceId == b.resourceId && a.indexInParent == b.indexInParent
        }
        if (!a.contentDescription.isNullOrBlank() && a.contentDescription == b.contentDescription) return true
        if (!a.text.isNullOrBlank() && a.text == b.text) return true
        if (labelNests(label(a), label(b))) return true
        return a.className != null && a.className == b.className && a.indexInParent == b.indexInParent &&
            a.text.isNullOrBlank() && b.text.isNullOrBlank() &&
            a.contentDescription.isNullOrBlank() && b.contentDescription.isNullOrBlank()
    }

    private fun label(a: ElementAnchor): String? =
        a.contentDescription?.takeIf { it.isNotBlank() } ?: a.text?.takeIf { it.isNotBlank() }

    /**
     * A web link and the title inside it: one tap seen twice, once as the
     * link ("Skribbl https://skribbl.io Skribbl") and once as its title text
     * ("Skribbl") — confirmed on-device, Chrome / Google results, 27 Sep
     * 2026. The longer label must START with the shorter one as a whole
     * word, so "Pizza" vs "Pizza Hut" nests but "Hut" vs "Pizza Hut" doesn't.
     */
    private fun labelNests(x: String?, y: String?): Boolean {
        if (x == null || y == null) return false
        val (short, long) = if (x.length <= y.length) x.trim() to y.trim() else y.trim() to x.trim()
        return short.isNotEmpty() && long.length > short.length &&
            long.startsWith(short) && long[short.length].isWhitespace()
    }
}
