package com.calo.domain.nlu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deliberately navigates JsonElement by hand instead of decoding into a
 * @Serializable data class — that would need the kotlinx-serialization
 * compiler plugin wired into whatever compiles this module, and this way
 * every field is defensively optional, which matters because the far end
 * is an LLM's free-text completion, not a typed API contract.
 *
 * Any malformed/unparseable response collapses to "no match, confidence 0"
 * rather than throwing — see MatchResult's doc for why that's the right
 * default (the orchestrator treats "couldn't understand" and "malformed
 * reply" the same way: say so, don't replay anything).
 */
object NluResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): MatchResult {
        val cleaned = stripMarkdownFence(raw)
        val element = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
            ?: return MatchResult(matchedFlowId = null)

        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: return MatchResult(matchedFlowId = null)

        val matchedFlowId = optionalString(obj["matchedFlowId"])
        val targetApp = optionalString(obj["targetApp"])

        val slotValues: Map<String, String> = obj["slotValues"]
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull
                if (text != null) key to text else null
            }
            ?.toMap()
            ?: emptyMap()

        val confidence = obj["confidence"]
            ?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
            ?.coerceIn(0.0, 1.0)
            ?: 0.0

        // A matchedFlowId with no candidate backing it is still "matched" from
        // the parser's point of view — NLUClient/orchestrator is responsible
        // for checking the id against the actual candidate list it sent, since
        // this parser has no access to that list and shouldn't need it.
        return MatchResult(
            matchedFlowId = matchedFlowId,
            slotValues = slotValues,
            confidence = confidence,
            targetApp = targetApp
        )
    }

    /** Missing, JSON null, blank, or the literal string "null" all mean "not given". */
    private fun optionalString(el: JsonElement?): String? =
        el?.let { if (it is JsonNull) null else (it as? JsonPrimitive)?.contentOrNull }
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun stripMarkdownFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
