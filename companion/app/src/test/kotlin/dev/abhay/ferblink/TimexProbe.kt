package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * A probe for "Plan - Dates and times through Gemma" (harness `timex`, bench only, not app code):
 * can Gemma 4 E2B write a request's time expressions as structure, with no date arithmetic, for
 * code to resolve? One flat object per expression, which a small model fills more reliably than
 * nested ones; null where the request doesn't say.
 */
object TimexProbe {
    const val PROMPT = """You find the time expressions in a request typed to a phone assistant, and write each one as structure. Never calculate a date or time: copy what the request says into the fields, and leave a field null when the request doesn't say it. Reply with JSON only: {"version": 1, "expressions": [...]}, one object per expression, in the order said, or an empty list when there is none.
Fields of each expression:
- "source": the words it came from, exactly as typed.
- "kind": "at" (a moment: a day and/or a time), "in" (after a length of time from now: "in 20 minutes"), "duration" (a length, as for a timer: "a 10 minute timer"), or "every" (a repeat).
- "days", "hours", "minutes", "seconds": for "in" and "duration", the length as said ("2 and a half hours" is hours 2, minutes 30; "dedh ghanta" is hours 1, minutes 30; "dhai minute" is minutes 2, seconds 30).
- "relative_day": 0 for today or aaj, 1 for tomorrow or kal, 2 for the day after tomorrow or parso. Null otherwise.
- "weekday": a day of the week in English, lowercase ("monday"), when one is named, in English or Hindi (somvar is monday, shukravar friday). "next": true when it says next or agle.
- "day" and "month": a date of the month as said ("20th October" is day 20, month 10). Never fill them from a weekday or a relative day.
- "hour" and "minute": a clock time as said, 12-hour as said ("7:15" is hour 7, minute 15; "sava saat" is hour 7, minute 15; "saade paanch" is hour 5, minute 30; "paune aath" is hour 7, minute 45; "quarter to 8" is hour 7, minute 45).
- "meridiem": "am" or "pm" only if the request says it, or says subah (am) or dopahar, shaam or raat (pm). Null otherwise.
- "part": "morning", "afternoon", "evening" or "night" when the request names one without a clock time ("this evening", "tonight", "subah"). Null otherwise.
- "every": "day", "week" or "month" for a repeat, and "on": the weekdays it repeats on (["monday"]), else [].
Context, for words like "tonight" and "this weekend" only: now is """

    // Closed enums with null, integers only, every field optional but "kind" (ferb-manager, the user's emphasis).
    private const val INT = """{"type": ["integer", "null"]}"""
    private const val WEEKDAY = """{"enum": ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", null]}"""
    private const val FORMAT = """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {"version": {"enum": [1]}, "expressions": {"type": "array", "items": {"type": "object", "properties": {""" +
        """"kind": {"enum": ["at", "in", "duration", "every"]}, "source": {"type": "string"}, """ +
        """"days": $INT, "hours": $INT, "minutes": $INT, "seconds": $INT, "relative_day": {"enum": [0, 1, 2, null]}, "weekday": $WEEKDAY, "next": {"type": ["boolean", "null"]}, """ +
        """"day": $INT, "month": $INT, "hour": $INT, "minute": $INT, "meridiem": {"enum": ["am", "pm", null]}, "part": {"enum": ["morning", "afternoon", "evening", "night", null]}, """ +
        """"every": {"enum": ["day", "week", "month", null]}, "on": {"type": "array", "items": $WEEKDAY}}, "required": ["kind"]}}}, "required": ["version", "expressions"]}}}"""

