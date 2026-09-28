package com.calo.domain.nlu

import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * NOT part of the normal test suite's signal — this hits the real Groq API
 * and costs real latency/quota, so it no-ops unless GROQ_API_KEY is set in
 * the environment. Run explicitly:
 *
 *   JAVA_HOME=<temurin17> GROQ_API_KEY=<key> ./gradlew.bat :domain:test \
 *       --tests "com.calo.domain.nlu.NluLiveHarness" -Dgroq.live=1
 *
 * Exercises the REAL production NluPrompt.build / NluResponseParser.parse /
 * NluMatchEvaluator.evaluate — this is deliberately not a reimplementation,
 * so the confidence numbers it records are the numbers production code
 * would actually see. Results are written to build/nlu-harness-report.tsv
 * (never printed to stdout/logs) so they can be read back as a table
 * without ever putting the API key itself in any tool output.
 */
class NluLiveHarness {

    private val apiKey = System.getenv("GROQ_API_KEY")
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val dominos = CandidateFlow(
        id = "dominos-order",
        triggerUtterance = "order a margherita pizza from Domino's",
        description = "Orders a pizza from the Domino's app",
        slotNames = listOf("item"),
        slotExampleValues = mapOf("item" to "Margherita")
    )
    private val pizzaHut = CandidateFlow(
        id = "pizzahut-order",
        triggerUtterance = "order a pizza from Pizza Hut",
        description = "Orders a pizza from the Pizza Hut app",
        slotNames = listOf("item"),
        slotExampleValues = mapOf("item" to "Pepperoni")
    )
    private val bankTransfer = CandidateFlow(
        id = "bank-transfer",
        triggerUtterance = "send 500 rupees to mom",
        description = "Sends a UPI transfer in the bank app",
        slotNames = listOf("amount", "recipient"),
        slotExampleValues = mapOf("amount" to "500", "recipient" to "mom")
    )
    private val alarm = CandidateFlow(
        id = "set-alarm",
        triggerUtterance = "set an alarm for 7am",
        description = "Sets an alarm in the clock app",
        slotNames = listOf("time"),
        slotExampleValues = mapOf("time" to "7am")
    )

    private val fourFlowSet = listOf(dominos, pizzaHut, bankTransfer, alarm)
    private val twoSimilarPizzaFlows = listOf(dominos, pizzaHut)

    @Test
    fun `T0 key check plus T12 threshold data plus T1 slot extraction plus T13 ambiguity`() {
        if (apiKey.isNullOrBlank()) {
            println("NluLiveHarness SKIPPED — set GROQ_API_KEY to run live Groq calls")
            return
        }

        val rows = mutableListOf<String>()
        rows += listOf("section", "utterance", "label", "matchedFlowId", "confidence", "alternatives", "slotValues", "status", "latencyMs").joinToString("\t")

        // --- Task 0: key check (first call doubles as latency sample) ---
        runOne("T0", "order a margherita pizza from Domino's", "exact", fourFlowSet, rows)

        // --- Task 2: T12 threshold data — 5 exact, 5 paraphrase, 5 unrelated, against fourFlowSet ---
        val exact = listOf(
            "order a margherita pizza from Domino's",
            "order a pizza from Pizza Hut",
            "send 500 rupees to mom",
            "set an alarm for 7am",
            "order a margherita pizza from Domino's"
        )
        val paraphrase = listOf(
            "get me a pepperoni from dominos",
            "order a Farmhouse pizza from Domino's",
            "transfer five hundred to mom on the bank app",
            "wake me up at 7 in the morning",
            "same as before but paneer"
        )
        val unrelated = listOf(
            "book me a cab",
            "what's the weather",
            "play some music",
            "turn on the flashlight",
            "call my sister"
        )
        exact.forEach { runOne("T12-exact", it, "exact", fourFlowSet, rows) }
        paraphrase.forEach { runOne("T12-paraphrase", it, "paraphrase", fourFlowSet, rows) }
        unrelated.forEach { runOne("T12-unrelated", it, "unrelated", fourFlowSet, rows) }

        // --- Task 1: slot extraction against a 2-candidate pizza set ---
        val slotTests = listOf(
            "order a Farmhouse pizza from Domino's",
            "get me a pepperoni from dominos",
            "same as before but paneer",
            "order a Margherita"
        )
        slotTests.forEach { runOne("T1-slots", it, "slot", twoSimilarPizzaFlows, rows) }

        // --- Task 3: T13 ambiguity — deliberately similar flows + a genuinely ambiguous command ---
        runOne("T13-ambiguous", "order a pizza", "ambiguous", twoSimilarPizzaFlows, rows)
        runOne("T13-control-dominos", "order a pizza from Domino's", "control", twoSimilarPizzaFlows, rows)
        runOne("T13-control-hut", "order a pizza from Pizza Hut", "control", twoSimilarPizzaFlows, rows)

        val outFile = File("build/nlu-harness-report.tsv")
        outFile.parentFile.mkdirs()
        outFile.writeText(rows.joinToString("\n"))
        println("NluLiveHarness wrote ${rows.size - 1} rows to ${outFile.absolutePath}")
    }

