package dev.abhay.ferblink

import org.json.JSONObject

/**
 * Time expressions as structure ("Plan - Dates and times through Gemma", time version 1): the
 * model writes what a request says about when, with no date arithmetic, and Ferb resolves it.
 * Closed enums, integers only, free text only in "source", every field null when not said; the
 * schema is enforced while the model writes (llama.cpp's json_schema), so it always parses.
 *
 * Shared by the prompts that ask for it ([Rewrite]'s +when, the harness's schema-call); [VERSION] is added
 * by code when passing an expression on ([versioned]), not written by the model.
 */
object TimeFormat {
    const val VERSION = 1

    private const val INT = """{"type": ["integer", "null"]}"""
    private const val WEEKDAY = """{"enum": ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]}"""
    private const val TIME = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"hour": $INT, "minute": $INT, "meridiem": {"enum": ["am", "pm", null]}, "part": {"enum": ["morning", "afternoon", "evening", "night", null]}}, "required": ["hour", "minute", "meridiem", "part"]}]}"""
    private const val DAY = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"relative": {"enum": [0, 1, 2]}}, "required": ["relative"]}, """ +
        """{"type": "object", "properties": {"weekday": $WEEKDAY, "which": {"enum": ["this", "next", null]}}, "required": ["weekday", "which"]}, """ +
        """{"type": "object", "properties": {"day": {"type": "integer"}, "month": $INT, "year": $INT}, "required": ["day", "month", "year"]}, """ +
        """{"type": "object", "properties": {"span": {"enum": ["weekend"]}}, "required": ["span"]}]}"""
    private const val LENGTH = """{"type": "object", "properties": {"days": {"type": "integer"}, "hours": {"type": "integer"}, "minutes": {"type": "integer"}, "seconds": {"type": "integer"}}, "required": ["days", "hours", "minutes", "seconds"]}"""
    private const val SOURCE = """"source": {"type": "string"}"""

    /** One expression, or null: the JSON schema of a "when". */
    const val SCHEMA = """{"anyOf": [{"type": "null"}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $TIME, $SOURCE}, "required": ["kind", "day", "time", "source"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["in", "duration"]}, "length": $LENGTH, $SOURCE}, "required": ["kind", "length", "source"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["every"]}, "period": {"enum": ["day", "week", "weekdays", "month", "year"]}, "on": {"type": "array", "items": $WEEKDAY}, "time": $TIME, $SOURCE}, "required": ["kind", "period", "on", "time", "source"]}""" +
        """]}"""

    /**
     * How to write a "when", for a prompt. t3: the probe's t2 (17 of 20 right) with "tonight"
     * (today, part night) and "this weekend" (a span, which Ferb asks about) after its misses.
     */
    const val PROMPT = """"when" is the time a request names, written as structure, or null when it names none. Never calculate a date or time and never guess: copy what is said, and use null for anything not said.
- {"kind": "at", "day": D or null, "time": T or null, "source": "..."}: a moment.
  D is {"relative": 0} for today, aaj or tonight, {"relative": 1} for tomorrow or kal, {"relative": 2} for the day after tomorrow or parso; or {"weekday": "friday", "which": "this", "next" or null} for a named weekday (Hindi: somvar monday, mangalvar tuesday, budhvar wednesday, guruvar thursday, shukravar friday, shanivar saturday, ravivar or itvar sunday; "next" for next or agle); or {"day": 20, "month": 10 or null, "year": null} for a date of the month; or {"span": "weekend"} for "this weekend".
  T is {"hour", "minute", "meridiem", "part"}: hour and minute as said (null when no clock time is said), meridiem "am" or "pm" only when said or from subah (am), dopahar, shaam, raat (pm), part "morning", "afternoon", "evening" or "night" only when a part of the day is named ("tonight" is part night). Never fill part for a bare clock time.
- {"kind": "in", "length": L, "source": "..."}: after a length of time from now ("in 20 minutes").
- {"kind": "duration", "length": L, "source": "..."}: a length, as for a timer.
  L is {"days", "hours", "minutes", "seconds"}, 0 for parts not said. sava is a quarter more, saade a half more, paune a quarter less, dedh one and a half, dhai two and a half ("dedh ghanta" is hours 1, minutes 30).
- {"kind": "every", "period": "day", "week", "weekdays", "month" or "year", "on": [weekdays], "time": T or null, "source": "..."}: a repeat ("every Monday at 8": period week, on ["monday"]).
Clock words: "sava saat" is 7:15, "saade paanch" 5:30, "paune aath" 7:45, "quarter to 8" 7:45, "half past 6" 6:30.
Examples: "kal shaam 5 baje" → {"kind": "at", "day": {"relative": 1}, "time": {"hour": 5, "minute": 0, "meridiem": "pm", "part": "evening"}, "source": "kal shaam 5 baje"}; "alarm for 6" → {"kind": "at", "day": null, "time": {"hour": 6, "minute": 0, "meridiem": null, "part": null}, "source": "6"}; "call mom tonight" → {"kind": "at", "day": {"relative": 0}, "time": {"hour": null, "minute": null, "meridiem": null, "part": "night"}, "source": "tonight"}."""

    // --- Trimmed shapes for one tool each (ferb-manager's option c): only the fields that tool uses,
    // and no "source" (the longest part of the answer): grounding reads the tool's own arguments. ---

    private const val CLOCK = """{"type": "object", "properties": {"hour": {"type": "integer"}, "minute": {"type": "integer"}, "meridiem": {"enum": ["am", "pm", null]}}, "required": ["hour", "minute", "meridiem"]}"""

    /** An alarm: a clock time, and the day only if one is said (Clock can't take a date; Ferb checks it). */
    const val ALARM = """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $CLOCK}, "required": ["kind", "day", "time"]}"""
    /** A timer: a length only. */
    const val TIMER = """{"type": "object", "properties": {"kind": {"enum": ["duration"]}, "length": $LENGTH}, "required": ["kind", "length"]}"""
    /** An event: a day and a time, which may be only a part of the day. */
    const val EVENT = """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $TIME}, "required": ["kind", "day", "time"]}"""
    /** A reminder: a moment, after a length, or a repeat. */
    const val REMINDER = """{"anyOf": [$EVENT, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["in"]}, "length": $LENGTH}, "required": ["kind", "length"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["every"]}, "period": {"enum": ["day", "week", "weekdays", "month", "year"]}, "on": {"type": "array", "items": $WEEKDAY}, "time": $TIME}, "required": ["kind", "period", "on", "time"]}]}"""

    private const val IN = """{"type": "object", "properties": {"kind": {"enum": ["in"]}, "length": $LENGTH}, "required": ["kind", "length"]}"""
    /** t4: an alarm may also be after a length ("alarm in 20 minutes"), which t3 turned into a clock time. */
    const val ALARM_T4 = """{"anyOf": [$ALARM, $IN]}"""

    /** Any trimmed shape, or null: for a command without a tool yet (transcribe's comprehend answer). */
    const val ANY_T4 = """{"anyOf": [{"type": "null"}, $EVENT, $IN, $TIMER, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["every"]}, "period": {"enum": ["day", "week", "weekdays", "month", "year"]}, "on": {"type": "array", "items": $WEEKDAY}, "time": $TIME}, "required": ["kind", "period", "on", "time"]}]}"""

    /** The trimmed "when" for a tool that takes a time, by Ferb's tool names; null for one that doesn't. */
    fun forTool(name: String, version: String = "t3"): String? = when (name) {
        "set_alarm" -> if (version == "t4" || version == "t5") ALARM_T4 else ALARM
        "set_timer" -> TIMER
        "add_event" -> EVENT
        "add_reminder" -> REMINDER
        else -> null
    }

    /** For the trimmed shapes: shorter than [PROMPT], with the examples the full probe missed (parso, next Friday). */
    const val PROMPT_TIMED = """For an alarm, timer, reminder or event, "when" is the time as said, as structure. Never calculate a date or time; null for what isn't said.
- day: {"relative": 0} today, aaj, tonight; {"relative": 1} tomorrow, kal; {"relative": 2} the day after tomorrow, parso; {"weekday": "friday", "which": "this", "next" or null} (next for next or agle; somvar monday, mangalvar tuesday, budhvar wednesday, guruvar thursday, shukravar friday, shanivar saturday, ravivar sunday); {"day": 20, "month": 10 or null, "year": null}; {"span": "weekend"}; or null.
- time: hour and minute as said, meridiem "am" or "pm" only when said or from subah (am), dopahar, shaam, raat (pm). For events and reminders, part "morning", "afternoon", "evening" or "night" only when named ("tonight" is night), with hour and minute null if no clock time is said. sava saat is 7:15, saade paanch 5:30, paune aath 7:45, quarter to 8 is 7:45.
- length (timers, "in …" reminders): days, hours, minutes, seconds, 0 when not said; dedh ghanta is 1 hour 30 minutes, dhai minute 2 minutes 30 seconds.
- every (reminders): period day, week, weekdays, month or year, on [weekdays], and time.
Examples: "parso shaam 6 baje" → {"kind": "at", "day": {"relative": 2}, "time": {"hour": 6, "minute": 0, "meridiem": "pm", "part": "evening"}}; "next Friday at 3" → {"kind": "at", "day": {"weekday": "friday", "which": "next"}, "time": {"hour": 3, "minute": 0, "meridiem": null, "part": null}}; "alarm for 6" → {"kind": "at", "day": null, "time": {"hour": 6, "minute": 0, "meridiem": null}}."""

    /**
     * t4, after times.txt's tune half (2026-10-18): "in N" was worked out into a clock time, repeats
     * never used "every", the examples' dates were copied into unrelated ones, a month, year or
     * this-Friday was added when not said, named days became +1, and Hindi numbers slipped. The
     * examples now use values that appear in no request (27 March, 45 minutes).
     */
    const val PROMPT_T4 = """For an alarm, timer, reminder or event, "when" is the time exactly as said, as structure. You never calculate: no clock time from "in an hour", no date from a weekday, no month or year that wasn't said. Anything not said is null.
- "in", "after", "mein" or "baad" with a length ("in 20 minutes", "aadhe ghante mein", "2 ghante baad") is always {"kind": "in", "length": ...}, for alarms too. Never turn it into a clock time.
- "every", "har", "roz", "daily", "each" is always {"kind": "every", ...}: "roz" or "daily" is period day; "every Monday" or "har somvar" is period week with on ["monday"]; "weekdays" is period weekdays.
- day: {"relative": 0} only for today, aaj or tonight; {"relative": 1} only for tomorrow or kal; {"relative": 2} only for the day after tomorrow or parso. A named weekday stays a weekday: {"weekday": ..., "which": "this", "next" or null}. A date stays as said: {"day": n, "month": m or null if no month is said, "year": null unless a year is said}. Null when no day is said: never add today or this weekday.
- time: hour and minute as said. meridiem only when am or pm is said, or from subah (am), dopahar, shaam or raat (pm); otherwise null. part only when a part of the day is named; never for a bare clock time.
- length: days, hours, minutes, seconds as said, 0 for the rest.
- Hindi numbers: ek 1, do 2, teen 3, chaar 4, paanch 5, chhe 6, saat 7, aath 8, nau 9, das 10, gyarah 11, barah 12, pandrah 15, bees 20, pachchees 25, tees 30, paintalees 45. sava is a quarter more (sava aath 8:15), saade a half more (saade saat 7:30), paune a quarter less (paune aath 7:45, paune ghanta 45 minutes), dedh one and a half, dhai two and a half, aadha half. With baje it is always a clock time, kind "at", never "in": "paune aath baje" is hour 7, minute 45; "saade paanch baje" is hour 5, minute 30.
Examples: "on the 27th of March at 4" → {"kind": "at", "day": {"day": 27, "month": 3, "year": null}, "time": {"hour": 4, "minute": 0, "meridiem": null, "part": null}}; "in 45 minutes" → {"kind": "in", "length": {"days": 0, "hours": 0, "minutes": 45, "seconds": 0}}; "har mangalvar shaam 9 baje" → {"kind": "every", "period": "week", "on": ["tuesday"], "time": {"hour": 9, "minute": 0, "meridiem": "pm", "part": "evening"}}; "on Wednesday" → {"kind": "at", "day": {"weekday": "wednesday", "which": null}, "time": null}."""

    /**
     * t5: t4 after its tune-half misses (2026-10-19), which matched the held-out ones: day words
     * came back as "in N days" ("kal subah 6 baje" as in 1 day), named days as relative days ("on
     * Sunday" as today, "the 2nd" as +1), "tonight" lost its part, "raat 2 baje" became pm, and
     * Hindi amounts slipped. Its examples use values in neither half of times.txt.
     */
    val PROMPT_T5 = PROMPT_T4 + """
More rules:
- A day word with a clock time is a moment, never "in": "kal subah 6 baje" is {"kind": "at", "day": {"relative": 1}, "time": {"hour": 6, "minute": 0, "meridiem": "am", "part": "morning"}}. "in" is only for "in", "after", "mein" or "baad" with a length.
- Only today, aaj, tonight, tomorrow, kal, the day after tomorrow and parso are relative. A weekday, in English or Hindi, is always {"weekday": ...}, even when it is today or tomorrow: "on Thursday" is {"weekday": "thursday", "which": null}. A number of the month alone ("the 9th") is {"day": 9, "month": null, "year": null}.
- "tonight" and "aaj raat" are {"relative": 0} with part night and hour null when no clock time is said.
- With raat, meridiem is null and part night: "raat 3 baje" is hour 3, part night; Ferb works out am or pm.
- Hindi amounts, exactly: "sava nau" is hour 9, minute 15; "paune das" is hour 9, minute 45; "dhai ghante" is hours 2, minutes 30; "dhai minute" is minutes 2, seconds 30.
Examples: "parso subah sava nau baje" → {"kind": "at", "day": {"relative": 2}, "time": {"hour": 9, "minute": 15, "meridiem": "am", "part": "morning"}}; "dhai ghante ka timer" → {"kind": "duration", "length": {"days": 0, "hours": 2, "minutes": 30, "seconds": 0}}."""

    /** [expression] as passed on in a reply: with "v", or JSON null. */
    fun versioned(expression: JSONObject?): Any = expression?.let { JSONObject(it.toString()).put("v", VERSION) } ?: JSONObject.NULL
}
