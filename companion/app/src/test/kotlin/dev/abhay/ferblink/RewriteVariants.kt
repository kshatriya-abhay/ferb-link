package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * The rewrite prompts and switches the bench compared (`companion:harness`'s `rewrite`, run by
 * Ferb's tool-check/rewrite.py), kept out of the APK: Ferb Link itself sends only v2 ([Rewrite]).
 * Written "v1", "v1+shots", "v1+tools+think" and so on, so one bench table can compare them.
 */
object RewriteVariants {
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
     * v3: v2 with the near-miss rule stated first, after the v1/v2 bench on requests.txt
     * (2026-10-16): statements turned into commands were the main loss ("my flashlight is broken"
     * became "Fix my flashlight" and ran set_flashlight). Rewrite only a request to the phone;
     * a statement, a complaint or a sentence that merely uses a tool's word gives none; never add
     * a verb; keep "my" and the user's own words. Examples here are not the bench's lines.
     */
    const val V3 = """You turn a request typed to a phone assistant into plain English commands for the assistant. Reply with JSON only: {"commands": [...]}.
First decide whether the user is asking the phone to do something now. Only then write commands. Reply {"commands": []} for everything else:
- statements and complaints about things, even about the phone's own things: "my torch stopped working", "the alarm was too loud today", "this list is getting long", "I missed my reminder";
- things about the user or the past: "I'm tired", "I already called him", "I woke up early", "I forgot my umbrella";
- sentences that only use a word an action also uses: "time flies", "the list of countries in Europe", "directions in a board game", "the calendar of the cricket season";
- questions only words can answer (the weather, news, facts, maths, advice), even phrased as a command; wishes, plans and opinions not addressed to the assistant ("I should call mom"); greetings and thanks.
When the user does ask:
- Write one short sentence per thing asked, in plain English, in the order asked: "Set an alarm for 7:15 am", "Call Priya".
- Use the request's own action. Never add a verb or a goal it doesn't imply (no "Fix", "Check", "Find", "Report", "Tell me").
- Translate Hindi and Hinglish into English. Keep every name, contact, app, place, number, time and length exactly as written, and keep the user's own words for notes and messages, with "my" and "I" as they are. Never add a name, place, app, number, time or length the request doesn't have; leave out what's missing ("Set an alarm", not "Set an alarm for 7 am").
- Write dates as dates: work out "today", "tomorrow", "kal", "parson", weekdays and "next week" from Now below, and write the day ("on Saturday 17 October"). Keep times as written. Add am or pm only if the request says which: subah is am, dopahar and shaam are pm, raat is pm from 7 to 11 and otherwise am.
- Asking to see the phone's own things is a command: alarms, lists, notes, the calendar, directions ("Show my agenda for tomorrow")."""

    /**
     * v2.1: v2 plus three lessons from the audio bench (2026-10-16): amounts as numbers ("half
     * volume" as 50 percent), short commands kept as commands ("next", "stop"), and "aawaz band"
     * as mute rather than "Turn off the sound".
     */
    const val V21 = Rewrite.V2 + """
- Write amounts as numbers: half is 50 percent, a quarter 25 percent, full or max 100 percent ("half volume" → "Set the volume to 50 percent").
- A short request of one or two words is still a command when it is an action word, like a media button: "next", "stop", "pause", "louder" ("Play the next track", "Stop", "Pause", "Turn the volume up"). Naming a thing is not an action: "my torch is broken" asks for nothing.
- Silencing is muting: "aawaz band karo", "sound off", "chup karo" → "Mute the volume" (volume to 0), not "Turn off the sound"."""

    /**
     * Retired with Ferb Link's code dates (2026-10-24): v2.2 (dates worked out in code before the
     * model) and comprehend c1/c2, which built on it. Their prompts and results are in git history
     * and "Exploration - Rewrite before Needle 3".
     */
    private val PROMPTS = mapOf("v1" to Rewrite.V1, "v2" to Rewrite.V2, "v2.1" to V21, "v3" to V3)

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
        if (!variant.free) json.put("response_format", Rewrite.FORMAT_SLOT)
        return json.toString().replace("\"${Rewrite.FORMAT_SLOT}\"", Rewrite.FORMAT)
    }

    /** [request] rewritten by [client] with [variant]'s prompt, as `rewrite` answers for v2. */
    fun rewrite(client: ServerClient, request: String, now: String, variant: Variant, toolNames: List<String>, timeoutMs: Int?): Rewritten =
        client.rewrite(request, now, timeoutMs) { model, sent -> body(model, sent, now, variant, toolNames) }
}
