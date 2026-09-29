package com.calo.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The one next move the AI helper asks for. */
sealed class AgentAction {
    data class Tap(val elementId: Int) : AgentAction()
    /** Tap whatever shows exactly this text — for web links that aren't listed as elements. */
    data class TapText(val text: String) : AgentAction()
    data class Type(val elementId: Int, val text: String) : AgentAction()
    /** Press the keyboard's Enter / Search key. */
    data object Submit : AgentAction()
    data class Scroll(val forward: Boolean) : AgentAction()
    data object Back : AgentAction()
    data object Done : AgentAction()
    data class GiveUp(val reason: String) : AgentAction()
}

/**
 * Same defensive stance as NluResponseParser: the far end is an LLM's free
 * text, so every field is optional and anything malformed is null (the
 * loop counts it as a wasted turn), never an exception.
 */
object AgentResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): AgentAction? {
        val obj = extractObject(raw) ?: return null
        val action = obj.string("action")?.lowercase()?.replace(' ', '_') ?: return null
        return when (action) {
            "tap", "click" -> obj.int("id")?.let { AgentAction.Tap(it) }
                ?: obj.string("text")?.let { AgentAction.TapText(it) }
            "tap_text" -> obj.string("text")?.let { AgentAction.TapText(it) }
            "type" -> {
                val id = obj.int("id") ?: return null
                val text = obj.string("text") ?: return null
                AgentAction.Type(id, text)
            }
            "submit", "enter" -> AgentAction.Submit
            "scroll" -> AgentAction.Scroll(forward = obj.string("direction")?.lowercase() != "up")
            "back" -> AgentAction.Back
            "done" -> AgentAction.Done
            "give_up", "giveup", "fail" -> AgentAction.GiveUp(obj.string("reason") ?: "no reason given")
            else -> null
        }
    }

    /** The first {...} in the reply — tolerates markdown fences or a stray sentence around it. */
    private fun extractObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() } }.getOrNull()
}
