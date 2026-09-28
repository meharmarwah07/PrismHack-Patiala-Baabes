package com.calo.domain.semantic

/** What the matcher needs to know beyond the role itself. */
data class RoleContext(
    /** The search query this replay typed (slot value or the taught one), if the flow searches. */
    val query: String? = null,
    /** For SELECT_RESULT: the result the user picked at teach time (or asked for via a slot). */
    val expectedLabel: String? = null,
    /** For SELECT_RESULT: which matching result, 1-based. */
    val index: Int = 1
)

sealed class RoleMatch {
    data class Found(val element: ScreenElement) : RoleMatch()

    /** Nothing on screen plays this role. */
    data class NotFound(val reason: String) : RoleMatch()

    /**
     * Several things could play this role and nothing separates them —
     * the caller must NOT pick one; it stops and hands back to the user.
     */
    data class Ambiguous(val reason: String) : RoleMatch()
}

/**
 * Finds "whatever plays this role" among the actionable elements on the
 * live screen — the replay-time half of the semantic layer (RoleLabeler is
 * the teach-time half). Pure function over [ScreenElement]s, so every rule
 * is unit-tested without a device.
 *
 * Same fail-closed philosophy as the rest of replay: when two candidates
 * are equally plausible and picking the wrong one would do something
 * different (two "ADD" buttons in a list), this returns Ambiguous rather
 * than guessing.
 */
object RoleMatcher {

    fun match(role: SemanticRole, elements: List<ScreenElement>, ctx: RoleContext = RoleContext()): RoleMatch =
        when (role) {
            SemanticRole.SEARCH_INPUT -> matchSearchInput(elements)
            // A typeable box isn't something to "open"; if one is already
            // showing, the planner treats this step as already done.
            SemanticRole.OPEN_SEARCH -> matchKeyword(elements.filter { !it.editable }, "a search button") {
                RoleKeywords.isSearch(it)
            }
            SemanticRole.SUBMIT_SEARCH -> matchSubmit(elements, ctx)
            SemanticRole.SELECT_RESULT -> matchResult(elements, ctx)
            SemanticRole.ADD_TO_CART -> matchKeyword(elements, "an add-to-cart button", allowListDuplicates = false) {
                RoleKeywords.isAddToCart(it)
            }
            SemanticRole.BUY_NOW -> matchKeyword(elements, "a buy-now button") { RoleKeywords.isBuyNow(it) }
            SemanticRole.GO_TO_CART -> matchKeyword(elements, "a cart button") { RoleKeywords.isGoToCart(it) }
            SemanticRole.CHECKOUT -> matchKeyword(elements, "a checkout button") { RoleKeywords.isCheckout(it) }
        }

    private fun cuesOf(e: ScreenElement) = RoleKeywords.cues(e.label, e.contentDescription, e.hintText, e.resourceId)

    private fun matchSearchInput(elements: List<ScreenElement>): RoleMatch {
        val editable = elements.filter { it.editable }
        if (editable.isEmpty()) return RoleMatch.NotFound("couldn't find a search box")

        val searchLike = editable.filter { RoleKeywords.isSearch(cuesOf(it)) }
        if (searchLike.isNotEmpty()) {
            return RoleMatch.Found(searchLike.firstOrNull { it.focused } ?: searchLike.first())
        }
        if (editable.size == 1) return RoleMatch.Found(editable.single())
        editable.singleOrNull { it.focused }?.let { return RoleMatch.Found(it) }
        return RoleMatch.Ambiguous("there are ${editable.size} text boxes and none looks like a search box")
    }

    /**
     * Keyword roles (add to cart, checkout, ...). When several elements
     * match, the one with the SHORTEST label wins: a product card that
     * contains an "Add to cart" button also contains that text in its
     * label, and the button itself is the tighter match.
     *
     * [allowListDuplicates] = false makes several distinct matches inside a
     * list Ambiguous — a menu where every dish has its own "ADD" button
     * gives no way to know which one the user means.
     */
    private fun matchKeyword(
        elements: List<ScreenElement>,
        what: String,
        allowListDuplicates: Boolean = true,
        predicate: (RoleKeywords.Cues) -> Boolean
    ): RoleMatch {
        val candidates = elements.filter { it.clickable && predicate(cuesOf(it)) }
        if (candidates.isEmpty()) return RoleMatch.NotFound("couldn't find $what")

        if (!allowListDuplicates) {
            val inList = candidates.filter { it.inList }
            if (inList.size > 1 && inList.size == candidates.size) {
                return RoleMatch.Ambiguous("found ${inList.size} matching buttons in a list and can't tell which one you mean")
            }
        }
        val best = candidates.minWith(compareBy<ScreenElement> { it.label?.length ?: 0 }.thenBy { it.id })
        return RoleMatch.Found(best)
    }

    private fun matchSubmit(elements: List<ScreenElement>, ctx: RoleContext): RoleMatch {
        val tappable = elements.filter { it.clickable && !it.editable }

        if (!ctx.query.isNullOrBlank()) {
            val suggestions = tappable.filter { RoleKeywords.looksLikeSuggestion(it.label, ctx.query) }
            if (suggestions.isNotEmpty()) {
                return RoleMatch.Found(suggestions.minWith(compareBy<ScreenElement> { it.label?.length ?: 0 }.thenBy { it.id }))
            }
        }
        // No suggestion: the planner presses the keyboard's search key,
        // which is more reliable than guessing which icon submits.
        return RoleMatch.NotFound("couldn't find a suggestion matching the search")
    }

    /**
     * Picking a result, in order of preference:
     *  1. The exact item the user picked when teaching (or asked for by
     *     slot), if it's on this screen too.
     *  2. The Nth list item that mentions the search query — "the first
     *     wireless-earbuds result", not just "the first row", so a banner
     *     or filter chip at the top of the list isn't picked.
     *  3. With no query to go on, the Nth list item.
     * Anything else is NotFound: tapping an unrelated item is worse than
     * stopping.
     */
    private fun matchResult(elements: List<ScreenElement>, ctx: RoleContext): RoleMatch {
        val candidates = elements.filter { e ->
            val c = cuesOf(e)
            e.clickable && !e.editable && !e.label.isNullOrBlank() &&
                !RoleKeywords.isNotAResult(c) && !RoleKeywords.isAddToCart(c) &&
                !RoleKeywords.isGoToCart(c) && !RoleKeywords.isSearch(c)
        }

        val expected = RoleKeywords.normalize(ctx.expectedLabel)
        if (expected.isNotEmpty()) {
            val exact = candidates.filter { RoleKeywords.normalize(it.label).contains(expected) }
            if (exact.isNotEmpty()) {
                return RoleMatch.Found(exact.minWith(compareBy<ScreenElement> { it.label?.length ?: 0 }.thenBy { it.id }))
            }
        }

        val index = ctx.index.coerceAtLeast(1)
        val listItems = candidates.filter { it.inList }.sortedBy { it.id }
        val query = RoleKeywords.normalize(ctx.query)
        val pool = if (query.isNotEmpty()) {
            val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
            listItems.filter { item -> tokens.any { RoleKeywords.normalize(item.label).contains(it) } }
        } else {
            listItems
        }

        return pool.getOrNull(index - 1)?.let { RoleMatch.Found(it) }
            ?: RoleMatch.NotFound(
                if (query.isNotEmpty()) "couldn't find result #$index that looks like '${ctx.query}'"
                else "couldn't find result #$index in a list"
            )
    }
}
