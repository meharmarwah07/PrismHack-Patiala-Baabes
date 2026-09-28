package com.calo.domain.nlu

/** What NLUClient offers the LLM to choose between — one per learned flow for this app. */
data class CandidateFlow(
    val id: String,
    val triggerUtterance: String,   // the exact sentence spoken while teaching
    val description: String,
    val slotNames: List<String>,    // names the LLM should extract values for, if matched
    val appName: String? = null     // human name of the app it was taught on, e.g. "Amazon"
)

/**
 * matchedFlowId == null means "the LLM decided none of the candidates fit this
 * utterance" — NOT a parse failure. Parse failures collapse to this same shape
 * (see NluResponseParser) because "couldn't understand" and "malformed reply"
 * should be handled identically by the orchestrator: say so, don't replay.
 */
data class MatchResult(
    val matchedFlowId: String?,
    val slotValues: Map<String, String> = emptyMap(),
    val confidence: Double = 0.0,
    // An app the user explicitly named ("...on Myntra"), which may differ
    // from the app the matched flow was taught on. Null = none named.
    val targetApp: String? = null
)
