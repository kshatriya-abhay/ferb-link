package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import dev.abhay.ferblink.LlamaHttp.Companion.msSince
import dev.abhay.ferblink.LlamaHttp.Companion.problem
import kotlin.math.exp

/**
 * Whether the server answers, and with which model; [failure] says why not. [state] and [audio]
 * are null when the server didn't say.
 */
data class Health(
    val model: String?,
    val ms: Long,
    val failure: Failure?,
    val state: ModelState? = null,
    val audio: Boolean? = null,
    /** The server's `--sleep-idle-seconds` (-1: never), when the router listed it. */
    val sleepIdleSeconds: Int? = null,
) {
    val ok: Boolean get() = failure == null
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
data class Props(val state: ModelState, val audio: Boolean?, val sleepIdleSeconds: Int? = null, val model: String? = null)

/**
 * llama.cpp's server on the laptop, through its OpenAI-style API, asked the way Ferb's bench asks
 * it (`tool-check/bench.py`), so the bench's results hold: the same system prompt, the tools as
 * functions, temperature 0, thinking off. Blocking: call it off the main thread.
 */
class ServerClient(
    baseUrl: String,
    apiKey: String,
    /** The model to ask for (the router needs it named); looked up in `/v1/models` when empty. */
    private var model: String = "",
    connectTimeoutMs: Int = 3_000,
    readTimeoutMs: Int = LinkContract.DEFAULT_TIMEOUT_MS,
    /** Gets one line of timings per HTTP request (Ferb Link logs them). */
    trace: ((String) -> Unit)? = null,
) {
    private val http = LlamaHttp(baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, trace)

    /** `/health`, then `/v1/models` with the key, both within [timeoutMs] each (default: the client's). */
    fun health(timeoutMs: Int? = null): Health {
        val started = System.nanoTime()
        val failed = { failure: Failure, _: Long -> Health(null, msSince(started), failure) }
        val up = http.exchange("/health", timeoutMs, auth = false)
        if (up is LlamaHttp.Exchange.Failed) return failed(up.failure, up.ms)
        return read(http.exchange("/v1/models", timeoutMs), failed) { models ->
            val model = JSONObject(models.text).optJSONArray("data")?.optJSONObject(0)?.optString("id")
            if (!model.isNullOrEmpty()) this.model = model
            val props = props(timeoutMs)
            Health(model, msSince(started), null, props?.state, props?.audio, props?.sleepIdleSeconds)
        }
    }

    /**
     * Asks the model for tool calls for [request], given [toolsJson] (a JSON array of schemas).
     * [timeoutMs] replaces the client's answer timeout for this call (and caps its connect timeout).
     */
    fun complete(request: String, toolsJson: String, facts: String? = null, timeoutMs: Int? = null): Reply {
        val tools = try {
            JSONArray(toolsJson)
        } catch (e: Exception) {
            return Reply.failed(ErrorKind.BadRequest, "The tools aren't a JSON array: ${e.message}", short = "bad request: tools")
        }
        val exchange = http.exchange(CHAT, timeoutMs) { Prompts.requestBody(modelName(it.left()), request, tools, facts).toString() }
        return read(exchange, { failure, ms -> Reply.failed(failure, ms) }) { parse(JSONObject(it.text), it.ms) }
    }

    /**
     * Where the model is and whether it takes audio, without loading or waking it: the router's
     * `/v1/models` status, then, when it's running, its `/props`. Null when they can't be read.
     */
    fun props(timeoutMs: Int? = null): Props? = try {
        val models = http.exchange("/v1/models", timeoutMs) as? LlamaHttp.Exchange.Answer
        val entry = models?.let { JSONObject(it.text).optJSONArray("data")?.optJSONObject(0) }
        val sleep = entry?.optJSONObject("status")?.optJSONArray("args")?.let(::sleepIdleSeconds)
        when (entry?.optJSONObject("status")?.optString("value")) {
            null -> null
            "unloaded" -> Props(ModelState.Unloaded, null, sleep, entry.optString("id").ifEmpty { null })
            "loading" -> Props(ModelState.Loading, null, sleep, entry.optString("id").ifEmpty { null })
            else -> {
                val name = entry.getString("id").also { model = it }
                val answer = http.exchange("/props?model=${URLEncoder.encode(name, "UTF-8")}&autoload=false", timeoutMs) as? LlamaHttp.Exchange.Answer
                answer?.let { JSONObject(it.text) }?.let {
                    Props(
                        if (it.optBoolean("is_sleeping")) ModelState.Asleep else ModelState.Loaded,
                        it.optJSONObject("modalities")?.optBoolean("audio") == true,
                        sleep,
                        name,
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
    fun unload(timeoutMs: Int? = null): Failure? {
        val exchange = http.exchange("/models/unload", timeoutMs, problemOf = { code, text ->
            if (code == 400 && text.contains("not running")) NOT_RUNNING else problem(code, text)
        }) { JSONObject().put("model", modelName(it.left())).toString() }
        if (exchange is LlamaHttp.Exchange.Failed) return exchange.failure.takeUnless { it === NOT_RUNNING }
        // The router answers before the model's process has exited (about 2 s): wait for it, so a
        // check straight after shows it unloaded.
        repeat(20) {
            if (props(LinkContract.CHECK_TIMEOUT_MS)?.state == ModelState.Unloaded) return null
            Thread.sleep(250)
        }
        return null
    }

    /**
     * Loads the model if it's asleep, with a one-token request, so the next request doesn't wait
     * for it. Returns what `/props` said before, or null when it couldn't be read.
     */
    fun wake(timeoutMs: Int? = null): Props? {
        val props = props(LinkContract.CHECK_TIMEOUT_MS) ?: return null
        if (props.state != ModelState.Loaded) http.exchange(CHAT, timeoutMs) { Prompts.wakeBody(modelName(it.left())) }
        return props
    }

    /** Writes down the request in [wav] (a WAV file), within [timeoutMs] (default: the client's). */
    fun transcribe(wav: ByteArray, timeoutMs: Int? = null): Transcript = transcribe({ Prompts.transcribeBody(it, wav) }, timeoutMs)

    /** [transcribe] with the request [body] given the model's name (the bench types heard lines in place of audio). */
    internal fun transcribe(body: (model: String) -> String, timeoutMs: Int?): Transcript =
        read(http.exchange(CHAT, timeoutMs) { body(modelName(it.left())) }, { failure, ms -> Transcript.failed(failure, ms) }) { parseTranscript(JSONObject(it.text), it.ms) }

    /**
     * [request] as one command (or none) with the caller's own prompt: [Instructed]. In [Instructed.Mode.Json]
     * the answer is JSON in its schema, given back parsed as `output`.
     */
    fun rewriteInstructed(request: String, mode: Instructed.Mode, timeoutMs: Int? = null): Rewritten {
        val asked = mode.asked
        var raw = ""
        var truncated = false
        val problemOf = if (mode is Instructed.Mode.Json) LlamaHttp.Companion::schemaProblem else LlamaHttp.Companion::problem
        val exchange = http.exchange(CHAT, timeoutMs, problemOf = problemOf) { Instructed.body(modelName(it.left()), request, mode) }
        return read(exchange, { failure, ms -> Rewritten.failed(failure, request, asked, ms, raw, truncated) }, "the command") { answer ->
            val ms = answer.ms
            val reply = JSONObject(answer.text)
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
        }
    }

    /**
     * [parse] the answer of [exchange], or [failed] with its failure, or with bad_reply when the
     * answer can't be read ("Couldn't read [what]: …").
     */
    private inline fun <T> read(exchange: LlamaHttp.Exchange, failed: (Failure, Long) -> T, what: String = "your computer's answer", parse: (LlamaHttp.Exchange.Answer) -> T): T =
        when (exchange) {
            is LlamaHttp.Exchange.Failed -> failed(exchange.failure, exchange.ms)
            is LlamaHttp.Exchange.Answer -> try {
                parse(exchange)
            } catch (e: Exception) {
                failed(Failure(ErrorKind.BadReply, "Couldn't read $what: ${e.message}"), exchange.ms)
            }
        }

    /** The model to name in requests: the one given, or the first in `/v1/models`. */
    private fun modelName(timeoutMs: Int?): String {
        if (model.isEmpty()) {
            when (val models = http.exchange("/v1/models", timeoutMs)) {
                is LlamaHttp.Exchange.Failed -> throw LlamaHttp.Companion.Refused(models.failure)
                is LlamaHttp.Exchange.Answer -> model = JSONObject(models.text).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
            }
        }
        return model
    }

    companion object {
        private const val CHAT = "/v1/chat/completions"

        /** unload's "not running" (HTTP 400): nothing to unload, so not a failure. */
        private val NOT_RUNNING = Failure(ErrorKind.Server, "The model isn't running")

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
                tokens!!.getJSONObject(i).optDouble("logprob").takeUnless { it.isNaN() }?.let { exp(it.coerceAtMost(0.0)) }
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
    }
}
