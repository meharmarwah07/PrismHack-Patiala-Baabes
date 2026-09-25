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

    /**
     * For a CLICK step with a slotName: the value to search the live screen
     * for BY TEXT/contentDescription, instead of re-tapping the step's own
     * (teach-time) anchor. Returns null whenever the ordinary anchor path
     * (`ReplayPlanner` calling `provider.findNode(step.target)`) is already
     * correct and this alternate search must NOT run:
     *
     *   - action isn't CLICK, or there's no slotName at all -> null (nothing
     *     to generalize; not a slot step in the first place).
     *   - slotName present but the caller supplied no value for it (T2, the
     *     debug exact-replay path, same fallback SlotResolver.resolveValue
     *     uses for SET_TEXT) -> null, so the original anchor gets re-tapped
     *     exactly as taught.
     *   - the caller's value is IDENTICAL to what was recorded at teach
     *     time (step.target.text/contentDescription) -> null. Re-tapping the
     *     original anchor already lands on the right element in this case,
     *     and skipping the alternate search here avoids a second, redundant
     *     resolution path for the common "nothing actually changed" replay.
     *
     * Only when a value was supplied AND it genuinely differs from what was
     * taught does this return non-null — the one case where the original
     * anchor would silently tap the WRONG (stale) element instead of
     * generalizing, which is the bug this function exists to close.
     *
     * `recorded` falls back to `step.recordedValue` after `target.text`/
     * `contentDescription` because a CLICK anchored to its nearest clickable
     * ANCESTOR (see :app's TeachRecorder null-source recovery) often has
     * both of those null on the anchor itself — the label that was actually
     * matched at teach time lives in `recordedValue` for exactly that case.
     * Without this fallback, `recorded` would be `null` for such a step,
     * `newValue != null` would always be true, and this would ALWAYS
     * search-by-value instead of ever recognizing "the requested value is
     * the same one that was taught" — not wrong (findNodeByValue still
     * finds the right element), just needlessly skipping the cheaper,
     * intended re-tap-the-anchor path every time.
     */
    fun resolveClickTarget(step: FlowStep, slotValues: Map<String, String>): String? {
        if (step.action != ActionType.CLICK) return null
        val slotName = step.slotName ?: return null
        val newValue = slotValues[slotName] ?: return null
        val recorded = step.target.text ?: step.target.contentDescription ?: step.recordedValue
        return if (newValue != recorded) newValue else null
    }
}
