package dev.abhay.ferblink

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException

/**
 * HTTP to llama-server: one request at a time, with its timings traced, and every way it can go
 * wrong turned into a [Failure] of the contract's kinds. [ServerClient] says what to ask and how
 * to read the answer. Blocking: call it off the main thread.
 */
class LlamaHttp(
    baseUrl: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    /** Gets one line of timings per HTTP request (Ferb Link logs them). */
    private val trace: ((String) -> Unit)?,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** A POST's outcome: the answer's text (HTTP 200), or why there's none, each with the time taken. */
    sealed interface Exchange {
        val ms: Long

        data class Answer(val text: String, override val ms: Long) : Exchange
        data class Failed(val failure: Failure, override val ms: Long) : Exchange
    }

    /**
     * POSTs [body] to [path] with the key. [body] is built inside, so a failure while building it
     * (looking up the model's name) is one too. An answer other than 200 fails by [problemOf].
     */
    fun post(path: String, timeoutMs: Int?, problemOf: (Int, String) -> Failure = { code, text -> problem(code, text) }, body: () -> String): Exchange {
        val started = System.nanoTime()
        return try {
            val (code, text) = send("POST", path, body(), auth = true, timeoutMs)
            if (code == 200) Exchange.Answer(text, msSince(started)) else Exchange.Failed(problemOf(code, text), msSince(started))
        } catch (e: IOException) {
            Exchange.Failed(failureOf(e), msSince(started))
        } catch (e: Exception) {
            Exchange.Failed(Failure(ErrorKind.BadReply, "Couldn't read your computer's answer: ${e.message}"), msSince(started))
        }
    }

    /**
     * One request. The connection isn't closed afterwards: reading the answer to the end returns it
     * to Android's pool, so the next request (transcribe, then complete) skips a new TCP handshake
     * over the tailnet. It's closed only when something went wrong.
     */
    fun send(method: String, path: String, body: String?, auth: Boolean, timeoutMs: Int?): Pair<Int, String> {
        val started = System.nanoTime()
        val connection = URL(base + path).openConnection() as HttpURLConnection
        var ok = false
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs?.let { minOf(it, connectTimeoutMs) } ?: connectTimeoutMs
            connection.readTimeout = timeoutMs ?: readTimeoutMs
            if (auth) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            // Connect before sending, so a failure here is "unreachable" and a timeout later is "timeout".
            try {
                connection.connect()
            } catch (e: IOException) {
                throw NotConnected(e)
            }
            val connected = System.nanoTime()
            val bytes = body?.toByteArray()
            if (bytes != null) connection.outputStream.use { it.write(bytes) }
            val sent = System.nanoTime()
            val code = connection.responseCode
            val answered = System.nanoTime()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            ok = true
            // A connect of a few ms means a pooled connection; a new one over the tailnet takes longer.
            trace?.invoke(
                "$method $path ${bytes?.size ?: 0} B → $code: connect ${ms(started, connected)} ms, send ${ms(connected, sent)} ms, " +
                    "wait ${ms(sent, answered)} ms, read ${ms(answered, System.nanoTime())} ms",
            )
            return code to text
        } catch (e: IOException) {
            trace?.invoke("$method $path failed after ${ms(started, System.nanoTime())} ms: $e")
            throw e
        } finally {
            if (!ok) connection.disconnect()
        }
    }

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    companion object {
        /** Unreachable details that already say what to do ([Guidance] adds nothing to them). */
        const val NO_NAME = "Can't find your computer's name. Is Tailscale on?"
        const val HTTP_NEEDS_TAILSCALE_NAME = "Plain HTTP only works with your computer's Tailscale name (….ts.net)"

        fun problem(code: Int, body: String): Failure {
            val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            val type = runCatching { JSONObject(body).getJSONObject("error").optString("type") }.getOrNull()
            return when {
                code == 400 && (type == "exceed_context_size_error" || message?.contains("context size") == true) ->
                    Failure(ErrorKind.BadRequest, "Too long for the model's context on your computer", "bad request: too long")
                else -> when (code) {
                401 -> Failure(ErrorKind.Auth, "Your computer turned down the API key")
                503 -> Failure(ErrorKind.Server, "Your computer is loading the model", "model loading")
                else -> Failure(ErrorKind.Server, "Your computer answered $code" + (message?.let { ": $it" } ?: ""), "server error $code")
                }
            }
        }

        /**
         * As [problem], but a schema llama-server can't turn into a grammar is bad_request. b11541
         * answers that with HTTP 500 and "JSON schema error at #/…: …" (probed 2026-11-06).
         */
        fun schemaProblem(code: Int, body: String): Failure {
            val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            if (code !in 400..500 || message?.startsWith("JSON schema error") != true) return problem(code, body)
            return Failure(ErrorKind.BadRequest, "Your computer couldn't use the JSON schema: $message", "bad request: schema not supported")
        }

        fun failureOf(e: IOException): Failure = when {
            e is Refused -> e.failure
            e is NotConnected -> Failure(ErrorKind.Unreachable, when (val cause = e.cause) {
                is UnknownHostException -> NO_NAME
                // Android's network security config: plain HTTP only to Tailscale names.
                is UnknownServiceException -> HTTP_NEEDS_TAILSCALE_NAME
                is ConnectException, is SocketTimeoutException -> "Your computer doesn't answer. Is the server running?"
                else -> cause?.message ?: e.toString()
            })
            e is SocketTimeoutException -> Failure(ErrorKind.Timeout, "Your computer took too long to answer")
            else -> Failure(ErrorKind.Unreachable, e.message ?: e.toString())
        }

        /** The server answered, but not with what was needed (looking up the model). */
        class Refused(val failure: Failure) : IOException(failure.message)

        /** The connection itself failed (as opposed to the answer taking too long). */
        private class NotConnected(cause: IOException) : IOException(cause)

        fun msSince(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}
