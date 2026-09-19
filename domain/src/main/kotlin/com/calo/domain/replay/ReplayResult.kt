package com.calo.domain.replay

/**
 * atStepOrder in every non-Completed case is the FlowStep.order that did NOT
 * run to completion — i.e. "got through everything before this, stopped here."
 * For Halted specifically that also means: this step and everything after it
 * were never attempted, no exceptions — that guarantee is what T11 grades.
 */
sealed class ReplayResult {
    data object Completed : ReplayResult()

    data class Halted(val atStepOrder: Int, val reason: String) : ReplayResult()

    data class Stuck(val atStepOrder: Int, val reason: String) : ReplayResult()
}
