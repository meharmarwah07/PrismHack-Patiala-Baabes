package com.calo.domain.semantic

/**
 * The shared vocabulary [RoleLabeler] (teach time) and [RoleMatcher] (replay
 * time) both use, so "what counts as an add-to-cart button" can't drift
 * between labelling a step and finding it again on another app.
 *
 * Every check reads four cues, all lower-cased: the visible label, the
 * accessibility contentDescription, the input hint, and the resource-id's
 * entry name with underscores turned into spaces ("add_to_cart_button" →
 * "add to cart button"), since many apps name ids more consistently than
 * they write labels.
 */
object RoleKeywords {

    data class Cues(
        val label: String,
        val contentDescription: String,
        val hint: String,
        val idWords: String
    ) {
        val all: List<String> get() = listOf(label, contentDescription, hint, idWords)
    }

    fun cues(label: String?, contentDescription: String?, hint: String?, resourceId: String?) = Cues(
        label = normalize(label),
        contentDescription = normalize(contentDescription),
        hint = normalize(hint),
        idWords = normalize(resourceId?.substringAfter(":id/")?.replace('_', ' ')?.replace('-', ' '))
    )

    fun normalize(s: String?): String =
        s?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private val ADD_TO_CART_PHRASES = listOf("add to cart", "add to bag", "add to basket", "add to trolley", "add item")

    // Food-delivery apps (Zomato, Swiggy) label the button just "ADD".
    private val ADD_EXACT = setOf("add", "add +", "+ add", "+add")

    private val BUY_NOW_PHRASES = listOf("buy now", "buy it now")

    private val CHECKOUT_PHRASES = listOf(
        "checkout", "check out", "proceed to buy", "proceed to checkout", "place order", "continue to checkout"
    )

    private val CART_WORD = Regex("\\b(cart|bag|basket)\\b")

    // Things that live in or above a result list but are never a result.
    private val NOT_A_RESULT = listOf("sort", "filter", "wishlist", "share", "navigate up", "more options", "back")

    fun isAddToCart(c: Cues): Boolean {
        if (c.all.any { it.contains("wishlist") }) return false
        return c.all.any { cue -> ADD_TO_CART_PHRASES.any { cue.contains(it) } } ||
            c.label in ADD_EXACT || c.contentDescription in ADD_EXACT
    }

    fun isBuyNow(c: Cues): Boolean = c.all.any { cue -> BUY_NOW_PHRASES.any { cue.contains(it) } }

    fun isCheckout(c: Cues): Boolean = c.all.any { cue -> CHECKOUT_PHRASES.any { cue.contains(it) } }

    /**
     * Only a SHORT label counts ("Cart", "Go to bag"): a long product title
     * like "Leather laptop bag, 15 inch" also contains "bag" and must not
     * be mistaken for the cart button. contentDescription and id are
     * trusted at any length since apps don't put product titles there.
     */
    fun isGoToCart(c: Cues): Boolean {
        if (isAddToCart(c)) return false
        val shortLabel = c.label.split(' ').size <= 3 && CART_WORD.containsMatchIn(c.label)
        return shortLabel || CART_WORD.containsMatchIn(c.contentDescription) || CART_WORD.containsMatchIn(c.idWords)
    }

    fun isSearch(c: Cues): Boolean {
        val shortLabel = c.label.split(' ').size <= 4 && c.label.contains("search")
        return shortLabel || c.contentDescription.contains("search") || c.hint.contains("search") ||
            c.idWords.contains("search")
    }

    /**
     * A search suggestion repeats the query, maybe with a few words added
     * ("wireless earbuds for iphone"), or is a shorter form of it. A
     * RESULT title that merely mentions the query somewhere ("Noise Buds
     * VS104 Wireless Earbuds") is not a suggestion.
     */
    fun looksLikeSuggestion(label: String?, query: String?): Boolean {
        val l = normalize(label)
        val q = normalize(query)
        if (l.isEmpty() || q.isEmpty()) return false
        if (q.contains(l)) return true
        return l.startsWith(q) && l.split(' ').size <= q.split(' ').size + 3
    }

    fun isNotAResult(c: Cues): Boolean {
        // A collapsible menu section heading ("Recommended for you double
        // tap to collapse"), confirmed mislabelled on-device, Zomato 27 Sep.
        if (c.all.any { it.contains("double tap to expand") || it.contains("double tap to collapse") }) return true
        val short = c.label.split(' ').size <= 3
        return (short && NOT_A_RESULT.any { c.label.contains(it) }) ||
            NOT_A_RESULT.any { c.contentDescription.contains(it) }
    }
}
