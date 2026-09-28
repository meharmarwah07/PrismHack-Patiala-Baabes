package com.calo.domain.replay

/**
 * Chooses between several on-screen elements that ALL match a step's
 * anchor — the normal case in a list, where every row's button shares one
 * resource id and one label. Confirmed on-device (Zomato menu, 27 Sep
 * 2026): every dish's ADD button is `ll_root` with contentDescription
 * "Add item. customisable", so the anchor alone can't say which dish.
 *
 * ElementAnchor.contextLabel records the text of the row around the tapped
 * element at teach time ("Crispy Veg Burger. | Highly reordered | ...").
 * This picks the candidate whose row text best resembles it.
 *
 * Fails closed: with a recorded context and no candidate clearly matching
 * it, returns null (replay goes Stuck) — tapping another dish's ADD would
 * silently order the wrong thing.
 */
object ContextPicker {

    const val THRESHOLD = 0.5
    const val MARGIN = 0.1

    fun pick(taughtContext: String?, candidateContexts: List<String?>): Int? {
        if (candidateContexts.isEmpty()) return null
        if (candidateContexts.size == 1) return 0
        // No context recorded (flows taught before this existed): keep the
        // old first-match behaviour.
        if (taughtContext.isNullOrBlank()) return 0

        val scored = candidateContexts.mapIndexed { i, c -> i to FuzzyLabel.similarity(taughtContext, c) }
            .sortedByDescending { it.second }
        val best = scored.first()
        if (best.second < THRESHOLD) return null
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        return if (best.second - runnerUp >= MARGIN) best.first else null
    }
}
