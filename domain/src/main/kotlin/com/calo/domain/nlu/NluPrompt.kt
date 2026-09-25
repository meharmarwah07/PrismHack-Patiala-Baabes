package com.calo.domain.nlu

/**
 * Builds the single LLM call this app makes for utterance-matching +
 * slot-extraction (the team chose one LLM call over an embedding index).
 * Kept as a pure string-builder so the exact prompt text is diffable and
 * unit-testable without hitting Groq — NLUClient (in :app) just sends
 * whatever this returns and passes the raw response to [NluResponseParser].
 */
object NluPrompt {

    /**
     * paraphrase-tolerant by construction: we give the LLM the ORIGINAL
     * taught utterance for each candidate, not a fixed grammar, and ask it
     * to judge semantic match — that's the whole point of using an LLM
     * call here instead of exact/fuzzy string matching.
     *
     * Candidates span EVERY app the user has taught a flow in, not just
     * whatever's on screen right now — CaloOrchestrator resolves which app
     * to launch from the matched flow's own stored targetPackage, so the
     * LLM's only job is picking the right flow by meaning.
     */
    fun build(utterance: String, candidates: List<CandidateFlow>): String {
        if (candidates.isEmpty()) {
            // Still produce a well-formed prompt; the LLM should just report no match.
            return buildPrompt(utterance, "(no flows have been taught yet)")
        }
        val candidatesBlock = candidates.joinToString("\n") { c ->
            val slots = if (c.slotNames.isEmpty()) {
                "none"
            } else {
                c.slotNames.joinToString(", ") { name ->
                    val taught = c.slotExampleValues[name]
                    if (taught != null) "$name (taught as \"$taught\")" else name
                }
            }
            "- id: \"${c.id}\"\n  taught phrase: \"${c.triggerUtterance}\"\n  description: \"${c.description}\"\n  slots to fill if matched: $slots"
        }
        return buildPrompt(utterance, candidatesBlock)
    }

    private fun buildPrompt(utterance: String, candidatesBlock: String): String = """
You match a spoken command to one previously-taught automation flow, or decide none match.

Spoken command: "$utterance"

Candidate flows the user has previously taught (any app):
$candidatesBlock

Rules:
- Match by MEANING, not exact wording. "order me a pepperoni pizza" should match a flow taught as "order a margherita pizza" if the flow has an "item" slot — the user is asking for the same ACTION with a different value.
- Each slot above shows the value it was taught with in parentheses. If the spoken command gives a NEW value for that slot, extract the new value. If the command doesn't mention that slot at all, OMIT it from slotValues entirely — do not return the taught value yourself and do not guess; the app already falls back to the taught value on its own whenever a slot is omitted, so silently repeating it here would be redundant, not helpful.
- If nothing genuinely matches, set matchedFlowId to null and slotValues to an empty object.
- confidence is your own calibrated 0.0-1.0 estimate that matchedFlowId is the right flow.
- If two or more candidates fit the command equally well and nothing in the command distinguishes which one was meant, do NOT arbitrarily pick a favorite and inflate its confidence — give matchedFlowId and its top alternative(s) similarly moderate, close confidences instead, so the app can tell this case apart from a genuinely clear match.
- alternatives: list up to 2 OTHER candidates (not matchedFlowId) you seriously considered, each with your confidence that THEY are the right flow instead. Omit any you'd consider negligible (below 0.1 confidence). Empty array if there are none worth mentioning.

Respond with ONLY this JSON shape, no other text, no markdown fences:
{"matchedFlowId": "<id or null>", "slotValues": {"<slotName>": "<value>"}, "confidence": <0.0-1.0>, "alternatives": [{"flowId": "<id>", "confidence": <0.0-1.0>}]}
""".trimIndent()
}
