package com.calo.domain.semantic

import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep

/**
 * Turns a recorded step list into a semantic workflow by giving each step
 * a [SemanticRole] where one fits, e.g.
 *
 *   CLICK "Search Amazon"        -> OPEN_SEARCH
 *   SET_TEXT "wireless earbuds"  -> SEARCH_INPUT
 *   CLICK "wireless earbuds"     -> SUBMIT_SEARCH   (the suggestion row)
 *   CLICK "boAt Airdopes 141..." -> SELECT_RESULT(1)
 *   CLICK "Add to Cart"          -> ADD_TO_CART
 *
 * Runs once after teaching (and lazily on flows saved before roles
 * existed). Deterministic keyword + sequence rules, not an LLM call: the
 * same recording always gets the same labels, it works offline, and every
 * rule is unit-tested. Anything that fits no rule keeps role = null and
 * still replays exactly on its own app — labelling only ever ADDS a way
 * to carry a step to another app, it never changes same-app replay.
 *
 * Idempotent: a step that already has a role keeps it.
 */
object RoleLabeler {

    fun label(steps: List<FlowStep>): List<FlowStep> {
        val ordered = steps.sortedBy { it.order }
        val out = mutableListOf<FlowStep>()

        var typedQuery: String? = null
        var resultChosen = false
        var cartStarted = false

        for (step in ordered) {
            val previousRole = out.lastOrNull { it.action != ActionType.SCROLL && it.action != ActionType.WAIT }?.role
            val role = step.role ?: decide(step, previousRole, typedQuery, resultChosen, cartStarted)

            when (role) {
                SemanticRole.SEARCH_INPUT -> typedQuery = typedQuery ?: step.recordedValue
                SemanticRole.SELECT_RESULT -> resultChosen = true
                SemanticRole.ADD_TO_CART, SemanticRole.BUY_NOW,
                SemanticRole.GO_TO_CART, SemanticRole.CHECKOUT -> cartStarted = true
                else -> Unit
            }

            out += step.copy(
                role = role,
                roleIndex = if (role == SemanticRole.SELECT_RESULT) (step.roleIndex ?: 1) else step.roleIndex
            )
        }
        return out
    }

    /**
     * Labels from scratch, ignoring roles already saved on the steps — used
     * at replay so a flow saved under older rules gets today's labels
     * (roles are derived purely from the recorded steps, so nothing the
     * user chose is lost).
     */
    fun relabel(steps: List<FlowStep>): List<FlowStep> =
        label(steps.map { it.copy(role = null, roleIndex = null) })

    private fun decide(
        step: FlowStep,
        previousRole: SemanticRole?,
        typedQuery: String?,
        resultChosen: Boolean,
        cartStarted: Boolean
    ): SemanticRole? {
        val label = step.target.text ?: step.recordedValue
        val cues = RoleKeywords.cues(label, step.target.contentDescription, step.target.hintText, step.target.resourceId)

        return when (step.action) {
            ActionType.SET_TEXT -> {
                // Only the first search of a flow; a later text box (an
                // address, a coupon) is not a search just because it
                // comes after one.
                val looksLikeSearch = RoleKeywords.isSearch(
                    // A SET_TEXT step's recordedValue is what was TYPED, not
                    // the field's label — judge the field by its own cues.
                    RoleKeywords.cues(step.target.text, step.target.contentDescription, step.target.hintText, step.target.resourceId)
                )
                if (typedQuery == null && (looksLikeSearch || previousRole == SemanticRole.OPEN_SEARCH)) {
                    SemanticRole.SEARCH_INPUT
                } else {
                    null
                }
            }

            ActionType.CLICK -> when {
                RoleKeywords.isAddToCart(cues) -> SemanticRole.ADD_TO_CART
                RoleKeywords.isBuyNow(cues) -> SemanticRole.BUY_NOW
                RoleKeywords.isCheckout(cues) -> SemanticRole.CHECKOUT
                RoleKeywords.isGoToCart(cues) -> SemanticRole.GO_TO_CART
                previousRole == SemanticRole.SEARCH_INPUT && isSubmit(cues, typedQuery) -> SemanticRole.SUBMIT_SEARCH
                typedQuery == null && RoleKeywords.isSearch(cues) -> SemanticRole.OPEN_SEARCH
                // The first plain tap after searching, before anything
                // cart-related, is picking a result.
                typedQuery != null && !resultChosen && !cartStarted && !RoleKeywords.isNotAResult(cues) ->
                    SemanticRole.SELECT_RESULT
                else -> null
            }

            ActionType.SCROLL, ActionType.WAIT -> null
        }
    }

    /** A suggestion row that repeats the typed query, or a search button. */
    private fun isSubmit(cues: RoleKeywords.Cues, typedQuery: String?): Boolean =
        RoleKeywords.isSearch(cues) || RoleKeywords.looksLikeSuggestion(cues.label, typedQuery)
}
