package com.calo.domain.semantic

import kotlinx.serialization.Serializable

/**
 * What a step MEANS, as opposed to which exact element it touched. A taught
 * FlowStep keeps its ElementAnchor (the exact, same-app recording) and may
 * additionally carry one of these — assigned after teaching by
 * [RoleLabeler]. The role is what lets a flow taught on one app be grounded
 * on a different app's UI (Amazon's "Add to Cart" vs Myntra's "ADD TO BAG"):
 * [RoleMatcher] finds "whatever plays this role" on the live screen.
 *
 * Deliberately a small, closed set covering the shopping-style tasks the
 * cross-platform demo needs. A step that fits none of these simply has no
 * role — it still replays exactly on its own app, it just can't carry over.
 */
@Serializable
enum class SemanticRole {
    /** Tap that opens a search box (a search icon or a search bar that isn't typeable yet). */
    OPEN_SEARCH,

    /** Typing the search query into the search box. */
    SEARCH_INPUT,

    /** Submitting the search: tapping a suggestion that matches the query, or the search button. */
    SUBMIT_SEARCH,

    /** Picking one result from a result list (roleIndex says which, 1-based). */
    SELECT_RESULT,

    ADD_TO_CART,
    BUY_NOW,
    GO_TO_CART,
    CHECKOUT
}
