package com.calo.domain.nlu

import org.junit.Assert.assertTrue
import org.junit.Test

class NluPromptTest {

    @Test
    fun `includes the spoken utterance verbatim`() {
        val prompt = NluPrompt.build("order me a pepperoni pizza", emptyList())
        assertTrue(prompt.contains("order me a pepperoni pizza"))
    }

    @Test
    fun `lists every candidate's taught phrase and slots`() {
        val candidates = listOf(
            CandidateFlow("flow-1", "order a margherita pizza", "Orders a pizza", listOf("item", "quantity"))
        )
        val prompt = NluPrompt.build("get me two pepperonis", candidates)
        assertTrue(prompt.contains("order a margherita pizza"))
        assertTrue(prompt.contains("item, quantity"))
        assertTrue(prompt.contains("flow-1"))
    }

    @Test
    fun `still produces a well-formed prompt with zero candidates`() {
        val prompt = NluPrompt.build("do something", emptyList())
        assertTrue(prompt.contains("do something"))
        assertTrue(prompt.contains("matchedFlowId"))
    }

    @Test
    fun `instructs strict JSON output with no markdown fences`() {
        val prompt = NluPrompt.build("x", emptyList())
        assertTrue(prompt.contains("no markdown fences"))
    }

    @Test
    fun `names each flow's app and asks for a target app`() {
        val candidates = listOf(CandidateFlow("flow-1", "add earbuds to cart", "Adds earbuds", listOf("query"), appName = "Amazon"))
        val prompt = NluPrompt.build("add earbuds to my bag on myntra", candidates)
        assertTrue(prompt.contains("learned on app: \"Amazon\""))
        assertTrue(prompt.contains("targetApp"))
    }
}
