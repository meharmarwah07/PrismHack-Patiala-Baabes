package com.calo.domain.teach

import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep

/**
 * Removes SCROLL steps whose anchor belongs to a different app than the flow's
 * target package.
 *
 * Seen on-device (2026-09-30, Zomato teach, twice, identical anchor): a SCROLL
 * on com.oneplus.deskclock:id/world_city_list was recorded as step 1 of a
 * Zomato flow, so replay died at step 1 and never reached the real steps. The
 * package is recoverable from the resourceId prefix ("pkg:id/name"); only
 * SCROLL steps are filtered, and only when the prefix clearly names another
 * package, so anchors with no resourceId are always kept.
 */
object ForeignScrollFilter {

    fun filter(steps: List<FlowStep>, targetPackage: String?): List<FlowStep> {
        if (targetPackage == null) return steps
        val kept = steps.filterNot { isForeignScroll(it, targetPackage) }
        if (kept.size == steps.size) return steps
        return kept.mapIndexed { i, step -> step.copy(order = i + 1) }
    }

    private fun isForeignScroll(step: FlowStep, targetPackage: String): Boolean {
        if (step.action != ActionType.SCROLL) return false
        val pkg = step.target.resourceId?.substringBefore(":id/", missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() } ?: return false
        return pkg != targetPackage && pkg != "android"
    }
}
