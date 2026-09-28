package com.calo.domain.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NluResponseParserTest {

    @Test
    fun `parses a clean match with a single slot`() {
        val raw = """{"matchedFlowId": "flow-1", "slotValues": {"item": "Pepperoni"}, "confidence": 0.92}"""
        val result = NluResponseParser.parse(raw)
        assertEquals("flow-1", result.matchedFlowId)
        assertEquals(mapOf("item" to "Pepperoni"), result.slotValues)
        assertEquals(0.92, result.confidence, 0.0001)
    }

    @Test
    fun `parses an explicit no-match response`() {
        val raw = """{"matchedFlowId": null, "slotValues": {}, "confidence": 0.0}"""
        val result = NluResponseParser.parse(raw)
        assertNull(result.matchedFlowId)
        assertTrue(result.slotValues.isEmpty())
    }

    @Test
    fun `strips markdown fences the model added despite instructions not to`() {
        val raw = "```json\n{\"matchedFlowId\": \"flow-2\", \"slotValues\": {}, \"confidence\": 0.5}\n```"
        val result = NluResponseParser.parse(raw)
        assertEquals("flow-2", result.matchedFlowId)
    }

    @Test
    fun `multiple slots all extracted`() {
        val raw = """{"matchedFlowId": "flow-3", "slotValues": {"item": "Pepperoni", "quantity": "2"}, "confidence": 0.8}"""
        val result = NluResponseParser.parse(raw)
        assertEquals(mapOf("item" to "Pepperoni", "quantity" to "2"), result.slotValues)
    }

    @Test
    fun `garbage response collapses to safe no-match rather than throwing`() {
        val result = NluResponseParser.parse("I'm not sure what you mean, could you clarify?")
        assertNull(result.matchedFlowId)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty string does not throw`() {
        val result = NluResponseParser.parse("")
        assertNull(result.matchedFlowId)
    }

    @Test
    fun `missing confidence field defaults to zero rather than crashing`() {
        val raw = """{"matchedFlowId": "flow-1", "slotValues": {}}"""
        val result = NluResponseParser.parse(raw)
        assertEquals("flow-1", result.matchedFlowId)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `confidence outside 0-1 is clamped, not trusted verbatim`() {
        val raw = """{"matchedFlowId": "flow-1", "slotValues": {}, "confidence": 5.0}"""
        assertEquals(1.0, NluResponseParser.parse(raw).confidence, 0.0001)

        val rawNeg = """{"matchedFlowId": "flow-1", "slotValues": {}, "confidence": -2.0}"""
        assertEquals(0.0, NluResponseParser.parse(rawNeg).confidence, 0.0001)
    }

    @Test
    fun `the literal string null for matchedFlowId is treated as no match`() {
        // Some LLMs emit the bare token null; others quote it as "null". Both must mean "no match".
        val raw = """{"matchedFlowId": "null", "slotValues": {}, "confidence": 0.0}"""
        assertNull(NluResponseParser.parse(raw).matchedFlowId)
    }

    @Test
    fun `parses a named target app`() {
        val raw = """{"matchedFlowId": "flow-1", "slotValues": {}, "targetApp": "Myntra", "confidence": 0.9}"""
        assertEquals("Myntra", NluResponseParser.parse(raw).targetApp)
    }

    @Test
    fun `missing, null, or the string null target app all mean none named`() {
        assertNull(NluResponseParser.parse("""{"matchedFlowId": "f"}""").targetApp)
        assertNull(NluResponseParser.parse("""{"matchedFlowId": "f", "targetApp": null}""").targetApp)
        assertNull(NluResponseParser.parse("""{"matchedFlowId": "f", "targetApp": "null"}""").targetApp)
    }
}
