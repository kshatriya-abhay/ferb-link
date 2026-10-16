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

    /**
     * v2: v1 plus ferb-manager's hard rule after the first look, never add a fact the request
     * doesn't hold, and questions no tool answers (the weather) give no command, not "Tell me…".
     */
    const val V2 = V1 + """
- Never add anything the request doesn't say: no name, contact, place, app, number, time, date or length that isn't in the request, except dates you work out from Now. If something needed is missing, leave it out ("Set an alarm", not "Set an alarm for 7 am").
- A question only words can answer (the weather, news, facts, maths, advice) is not a command, even when it's phrased as one: reply {"commands": []}. Don't write "Tell me …" commands."""

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
    const val V21 = V2 + """
- Write amounts as numbers: half is 50 percent, a quarter 25 percent, full or max 100 percent ("half volume" → "Set the volume to 50 percent").
- A short request of one or two words is still a command when it is an action word, like a media button: "next", "stop", "pause", "louder" ("Play the next track", "Stop", "Pause", "Turn the volume up"). Naming a thing is not an action: "my torch is broken" asks for nothing.
- Silencing is muting: "aawaz band karo", "sound off", "chup karo" → "Mute the volume" (volume to 0), not "Turn off the sound"."""

    private val PROMPTS = mapOf("v1" to V1, "v2" to V2, "v2.1" to V21, "v3" to V3)

    /** Numbers a request holds as words: v2.1 writes them as digits, which isn't inventing them. */
    private val numberWords: Map<String, Int> = Amounts.numbers + mapOf(
        "half" to 50, "aadha" to 50, "aadhi" to 50, "quarter" to 25, "full" to 100, "max" to 100, "maximum" to 100, "poora" to 100, "poori" to 100,
        "mute" to 0, "band" to 0, "zero" to 0,
    ) + listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty").withIndex().associate { (i, word) -> word to i + 1 } +
        mapOf("thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "hundred" to 100)

    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val clockWords = setOf("am", "pm")

    /**
     * Facts in [commands] that [request] doesn't hold: numbers, and capitalised words after a
     * command's first (names, places, apps), compared case-blind with the request as typed and as
     * [sent]. Dates are allowed (weekdays, months, and day numbers up to 31 when a month or weekday
     * is there), and so are numbers from [Amounts]' own work on the request. English words a
     * translation brings ("Set an alarm" for "alarm laga do") aren't facts and aren't flagged.
     */
    fun invented(request: String, sent: String, commands: List<String>): List<String> {
        val given = (request + " " + sent).lowercase()
        val givenWords = given.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        val givenNumbers = Regex("\\d+").findAll(given).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet() +
            givenWords.mapNotNull { numberWords[it]?.toString() }
        return commands.flatMap { command ->
            // Split as the request is, so "I'll" is "I" and "ll", neither of them a fact.
            val words = command.split(Regex("[^\\p{L}\\p{N}:]+")).flatMap { it.split(":") }.filter { it.isNotEmpty() }
            val dated = words.any { it.lowercase() in months || it.lowercase() in weekdays }
            words.drop(1).filter { word ->
                val lower = word.lowercase()
                when {
                    word.first().isDigit() -> {
                        val number = Regex("\\d+").find(word)!!.value.trimStart('0').ifEmpty { "0" }
                        number !in givenNumbers && !(dated && number.toInt() in 1..31) && !(number.length == 4 && dated)
                    }
                    word.first().isUpperCase() -> lower !in givenWords && lower !in months && lower !in weekdays && lower !in clockWords && word != "I"
                    else -> false
                }
            }
        }.distinct()
    }

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
