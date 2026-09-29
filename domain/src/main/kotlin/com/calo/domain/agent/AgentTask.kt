package com.calo.domain.agent

import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep
import com.calo.domain.slots.SlotResolver

/**
 * What the AI helper is asked to finish when exact replay gets stuck: the
 * goal in the user's own words, plus the taught steps as HINTS — what the
 * person did last time, not a script to copy tap for tap.
 */
data class AgentTask(
    val goal: String,
    val appName: String,
    val hints: List<String>,
    // How many of [hints] exact replay already got through before it stuck.
    val hintsAlreadyDone: Int
)

/**
 * Builds an [AgentTask] from a saved flow with no AI call of its own — the
 * trigger phrase, description and each step's recorded label are already
 * everything the helper needs, so saving a flow stays free.
 */
object AgentTaskBuilder {

    private const val MAX_LABEL_CHARS = 60

    fun from(
        spokenCommand: String,
        triggerUtterance: String,
        description: String,
        appName: String,
        steps: List<FlowStep>,
        slotValues: Map<String, String>,
        stuckAtOrder: Int
    ): AgentTask {
        val ordered = steps.sortedBy { it.order }
        val hints = mutableListOf<String>()
        var done = 0
        for (step in ordered) {
            val hint = hintFor(step, slotValues) ?: continue
            hints += hint
            if (step.order < stuckAtOrder) done = hints.size
        }
        val goal = buildString {
            append("\"").append(spokenCommand).append("\"")
            if (!triggerUtterance.equals(spokenCommand, ignoreCase = true)) {
                append(" (taught as \"").append(triggerUtterance).append("\"")
                if (description.isNotBlank() && !description.equals(triggerUtterance, ignoreCase = true)) {
                    append(", described as \"").append(description).append("\"")
                }
                append(")")
            }
        }
        return AgentTask(goal = goal, appName = appName, hints = hints, hintsAlreadyDone = done)
    }

    /** One short line per step, or null for a step that says nothing useful. */
    private fun hintFor(step: FlowStep, slotValues: Map<String, String>): String? = when (step.action) {
        ActionType.CLICK -> {
            val value = step.slotName?.let { slotValues[it] }
            (value ?: readable(step.target.text) ?: readable(step.target.contentDescription) ?: readable(step.recordedValue))
                ?.let { "tap \"$it\"" }
        }
        ActionType.SET_TEXT -> SlotResolver.resolveValue(step, slotValues)?.let { typed ->
            val field = readable(step.target.hintText) ?: readable(step.target.text)
            if (field != null) "type \"$typed\" into \"$field\"" else "type \"$typed\""
        }
        ActionType.SCROLL -> "scroll"
        ActionType.WAIT -> null
    }

    /**
     * A label a person would recognise, trimmed — or null for blanks and
     * machine junk (image data / hashes recorded as a content description,
     * seen on-device: "a1iBuLkeCK5gJws3...YII=").
     */
    private fun readable(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > 24 && t.none { it.isWhitespace() }) return null
        return if (t.length > MAX_LABEL_CHARS) t.take(MAX_LABEL_CHARS) + "…" else t
    }
}
