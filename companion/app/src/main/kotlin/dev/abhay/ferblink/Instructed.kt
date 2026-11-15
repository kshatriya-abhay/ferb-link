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

    /** How Ferb's prompt is answered: one command on the first line, or JSON in a schema. */
    sealed interface Mode {
        val instructions: String
        val maxTokens: Int
        val asked: RewriteAsked

        data class Lines(override val instructions: String, override val maxTokens: Int) : Mode {
            override val asked get() = RewriteAsked(instructions = true, schema = false)
        }

        data class Json(override val instructions: String, override val maxTokens: Int, val schema: JSONObject) : Mode {
            override val asked get() = RewriteAsked(instructions = true, schema = true)
        }
    }

    /**
     * What a `rewrite` call's extras ask for: [asked], echoed in every reply; and either [mode], or
     * [failure], a bad request, before anything is sent.
     */
    data class Ask(val asked: RewriteAsked, val mode: Mode?, val failure: Failure?) {
        companion object {
            fun from(extras: Map<String, Any?>): Ask {
                val instructions = (extras[LinkContract.EXTRA_INSTRUCTIONS] as? String)?.takeIf { it.isNotBlank() }
                val schemaText = (extras[LinkContract.EXTRA_JSON_SCHEMA] as? String)?.takeIf { it.isNotBlank() }
                val asked = RewriteAsked(instructions = instructions != null, schema = schemaText != null)
                fun bad(message: String, short: String) = Ask(asked, null, Failure(ErrorKind.BadRequest, message, short))
                return when {
                    instructions != null && instructions.length > LinkContract.MAX_INSTRUCTIONS_CHARS ->
                        bad("Instructions over ${LinkContract.MAX_INSTRUCTIONS_CHARS} characters", "bad request: instructions too long")
                    schemaText != null && instructions == null -> bad("A JSON schema needs instructions", "bad request: schema without instructions")
                    schemaText != null && schemaText.length > LinkContract.MAX_JSON_SCHEMA_CHARS ->
                        bad("JSON schema over ${LinkContract.MAX_JSON_SCHEMA_CHARS} characters", "bad request: schema")
                    instructions == null -> bad("A rewrite needs instructions (Ferb's own prompt)", "bad request: no instructions")
                    schemaText == null -> Ask(asked, Mode.Lines(instructions, maxTokens(extras)), null)
                    else -> runCatching { JSONObject(schemaText) }.getOrNull()?.let { Ask(asked, Mode.Json(instructions, maxTokens(extras), it), null) }
                        ?: bad("The JSON schema isn't a JSON object", "bad request: schema")
                }
            }
        }
    }

    fun body(model: String, request: String, mode: Mode): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", mode.instructions))
            .put(JSONObject().put("role", "user").put("content", request)))
        .put("temperature", 0)
        .put("top_k", 1)
        .put("max_tokens", mode.maxTokens)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .apply {
            if (mode is Mode.Json) put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "answer").put("schema", mode.schema)))
        }
        .toString()

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
