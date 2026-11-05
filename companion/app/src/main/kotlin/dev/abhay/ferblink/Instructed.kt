package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * `rewrite` with Ferb's own prompt ([LinkContract.EXTRA_INSTRUCTIONS]): the instructions as the
 * system message and the request, verbatim, as the user message. Greedy, no thinking, no pre-pass
 * (no Amounts), so Gemma gets exactly what Gemini Nano gets on the phone. With a schema
 * ([LinkContract.EXTRA_JSON_SCHEMA]) the answer is JSON in it, through llama-server's response_format.
 */
object Instructed {
    const val NONE = "NONE"

    fun body(model: String, instructions: String, request: String, maxTokens: Int, schema: JSONObject? = null): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions))
            .put(JSONObject().put("role", "user").put("content", request)))
        .put("temperature", 0)
        .put("top_k", 1)
        .put("max_tokens", maxTokens)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .apply {
            if (schema != null) put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "answer").put("schema", schema)))
        }
        .toString()

    /** [LinkContract.EXTRA_JSON_SCHEMA] as a JSON object, or null when absent or blank; throws when it isn't one. */
    fun schema(extras: Map<String, Any?>): JSONObject? {
        val text = (extras[LinkContract.EXTRA_JSON_SCHEMA] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalArgumentException("not a JSON object")
    }

    /** A schema-shaped answer: the whole of [raw] as one JSON object. */
    fun parseJson(raw: String): JSONObject {
        val text = raw.trim()
        require(text.startsWith("{") && text.endsWith("}")) { "not a JSON object" }
        return JSONObject(text)
    }

    /**
     * The one command in [raw], or null for NONE: Ferb's GeminiRewrite.parse, rule for rule. The
     * first non-blank line, after the first "->", without "Command:" or quotes; NONE in any case,
     * with or without a full stop. Ferb parses `raw` itself too, so this only fills `commands`.
     */
    fun parse(raw: String): String? {
        val line = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            .substringAfter("->").removePrefix("Command:").trim().trim('"', '“', '”', '`').trim()
        require(line.isNotEmpty()) { "the model wrote nothing" }
        return if (line.trimEnd('.').equals(NONE, ignoreCase = true)) null else line
    }

    /**
     * Whether the answer's command may be cut short: the model stopped at its token cap
     * ([finishReason] "length") before ending the first line. A later line cut short doesn't
     * matter ([parse] reads only the first), and NONE is whole however it ended.
     */
    fun cutOff(raw: String, finishReason: String?): Boolean {
        if (finishReason != "length" || raw.trim().contains('\n')) return false
        // NONE (null) is whole; an empty answer is parse's own error.
        return runCatching { parse(raw) }.getOrNull() != null
    }

    /**
     * [LinkContract.EXTRA_MAX_TOKENS], clamped; without it [LinkContract.REWRITE_MAX_TOKENS], or
     * [LinkContract.REWRITE_JSON_MAX_TOKENS] with a schema.
     */
    fun maxTokens(extras: Map<String, Any?>): Int {
        val default = if ((extras[LinkContract.EXTRA_JSON_SCHEMA] as? String).isNullOrBlank()) LinkContract.REWRITE_MAX_TOKENS
            else LinkContract.REWRITE_JSON_MAX_TOKENS
        return ((extras[LinkContract.EXTRA_MAX_TOKENS] as? Number)?.toInt() ?: default).coerceIn(1, LinkContract.MAX_REWRITE_TOKENS)
    }
}
