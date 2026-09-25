package com.calo.domain.nlu

/**
 * Turns an AMBIGUOUS MatchResult plus the flow id the user's follow-up
 * voice answer picked into a real MATCHED one, to be handed to the exact
 * same "check for a missing slot, then replay" path a normal match goes
 * through -- one code path for both, rather than a second simplified
 * one that's easy to let drift out of sync (that's how the slotValues
 * got silently dropped the first time; see NluMatchEvaluator's doc).
 *
 * Deliberately just carries [originalMatch]'s slotValues through
 * unchanged: they were extracted from the utterance itself, not tied to
 * whichever candidate the model happened to rank first, so which of the
 * tied candidates the user's answer picks doesn't change what they
 * already said.
 */
object AmbiguityResolver {

    fun resolve(originalMatch: MatchResult, chosenFlowId: String): MatchResult =
        originalMatch.copy(
            matchedFlowId = chosenFlowId,
            status = MatchStatus.MATCHED,
            alternatives = emptyList()
        )
}
