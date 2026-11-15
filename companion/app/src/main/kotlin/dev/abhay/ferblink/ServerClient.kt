package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.URLEncoder
import java.net.UnknownServiceException
import java.util.Base64
import kotlin.math.exp

/**
 * Whether the server answers, and with which model; [failure] says why not. [state] and [audio]
 * are null when the server didn't say.
 */
data class Health(
    val ok: Boolean,
    val model: String?,
    val ms: Long,
    val failure: Failure?,
    val state: ModelState? = null,
    val audio: Boolean? = null,
    /** The server's `--sleep-idle-seconds` (-1: never), when the router listed it. */
    val sleepIdleSeconds: Int? = null,
) {
    val message: String get() = failure?.message ?: "Connected"
    val short: String get() = failure?.short ?: "connected"
}

/** Where the model is: in memory, asleep (llama-server's idle sleep), or not running at all. */
enum class ModelState(val words: String) {
    Loaded("loaded"),
    Loading("loading"),
    Asleep("asleep"),
    Unloaded("unloaded"),
}

/**
 * What the server says about its model without loading it. [audio] is null when it can't tell
 * (unloaded); [sleepIdleSeconds] when the router lists the model's command line.
 */
data class Props(val state: ModelState, val audio: Boolean?, val sleepIdleSeconds: Int? = null)

/**
 * llama.cpp's server on the laptop, through its OpenAI-style API, asked the way Ferb's bench asks
 * it (`tool-check/bench.py`), so the bench's results hold: the same system prompt, the tools as
 * functions, temperature 0, thinking off. Blocking: call it off the main thread.
 */
