package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * `rewrite` with Ferb's own prompt ([LinkContract.EXTRA_INSTRUCTIONS]): the instructions as the
 * system message and the request, verbatim, as the user message. Greedy, no thinking, no pre-pass
 * (no Amounts), so Gemma gets exactly what Gemini Nano gets on the phone.
 */
object Instructed {
    const val NONE = "NONE"

    fun body(model: String, instructions: String, request: String, maxTokens: Int): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", instructions))
            .put(JSONObject().put("role", "user").put("content", request)))
        .put("temperature", 0)
        .put("top_k", 1)
        .put("max_tokens", maxTokens)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .toString()

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

    /** [LinkContract.EXTRA_MAX_TOKENS], clamped; [LinkContract.REWRITE_MAX_TOKENS] without it. */
    fun maxTokens(extras: Map<String, Any?>): Int =
        ((extras[LinkContract.EXTRA_MAX_TOKENS] as? Number)?.toInt() ?: LinkContract.REWRITE_MAX_TOKENS)
            .coerceIn(1, LinkContract.MAX_REWRITE_TOKENS)
}
