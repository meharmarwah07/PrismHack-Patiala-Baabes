package com.calo.nlu

import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * T0: NLUClient must never let a transport failure look like a genuine
 * "no match" -- these exercise the real production code against a mocked
 * HTTP layer (MockWebServer), not a reimplementation, so a regression
 * here would be a regression in what ships.
 */
class NLUClientTest {

    private lateinit var server: MockWebServer
    private val candidates = listOf(
        CandidateFlow("flow-1", "order a margherita pizza", "Orders a pizza", listOf("item"), mapOf("item" to "Margherita"))
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(readTimeoutMs: Long = 5000): NLUClient {
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return NLUClient(apiKey = "test-key", client = http, endpoint = server.url("/").toString())
    }

    @Test
    fun `401 unauthorized becomes ERROR status, not a silent no-match`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "invalid api key"}"""))
        val result = clientFor().match("order a margherita pizza", candidates)
        assertEquals(MatchStatus.ERROR, result.status)
        assertNull(result.matchedFlowId)
    }

    @Test
    fun `429 rate limited becomes ERROR status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error": "rate limit exceeded"}"""))
        val result = clientFor().match("order a margherita pizza", candidates)
        assertEquals(MatchStatus.ERROR, result.status)
    }

    @Test
    fun `a read timeout becomes ERROR status`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = clientFor(readTimeoutMs = 300).match("order a margherita pizza", candidates)
        assertEquals(MatchStatus.ERROR, result.status)
    }

    @Test
    fun `a 500 server error becomes ERROR status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        val result = clientFor().match("order a margherita pizza", candidates)
        assertEquals(MatchStatus.ERROR, result.status)
    }

    @Test
    fun `a healthy 200 response with a confident match reaches MATCHED via the real evaluator`() = runTest {
        val body = """{"choices":[{"message":{"content":"{\"matchedFlowId\": \"flow-1\", \"slotValues\": {\"item\": \"Pepperoni\"}, \"confidence\": 0.95, \"alternatives\": []}"}}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val result = clientFor().match("get me a pepperoni", candidates)
        assertEquals(MatchStatus.MATCHED, result.status)
        assertEquals("flow-1", result.matchedFlowId)
        assertEquals(mapOf("item" to "Pepperoni"), result.slotValues)
    }

    @Test
    fun `a healthy 200 response with a low-confidence guess is gated to NO_MATCH, not replayed`() = runTest {
        val body = """{"choices":[{"message":{"content":"{\"matchedFlowId\": \"flow-1\", \"slotValues\": {}, \"confidence\": 0.4, \"alternatives\": []}"}}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val result = clientFor().match("book me a cab", candidates)
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertNull(result.matchedFlowId)
    }

    @Test
    fun `a blank API key becomes ERROR without making any network call`() = runTest {
        // No response enqueued -- if this made a request, MockWebServer would fail the test.
        val result = NLUClient(apiKey = "", endpoint = server.url("/").toString()).match("x", candidates)
        assertEquals(MatchStatus.ERROR, result.status)
        assertEquals(0, server.requestCount)
    }
}
