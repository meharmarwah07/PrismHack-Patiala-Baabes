package com.calo.domain.slots

import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep

/**
 * The entire mechanism that turns a one-off recording into something that
 * generalizes. Kept as one small pure function so the fallback behavior is
 * impossible to get wrong by accident in ReplayEngine:
 *
 *   - step has no slotName            -> always recordedValue, verbatim.
 *   - step has a slotName, and the    -> use the substituted value (T4–T6:
 *     caller supplied a value for it     generalized replay via NLU-extracted
 *                                         slots)
 *   - step has a slotName, but the    -> fall back to recordedValue (T2: the
 *     caller did NOT supply one          debug "exact Replay" path, and the
 *     (e.g. slotValues = emptyMap())     safe default if NLU extraction
 *                                         silently failed to fill a slot)
 */
object SlotResolver {

    /** Returns null for actions that don't carry a text value (CLICK/SCROLL/WAIT). */
    fun resolveValue(step: FlowStep, slotValues: Map<String, String>): String? {
        if (step.action != ActionType.SET_TEXT) return null

        val slotName = step.slotName
        if (slotName != null) {
            slotValues[slotName]?.let { return it }
        }
        return step.recordedValue
    }
}