    @Test
    fun `T13 ambiguity margin resampling`() {
        if (apiKey.isNullOrBlank()) {
            println("NluLiveHarness SKIPPED — set GROQ_API_KEY to run live Groq calls")
            return
        }
        val rows = mutableListOf<String>()
        rows += listOf("section", "utterance", "label", "matchedFlowId", "confidence", "alternatives", "slotValues", "status", "latencyMs").joinToString("\t")

        val ambiguousUtterances = listOf("order a pizza", "order a pizza", "I want a pizza", "get me pizza", "order me a pizza")
        ambiguousUtterances.forEach { runOne("T13-ambiguous-resample", it, "ambiguous", twoSimilarPizzaFlows, rows) }
        runOne("T13-control-dominos-resample", "order a pizza from Domino's", "control", twoSimilarPizzaFlows, rows)
        runOne("T13-control-hut-resample", "order a pizza from Pizza Hut", "control", twoSimilarPizzaFlows, rows)

        val outFile = File("build/nlu-harness-ambiguity-report.tsv")
        outFile.parentFile.mkdirs()
        outFile.writeText(rows.joinToString("\n"))
        println("NluLiveHarness wrote ${rows.size - 1} rows to ${outFile.absolutePath}")
    }

    // Hypothesis test: gpt-oss-20b is a reasoning model whose "reasoning" tokens come
    // before the final "content" JSON. If completion is capped low enough that
    // reasoning eats the whole budget, content truncates to "" with finish_reason
    // "length" -- and NluResponseParser's safe-default (by design, for genuinely
    // malformed replies) would silently turn that into confidence=0/no-match with
    // zero log trace, since it has no android.util.Log and swallows via runCatching.
    // Forcing a tiny max_completion_tokens here to see exactly what that looks like.
    @Test
    fun `repro -- forced truncation via tiny max_completion_tokens`() {
        if (apiKey.isNullOrBlank()) {
            println("NluLiveHarness SKIPPED — set GROQ_API_KEY to run live Groq calls")
            return
        }
        val candidate = CandidateFlow(
            id = java.util.UUID.randomUUID().toString(),
            triggerUtterance = "Order Pizza",
            description = "Order Pizza",
            slotNames = emptyList()
        )
        val prompt = NluPrompt.build("order pizza", listOf(candidate))
        val body = buildJsonObject {
            put("model", "openai/gpt-oss-20b")
            put("temperature", 0.0)
            put("max_completion_tokens", 15) // deliberately tiny -- reasoning alone exceeds this
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
        }.toString()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val rawContent = extractContent(response.body())
        val parsed = NluResponseParser.parse(rawContent ?: "")
        val evaluated = NluMatchEvaluator.evaluate(parsed)

        val out = File("build/nlu-repro-truncation.txt")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("HTTP STATUS: ${response.statusCode()}")
                appendLine("FULL RESPONSE BODY:")
                appendLine(response.body())
                appendLine()
                appendLine("EXTRACTED message.content (rawContent): [${rawContent}]")
                appendLine("PARSED (pre-evaluator): matchedFlowId=${parsed.matchedFlowId} confidence=${parsed.confidence}")
                appendLine("EVALUATED: matchedFlowId=${evaluated.matchedFlowId} confidence=${evaluated.confidence} status=${evaluated.status}")
            }
        )
        println("Truncation repro written to ${out.absolutePath}")
    }

    // Temporary repro for the "order pizza" / "Order Pizza" zero-confidence report --
    // dumps the RAW Groq response text (not just parsed fields) to distinguish a
    // parser bug from a genuine model no-match.
    @Test
    fun `repro -- single candidate 'Order Pizza' trigger vs 'order pizza' utterance`() {
        if (apiKey.isNullOrBlank()) {
            println("NluLiveHarness SKIPPED — set GROQ_API_KEY to run live Groq calls")
            return
        }
        val candidate = CandidateFlow(
            // Production ids are UUID.randomUUID().toString() (see LearnedFlow.kt),
            // not a readable slug -- testing with a realistic id in case that's the
            // actual difference from the reported failure.
            id = java.util.UUID.randomUUID().toString(),
            triggerUtterance = "Order Pizza",
            description = "Order Pizza",
            slotNames = emptyList()
        )
        val prompt = NluPrompt.build("order pizza", listOf(candidate))
        val out = File("build/nlu-repro-order-pizza.txt")
        out.parentFile.mkdirs()
        val allRuns = StringBuilder()
        allRuns.appendLine("PROMPT SENT (same every run):")
        allRuns.appendLine(prompt)
        allRuns.appendLine()

        repeat(5) { attempt ->
            val body = buildJsonObject {
                put("model", "openai/gpt-oss-20b")
                put("temperature", 0.0)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            }.toString()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            var response = client.send(request, HttpResponse.BodyHandlers.ofString())
            var retries = 0
            while (response.statusCode() == 429 && retries < 5) {
                val retryAfterSec = response.headers().firstValue("retry-after").map { it.toLongOrNull() ?: 5L }.orElse(5L)
                Thread.sleep((retryAfterSec + 1) * 1000)
                response = client.send(request, HttpResponse.BodyHandlers.ofString())
                retries++
            }
            val rawContent = extractContent(response.body())
            val parsed = NluResponseParser.parse(rawContent ?: "")
            val evaluated = NluMatchEvaluator.evaluate(parsed)

            allRuns.appendLine("=== ATTEMPT ${attempt + 1} (candidate id = ${candidate.id}) ===")
            allRuns.appendLine("HTTP STATUS: ${response.statusCode()}")
            allRuns.appendLine("FULL RESPONSE BODY:")
            allRuns.appendLine(response.body())
            allRuns.appendLine("EXTRACTED message.content (rawContent):")
            allRuns.appendLine(rawContent ?: "<null - extractContent failed>")
            allRuns.appendLine("PARSED (pre-evaluator): matchedFlowId=${parsed.matchedFlowId} confidence=${parsed.confidence} slotValues=${parsed.slotValues} alternatives=${parsed.alternatives}")
            allRuns.appendLine("EVALUATED (what NLUClient.match() actually returns): matchedFlowId=${evaluated.matchedFlowId} confidence=${evaluated.confidence} status=${evaluated.status}")
            allRuns.appendLine()
            out.writeText(allRuns.toString())
            if (attempt < 4) Thread.sleep(2500)
        }
        println("Repro (5 attempts) written to ${out.absolutePath}")
    }

    private fun runOne(section: String, utterance: String, label: String, candidates: List<CandidateFlow>, rows: MutableList<String>) {
        val prompt = NluPrompt.build(utterance, candidates)
        val body = buildJsonObject {
            put("model", "openai/gpt-oss-20b")
            put("temperature", 0.0)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val start = System.currentTimeMillis()
        var response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            rows += listOf(section, utterance, label, "EXCEPTION", "", "", "", e.javaClass.simpleName, "").joinToString("\t")
            return
        }
        // Free-tier Groq RPM limit — back off and retry rather than recording
        // a spurious "no match"; a 429 is a rate limit, not the model's answer.
        var retries = 0
        while (response.statusCode() == 429 && retries < 5) {
            val retryAfterSec = response.headers().firstValue("retry-after").map { it.toLongOrNull() ?: 5L }.orElse(5L)
            Thread.sleep((retryAfterSec + 1) * 1000)
            response = client.send(request, HttpResponse.BodyHandlers.ofString())
            retries++
        }
        val latency = System.currentTimeMillis() - start

        if (response.statusCode() != 200) {
            rows += listOf(section, utterance, label, "HTTP_${response.statusCode()}", "", "", "", "ERROR", latency.toString()).joinToString("\t")
            return
        }
        Thread.sleep(2500) // stay under the per-minute rate limit for the next call

        val content = extractContent(response.body())
        val raw = NluResponseParser.parse(content ?: "")
        val evaluated = NluMatchEvaluator.evaluate(raw)

        rows += listOf(
            section,
            utterance,
            label,
            raw.matchedFlowId ?: "null",
            raw.confidence.toString(),
            raw.alternatives.joinToString(";") { "${it.flowId}=${it.confidence}" },
            raw.slotValues.entries.joinToString(";") { "${it.key}=${it.value}" },
            evaluated.status.name,
            latency.toString()
        ).joinToString("\t")
    }

    private fun extractContent(responseBody: String): String? = try {
        val root = json.parseToJsonElement(responseBody).jsonObject
        root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
