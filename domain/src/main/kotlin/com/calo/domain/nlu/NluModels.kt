package com.calo.domain.nlu

/** What NLUClient offers the LLM to choose between — one per learned flow for this app. */
data class CandidateFlow(
    val id: String,
    val triggerUtterance: String,   // the exact sentence spoken while teaching
    val description: String,
    val slotNames: List<String>,    // names the LLM should extract values for, if matched
    val slotExampleValues: Map<String, String> = emptyMap() // name -> value it was taught with (T1)
)

/** One other flow the LLM seriously considered besides its top pick (T13 ambiguity). */
data class Alternative(
    val flowId: String,
    val confidence: Double
)

/**
 * Where a [MatchResult] landed after threshold/margin gating (see
 * NluMatchEvaluator) — the orchestrator branches on this instead of
 * inferring status from nullability, so "no match" (T12), "ambiguous"
 * (T13), and "matched but missing a slot" (T14 bonus) are each handled
 * explicitly rather than falling through to replay by accident.
 */
enum class MatchStatus {
    MATCHED,
    NO_MATCH,
    AMBIGUOUS,
    NEEDS_SLOT,
    ERROR
}

/**
 * matchedFlowId == null means "the LLM decided none of the candidates fit this
 * utterance" — NOT a parse failure. Parse failures collapse to this same shape
 * (see NluResponseParser) because "couldn't understand" and "malformed reply"
 * should be handled identically by the orchestrator: say so, don't replay.
 *
 * status/alternatives/missingSlot are additive (contract: don't rename/retype
 * matchedFlowId, slotValues, confidence — Lane B depends on those three as-is).
 */
data class MatchResult(
    val matchedFlowId: String?,
    val slotValues: Map<String, String> = emptyMap(),
    val confidence: Double = 0.0,
    val status: MatchStatus = if (matchedFlowId != null) MatchStatus.MATCHED else MatchStatus.NO_MATCH,
    val alternatives: List<Alternative> = emptyList(),
    val missingSlot: String? = null
)
