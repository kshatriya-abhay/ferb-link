package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

// What Ferb gets back from Ferb Link's methods, and how it's written on the wire: the replies' JSON
// (the `reply` string in each result Bundle) and the error kinds. LinkContract holds the keys and
// versions; contract/v1 pins these shapes.

/** One call the model asked for: a tool name and its arguments. */
data class Call(
    val name: String,
    val arguments: JSONObject,
) {
    /** `name(key = value, …)` with strings quoted, for display. */
    fun signature(): String = arguments.keys().asSequence().joinToString(prefix = "$name(", postfix = ")") { key ->
        val value = arguments.get(key)
        "$key = " + if (value is String) "\"$value\"" else value.toString()
    }
}

/**
 * The laptop's answer to one request. Other models have no confidence score like Needle 3's, so
 * [confidence] is the probability of the least likely token the model wrote (its weakest choice),
 * [first] that of its first token (whether to call a tool at all), [mean] their mean: the same
 * stand-ins Ferb's bench scores (`tool-check/bench.py`).
 */
data class Reply(
    val calls: List<Call>,
    val text: String,
    val confidence: Double,
    val first: Double,
    val mean: Double,
    val model: String,
    val ms: Long,
    /** Set when there's no answer. */
    val failure: Failure?,
) {
    /** What went wrong, in a few lowercase words ("timed out"); null when there's an answer. */
    val error: String? get() = failure?.short

    /** What went wrong, in a sentence. */
    val errorDetail: String? get() = failure?.message

    /** Shaped like Needle 3's reply, so Ferb reads it with the same parser. */
    fun toJson(): String = JSONObject()
        .put("success", error == null)
        .put("function_calls", JSONArray(calls.map { call ->
            JSONObject().put("name", call.name).put("arguments", call.arguments)
        }))
        .put("confidence", confidence)
        .put("first", first)
        .put("mean", mean)
        .put("text", text)
        .put("model", model)
        .put("ms", ms)
        .put("source", "laptop")
        .apply { if (failure != null) put("error", failure.short).put("error_detail", failure.message).put("error_kind", failure.kind.wire) }
        .toString()

    companion object {
        fun failed(kind: ErrorKind, message: String, ms: Long = 0, short: String = kind.short) =
            Reply(emptyList(), "", 0.0, 0.0, 0.0, "", ms, Failure(kind, message, short))

        fun failed(failure: Failure, ms: Long = 0) = Reply(emptyList(), "", 0.0, 0.0, 0.0, "", ms, failure)
    }
}

/**
 * Why there's no answer, as LinkContract's `error_kind` values, so Ferb can word its own notice.
 * [short] is the reply's `error` unless a failure gives its own: lowercase, no full stop, one line
 * in Ferb's chat ("📱 timed out").
 */
enum class ErrorKind(val wire: String, val short: String) {
    Off("off", "turned off"),
    NotSetUp("not_set_up", "not set up"),
    /** No request, or the tools aren't a JSON array. */
    BadRequest("bad_request", "bad request"),
    /** No connection: an unknown name, refused, or plain HTTP to a non-Tailscale name. */
    Unreachable("unreachable", "can't reach your computer"),
    /** Connected, but no answer in time. */
    Timeout("timeout", "timed out"),
    /** The server turned down the API key: fixable in Ferb Link. */
    Auth("auth", "key refused"),
    /** Any other answer than 200 (503 while the model loads). */
    Server("server", "server error"),
    /** An answer that isn't the expected JSON. */
    BadReply("bad_reply", "unexpected answer"),
}

/** [message] is a sentence for details (the reply's `error_detail`); [short] goes in `error`. */
data class Failure(val kind: ErrorKind, val message: String, val short: String = kind.short)

/** [ServerClient.rewrite]'s answer: the commands (empty: no action asked), what was [sent], and the model's [raw] text. */
data class Rewritten(
    val commands: List<String>,
    val sent: String,
    val raw: String,
    val model: String,
    val ms: Long,
    val failure: Failure?,
    /** Facts in [commands] the request didn't hold ([Rewrite.invented]): ferb-manager's hard rule, counted apart on the bench. */
    val invented: List<String> = emptyList(),
    /** What the request asked for, which the reply echoes, failed or not. */
    val asked: RewriteAsked = RewriteAsked.LEGACY,
    /** Instructed only: the model stopped at the token cap (finish_reason "length"). */
    val truncated: Boolean = false,
    /** A JSON answer ([RewriteAsked.schema]), parsed. */
    val output: JSONObject? = null,
) {
    fun toJson(): String = json().toString()

    private fun json(): JSONObject = JSONObject()
        .put("success", failure == null)
        .put("commands", JSONArray(commands))
        .put("invented", JSONArray(invented))
        .put("sent", sent)
        .put("raw", raw)
        .put("model", model)
        .put("ms", ms)
        .apply {
            failure?.let {
                put("error", it.short)
                put("error_detail", it.message)
                put("error_kind", it.kind.wire)
            }
        }

    /**
     * What `rewrite` gives Ferb: the commands and `invented`, without the harness's `sent`. `raw`
     * only for an instructed rewrite, which Ferb parses itself, with `"instructions": true` and
     * `truncated`; with a schema, also `"schema": true` and `output`.
     */
    fun toReplyJson(): String = json().apply {
        remove("sent")
        if (asked.instructions) put("instructions", true).put("truncated", truncated) else remove("raw")
        if (asked.schema) {
            put("schema", true)
            output?.let { put("output", it) }
        }
        put("source", "laptop")
    }.toString()

    companion object {
        fun failed(failure: Failure, sent: String, asked: RewriteAsked = RewriteAsked.LEGACY, ms: Long = 0, raw: String = "", truncated: Boolean = false) =
            Rewritten(emptyList(), sent, raw, "", ms, failure, asked = asked, truncated = truncated)
    }
}

/**
 * Which extras a `rewrite` request gave, as its reply echoes them (`instructions`, `schema`): Ferb's
 * own prompt or not, and a JSON schema or not. A schema without instructions is a bad request, but
 * its reply still says a schema was asked for.
 */
data class RewriteAsked(val instructions: Boolean, val schema: Boolean) {
    companion object {
        val LEGACY = RewriteAsked(instructions = false, schema = false)
    }
}

/**
 * A spoken request written down: [heard] is what was said (Hindi in Latin letters), [text] the
 * same request as a plain English command (the same as [heard] when it was said in English).
 */
data class Transcript(val heard: String, val text: String, val model: String, val ms: Long, val failure: Failure?) {
    val error: String? get() = failure?.short
    val errorDetail: String? get() = failure?.message

    fun toJson(): String = JSONObject()
        .put("success", error == null)
        .put("heard", heard)
        .put("text", text)
        .put("model", model)
        .put("ms", ms)
        .put("source", "laptop")
        .apply { if (failure != null) put("error", failure.short).put("error_detail", failure.message).put("error_kind", failure.kind.wire) }
        .toString()

    companion object {
        fun failed(kind: ErrorKind, message: String, ms: Long = 0, short: String = kind.short) =
            Transcript("", "", "", ms, Failure(kind, message, short))

        fun failed(failure: Failure, ms: Long = 0) = Transcript("", "", "", ms, failure)
    }
}
