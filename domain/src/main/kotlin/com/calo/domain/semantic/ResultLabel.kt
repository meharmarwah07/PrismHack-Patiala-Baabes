package com.calo.domain.semantic

/**
 * Pulls a stable "which result did the user pick" label out of a CLICK
 * anchor's contentDescription, for SELECT_RESULT steps whose anchor has no
 * text (2026-09-30, on-device, Zomato: a restaurant card labelled only by
 * contentDescription "Restaurant Name is Bake By Ecco ₹100 OFF above ₹199
 * delivers in 25 minutesSwipe up or down for more actions").
 *
 * Only the leading name is stable: everything from the first currency
 * symbol or digit onward (offers, delivery time) changes between runs, and
 * trailing accessibility boilerplate is never part of the name. Returns null
 * when nothing usable is left, so the caller falls back to positional
 * matching exactly as before.
 */
object ResultLabel {

    private val LEADING_BOILERPLATE = Regex("^restaurant name is\\s+", RegexOption.IGNORE_CASE)
    private val TRAILING_BOILERPLATE = Regex("swipe up or down.*$", RegexOption.IGNORE_CASE)
    private val VOLATILE_START = Regex("[₹$€£\\d]")
    private const val MIN_LENGTH = 3

    /** Lowercased, whitespace-normalized: for matching against screen text. */
    fun fromContentDescription(contentDescription: String?): String? =
        displayName(contentDescription)?.let { RoleKeywords.normalize(it) }

    /** Same extraction, original casing: for showing or speaking to the user. */
    fun displayName(contentDescription: String?): String? {
        var s = contentDescription?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (s.isEmpty()) return null
        s = LEADING_BOILERPLATE.replace(s, "")
        s = TRAILING_BOILERPLATE.replace(s, "")
        VOLATILE_START.find(s)?.let { s = s.substring(0, it.range.first) }
        s = s.trim()
        return s.takeIf { it.length >= MIN_LENGTH }
    }
}
