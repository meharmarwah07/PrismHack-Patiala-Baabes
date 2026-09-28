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

    /**
     * [actionMayHaveExecuted] is true only when this Stuck came from a
     * timed-out performClick/performSetText/performScroll call (2026-09-28)
     * — a call that was still in flight, on a real device, when we gave up
     * waiting on it. Unlike a timed-out read-only query, that action can
     * still land for real on the device after this result has already been
     * returned and spoken to the user, with nobody watching it happen. A
     * caller seeing this true must not treat it like an ordinary Stuck —
     * see CaloOrchestrator.handleReplayResult, which refuses to offer retry
     * in this case specifically to avoid a double-fire on top of an action
     * that may or may not have already gone through.
     */
    data class Stuck(
        val atStepOrder: Int,
        val reason: String,
        val actionMayHaveExecuted: Boolean = false
    ) : ReplayResult()
}
