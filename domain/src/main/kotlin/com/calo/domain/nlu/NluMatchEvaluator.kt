package com.calo.domain.nlu

/**
 * Applies the confidence threshold (T12: reject low-confidence "best
 * guess" matches instead of replaying them) and the ambiguity margin
 * (T13: two candidates close enough that guessing between them is
 * unsafe) on top of whatever NluResponseParser handed back raw from the
 * model. Kept separate from the parser deliberately — parsing "what did
 * the model say" and deciding "is that good enough to act on" are
 * different questions, and mixing them made the parser's own tests
 * (which use confidences chosen to exercise parsing, not to be
 * realistic) fragile against threshold tuning.
 *
 * All three constants below are set from a live Groq confidence table
 * (NluLiveHarness, 23 utterances across exact/paraphrase/unrelated/
 * ambiguous categories — see the lane report), not guessed:
 *   - genuine matches (exact + paraphrase):      0.95 - 0.99
 *   - genuinely ambiguous ties ("order a pizza"
 *     against two equally-taught pizza flows,
 *     resampled 5x for consistency):              0.50 / 0.50, every time
 *   - unrelated commands ("book me a cab"):        0.00
 * The gaps between those three clusters are wide (0.50->0.95 is a 0.45
 * jump, 0.0->0.50 is 0.50), so these thresholds have generous headroom
 * on both sides rather than sitting on a knife's edge.
 *
 * Ambiguity is checked BEFORE the match threshold, not after — the tied
 * case sits at 0.50, which is below MATCH_THRESHOLD. Checking the
 * threshold first would collapse a genuine "which one did you mean?"
 * into a flat "I don't know that command," losing the distinction T13
 * exists to make.
 */
object NluMatchEvaluator {

    const val MATCH_THRESHOLD = 0.8
    const val AMBIGUITY_MARGIN = 0.15
    /** A candidate below this isn't "in the running" at all — excludes a merely non-negligible alternative (e.g. 0.1) from being mistaken for a tie. */
    const val AMBIGUITY_FLOOR = 0.35

    fun evaluate(raw: MatchResult): MatchResult {
        val topId = raw.matchedFlowId
        val topConfidence = raw.confidence
        val runnerUp = raw.alternatives.maxByOrNull { it.confidence }

        if (topId != null && runnerUp != null &&
            topConfidence >= AMBIGUITY_FLOOR && runnerUp.confidence >= AMBIGUITY_FLOOR &&
            (topConfidence - runnerUp.confidence) <= AMBIGUITY_MARGIN
        ) {
            val allConsidered = (listOf(Alternative(topId, topConfidence)) + raw.alternatives)
                .sortedByDescending { it.confidence }
            return raw.copy(
                matchedFlowId = null,
                slotValues = emptyMap(),
                status = MatchStatus.AMBIGUOUS,
                alternatives = allConsidered
            )
        }

        if (topId == null || topConfidence < MATCH_THRESHOLD) {
            return raw.copy(
                matchedFlowId = null,
                slotValues = emptyMap(),
                status = MatchStatus.NO_MATCH
            )
        }

        return raw.copy(status = MatchStatus.MATCHED)
    }
}
