package com.calo.domain.replay

/**
 * Tolerant comparison of a taught label with what's on screen now, for
 * labels that carry live data. Confirmed on-device (27 Sep 2026, Zomato):
 * a restaurant card's contentDescription was taught as
 *   "Restaurant Name is Burger King 50% OFF select items delivers in 48 minutes
 *    Swipe up or down for more actions"
 * and by replay time said "...delivers in 35 minutes". Exact matching can
 * never find that card again.
 *
 * Numbers and screen-reader hints ("Swipe up or down for more actions",
 * "double tap to collapse") are dropped, and the remaining words are
 * compared as sets. The bar is deliberately high ([THRESHOLD]) and the best
 * match must be clearly ahead of the runner-up ([MARGIN]): "Burger King
 * ... delivers in N minutes" vs "KFC ... delivers in N minutes" share most
 * of their words, and tapping the wrong restaurant is worse than stopping.
 */
object FuzzyLabel {

    const val THRESHOLD = 0.8
    const val MARGIN = 0.1

    // Talkback usage hints Compose/Views append to labels, sometimes with no
    // space before them ("BeveragesSwipe up or down for more actions").
    private val HINT_PHRASES = listOf(
        "swipe up or down for more actions",
        "double tap to collapse",
        "double tap to expand",
        "double tap to activate",
        "double tap to open"
    )

    fun tokens(label: String?): Set<String> {
        var s = label?.lowercase() ?: return emptySet()
        for (phrase in HINT_PHRASES) s = s.replace(phrase, " ")
        return s.split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }.toSet()
    }

    /** Jaccard similarity of the two labels' word sets, 0.0–1.0. */
    fun similarity(a: String?, b: String?): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return (ta intersect tb).size.toDouble() / (ta union tb).size
    }

    /**
     * Index of the one candidate that clearly matches [taught], or null if
     * none reaches [THRESHOLD] or the top two are within [MARGIN] of each
     * other.
     */
    fun bestUnique(taught: String?, candidates: List<String?>): Int? {
        val scored = candidates.mapIndexed { i, c -> i to similarity(taught, c) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < THRESHOLD) return null
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        return if (best.second - runnerUp >= MARGIN) best.first else null
    }
}
