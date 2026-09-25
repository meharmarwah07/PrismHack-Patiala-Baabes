package com.calo.domain.replay

import com.calo.domain.model.ElementAnchor

/**
 * Builds the specific question asked (shown AND spoken) when replay goes
 * Stuck (Task 2, Lane B) — never a generic "Replay failed". Uses the best
 * human-readable label available for the step's anchor, in the same
 * priority order the rest of this project already uses for a taught CLICK
 * (see SlotResolver.resolveClickTarget's doc): text -> contentDescription
 * -> the resourceId's last path segment (underscores read as spaces, since
 * that's how Android resource names are conventionally written) -> "the
 * button I tapped at step N" when the anchor has none of the above (the
 * same "low-confidence anchor" case TeachRecorder already logs at teach
 * time — see its class doc).
 */
object StuckQuestion {

    fun build(anchor: ElementAnchor, stepOrder: Int): String {
        val label = humanLabel(anchor, stepOrder)
        return "I couldn't find $label on this screen. Should I pick something else, or stop?"
    }

    private fun humanLabel(anchor: ElementAnchor, stepOrder: Int): String {
        anchor.text?.takeIf { it.isNotBlank() }?.let { return "'$it'" }
        anchor.contentDescription?.takeIf { it.isNotBlank() }?.let { return "'$it'" }
        anchor.resourceId?.takeIf { it.isNotBlank() }?.let {
            val segment = it.substringAfterLast('/').replace('_', ' ')
            return "'$segment'"
        }
        return "the button I tapped at step $stepOrder"
    }
}