class ServerClient(
    baseUrl: String,
    private val apiKey: String,
    /** The model to ask for (the router needs it named); looked up in `/v1/models` when empty. */
    private var model: String = "",
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 30_000,
    /** Gets one line of timings per HTTP request (Ferb Link logs them). */
    private val trace: ((String) -> Unit)? = null,
) {
    private val base = baseUrl.trim().trimEnd('/')

    /** `/health`, then `/v1/models` with the key, both within [timeoutMs] each (default: the client's). */
    fun health(timeoutMs: Int? = null): Health {
        val started = System.nanoTime()
        return try {
            val (code, body) = send("GET", "/health", null, auth = false, timeoutMs)
            if (code == 503) return Health(false, null, msSince(started), Failure(ErrorKind.Server, "Your computer is loading the model", "model loading"))
            if (code != 200) return Health(false, null, msSince(started), problem(code, body))
            val (modelsCode, models) = send("GET", "/v1/models", null, auth = true, timeoutMs)
            if (modelsCode != 200) return Health(false, null, msSince(started), problem(modelsCode, models))
            val model = JSONObject(models).optJSONArray("data")?.optJSONObject(0)?.optString("id")
            if (!model.isNullOrEmpty()) this.model = model
            val props = props(timeoutMs)
            Health(true, model, msSince(started), null, props?.state, props?.audio, props?.sleepIdleSeconds)
        } catch (e: IOException) {
            Health(false, null, msSince(started), failureOf(e))
        } catch (e: Exception) {
            Health(false, null, msSince(started), Failure(ErrorKind.BadReply, "Couldn't read your computer's answer: ${e.message}"))
        }
    }

    /**
     * Asks the model for tool calls for [request], given [toolsJson] (a JSON array of schemas).
     * [timeoutMs] replaces the client's answer timeout for this call (and caps its connect timeout).
     */
    fun complete(request: String, toolsJson: String, facts: String? = null, timeoutMs: Int? = null): Reply {
        val started = System.nanoTime()
        val tools = try {
            JSONArray(toolsJson)
        } catch (e: Exception) {
            return Reply.failed(ErrorKind.BadRequest, "The tools aren't a JSON array: ${e.message}", short = "bad request: tools")
        }
        val body = try {
            requestBody(modelName(timeoutMs), request, tools, facts)
        } catch (e: IOException) {
            return failureOf(e).let { Reply.failed(it, msSince(started)) }
        }
        return try {
            val (code, text) = send("POST", "/v1/chat/completions", body.toString(), auth = true, timeoutMs)
            val ms = msSince(started)
            if (code != 200) problem(code, text).let { Reply.failed(it, ms) } else parse(JSONObject(text), ms)
        } catch (e: IOException) {
            failureOf(e).let { Reply.failed(it, msSince(started)) }
        } catch (e: Exception) {
            Reply.failed(ErrorKind.BadReply, "Couldn't read your computer's answer: ${e.message}", msSince(started))
        }
    }

    /**
     * Where the model is and whether it takes audio, without loading or waking it: the router's
     * `/v1/models` status, then, when it's running, its `/props`. Null when they can't be read.
     */
    fun props(timeoutMs: Int? = null): Props? = try {
        val (code, body) = send("GET", "/v1/models", null, auth = true, timeoutMs)
        val entry = if (code == 200) JSONObject(body).optJSONArray("data")?.optJSONObject(0) else null
        val sleep = entry?.optJSONObject("status")?.optJSONArray("args")?.let(::sleepIdleSeconds)
        when (entry?.optJSONObject("status")?.optString("value")) {
            null -> null
            "unloaded" -> Props(ModelState.Unloaded, null, sleep)
            "loading" -> Props(ModelState.Loading, null, sleep)
            else -> {
                val name = entry.getString("id").also { model = it }
                val (propsCode, propsBody) = send("GET", "/props?model=${URLEncoder.encode(name, "UTF-8")}&autoload=false", null, auth = true, timeoutMs)
                if (propsCode != 200) null else JSONObject(propsBody).let {
                    Props(
                        if (it.optBoolean("is_sleeping")) ModelState.Asleep else ModelState.Loaded,
                        it.optJSONObject("modalities")?.optBoolean("audio") == true,
                        sleep,
                    )
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Stops the model's process on the laptop, freeing its GPU memory and RAM; the next request
     * starts it again (a few seconds, or longer from disk). Null when it worked or wasn't running.
     */
    fun unload(timeoutMs: Int? = null): Failure? = try {
        val body = JSONObject().put("model", modelName(timeoutMs)).toString()
        val (code, text) = send("POST", "/models/unload", body, auth = true, timeoutMs)
        val notRunning = code == 400 && text.contains("not running")
        when {
            notRunning -> null
            code != 200 -> problem(code, text)
            else -> {
                // The router answers before the model's process has exited (about 2 s): wait for
                // it, so a check straight after shows it unloaded.
                repeat(20) {
                    if (props(LinkContract.CHECK_TIMEOUT_MS)?.state == ModelState.Unloaded) return null
                    Thread.sleep(250)
                }
                null
            }
        }
    } catch (e: IOException) {
        failureOf(e)
    } catch (e: Exception) {
        Failure(ErrorKind.BadReply, "Couldn't read your computer's answer: ${e.message}")
    }

    /**
     * Loads the model if it's asleep, with a one-token request, so the next request doesn't wait
     * for it. Returns what `/props` said before, or null when it couldn't be read.
     */
    fun wake(timeoutMs: Int? = null): Props? {
        val props = props(LinkContract.CHECK_TIMEOUT_MS) ?: return null
        if (props.state != ModelState.Loaded) runCatching {
            send("POST", "/v1/chat/completions", wakeBody(modelName(timeoutMs)), auth = true, timeoutMs)
        }
        return props
    }

    /** Writes down the request in [wav] (a WAV file), within [timeoutMs] (default: the client's). */
    fun transcribe(wav: ByteArray, timeoutMs: Int? = null): Transcript = transcribe({ transcribeBody(it, wav) }, timeoutMs)

    /** [transcribe] with the request [body] given the model's name (the bench types heard lines in place of audio). */
    internal fun transcribe(body: (model: String) -> String, timeoutMs: Int?): Transcript {
        val started = System.nanoTime()
        return try {
            val (code, text) = send("POST", "/v1/chat/completions", body(modelName(timeoutMs)), auth = true, timeoutMs)
            val ms = msSince(started)
            if (code != 200) problem(code, text).let { Transcript.failed(it, ms) } else parseTranscript(JSONObject(text), ms)
        } catch (e: IOException) {
            failureOf(e).let { Transcript.failed(it, msSince(started)) }
        } catch (e: Exception) {
            Transcript.failed(ErrorKind.BadReply, "Couldn't read your computer's answer: ${e.message}", msSince(started))
        }
    }

    /**
     * [Rewrite]: [request] as plain English commands, after [Amounts.forRequest], with [now] for the
     * prompt. [body] builds the request from the model's name and what's sent (the bench's variants pass their own).
     */
    fun rewrite(
        request: String, now: String, timeoutMs: Int? = null,
        body: (model: String, sent: String) -> String = { model, sent -> Rewrite.body(model, sent, now) },
    ): Rewritten {
        val started = System.nanoTime()
        val sent = Amounts.forRequest(request)
        if (request.isBlank()) return Rewritten.failed(Failure(ErrorKind.BadRequest, "No request", "bad request: no request"), sent)
        var raw = ""
        return try {
            val (code, text) = send("POST", "/v1/chat/completions", body(modelName(timeoutMs), sent), auth = true, timeoutMs)
            val ms = msSince(started)
            if (code != 200) return Rewritten.failed(problem(code, text), sent, ms = ms)
            val reply = JSONObject(text)
            raw = reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            val commands = Rewrite.parse(raw)
            Rewritten(commands, sent, raw, reply.optString("model"), ms, null, Rewrite.invented(request, sent, commands))
        } catch (e: IOException) {
            Rewritten.failed(failureOf(e), sent, ms = msSince(started))
        } catch (e: Exception) {
            Rewritten.failed(Failure(ErrorKind.BadReply, "Couldn't read the commands: ${e.message}"), sent, ms = msSince(started), raw = raw)
        }
    }

    /**
     * [request] as one command (or none) with the caller's own prompt: [Instructed]. In [Instructed.Mode.Json]
     * the answer is JSON in its schema, given back parsed as `output`.
     */
    fun rewriteInstructed(request: String, mode: Instructed.Mode, timeoutMs: Int? = null): Rewritten {
        val started = System.nanoTime()
        val asked = mode.asked
        if (request.isBlank()) return Rewritten.failed(Failure(ErrorKind.BadRequest, "No request", "bad request: no request"), request, asked)
        var raw = ""
        var truncated = false
        return try {
            val (code, text) = send("POST", "/v1/chat/completions", Instructed.body(modelName(timeoutMs), request, mode), auth = true, timeoutMs)
            val ms = msSince(started)
            if (code != 200) return Rewritten.failed(if (mode is Instructed.Mode.Json) schemaProblem(code, text) else problem(code, text), request, asked, ms)
            val reply = JSONObject(text)
            val choice = reply.getJSONArray("choices").getJSONObject(0)
            raw = choice.getJSONObject("message").optString("content")
            truncated = choice.optString("finish_reason") == "length"
            when (mode) {
                is Instructed.Mode.Json -> {
                    // Cut JSON is half an answer, whatever it holds.
                    if (truncated) return Rewritten.failed(
                        Failure(ErrorKind.BadReply, "The answer was cut off at ${mode.maxTokens} tokens", "answer cut off"), request, asked, ms, raw, truncated = true)
                    val output = runCatching { Instructed.parseJson(raw) }.getOrNull() ?: return Rewritten.failed(
                        Failure(ErrorKind.BadReply, "The answer isn't a JSON object", "not JSON"), request, asked, ms, raw)
                    Rewritten(emptyList(), request, raw, reply.optString("model"), ms, null, emptyList(), asked, output = output)
                }
                is Instructed.Mode.Lines -> {
                    // A command cut off at the cap ("send bhaiya a message: main aaj") must not run.
                    if (Instructed.cutOff(raw, choice.optString("finish_reason").ifEmpty { null })) return Rewritten.failed(
                        Failure(ErrorKind.BadReply, "The command was cut off at ${mode.maxTokens} tokens", "answer cut off"), request, asked, ms, raw, truncated = true)
                    // No `invented`: Ferb's prompt writes Hindi amounts as numbers ("sava saat" → 7:15),
                    // which the check, without Amounts, would flag.
                    Rewritten(listOfNotNull(Instructed.parse(raw)), request, raw, reply.optString("model"), ms, null, emptyList(), asked, truncated)
                }
            }
        } catch (e: IOException) {
            Rewritten.failed(failureOf(e), request, asked, msSince(started))
        } catch (e: Exception) {
            Rewritten.failed(Failure(ErrorKind.BadReply, "Couldn't read the command: ${e.message}"), request, asked, msSince(started), raw, truncated)
        }
    }

    /** The model to name in requests: the one given, or the first in `/v1/models`. */
    private fun modelName(timeoutMs: Int?): String {
        if (model.isEmpty()) {
            val (code, body) = send("GET", "/v1/models", null, auth = true, timeoutMs)
            if (code != 200) throw Refused(problem(code, body))
            model = JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
        }
        return model
    }

    /**
     * One request. The connection isn't closed afterwards: reading the answer to the end returns it
     * to Android's pool, so the next request (transcribe, then complete) skips a new TCP handshake
     * over the tailnet. It's closed only when something went wrong.
     */
    private fun send(method: String, path: String, body: String?, auth: Boolean, timeoutMs: Int?): Pair<Int, String> {
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

        /** Ferb's bench's instruction, word for word (`tool-check/bench.py`), so its results hold here. */
        const val SYSTEM = "You are Ferb, an assistant on an Android phone. When the user asks for something one of the tools does, " +
            "call that tool with the arguments in the user's words. If no tool fits, or the user isn't asking for an " +
            "action, reply in a few words without calling a tool."

        /**
         * How a spoken request is written down. Hindi comes back in Latin letters for the user to
         * read, and translated, because Ferb's keywords, date reader and Needle 3 need English.
         * "speech" comes first, so the model decides whether anyone spoke before writing words: asked
         * only to leave the fields empty, it turned silence and room noise into commands ("call
         * mom"). The model also "translated" English (it dropped a "not"), so it says whether any
         * word was Hindi, and [parseTranscript] keeps what was heard for English. Tried on synthetic
         * clips, noise and silence (2026-10-12). The amounts paragraph is from the audio bench, where
         * sava, dhai and paune were misheard ("subah", "thai", "phone") or dropped: named, they're heard.
         * Its sums still slip, so [Amounts] works them out from "heard".
         */
        const val TRANSCRIBE = """You write down a voice request to a phone assistant. The recording may contain no speech at all: silence, room noise, hiss, hum, wind, breathing, rustling or a tap on the phone. Reply with JSON only, with four fields, in this order:
- "speech": true only if you clearly hear a person saying words. False for silence, noise or any other sound. Never guess words from noise.
- "hindi": true if any word said was Hindi (Hinglish counts), false if every word was English.
- "heard": the words that were said, in Latin letters. Write Hindi words the way people type Hinglish ("subah 5 baje ka alarm laga do"), never in Devanagari. Write English words in English. Write numbers as digits. Empty when "speech" is false.
- "text": the same request as a short, plain English command, for example "Set an alarm for 5 am". Translate only: keep every name, app, place, number, time and date exactly as in "heard", don't add, drop or guess anything, and don't answer the request. Keep a word you aren't sure of as it is in "heard" rather than replacing it with a word you know. Empty when "speech" is false.
Hindi amounts are common in these requests; listen for them and keep them: sava (a quarter more: sava saat = 7:15, sava ghanta = 1 hour 15 minutes), saade (a half more: saade paanch = 5:30), paune (a quarter less: paune aath = 7:45, paune do ghante = 1 hour 45 minutes), dedh (one and a half: dedh ghanta = 1 hour 30 minutes), dhai (two and a half: dhai minute = 2 minutes 30 seconds, dhai baje = 2:30). In "heard" write the amount word as said ("sava 7 baje"). In "text" write the exact time or length ("Wake me up at 7:15", "Set a timer for 2 minutes 30 seconds"). If you aren't sure which amount word was said, keep it as said in "text" too ("Set a timer for dhai minutes") rather than guessing a number.
In "text", write other amounts as numbers too: half is 50 percent, a quarter 25 percent, full or max 100 percent ("aadhi aawaz" → "Set the volume to 50 percent"). A request of one or two action words is still a command: "next", "stop", "pause", "louder" stay as said ("Play the next track", "Stop", "Pause", "Turn the volume up")."""

        /**
         * Common Hindi words in Latin letters, as a second opinion on the model's "hindi", which
         * called "zoom kholo" English. Only words that aren't also everyday English.
         */
        private val HINDI_WORDS = setOf(
            "kholo", "karo", "kar", "kardo", "laga", "lagao", "lagado", "chalao", "chalu", "bajao", "bhejo", "batao", "dikhao",
            "ka", "ki", "ke", "ko", "hai", "hain", "mein", "se", "mujhe", "mera", "meri", "mere", "kya", "kaise", "kaisa",
            "baje", "kal", "aaj", "subah", "shaam", "raat", "ghante", "ghanta", "dedh", "dhai", "abhi", "jaldi", "nahi",
            "aur", "wala", "wali", "rasta", "liye", "saath", "daal", "dalo", "bolo", "sunao", "yaad", "dilana",
        )

        /** Whether [heard] has a Hindi word in it (see [HINDI_WORDS]). */
        fun soundsHindi(heard: String) = heard.lowercase().split(Regex("[^a-z]+")).any { it in HINDI_WORDS }

        /** The value after `--sleep-idle-seconds` in a model's command line, as the router lists it. */
        private fun sleepIdleSeconds(args: JSONArray): Int? = (0 until args.length() - 1)
            .firstOrNull { args.optString(it) == "--sleep-idle-seconds" }?.let { args.optString(it + 1).toIntOrNull() }

        /** The smallest request that loads the model. */
        private fun wakeBody(model: String) = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("max_tokens", 1)
            .toString()

        /** The bench's stop strings: FunctionGemma otherwise goes on to imagine the tool's answer. */
        private val STOP = listOf("<start_function_response>", "<end_function_call>call:")

        fun requestBody(model: String, request: String, tools: JSONArray, facts: String?): JSONObject {
            val system = if (facts.isNullOrBlank()) SYSTEM else "$SYSTEM\n\n$facts"
            return JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", request)))
                .put("tools", JSONArray((0 until tools.length()).map { JSONObject().put("type", "function").put("function", tools.get(it)) }))
                .put("tool_choice", "auto")
                .put("temperature", 0)
                .put("max_tokens", 256)
                .put("logprobs", true)
                .put("stop", JSONArray(STOP))
                // Thinking models (Qwen3.5) answer straight away, as in the bench.
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }

        /**
         * The answer's shape, as a literal: the model writes the fields in this order (whether anyone
         * spoke, before any words), and the JVM's org.json (the laptop harness) doesn't keep key order.
         */
        internal const val TRANSCRIBE_FORMAT = """{"type": "json_schema", "json_schema": {"schema": {"type": "object", """ +
            """"properties": {"speech": {"type": "boolean"}, "hindi": {"type": "boolean"}, "heard": {"type": "string"}, "text": {"type": "string"}}, """ +
            """"required": ["speech", "hindi", "heard", "text"]}}}"""

        fun transcribeBody(model: String, wav: ByteArray): String {
            val audio = JSONObject().put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav")
            return JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", TRANSCRIBE))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "input_audio").put("input_audio", audio)))))
                .put("temperature", 0)
                .put("max_tokens", 300)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                // Constrained to the four fields, so the answer is always JSON.
                .put("response_format", FORMAT_SLOT)
                .toString()
                .replace("\"$FORMAT_SLOT\"", TRANSCRIBE_FORMAT)
        }

        internal const val FORMAT_SLOT = "@response_format@"


        fun parseTranscript(reply: JSONObject, ms: Long): Transcript {
            val content = reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val fields = JSONObject(content)
            if (!fields.optBoolean("speech", true)) return Transcript("", "", reply.optString("model"), ms, null)
            val heard = fields.getString("heard").trim()
            val hindi = fields.optBoolean("hindi") || soundsHindi(heard)
            val text = if (hindi) fields.getString("text").trim() else heard
            return Transcript(heard, text, reply.optString("model"), ms, null)
        }

        fun parse(reply: JSONObject, ms: Long): Reply {
            val choice = reply.getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls")?.let { array ->
                (0 until array.length()).map { i ->
                    val function = array.getJSONObject(i).getJSONObject("function")
                    Call(function.getString("name"), arguments(function.opt("arguments")))
                }
            }.orEmpty()
            val tokens = choice.optJSONObject("logprobs")?.optJSONArray("content")
            val probabilities = (0 until (tokens?.length() ?: 0)).mapNotNull { i ->
                tokens!!.getJSONObject(i).optDouble("logprob").takeUnless { it.isNaN() }?.let { exp(it) }
            }
            return Reply(
                calls = calls,
                text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty(),
                confidence = probabilities.minOrNull() ?: 0.0,
                first = probabilities.firstOrNull() ?: 0.0,
                mean = if (probabilities.isEmpty()) 0.0 else probabilities.average(),
                model = reply.optString("model"),
                ms = ms,
                failure = null,
            )
        }

        /** The arguments come as a JSON string (OpenAI's format) or, from some servers, an object. */
        private fun arguments(value: Any?): JSONObject = when (value) {
            is JSONObject -> value
            is String -> try {
                JSONObject(value.ifBlank { "{}" })
            } catch (e: Exception) {
                JSONObject().put("_unparsed", value)
            }
            else -> JSONObject()
        }

        private fun problem(code: Int, body: String): Failure {
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
        private fun schemaProblem(code: Int, body: String): Failure {
            val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            if (code !in 400..500 || message?.startsWith("JSON schema error") != true) return problem(code, body)
            return Failure(ErrorKind.BadRequest, "Your computer couldn't use the JSON schema: $message", "bad request: schema not supported")
        }

        private fun failureOf(e: IOException): Failure = when {
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
        private class Refused(val failure: Failure) : IOException(failure.message)

        /** The connection itself failed (as opposed to the answer taking too long). */
        private class NotConnected(cause: IOException) : IOException(cause)

        private fun msSince(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}
