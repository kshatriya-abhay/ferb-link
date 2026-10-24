package dev.abhay.ferblink

import org.json.JSONObject

/**
 * Time expressions as structure ("Plan - Dates and times through Gemma", time version 1): the
 * model writes what a request says about when, with no date arithmetic, and Ferb resolves it.
 * Closed enums, integers only, free text only in "source", every field null when not said; the
 * schema is enforced while the model writes (llama.cpp's json_schema), so it always parses.
 *
 * Bench only (the harness's schema-call baseline); Ferb keeps the format for its own use. [VERSION] is added
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

    /** [expression] as passed on in a reply: with "v", or JSON null. */
    fun versioned(expression: JSONObject?): Any = expression?.let { JSONObject(it.toString()).put("v", VERSION) } ?: JSONObject.NULL
}
