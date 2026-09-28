package com.calo.domain.teach

/**
 * Whether a finger touch was a TAP — landed and lifted in about the same
 * place, without being held — as opposed to a swipe/scroll or long-press.
 *
 * Confirmed on-device (Zomato, 27 Sep 2026): quickly scrolling a search
 * results list produced three touch-downs in under a second, and each was
 * saved as a CLICK on whatever row happened to be under the finger when
 * the swipe began ("Thalaiva Biryani" x3, then a promo banner). Replay then
 * tapped all of them. Only touches that pass this check may become a
 * recorded CLICK.
 */
object TapGesture {

    /**
     * [slopPx]: how far the finger may drift and still be a tap (the
     * caller passes a lenient multiple of the platform touch slop).
     * [longPressMs]: held at least this long = a long-press, not a tap.
     */
    fun isTap(downX: Float, downY: Float, maxDistancePx: Float, heldMs: Long, slopPx: Float, longPressMs: Long): Boolean =
        maxDistancePx <= slopPx && heldMs < longPressMs

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
