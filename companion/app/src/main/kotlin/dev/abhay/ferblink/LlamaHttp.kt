package dev.abhay.ferblink

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /** How a name is looked up (tests pass their own). */
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** A POST's outcome: the answer's text (HTTP 200), or why there's none, each with the time taken. */
    sealed interface Exchange {
        val ms: Long

        data class Answer(val text: String, override val ms: Long) : Exchange
        data class Failed(val failure: Failure, override val ms: Long) : Exchange
    }

    /**
     * One call's time limit, shared by all its requests (looking up the model's name, then the
     * answer), so the call as a whole ends by then.
     */
    class Deadline(ms: Int) {
        private val end = System.nanoTime() + ms * 1_000_000L

        /** The ms left; a [SocketTimeoutException] (a timeout) once none are. */
        fun left(): Int {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("No time left")
            return left.toInt()
        }
    }

    /**
     * POSTs [body] to [path] with the key, all within [timeoutMs] (the client's read timeout
     * without it). [body] is built inside with the call's [Deadline], so a failure while building
     * it (looking up the model's name) is one too, and takes its time from the same limit. An
     * answer other than 200 fails by [problemOf].
     */
    fun post(path: String, timeoutMs: Int?, problemOf: (Int, String) -> Failure = { code, text -> problem(code, text) }, body: (Deadline) -> String): Exchange {
        val started = System.nanoTime()
        val deadline = Deadline(timeoutMs ?: readTimeoutMs)
        return try {
            val text = body(deadline)
            val (code, answer) = send("POST", path, text, auth = true, deadline.left())
            if (code == 200) Exchange.Answer(answer, msSince(started)) else Exchange.Failed(problemOf(code, answer), msSince(started))
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
        // Connect and read time out apart, so together they could take twice [timeoutMs]: past
        // it, the connection is closed, and the request is a timeout.
        val cutOff = timeoutMs?.let { WATCHDOG.schedule({ connection.disconnect() }, it.toLong(), TimeUnit.MILLISECONDS) }
        fun overdue() = cutOff?.let { it.isDone && !it.isCancelled } == true
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs?.let { minOf(it, connectTimeoutMs) } ?: connectTimeoutMs
            connection.readTimeout = timeoutMs ?: readTimeoutMs
            if (auth) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            // Plain HTTP is safe only inside the tailnet (WireGuard encrypts it): a ….ts.net name that
            // resolves anywhere else (Tailscale off, a spoofed answer) would get the key in the clear.
            if (connection.url.protocol == "http") {
                val addresses = try {
                    resolve(connection.url.host)
                } catch (e: IOException) {
                    throw NotConnected(e)
                }
                if (addresses.isEmpty() || !addresses.all(::insideTailnet)) throw Refused(Failure(ErrorKind.Unreachable, NOT_ON_TAILNET))
            }
            // Connect before sending, so a failure here is "unreachable" and a timeout later is "timeout".
            try {
                connection.connect()
            } catch (e: IOException) {
                if (overdue()) throw SocketTimeoutException("Timed out connecting")
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
            throw if (overdue() && e !is SocketTimeoutException) SocketTimeoutException("Timed out after $timeoutMs ms") else e
        } finally {
            cutOff?.cancel(false)
            if (!ok) connection.disconnect()
        }
    }

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    companion object {
        /** Closes requests that run past their time ([send]); one daemon thread for the process. */
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { Thread(it, "ferblink-http-deadline").apply { isDaemon = true } }

        /** Unreachable details that already say what to do ([Guidance] adds nothing to them). */
        const val NO_NAME = "Can't find your computer's name. Is Tailscale on?"
        const val NOT_ON_TAILNET = "Your computer's name didn't lead into your tailnet, so nothing was sent. Is Tailscale on?"

        /**
         * Whether [address] is inside a tailnet: Tailscale's 100.64.0.0/10 or fd7a:115c:a1e0::/48,
         * or this device itself (loopback, for tests and `adb reverse`), where nothing leaves it.
         */
        fun insideTailnet(address: InetAddress): Boolean {
            val b = address.address
            return address.isLoopbackAddress ||
                (address is Inet4Address && b[0] == 100.toByte() && (b[1].toInt() and 0xC0) == 64) ||
                (address is Inet6Address && b[0] == 0xfd.toByte() && b[1] == 0x7a.toByte() && b[2] == 0x11.toByte() &&
                    b[3] == 0x5c.toByte() && b[4] == 0xa1.toByte() && b[5] == 0xe0.toByte())
        }

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
