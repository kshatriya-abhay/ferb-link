package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * B's call as JSON the server enforces (harness `schema-call`, bench only): Gemma's native tool
 * calls get no grammar in llama-server, so a nested time parameter came back as free strings
 * ("day": "kal"). Here the whole answer is a json_schema: {"calls": [{"tool", "arguments",
 * "when"}...]}, one shape per routed tool, empty for no tool. With [rewrite], it may give plain
 * English "commands" instead of calls. The tools' own arguments stay (Ferb's checks read them);
 * "when" ([TimeFormat]) is beside them, for Ferb's resolver.
 */
object SchemaCall {
    fun prompt(tools: JSONArray, rewrite: Boolean) = buildString {
        append("You are Ferb, an assistant on an Android phone. Reply with JSON only: {\"calls\": [...]")
        if (rewrite) append(", \"commands\": [...]")
        append("}.\n")
        append("- \"calls\": one call for each thing the user asks the phone to do, in order: {\"tool\": its name, \"arguments\": its arguments in the user's words, \"when\": its time}. ")
        append("An empty list when no tool fits, or when the user isn't asking for an action (a question about the world, chat, a statement about something, the past or themselves).\n")
        if (rewrite) {
            append("- \"commands\": when the request is Hinglish, vague or needs more than the tools' words, leave \"calls\" empty and instead write each thing asked as a plain English command (\"Set an alarm for 7:15 am tomorrow\"); otherwise [].\n")
        }
        append("Never add a name, place, number, time or date the request doesn't have.\n")
        append(TimeFormat.PROMPT)
        append("\n\nThe tools:\n")
        for (i in 0 until tools.length()) append(tools.getJSONObject(i).toString()).append('\n')
    }

    /** The answer's schema: each call's tool name, that tool's arguments schema, and a "when". */
    fun format(tools: JSONArray, rewrite: Boolean): String {
        val calls = (0 until tools.length()).joinToString(", ") { i ->
            val tool = tools.getJSONObject(i)
            val parameters = tool.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            """{"type": "object", "properties": {"tool": {"enum": [${JSONObject.quote(tool.getString("name"))}]}, "arguments": $parameters, "when": ${TimeFormat.SCHEMA}}, "required": ["tool", "arguments", "when"]}"""
        }
        val items = if (tools.length() == 0) """{"type": "object"}""" else """{"anyOf": [$calls]}"""
        val properties = """"calls": {"type": "array", "items": $items}""" + if (rewrite) """, "commands": {"type": "array", "items": {"type": "string"}}""" else ""
        val required = if (rewrite) """["calls", "commands"]""" else """["calls"]"""
        return """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {$properties}, "required": $required}}}"""
    }

    fun body(model: String, request: String, now: String, tools: JSONArray, rewrite: Boolean): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt(tools, rewrite) + "\nNow: $now."))
            .put(JSONObject().put("role", "user").put("content", Amounts.forRequest(request))))
        .put("temperature", 0)
        .put("max_tokens", 600)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .put("response_format", "@format@")
        .toString()
        .replace("\"@format@\"", format(tools, rewrite))

    /** The answer as the harness writes it: calls with each "when" versioned, and commands. */
    fun reply(content: String): JSONObject {
        val answer = JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1))
        val calls = answer.getJSONArray("calls")
        val out = JSONArray((0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            JSONObject().put("tool", call.getString("tool")).put("arguments", call.optJSONObject("arguments") ?: JSONObject())
                .put("when", TimeFormat.versioned(call.optJSONObject("when")))
        })
        return JSONObject().put("calls", out).put("commands", answer.optJSONArray("commands") ?: JSONArray())
    }
}