    /**
     * t2: one object shape per kind (anyOf), so a field that doesn't belong to a kind can't be
     * filled, nested day and time, and a few examples. After t1 mixed up fields (a weekday as a
     * repeat, "kal" as a weekday) and guessed a part of day.
     */
    const val PROMPT_T2 = """You find the time expressions in a request typed to a phone assistant and write each as structure. Never calculate a date or time and never guess: copy what the request says, and use null for anything it doesn't say. Reply with JSON only: {"version": 2, "expressions": [...]}, or an empty list when there is none.
Each expression is one of:
- {"kind": "at", "day": D or null, "time": T or null, "source": "..."}: a moment.
  D is {"relative": 0} for today or aaj, {"relative": 1} for tomorrow or kal, {"relative": 2} for the day after tomorrow or parso; or {"weekday": "friday", "next": true or false} for a named weekday (Hindi: somvar monday, mangalvar tuesday, budhvar wednesday, guruvar thursday, shukravar friday, shanivar saturday, ravivar or itvar sunday; next is true for "next" or "agle"); or {"day": 20, "month": 10} for a date of the month.
  T is {"hour": 1-12 or 0-23 as said, "minute": 0-59, "meridiem": "am", "pm" or null, "part": "morning", "afternoon", "evening", "night" or null}. meridiem only when said, or from subah (am), dopahar, shaam, raat (pm). part only when a part of the day is named. A time with no clock ("tonight", "kal subah") has hour and minute null.
- {"kind": "in", "length": L, "source": "..."}: after a length of time from now ("in 20 minutes").
- {"kind": "duration", "length": L, "source": "..."}: a length, as for a timer.
  L is {"days": n, "hours": n, "minutes": n, "seconds": n}, 0 for parts not said. Hindi amounts: sava is a quarter more, saade a half more, paune a quarter less, dedh one and a half, dhai two and a half ("dedh ghanta" is hours 1, minutes 30).
- {"kind": "every", "period": "day", "week" or "month", "on": [weekdays], "time": T or null, "source": "..."}: a repeat ("every Monday at 8": period week, on ["monday"]).
Clock words: "sava saat" is 7:15, "saade paanch" 5:30, "paune aath" 7:45, "quarter to 8" 7:45, "half past 6" 6:30.
Examples:
"kal shaam 5 baje" → {"kind": "at", "day": {"relative": 1}, "time": {"hour": 5, "minute": 0, "meridiem": "pm", "part": "evening"}, "source": "kal shaam 5 baje"}
"alarm for 6" → {"kind": "at", "day": null, "time": {"hour": 6, "minute": 0, "meridiem": null, "part": null}, "source": "6"}
"next Tuesday" → {"kind": "at", "day": {"weekday": "tuesday", "next": true}, "time": null, "source": "next Tuesday"}
"every day at 9 pm" → {"kind": "every", "period": "day", "on": [], "time": {"hour": 9, "minute": 0, "meridiem": "pm", "part": null}, "source": "every day at 9 pm"}
Context, for "tonight" and "this weekend" only: now is """

    private const val LENGTH = """{"type": "object", "properties": {"days": {"type": "integer"}, "hours": {"type": "integer"}, "minutes": {"type": "integer"}, "seconds": {"type": "integer"}}, "required": ["days", "hours", "minutes", "seconds"]}"""
    private const val TIME = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"hour": $INT, "minute": $INT, "meridiem": {"enum": ["am", "pm", null]}, "part": {"enum": ["morning", "afternoon", "evening", "night", null]}}, "required": ["hour", "minute", "meridiem", "part"]}]}"""
    private const val DAY = """{"anyOf": [{"type": "null"}, {"type": "object", "properties": {"relative": {"enum": [0, 1, 2]}}, "required": ["relative"]}, """ +
        """{"type": "object", "properties": {"weekday": {"enum": ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]}, "next": {"type": "boolean"}}, "required": ["weekday", "next"]}, """ +
        """{"type": "object", "properties": {"day": {"type": "integer"}, "month": {"type": "integer"}}, "required": ["day", "month"]}]}"""
    private const val SOURCE = """"source": {"type": "string"}"""
    private const val FORMAT_T2 = """{"type": "json_schema", "json_schema": {"schema": {"type": "object", "properties": {"version": {"enum": [2]}, "expressions": {"type": "array", "items": {"anyOf": [""" +
        """{"type": "object", "properties": {"kind": {"enum": ["at"]}, "day": $DAY, "time": $TIME, $SOURCE}, "required": ["kind", "day", "time", "source"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["in", "duration"]}, "length": $LENGTH, $SOURCE}, "required": ["kind", "length", "source"]}, """ +
        """{"type": "object", "properties": {"kind": {"enum": ["every"]}, "period": {"enum": ["day", "week", "month"]}, "on": {"type": "array", "items": {"enum": ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]}}, "time": $TIME, $SOURCE}, "required": ["kind", "period", "on", "time", "source"]}""" +
        """]}}}, "required": ["version", "expressions"]}}}"""

    fun body(model: String, request: String, now: String, variant: String = "t1"): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", (if (variant == "t2") PROMPT_T2 else PROMPT) + "$now."))
            .put(JSONObject().put("role", "user").put("content", request)))
        .put("temperature", 0)
        .put("max_tokens", 600)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .put("response_format", "@format@")
        .toString()
        .replace("\"@format@\"", if (variant == "t2") FORMAT_T2 else FORMAT)
}
