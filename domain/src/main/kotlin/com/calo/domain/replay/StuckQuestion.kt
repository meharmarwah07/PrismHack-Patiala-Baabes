package com.calo.domain.replay

import com.calo.domain.model.ElementAnchor
import com.calo.domain.semantic.ResultLabel

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

    // 2026-09-30, on-device: Zomato restaurant cards read out as "Restaurant Name is Bake By
    // Ecco ₹100 OFF above ₹199 delivers in 24 minutesSwipe up or down for more actions". A
    // short description is used as-is; a verbose one is cut to the name (ResultLabel), or
    // failing that truncated so the spoken question stays short.
    private const val MAX_SPOKEN_LABEL = 60

    private fun speakable(cd: String): String {
        val verbose = cd.length > MAX_SPOKEN_LABEL ||
            cd.contains("swipe up or down", ignoreCase = true) ||
            cd.startsWith("restaurant name is", ignoreCase = true)
        if (!verbose) return cd
        return ResultLabel.displayName(cd) ?: cd.take(MAX_SPOKEN_LABEL).trimEnd()
    }

    private fun humanLabel(anchor: ElementAnchor, stepOrder: Int): String {
        anchor.text?.takeIf { it.isNotBlank() }?.let { return "'$it'" }
        anchor.contentDescription?.takeIf { it.isNotBlank() }?.let { return "'${speakable(it)}'" }
        anchor.resourceId?.takeIf { it.isNotBlank() }?.let {
            val segment = it.substringAfterLast('/').replace('_', ' ')
            return "'$segment'"
        }
        return "the button I tapped at step $stepOrder"
    }
}
