package com.calo.nlu

import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchResult
import com.calo.domain.nlu.MatchStatus

/**
 * Deterministic, network-free fallback for when NLUClient reports
 * MatchStatus.ERROR (missing key, 401/429/5xx, timeout, truncation). Used ONLY
 * in that case -- never a replacement for the LLM match.
 *
 * Deliberately conservative: a false positive here (e.g. "book me a cab" replaying
 * a pizza flow) is worse than no fallback at all, so anything not an unambiguous
 * exact/keyword hit returns NO_MATCH. No slot extraction is attempted; empty
 * slotValues make SlotResolver fall back to each step's recordedValue, which
 * reproduces the taught flow verbatim.
 */
object OfflineFlowMatcher {

    private const val MIN_OVERLAP = 0.7
    private const val MIN_MARGIN = 0.2

    private val STOPWORDS = setOf(
        "a", "an", "the", "me", "my", "please", "from", "on", "to", "for", "of", "and", "i", "want", "get"
    )

    fun match(utterance: String, candidates: List<CandidateFlow>): MatchResult {
        val normalized = normalize(utterance)
        if (normalized.isEmpty()) return noMatch()

        val exact = candidates.filter { normalize(it.triggerUtterance) == normalized }
        if (exact.size == 1) {
            return MatchResult(
                matchedFlowId = exact.single().id,
                confidence = 1.0,
                status = MatchStatus.MATCHED,
                slotValues = emptyMap()
            )
        }

        val utteranceTokens = tokens(normalized)
        if (utteranceTokens.isEmpty()) return noMatch()

        val scored = candidates
            .map { candidate ->
                val candidateTokens = tokens(normalize(candidate.triggerUtterance + " " + candidate.description))
                candidate to utteranceTokens.count { it in candidateTokens }.toDouble() / utteranceTokens.size
            }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return noMatch()
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        if (best.second >= MIN_OVERLAP && best.second - runnerUp >= MIN_MARGIN) {
            return MatchResult(
                matchedFlowId = best.first.id,
                confidence = best.second,
                status = MatchStatus.MATCHED,
                slotValues = emptyMap()
            )
        }
        return noMatch()
    }

    private fun noMatch() = MatchResult(matchedFlowId = null, status = MatchStatus.NO_MATCH)

    private fun normalize(s: String): String =
        s.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun tokens(normalized: String): Set<String> =
        normalized.split(" ").filter { it.isNotEmpty() && it !in STOPWORDS }.toSet()
}
