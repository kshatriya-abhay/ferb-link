package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * An experiment (bench only, `companion:harness`'s `rewrite`): Gemma reads a typed request and
 * writes it as plain English commands, one per action, and Needle 3 on the phone then calls the
 * tools. The request first goes through [Amounts.forRequest], so Hindi amounts reach the model
 * as digits ("sava saat baje" as 7:15), as for `complete`; the model never does the sums.
 *
 * [Variant]s are written "v1", "v1+shots", "v1+tools+think" and so on, so one bench table can
 * compare them. Each is documented in the Laptop Model Server notes ("Exploration - Rewrite before Needle 3").
 */
object Rewrite {
    /** A prompt version and its switches: few-shot examples, Ferb's tool names, thinking on, no schema. */
    data class Variant(val base: String, val shots: Boolean, val tools: Boolean, val think: Boolean, val free: Boolean) {
        companion object {
            fun of(name: String): Variant {
                val parts = name.trim().split("+").map { it.trim().lowercase() }
                val unknown = parts.drop(1) - setOf("shots", "tools", "think", "free")
                require(parts.first() in PROMPTS && unknown.isEmpty()) { "unknown variant \"$name\": ${PROMPTS.keys} with +shots, +tools, +think, +free" }
                return Variant(parts.first(), "shots" in parts, "tools" in parts, "think" in parts, "free" in parts)
            }
        }
    }

    /**
     * v1, from the transcribe prompt's lessons: keep every name and number as written (the model
     * otherwise "corrects" words it doesn't know), no answering, and near misses spelled out, since
     * a command written for a request that asks for nothing would run a tool.
     */
    const val V1 = """You turn a request typed to a phone assistant into plain English commands for the assistant. Reply with JSON only: {"commands": [...]}.
- Each command is one short sentence in plain English that tells the assistant to do one thing, for example "Set an alarm for 7:15 am" or "Call Priya".
- Translate Hindi and Hinglish into English. Keep every name, contact, app, place, number, time and length exactly as written; don't add, drop or guess anything, and don't answer the request.
- Write dates as dates: work out "today", "tomorrow", "kal", "parson", weekdays and "next week" from Now below, and write the day ("on Saturday 17 October"). Keep times as written. Add am or pm only if the request says which: subah is am, dopahar and shaam are pm, raat is pm from 7 to 11 and otherwise am.
- If the request asks for several things, write one command for each, in the order asked.
- Asking to see the phone's own things is a command: alarms, lists, notes, the calendar, directions ("Show my agenda for tomorrow").
- If the request doesn't ask the assistant to do anything, reply {"commands": []}. That includes questions about the world, opinions, chat, greetings and thanks, statements about the past or about yourself ("I set an alarm yesterday", "alarms are annoying"), and wishes or plans not addressed to the assistant ("I should call mom")."""

    private val PROMPTS = mapOf("v1" to V1)

    /** For +shots: a few requests and their commands, near misses and several actions among them. */
    val SHOTS = listOf(
        "kal subah 6 baje ka alarm laga do" to listOf("Set an alarm for 6 am on Saturday 17 October"),
        "torch on karo aur volume kam karo" to listOf("Turn on the flashlight", "Turn the volume down"),
        "I set an alarm for 6 yesterday" to emptyList(),
        "remind me to pay the rent on Monday at 10" to listOf("Remind me to pay the rent on Monday 19 October at 10"),
        "mummy ko call karo" to listOf("Call mummy"),
        "what's the capital of France" to emptyList(),
        "add milk and eggs to the shopping list" to listOf("Add milk to the shopping list", "Add eggs to the shopping list"),
        "should I set a timer" to emptyList(),
    )

    /** The "now" the shots were written against, so their dates make sense. */
    const val SHOTS_NOW = "2026-10-16T09:00 (Friday)"

    /**
     * The answer's shape, as a literal (org.json on the JVM doesn't keep key order): an object
     * with a list of strings. Left out for +free, where only the prompt asks for JSON.
     */
    private const val FORMAT = """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {"commands": {"type": "array", "items": {"type": "string"}}}, "required": ["commands"]}}}"""
    private const val FORMAT_SLOT = "@response_format@"

    fun body(model: String, sent: String, now: String, variant: Variant, toolNames: List<String>): String {
        val system = buildString {
            append(PROMPTS.getValue(variant.base))
            if (variant.tools && toolNames.isNotEmpty()) append("\n\nThe assistant's actions: ${toolNames.joinToString(", ")}. A command may only ask for these.")
            append("\n\nNow: $now.")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        if (variant.shots) {
            messages.put(JSONObject().put("role", "system").put("content", "Examples, written when it was $SHOTS_NOW:"))
            for ((request, commands) in SHOTS) {
                messages.put(JSONObject().put("role", "user").put("content", Amounts.forRequest(request)))
                messages.put(JSONObject().put("role", "assistant").put("content", JSONObject().put("commands", JSONArray(commands)).toString()))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", sent))
        val json = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            // Thinking takes room before the answer.
            .put("max_tokens", if (variant.think) 1024 else 256)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", variant.think))
        if (!variant.free) json.put("response_format", FORMAT_SLOT)
        return json.toString().replace("\"$FORMAT_SLOT\"", FORMAT)
    }

    /** The commands in the model's answer (a JSON object; in +free, the first one in its text), clocks made plain. */
    fun parse(content: String): List<String> {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in the answer" }
        val commands = JSONObject(content.substring(start, end + 1)).getJSONArray("commands")
        return (0 until commands.length()).map { Amounts.englishClock(commands.getString(it).trim()) }.filter { it.isNotEmpty() }
    }
}
