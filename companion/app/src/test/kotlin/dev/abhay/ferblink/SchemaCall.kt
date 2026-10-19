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
    fun prompt(tools: JSONArray, rewrite: Boolean, timed: Boolean = false, version: String = "t3") = buildString {
        append("You are Ferb, an assistant on an Android phone. Reply with JSON only: {\"calls\": [...]")
        if (rewrite) append(", \"commands\": [...]")
        append("}.\n")
        append("- \"calls\": one call for each thing the user asks the phone to do, in order: {\"tool\": its name, \"arguments\": its arguments in the user's words, \"when\": its time, or null for a tool that takes none}. ")
        append("An empty list when no tool fits, or when the user isn't asking for an action (a question about the world, chat, a statement about something, the past or themselves).\n")
        if (rewrite) {
            append("- \"commands\": when the request is Hinglish, vague or needs more than the tools' words, leave \"calls\" empty and instead write each thing asked as a plain English command (\"Set an alarm for 7:15 am tomorrow\"); otherwise [].\n")
        }
        append("Never add a name, place, number, time or date the request doesn't have.\n")
        // timed (option c): "when" only in the timed tools' shapes, trimmed; otherwise every call has one.
        append(if (!timed) TimeFormat.PROMPT else if (version == "t4") TimeFormat.PROMPT_T4 else TimeFormat.PROMPT_TIMED)
        append("\n\nThe tools:\n")
        for (i in 0 until tools.length()) append(tools.getJSONObject(i).toString()).append('\n')
    }

    /** The answer's schema: each call's tool name, that tool's arguments schema, and a "when". */
    fun format(tools: JSONArray, rewrite: Boolean, timed: Boolean = false, version: String = "t3"): String {
        val calls = (0 until tools.length()).joinToString(", ") { i ->
            val tool = tools.getJSONObject(i)
            val name = tool.getString("name")
            val parameters = tool.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            val shape = if (timed) TimeFormat.forTool(name, version) else TimeFormat.SCHEMA
            // A tool without a time still ends with "when": null. Ending at "arguments" let the model
            // write the answer's closing JSON inside a string argument ("Calculator}}]}```").
            if (shape == null) """{"type": "object", "properties": {"tool": {"enum": [${JSONObject.quote(name)}]}, "arguments": $parameters, "when": {"type": "null"}}, "required": ["tool", "arguments", "when"]}"""
            else """{"type": "object", "properties": {"tool": {"enum": [${JSONObject.quote(name)}]}, "arguments": $parameters, "when": $shape}, "required": ["tool", "arguments", "when"]}"""
        }
        val items = if (tools.length() == 0) """{"type": "object"}""" else """{"anyOf": [$calls]}"""
        val properties = """"calls": {"type": "array", "items": $items}""" + if (rewrite) """, "commands": {"type": "array", "items": {"type": "string"}}""" else ""
        val required = if (rewrite) """["calls", "commands"]""" else """["calls"]"""
        return """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {$properties}, "required": $required}}}"""
    }

    fun body(model: String, request: String, now: String, tools: JSONArray, rewrite: Boolean, timed: Boolean = false, version: String = "t3"): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt(tools, rewrite, timed, version) + "\nNow: $now."))
            // timed: the words as said, no code-worked amounts or dates; the model writes the time.
            .put(JSONObject().put("role", "user").put("content", if (timed) request else Amounts.forRequest(request))))
        .put("temperature", 0)
        .put("max_tokens", 600)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .put("response_format", "@format@")
        .toString()
        .replace("\"@format@\"", format(tools, rewrite, timed, version))

    private val parso = Regex("\\b(parso|parson|parsoon)\\b", RegexOption.IGNORE_CASE)
    private val repeat = Regex("\\b(every|har|daily)\\b", RegexOption.IGNORE_CASE)

    /**
     * Ask-only checks on a call's time, after the model (ferb-manager): they never correct, only
     * make the call ask, and in the app they belong in Ferb's grounding check, not its resolver.
     * Gemma 4 E2B wrote "parso" as tomorrow every time, and dropped a reminder's repeat quietly.
     */
    fun asks(request: String, tool: String, `when`: JSONObject?): List<String> = buildList {
        val day = `when`?.optJSONObject("day")
        if (parso.containsMatchIn(request) && `when` != null && day?.optInt("relative", -1) != 2) add("parso is not the day after tomorrow")
        if (repeat.containsMatchIn(request) && tool == "add_reminder" && `when`?.optString("kind") != "every") add("a repeat was asked for but not given")
        if (`when` != null) amountDisagrees(request, `when`)?.let { add(it) }
    }

    private val hindiCount = Regex("\\b(${Amounts.numbers.keys.joinToString("|")})\\s+(minute|minut|mint|min|ghante|ghanta|second|sekand)\\b", RegexOption.IGNORE_CASE)

    /**
     * A Hindi amount or count in the request (worked out by [Amounts], code that is already tested)
     * that the structure disagrees with: an ask, never a correction. Gemma wrote pandrah as 19,
     * bees as 2 and sava aath as 7:45 in times.txt's first run.
     */
    fun amountDisagrees(request: String, `when`: JSONObject): String? {
        val time = `when`.optJSONObject("time")
        val length = `when`.optJSONObject("length")
        Amounts.parse(request)?.let { amount ->
            // A clock time said ("paune aath baje") written as a length, or a length as a moment.
            if (amount.isTime && `when`.optString("kind") in setOf("in", "duration")) return "the Hindi time (${amount.english}) came back as a length"
            if (!amount.isTime && `when`.optString("kind") == "at") return "the Hindi length (${amount.english}) came back as a moment"
            if (amount.isTime && time != null && !time.isNull("hour")) {
                val (h, m) = amount.english.split(":").map { it.toInt() }
                val hour = time.optInt("hour") % 12
                if (hour != h % 12 || time.optInt("minute") != m) return "the Hindi time (${amount.english}) doesn't match"
            }
            if (!amount.isTime && length != null && seconds(length) != secondsOf(amount.english)) return "the Hindi length (${amount.english}) doesn't match"
            return null
        }
        hindiCount.find(request)?.let { match ->
            val n = Amounts.numbers.getValue(match.groupValues[1].lowercase())
            val unit = match.groupValues[2].lowercase()
            val want = when { unit.startsWith("ghant") -> n * 3600; unit.startsWith("s") -> n; else -> n * 60 }
            if (length != null && seconds(length) != want) return "the Hindi number (${match.value}) doesn't match"
        }
        return null
    }

    /** "1 hour 15 minutes", as Amounts writes a length, in seconds. */
    private fun secondsOf(english: String): Int = Regex("(\\d+)\\s*(hour|minute|second)").findAll(english).sumOf { m ->
        val n = m.groupValues[1].toInt()
        when (m.groupValues[2]) { "hour" -> n * 3600; "minute" -> n * 60; else -> n }
    }

    private fun seconds(length: JSONObject) = length.optInt("days") * 86400 + length.optInt("hours") * 3600 + length.optInt("minutes") * 60 + length.optInt("seconds")

    private val leaked = Regex("\\}\\}|```")

    /**
     * A string argument with the answer's own closing written into it ("Maps}}]}```"): Gemma
     * sometimes closes the JSON before the string, and the grammar lets it stay inside. No real
     * name holds "}}" or "```", so the string is cut there, and [trimmed] says so.
     */
    fun clean(arguments: JSONObject): Pair<JSONObject, Boolean> {
        var trimmed = false
        val out = JSONObject()
        for (key in arguments.keys()) {
            val value = arguments.get(key)
            if (value is String && leaked.containsMatchIn(value)) {
                out.put(key, value.substring(0, leaked.find(value)!!.range.first).trimEnd())
                trimmed = true
            } else out.put(key, value)
        }
        return out to trimmed
    }

    /** The answer as the harness writes it: calls with each "when" versioned, and commands. */
    fun reply(content: String, request: String = ""): JSONObject {
        val answer = JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1))
        val calls = answer.getJSONArray("calls")
        val out = JSONArray((0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val (arguments, trimmed) = clean(call.optJSONObject("arguments") ?: JSONObject())
            JSONObject().put("tool", call.getString("tool")).put("arguments", arguments).put("trimmed", trimmed)
                .put("when", TimeFormat.versioned(call.optJSONObject("when")))
                .put("asks", JSONArray(asks(request, call.getString("tool"), call.optJSONObject("when"))))
        })
        return JSONObject().put("calls", out).put("commands", answer.optJSONArray("commands") ?: JSONArray())
    }
}
