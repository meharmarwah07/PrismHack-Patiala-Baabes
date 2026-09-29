package com.calo.domain.agent

import com.calo.domain.semantic.ScreenElement

/**
 * The prompt for one AI-helper turn. Kept small on purpose — this is sent
 * once per action, so its size IS the cost: elements and screen text are
 * capped and trimmed, and only the last few actions are repeated back.
 */
object AgentPrompt {

    const val MAX_ELEMENTS = 50
    const val MAX_SCREEN_TEXTS = 40
    const val MAX_HISTORY = 6
    private const val MAX_FIELD_CHARS = 70

    fun build(
        task: AgentTask,
        elements: List<ScreenElement>,
        screenTexts: List<String>,
        history: List<String>,
        actionsLeft: Int
    ): String {
        val hints = task.hints.mapIndexed { i, h ->
            val mark = if (i < task.hintsAlreadyDone) " (already done)" else ""
            "${i + 1}. $h$mark"
        }.joinToString("\n").ifEmpty { "(none recorded)" }

        val elementLines = elements.take(MAX_ELEMENTS).joinToString("\n") { describe(it) }
            .ifEmpty { "(no tappable elements found)" }

        val texts = screenTexts.asSequence()
            .map { it.trim().replace('\n', ' ') }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SCREEN_TEXTS)
            .joinToString(" | ") { clip(it) }
            .ifEmpty { "(none)" }

        val recent = history.takeLast(MAX_HISTORY).joinToString("\n") { "- $it" }.ifEmpty { "- (nothing yet)" }

        return """
You are operating an Android phone to finish a task for the user, one action at a time.

Task: ${task.goal} on the app "${task.appName}".
What the user did when they taught this (hints, not a strict script — skip, reorder or improvise to reach the same result):
$hints

The automatic replay got stuck, so you are taking over from the current screen.

Tappable elements on screen now (id: description):
$elementLines

Other text visible on screen: $texts

Your actions so far:
$recent

Rules:
- Reply with exactly ONE action as JSON, nothing else.
- Actions: {"action":"tap","id":N} | {"action":"tap_text","text":"exact visible text"} | {"action":"type","id":N,"text":"..."} | {"action":"submit"} (press keyboard Enter/Search) | {"action":"scroll","direction":"down"|"up"} | {"action":"back"} | {"action":"done"} | {"action":"give_up","reason":"..."}
- Use tap_text only for visible text that isn't in the element list (web page links).
- Say "done" as soon as the task's final result is on screen or its last step has been done. Don't do extra things the task didn't ask for.
- Never pay, place an order, log in, or enter passwords/OTPs/card details; give_up instead.
- If the same action didn't change anything, try something different. You have $actionsLeft action(s) left.
""".trimIndent()
    }

    private fun describe(e: ScreenElement): String {
        val parts = buildList {
            e.label?.let { add("\"${clip(it)}\"") }
            e.contentDescription?.takeIf { it.isNotBlank() && it != e.label }?.let { add("desc \"${clip(it)}\"") }
            e.hintText?.takeIf { it.isNotBlank() }?.let { add("hint \"${clip(it)}\"") }
            if (e.editable) add("text field")
            if (e.focused) add("focused")
            e.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() && e.label == null && e.contentDescription == null }?.let { add(it) }
        }
        return "${e.id}: ${parts.joinToString(", ").ifEmpty { "(no label)" }}"
    }

    private fun clip(s: String): String = if (s.length > MAX_FIELD_CHARS) s.take(MAX_FIELD_CHARS) + "…" else s
}
