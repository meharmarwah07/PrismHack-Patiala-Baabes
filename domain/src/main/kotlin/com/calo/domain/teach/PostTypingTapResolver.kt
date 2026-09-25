package com.calo.domain.teach

/**
 * Finding 6 fallback, part 2 (2026-09-26): resolves a tap that happens
 * WHILE THE KEYBOARD IS STILL VISIBLE (a search result row tapped right
 * after typing a query) — TouchInteractionController's raw-coordinate
 * capture is deliberately OFF for the whole time the keyboard is up (see
 * CaloAccessibilityService's keyboard-release toggle: touch exploration
 * capability is relinquished so the keyboard itself keeps working), so
 * there are no touch coordinates to fall back on for this specific tap.
 *
 * Instead: after typing (SET_TEXT), if the keyboard hides and a new
 * screen appears shortly after, that combination itself is evidence a tap
 * happened (Zomato's silent-tap pattern strikes again — no CLICK event,
 * but SOMETHING navigated). [newScreenTexts] is every visible text/
 * contentDescription on the destination screen; [preTapCandidateTexts] is
 * the same, per clickable-relevant node, on the screen from BEFORE the
 * tap (one entry per node, duplicates preserved on purpose — see below).
 * Whichever pre-tap candidate's text also appears on the new screen is
 * the tapped element, PROVIDED there's exactly one such candidate.
 *
 * Duplicates in [preTapCandidateTexts] are the whole reason this resolves
 * to a node, not just a text match: two different result rows sharing a
 * name (two branches of the same restaurant) must NOT resolve to either
 * one silently — that's exactly the kind of guess this project's
 * fail-closed approach elsewhere (CredentialGateRules, the null-source
 * CLICK recovery) refuses to make. A caller that gets [Outcome.Matched]
 * still has to re-resolve that text to an actual node via
 * NodeWalker.findByValue on the pre-tap root — this class has no Android
 * dependency and only ever sees plain strings.
 */
object PostTypingTapResolver {

    sealed class Outcome {
        data class Matched(val text: String) : Outcome()
        data object NoMatch : Outcome()
        data class Ambiguous(val matchedTexts: List<String>) : Outcome()
    }

    fun resolve(newScreenTexts: Set<String>, preTapCandidateTexts: List<String>): Outcome {
        val matches = preTapCandidateTexts.filter { it in newScreenTexts }
        return when (matches.size) {
            0 -> Outcome.NoMatch
            1 -> Outcome.Matched(matches.single())
            else -> Outcome.Ambiguous(matches)
        }
    }
}
