package com.calo.nlu

import com.calo.BuildConfig
import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchResult
import com.calo.domain.nlu.MatchStatus
import com.calo.domain.nlu.NluMatchEvaluator
import com.calo.domain.nlu.NluPrompt
import com.calo.domain.nlu.NluResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The single LLM call the team decided on for utterance-matching +
 * slot-extraction (chosen over an embedding index). All prompt-building,
 * response-parsing, and threshold/ambiguity gating logic lives in :domain
 * (NluPrompt / NluResponseParser / NluMatchEvaluator) and is unit-tested
 * there; this class is just the HTTP transport plus the one piece that
 * genuinely can't be unit-tested here — an actual network call to Groq.
 *
 * Verified live against the rotated GROQ_API_KEY on 2026-09-26 via
 * NluLiveHarness (domain module, JVM-only, no device needed) — see the
 * lane report for the confidence table MATCH_THRESHOLD/AMBIGUITY_MARGIN
 * were derived from. Model is openai/gpt-oss-20b (DEFAULT_MODEL below).
 */
class NLUClient(
    private val apiKey: String = BuildConfig.GROQ_API_KEY,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    // Overridable only so tests can point this at a MockWebServer instead of
    // the real Groq endpoint; production code never passes this parameter.
    private val endpoint: String = DEFAULT_ENDPOINT
) {
    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        const val ERROR_MESSAGE = "Voice matching is unavailable right now"
        const val DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // openai/gpt-oss-20b is a reasoning model: it emits a "reasoning" stream
        // BEFORE the final "content" JSON, both drawn from the same completion
        // budget. Confirmed live (2026-09-28): with no cap set, a single-candidate
        // "order pizza" call used 64-93 reasoning tokens with finish_reason "stop";
        // forcing max_completion_tokens down to 15 reproduced content="" with
        // finish_reason "length" -- reasoning alone exceeded the budget before any
        // JSON was emitted. Uncapped, Groq's account/model default applies, which
        // isn't guaranteed generous enough as candidate lists grow. 1024 gives wide
        // headroom over the ~150-token completions actually observed.
        private const val MAX_COMPLETION_TOKENS = 1024
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun errorResult(logReason: String, cause: Throwable? = null): MatchResult {
        android.util.Log.e("NLUClient", "Groq call failed ($ERROR_MESSAGE): $logReason", cause)
        return MatchResult(matchedFlowId = null, status = MatchStatus.ERROR)
    }

    /**
     * ERROR status (never a bare "no match") on a missing key, a failed
     * HTTP call (401/429/5xx), a network/timeout exception, or a
     * truncated completion (finish_reason "length" -- see MAX_COMPLETION_TOKENS'
     * doc: reproduced live on 2026-09-28, this is what "zero confidence,
     * no match, no exception" turned out to be. gpt-oss-20b's reasoning
     * can exhaust the completion budget before it emits any JSON, leaving
     * content="". NluResponseParser correctly treats unparseable content
     * as a safe no-match by design -- but with no way to tell "the model
     * looked and said no" apart from "the model never got to answer",
     * that safe default was silently masking a truncation, not a
     * judgment. Checking finish_reason here, before content ever reaches
     * the parser, is what makes that distinction instead of losing it.
     * A 200 response with unparseable-but-complete content (finish_reason
     * "stop") still collapses to NluResponseParser's safe no-match default
     * (that's "couldn't understand," a model-quality issue, not a
     * transport error) — see NluResponseParser's class doc. Threshold/
     * ambiguity gating (T12/T13) is applied via NluMatchEvaluator only
     * on the happy path, since an ERROR/no-match result has nothing to
     * gate.
     */
    suspend fun match(utterance: String, candidates: List<CandidateFlow>): MatchResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext errorResult("GROQ_API_KEY is not set; see app/build.gradle.kts")
            }

            val prompt = NluPrompt.build(utterance, candidates)
            val requestBody = buildRequestBody(prompt)

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val message = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext errorResult("HTTP ${response.code}")
                    }
                    val bodyString = response.body?.string()
                        ?: return@withContext errorResult("HTTP ${response.code} had no body")
                    extractMessage(bodyString)
                }
            } catch (e: IOException) {
                // Covers java.net.SocketTimeoutException (connect/read timeout) as well as
                // any other transport failure -- all "Groq call didn't complete", not "no match".
                return@withContext errorResult("network/timeout: ${e.javaClass.simpleName}", e)
            }

            if (message == null) {
                return@withContext errorResult("Groq response body wasn't the expected shape")
            }

            if (message.finishReason == "length") {
                return@withContext errorResult(
                    "Groq truncated before emitting a full answer (finish_reason=length) -- " +
                        "content was ${if (message.content.isNullOrEmpty()) "empty" else "partial: ${message.content}"}"
                )
            }

            val rawContent = message.content
            if (rawContent == null) {
                return@withContext errorResult("Groq response had no message.content")
            }

            NluMatchEvaluator.evaluate(NluResponseParser.parse(rawContent))
        }

    private fun buildRequestBody(prompt: String) = buildJsonObject {
        put("model", model)
        put("temperature", 0.0) // deterministic matching, not creative
        put("max_completion_tokens", MAX_COMPLETION_TOKENS)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
    }

    private class GroqMessage(val content: String?, val finishReason: String?)

    private fun extractMessage(responseBody: String): GroqMessage? = try {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        GroqMessage(content, finishReason)
    } catch (e: Exception) {
        android.util.Log.e("NLUClient", "Could not parse Groq response shape", e)
        null
    }
}
