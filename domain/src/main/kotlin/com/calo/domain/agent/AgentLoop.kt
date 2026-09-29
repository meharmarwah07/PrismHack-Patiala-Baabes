package com.calo.domain.agent

import com.calo.domain.gate.CredentialGateRules
import com.calo.domain.gate.GateVerdict
import com.calo.domain.replay.NodeProvider
import com.calo.domain.replay.ReplayResult

/**
 * The AI helper: when exact replay gets Stuck, finish the task by asking
 * the LLM for one action at a time against whatever is on screen now,
 * with the taught steps as hints rather than a script.
 *
 * It only ever runs AFTER exact replay (which costs nothing) has failed,
 * and at most [maxActions] LLM calls per rescue — that's the cost control.
 * CaloOrchestrator additionally only ever gives this ONE attempt per voice
 * command: if it also ends in Stuck, the existing StuckQuestion voice flow
 * takes over from there rather than retrying the agent.
 *
 * Safety is enforced HERE, in code, never left to the model: the same
 * credential gate as ReplayPlanner runs before every single action, and a
 * Blocked verdict ends the run as Halted with nothing more touched. The
 * prompt also tells the model never to pay or log in, but that's a second
 * line, not the guarantee.
 *
 * [llm] sends one prompt and returns the raw reply, or null if the call
 * failed. It blocks; run this whole loop off the main thread.
 */
object AgentLoop {

    const val DEFAULT_MAX_ACTIONS = 10

    // Unparseable replies in a row before giving up — a broken model or
    // prompt shouldn't burn the whole action budget.
    private const val MAX_BAD_REPLIES = 2

    fun run(
        task: AgentTask,
        stuckAtOrder: Int,
        provider: NodeProvider,
        llm: (String) -> String?,
        maxActions: Int = DEFAULT_MAX_ACTIONS,
        log: (String) -> Unit = {}
    ): ReplayResult {
        val history = mutableListOf<String>()
        var badReplies = 0

        for (turn in 1..maxActions) {
            val verdict = CredentialGateRules.classify(provider.currentScreenSignals())
            if (verdict is GateVerdict.Blocked) {
                return ReplayResult.Halted(stuckAtOrder, verdict.reason)
            }

            val elements = provider.screenElements()
            val prompt = AgentPrompt.build(task, elements, provider.screenTexts(), history, maxActions - turn + 1)
            val reply = llm(prompt)
                ?: return ReplayResult.Stuck(stuckAtOrder, "the AI helper couldn't be reached")

            val action = AgentResponseParser.parse(reply)
            log("AI helper turn $turn: $action (raw=${reply.take(200)})")
            if (action == null) {
                if (++badReplies >= MAX_BAD_REPLIES) {
                    return ReplayResult.Stuck(stuckAtOrder, "the AI helper gave replies Calo couldn't understand")
                }
                history += "(your last reply wasn't valid JSON for one action — reply with JSON only)"
                continue
            }
            badReplies = 0

            val outcome: String = when (action) {
                AgentAction.Done -> return ReplayResult.Completed
                is AgentAction.GiveUp -> return ReplayResult.Stuck(stuckAtOrder, "the AI helper couldn't finish: ${action.reason}")
                is AgentAction.Tap -> {
                    val element = elements.firstOrNull { it.id == action.elementId }
                    val node = element?.let { provider.nodeForElement(it) }
                    when {
                        element == null -> "tap id ${action.elementId}: no such element"
                        node == null -> "tap id ${action.elementId}: element disappeared"
                        else -> "tapped ${action.elementId} (${element.label ?: element.contentDescription ?: "no label"}): " +
                            okOrFailed(provider.performClick(node))
                    }
                }
                is AgentAction.TapText ->
                    "tapped text \"${action.text}\": " + if (provider.tapText(action.text)) "ok" else "not found on screen"
                is AgentAction.Type -> {
                    val element = elements.firstOrNull { it.id == action.elementId }
                    val node = element?.let { provider.nodeForElement(it) }
                    when {
                        element == null -> "type into id ${action.elementId}: no such element"
                        !element.editable -> "type into id ${action.elementId}: that isn't a text field"
                        node == null -> "type into id ${action.elementId}: element disappeared"
                        else -> "typed \"${action.text}\" into ${action.elementId}: " +
                            okOrFailed(provider.performSetText(node, action.text))
                    }
                }
                AgentAction.Submit -> "pressed Enter: " + okOrFailed(provider.submitCurrentInput())
                is AgentAction.Scroll ->
                    "scrolled ${if (action.forward) "down" else "up"}: " + okOrFailed(provider.scrollScreen(action.forward))
                AgentAction.Back -> "pressed Back: " + okOrFailed(provider.pressBack())
            }
            history += outcome
            provider.awaitScreenChange()
        }
        return ReplayResult.Stuck(stuckAtOrder, "the AI helper ran out of its $maxActions actions before finishing")
    }

    private fun okOrFailed(ok: Boolean) = if (ok) "ok" else "failed"
}
