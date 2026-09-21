package com.calo.nlu

import com.calo.BuildConfig
import com.calo.domain.nlu.CandidateFlow
import com.calo.domain.nlu.MatchResult
import com.calo.domain.nlu.NluPrompt
import com.calo.domain.nlu.NluResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
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
 * slot-extraction (chosen over an embedding index). All prompt-building
 * and response-parsing logic lives in :domain (NluPrompt / NluResponseParser)
 * and is unit-tested there; this class is just the HTTP transport plus the
 * one piece that genuinely can't be unit-tested here — an actual network
 * call to Groq. NOT exercised against a live API key in this build pass;
 * see the README's unverified list.
 *
 * Model choice (as of Sep 2026, per console.groq.com/docs/models):
 * llama-3.1-8b-instant — fast/cheap, plenty for a short matching+extraction
 * prompt. Swap via GROQ_MODEL if the team wants the larger 70b model for
 * accuracy on trickier paraphrases; Groq's model lineup changes over time,
 * so re-check console.groq.com/docs/models before the demo.
 */
class NLUClient(
    private val apiKey: String = BuildConfig.GROQ_API_KEY,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns a "no match" MatchResult (never throws to the caller) on any
     * network/HTTP/parse failure — matches NluResponseParser's own
     * fail-safe default, so the orchestrator has exactly one shape to
     * handle: "didn't find a match" covers network errors, malformed
     * replies, and genuine no-match alike. Callers that need to
     * distinguish "Groq call failed" for logging/retry should catch
     * IOException around this call instead of relying on the result shape.
     */
    suspend fun match(utterance: String, candidates: List<CandidateFlow>): MatchResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                // Fails loudly in logs, quietly (no-match) to the caller —
                // a missing key should never look like "the user said
                // something unrecognized."
                android.util.Log.e("NLUClient", "GROQ_API_KEY is not set; see app/build.gradle.kts")
                return@withContext MatchResult(matchedFlowId = null)
            }

            val prompt = NluPrompt.build(utterance, candidates)
            val requestBody = buildRequestBody(prompt)

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val rawContent = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.e("NLUClient", "Groq call failed: HTTP ${response.code}")
                        return@withContext MatchResult(matchedFlowId = null)
                    }
                    val bodyString = response.body?.string() ?: return@withContext MatchResult(matchedFlowId = null)
                    extractMessageContent(bodyString)
                }
            } catch (e: IOException) {
                android.util.Log.e("NLUClient", "Groq call threw", e)
                return@withContext MatchResult(matchedFlowId = null)
            }

            NluResponseParser.parse(rawContent ?: return@withContext MatchResult(matchedFlowId = null))
        }

    private fun buildRequestBody(prompt: String) = buildJsonObject {
        put("model", model)
        put("temperature", 0.0) // deterministic matching, not creative
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
    }

    private fun extractMessageContent(responseBody: String): String? = try {
        val root = json.parseToJsonElement(responseBody).jsonObject
        root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.content
    } catch (e: Exception) {
        android.util.Log.e("NLUClient", "Could not parse Groq response shape", e)
        null
    }
}
