package com.calo.domain.replay

/**
 * Decides which on-screen candidate (if any) a CLICK step's substituted
 * slot value should resolve to (Task 1, Lane B — item substitution for a
 * taught CLICK, e.g. tapping "Margherita" in a result list, replayed with
 * "Farmhouse"). Matching is case-insensitive and trimmed, since a spoken
 * NLU slot value ("farmhouse") and an on-screen label ("Farmhouse ") won't
 * agree on case or padding even when they mean the same thing.
 *
 * A contains-match is only attempted when the exact pass finds NOTHING
 * (never as a tiebreaker for ambiguous exact matches), and is only trusted
 * when it narrows to exactly one candidate. Any other outcome — zero
 * candidates at either stage, or more than one at either stage — returns
 * null: this project fails closed on ambiguity rather than guessing which
 * element to tap (see ReplayPlanner's Stuck contract).
 *
 * Generic over [T] so the real Android-side caller (NodeWalker, which
 * can't run in a JUnit environment with no Android SDK) can pass real
 * AccessibilityNodeInfo-backed candidates while this algorithm itself
 * stays here, fully unit-tested, with zero Android dependency.
 */
object ClickValueMatcher {

    fun <T> resolve(candidates: List<T>, value: String, labelOf: (T) -> String): T? {
        val target = value.trim().lowercase()

        val exact = candidates.filter { labelOf(it).trim().lowercase() == target }
        if (exact.size == 1) return exact.single()
        if (exact.isNotEmpty()) return null // 2+ exact matches: ambiguous, never fall through to contains

        val contains = candidates.filter { labelOf(it).trim().lowercase().contains(target) }
        return contains.singleOrNull()
    }
}
