package com.calo.domain.nlu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
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
        val cleaned = extractJsonObject(raw)
        val element = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
            ?: return MatchResult(matchedFlowId = null)

        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: return MatchResult(matchedFlowId = null)

        val matchedFlowId = obj["matchedFlowId"]?.let { el ->
            if (el is JsonNull) null else (el as? JsonPrimitive)?.contentOrNull
        }?.takeIf { it.isNotBlank() && it != "null" }

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

        val alternatives: List<Alternative> = obj["alternatives"]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { el ->
                val altObj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = (altObj["flowId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val conf = altObj["confidence"]
                    ?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                    ?.coerceIn(0.0, 1.0)
                    ?: return@mapNotNull null
                Alternative(id, conf)
            }
            ?: emptyList()

        // A matchedFlowId with no candidate backing it is still "matched" from
        // the parser's point of view — NLUClient/orchestrator is responsible
        // for checking the id against the actual candidate list it sent, since
        // this parser has no access to that list and shouldn't need it.
        return MatchResult(
            matchedFlowId = matchedFlowId,
            slotValues = slotValues,
            confidence = confidence,
            alternatives = alternatives
        )
    }

    /**
     * Strips markdown fences AND, if the model wrapped the JSON in prose
     * ("Sure, here's the match: {...}") despite instructions not to, pulls
     * out the first balanced {...} block instead of failing outright.
     */
    private fun extractJsonObject(raw: String): String {
        val trimmed = stripMarkdownFence(raw)
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = trimmed.indexOf('{')
        if (start == -1) return trimmed

        var depth = 0
        for (i in start until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(start, i + 1)
                }
            }
        }
        return trimmed
    }

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
