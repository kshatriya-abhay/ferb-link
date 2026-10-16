package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * TypeSafe's Jev, a cloud decision model (docs.typesafe.ai, "API reference"): one Choice over the
 * tools Ferb routed a request to, plus [NONE], to pick the tool for Ferb. It never
 * fills arguments (Jev is weak with times and numbers): those stay with Needle 3 or Gemma.
 *
 * Only the request and each tool's name and description are sent, never parameters or facts. The
 * key goes in the Authorization header and nowhere else: [trace] gets method, status and timings.
 * Blocking: call it off the main thread.
 */
class JevClient(
    private val apiKey: String,
    baseUrl: String = BASE_URL,
    /** Pinned, so a bar set on the bench keeps its meaning; a newer model means a new bench run. */
    private val model: String = MODEL,
    private val connectTimeoutMs: Int = 2_000,
    private val trace: ((String) -> Unit)? = null,
) {
    private val url = baseUrl.trim().trimEnd('/') + PATH

    /** Which of [toolsJson]'s tools (a JSON array of schemas, as for `complete`) [request] wants, within [timeoutMs]. */
    fun decide(request: String, toolsJson: String, timeoutMs: Int = LinkContract.DECIDE_TIMEOUT_MS): Decision {
        val started = System.nanoTime()
        val tools = try {
            tools(JSONArray(toolsJson))
        } catch (e: Exception) {
            return Decision.failed(Failure(ErrorKind.BadRequest, "The tools aren't a JSON array of named tools: ${e.message}", "bad request: tools"))
        }
        if (request.isBlank()) return Decision.failed(Failure(ErrorKind.BadRequest, "No request", "bad request: no request"))
        val options = options(request, tools.map { it.first })
        val body = body(request, tools.toMap(), options, model)
        val estimate = estimateTokens(body, options.size)
        var sent = false
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            var ok = false
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = minOf(connectTimeoutMs, timeoutMs)
                connection.readTimeout = timeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                try {
                    connection.connect()
                } catch (e: IOException) {
                    throw NotConnected(e)
                }
                sent = true
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                ok = true
                val ms = msSince(started)
                trace?.invoke("POST $PATH ${body.length} chars → $code in $ms ms")
                val usage = runCatching { JSONObject(text).getJSONObject("usage").getInt("input_tokens") }.getOrNull()
                val billed = Billed(usage ?: estimate, estimated = usage == null)
                if (code != 200) Decision.failed(problem(code, text), ms, options, billed)
                else parse(JSONObject(text), options, ms, billed)
            } finally {
                if (!ok) connection.disconnect()
            }
        } catch (e: NotConnected) {
            trace?.invoke("POST $PATH couldn't connect: ${e.cause}")
            val cause = e.cause
            val message = if (cause is UnknownHostException) "Can't find TypeSafe's server. Is the phone online?" else "TypeSafe doesn't answer: ${cause?.message}"
            Decision.failed(Failure(ErrorKind.Unreachable, message, "can't reach Jev"), msSince(started), options)
        } catch (e: SocketTimeoutException) {
            trace?.invoke("POST $PATH timed out after ${msSince(started)} ms")
            // Sent, so it may be billed: count the estimate.
            Decision.failed(Failure(ErrorKind.Timeout, "TypeSafe took too long to answer", "Jev timed out"), msSince(started), options, Billed(estimate, true))
        } catch (e: IOException) {
            trace?.invoke("POST $PATH failed: $e")
            Decision.failed(Failure(ErrorKind.Unreachable, "Lost TypeSafe: ${e.message}", "can't reach Jev"), msSince(started), options, if (sent) Billed(estimate, true) else Billed.NONE)
        } catch (e: Exception) {
            Decision.failed(Failure(ErrorKind.BadReply, "Couldn't read TypeSafe's answer: ${e.message}", "bad Jev answer"), msSince(started), options, Billed(estimate, true))
        }
    }

    private fun parse(reply: JSONObject, options: List<String>, ms: Long, billed: Billed): Decision {
        val answer = reply.getJSONObject("answers").getJSONObject(QUESTION)
        val choice = answer.getString("choice")
        val probabilities = answer.getJSONObject("probabilities")
        require(choice in options) { "Jev chose \"$choice\", which wasn't offered" }
        return Decision(choice, options.associateWith { probabilities.optDouble(it, 0.0) }, options, reply.optString("model"), ms, billed, null)
    }

    /** What Jev's answer cost: input tokens from its `usage`, or [estimated] from the body's length. */
    data class Billed(val inputTokens: Int, val estimated: Boolean) {
        val usd: Double get() = inputTokens * USD_PER_INPUT_TOKEN

        companion object {
            val NONE = Billed(0, false)
        }
    }

    /** Jev's pick and every option's probability (in the order asked, [options]), or why there's none. */
    data class Decision(
        val choice: String,
        val probabilities: Map<String, Double>,
        val options: List<String>,
        val model: String,
        val ms: Long,
        val billed: Billed,
        val failure: Failure?,
    ) {
        /** Shaped as Ferb Link's other replies: `success`, then the answer or `error`, `error_detail`, `error_kind`. */
        fun toJson(): String = JSONObject()
            .put("success", failure == null)
            .put("choice", choice)
            .put("probabilities", JSONObject(probabilities))
            .put("options", JSONArray(options))
            .put("model", model)
            .put("ms", ms)
            .put("source", "jev")
            .apply {
                failure?.let {
                    put("error", it.short)
                    put("error_detail", it.message)
                    put("error_kind", it.kind.wire)
                }
            }
            .toString()

        companion object {
            fun failed(failure: Failure, ms: Long = 0, options: List<String> = emptyList(), billed: Billed = Billed.NONE) =
                Decision("", emptyMap(), options, "", ms, billed, failure)
        }
    }

    private class NotConnected(cause: IOException) : IOException(cause)

    companion object {
        const val BASE_URL = "https://api.typesafe.ai"
        const val PATH = "/v1/systemone"
        const val MODEL = "jev-1.13.0"
        /** $0.042 per million input tokens; output tokens are free (docs.typesafe.ai, "Models"). */
        const val USD_PER_INPUT_TOKEN = 0.042 / 1_000_000
        const val NONE = "none"
        const val QUESTION = "tool"

        const val INSTRUCTIONS = "Which of the phone assistant's actions does the user want? Pick none if no action fits."
        const val NONE_DESCRIPTION = "No action fits: the user is chatting, asking a question, or wants something these actions don't do."

        /** Each tool's name and description, nothing else. */
        fun tools(schemas: JSONArray): List<Pair<String, String>> = (0 until schemas.length()).map {
            val tool = schemas.getJSONObject(it)
            tool.getString("name") to tool.optString("description")
        }.also { tools -> require(tools.none { it.first == NONE }) { "a tool is called \"$NONE\"" } }

        /**
         * The options in a shuffled order, seeded by the request: Jev leans towards the first
         * option, so no tool is always first, yet a request is always asked the same way.
         */
        fun options(request: String, names: List<String>): List<String> =
            (names.sorted() + NONE).shuffled(Random(request.trim().lowercase().hashCode()))

        /**
         * The request body, with the options in [options]' order. Written by hand: JSONObject
         * doesn't keep its keys' order, and JSONStringer differs between Android and org.json.
         */
        fun body(request: String, descriptions: Map<String, String>, options: List<String>, model: String = MODEL): String {
            fun q(text: String) = JSONObject.quote(text)
            val criteria = options.joinToString(",") { "${q(it)}:${q(if (it == NONE) NONE_DESCRIPTION else descriptions.getValue(it))}" }
            return """{"model":${q(model)},"state":${q(request)},"questions":{${q(QUESTION)}:""" +
                """{"type":"choice","instructions":${q(INSTRUCTIONS)},"criteria":{$criteria}}}}"""
        }

        /**
         * Input tokens, for when the answer has no `usage` and for a run's budget: about four
         * characters a token, plus what Jev adds to every call (about 255) and to each option
         * (about 8). Fitted on the smoke run of 2026-10-15: 354–871 tokens, within 5%.
         */
        fun estimateTokens(body: String, options: Int) = 255 + (body.length + 3) / 4 + 8 * options

        private fun problem(code: Int, body: String): Failure {
            val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                ?: runCatching { JSONObject(body).getString("detail") }.getOrNull()
            return when (code) {
                401, 403 -> Failure(ErrorKind.Auth, "TypeSafe turned down the API key", "Jev key refused")
                429 -> Failure(ErrorKind.Server, "TypeSafe's rate limit: try again later", "Jev rate limited")
                400, 413, 415, 422 -> Failure(ErrorKind.BadRequest, "TypeSafe refused the request ($code)" + (message?.let { ": $it" } ?: ""), "bad request: Jev")
                else -> Failure(ErrorKind.Server, "TypeSafe answered $code" + (message?.let { ": $it" } ?: ""), if (code == 529) "Jev busy" else "Jev error $code")
            }
        }

        private fun msSince(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}
