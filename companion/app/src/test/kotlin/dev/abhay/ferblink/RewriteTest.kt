package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** The rewrite experiment's request and parsing; the prompt itself is judged on the bench. */
class RewriteTest {
    @Test
    fun `variants are a version and switches`() {
        assertThat(Rewrite.Variant.of("v1")).isEqualTo(Rewrite.Variant("v1", shots = false, tools = false, think = false, free = false))
        assertThat(Rewrite.Variant.of("v1+shots+tools+think+free")).isEqualTo(Rewrite.Variant("v1", shots = true, tools = true, think = true, free = true))
        assertThrows(IllegalArgumentException::class.java) { Rewrite.Variant.of("v9") }
        assertThrows(IllegalArgumentException::class.java) { Rewrite.Variant.of("v1+magic") }
    }

    @Test
    fun `v1 is constrained JSON at temperature 0 with thinking off, and now in the prompt`() {
        val body = JSONObject(Rewrite.body("gemma", "set a timer", "2026-10-16T09:00 (Friday)", Rewrite.Variant.of("v1"), listOf("set_timer")))
        assertThat(body.getInt("temperature")).isEqualTo(0)
        assertThat(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking")).isFalse()
        assertThat(body.getJSONObject("response_format").getString("type")).isEqualTo("json_schema")
        val messages = body.getJSONArray("messages")
        assertThat(messages.length()).isEqualTo(2)
        val system = messages.getJSONObject(0).getString("content")
        assertThat(system).contains("Now: 2026-10-16T09:00 (Friday).")
        assertThat(system).doesNotContain("set_timer")
        assertThat(messages.getJSONObject(1).getString("content")).isEqualTo("set a timer")
    }

    @Test
    fun `switches add examples, tool names, thinking, or drop the schema`() {
        val body = JSONObject(Rewrite.body("gemma", "x", "now", Rewrite.Variant.of("v1+shots+tools+think+free"), listOf("set_timer", "make_call")))
        assertThat(body.has("response_format")).isFalse()
        assertThat(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking")).isTrue()
        val messages = body.getJSONArray("messages")
        assertThat(messages.getJSONObject(0).getString("content")).contains("set_timer, make_call")
        assertThat(messages.length()).isEqualTo(2 + 1 + 2 * Rewrite.SHOTS.size)
        // The examples' requests are sent as the bench's are: Hindi hours already as times.
        assertThat(messages.getJSONObject(2).getString("content")).isEqualTo(Amounts.forRequest(Rewrite.SHOTS.first().first))
    }

    @Test
    fun `the commands are read from the answer, an empty list kept empty`() {
        assertThat(Rewrite.parse("""{"commands": ["Set an alarm for quarter past 7", " Call Priya "]}""")).containsExactly("Set an alarm for 7:15", "Call Priya").inOrder()
        assertThat(Rewrite.parse("""{"commands": []}""")).isEmpty()
        assertThat(Rewrite.parse("Sure! {\"commands\": [\"Turn on the flashlight\"]} Done.")).containsExactly("Turn on the flashlight")
        assertThrows(IllegalArgumentException::class.java) { Rewrite.parse("no json") }
    }
}

class RewriteInventedTest {
    private fun invented(request: String, vararg commands: String) = Rewrite.invented(request, Amounts.forRequest(request), commands.toList())

    @Test
    fun `facts from the request, its amounts and worked-out dates are fine`() {
        assertThat(invented("what's the weather like in Delhi", "Show the weather in Delhi")).isEmpty()
        assertThat(invented("kal subah sava saat baje ka alarm laga do", "Set an alarm for 7:15 am on Saturday 17 October")).isEmpty()
        assertThat(invented("mummy ko call karo", "Call Mummy")).isEmpty()
        assertThat(invented("torch on karo aur volume badhao", "Turn on the flashlight", "Turn the volume up")).isEmpty()
        assertThat(invented("remind me to pay rent on Monday", "Remind me to pay rent on Monday 19 October 2026")).isEmpty()
    }

    @Test
    fun `a place, name, number or time the request didn't hold is flagged`() {
        assertThat(invented("what's the weather like", "Show the weather in Delhi")).containsExactly("Delhi")
        assertThat(invented("call my sister", "Call Priya")).containsExactly("Priya")
        assertThat(invented("set an alarm", "Set an alarm for 7 am")).containsExactly("7")
        assertThat(invented("set a timer for 5 minutes", "Set a timer for 15 minutes")).containsExactly("15")
    }
}

class RewriteInventedWordsTest {
    @Test
    fun `I, I'll and other words of the request aren't facts`() {
        val request = "sms dad I'll be home by 9"
        assertThat(Rewrite.invented(request, Amounts.forRequest(request), listOf("Text Dad: I'll be home by 9"))).isEmpty()
        assertThat(Rewrite.invented("set an alarm for 7:15", "set an alarm for 7:15", listOf("Set an alarm for 7:15 am"))).isEmpty()
        assertThat(Rewrite.invented("set an alarm for 7", "set an alarm for 7", listOf("Set an alarm for 7:30"))).containsExactly("30")
    }
}

class RewriteV21Test {
    private fun invented(request: String, vararg commands: String) = Rewrite.invented(request, Amounts.forRequest(request), commands.toList())

    @Test
    fun `v2_1 is v2 plus amounts, short commands and muting`() {
        assertThat(Rewrite.Variant.of("v2.1+shots").base).isEqualTo("v2.1")
        assertThat(Rewrite.V21).startsWith(Rewrite.V2)
        assertThat(Rewrite.V21).contains("50 percent")
    }

    @Test
    fun `numbers said as words aren't invented`() {
        assertThat(invented("half volume", "Set the volume to 50 percent")).isEmpty()
        assertThat(invented("aawaz band karo", "Set the volume to 0")).isEmpty()
        assertThat(invented("timer for das minute", "Set a timer for 10 minutes")).isEmpty()
        assertThat(invented("set a timer for ten minutes", "Set a timer for 10 minutes")).isEmpty()
        assertThat(invented("half volume", "Set the volume to 60 percent")).containsExactly("60")
    }
}

class RewriteRespelledTest {
    private fun invented(request: String, vararg commands: String) = Rewrite.invented(request, request, commands.toList())

    @Test
    fun `an app name respelled isn't invented, another name is`() {
        assertThat(invented("open phone pay", "Open PhonePe")).isEmpty()
        assertThat(invented("swiggi kholo", "Open Swiggy")).isEmpty()
        assertThat(invented("call my sister", "Call Priya")).containsExactly("Priya")
        assertThat(invented("open maps", "Open Uber")).containsExactly("Uber")
    }
}

class ComprehendTest {
    @Test
    fun `c1 decides first, then rewrites with v2_2's rules, nothing-asked said once`() {
        val c1 = Rewrite.C1
        println(c1)
        assertThat(c1.indexOf("1. If the request doesn't ask")).isLessThan(c1.indexOf("2. If it is already one clear command"))
        assertThat(c1).contains("torch band karo")
        assertThat(c1).contains("already written out")
        assertThat(Regex("doesn't ask the assistant to do anything").findAll(c1).count()).isEqualTo(1)
        assertThat(c1).doesNotContain("You turn a request")
        assertThat(Rewrite.comprehends(Rewrite.Variant.of("c1"))).isTrue()
        assertThat(Rewrite.datesInCode(Rewrite.Variant.of("c1"))).isTrue()
    }

    @Test
    fun `c1 asks for direct and commands, and a direct answer has no commands`() {
        val body = JSONObject(Rewrite.body("gemma", "x", "now", Rewrite.Variant.of("c1"), emptyList()))
        assertThat(body.getJSONObject("response_format").toString()).contains("direct")
        assertThat(Rewrite.direct("""{"direct": true, "commands": []}""")).isTrue()
        assertThat(Rewrite.direct("""{"direct": false, "commands": ["Call Priya"]}""")).isFalse()
        val rewritten = Rewritten(listOf("a"), "x", "", "m", 1, null, direct = false)
        assertThat(JSONObject(rewritten.toJson()).getBoolean("direct")).isFalse()
        assertThat(JSONObject(Rewritten(emptyList(), "x", "", "m", 1, null).toJson()).has("direct")).isFalse()
    }
}

class Comprehend2Test {
    @Test
    fun `c2 is c1 with the phone's own questions, messages and notes, and media words`() {
        val c2 = Rewrite.C2
        assertThat(c2).isNotEqualTo(Rewrite.C1)
        assertThat(c2).contains("A question about the user's own phone is a request")
        assertThat(c2).contains("A message or a note keeps its action")
        assertThat(c2).contains("\"resume\" means the music")
        assertThat(c2).contains("Get directions to the station")
        assertThat(Rewrite.comprehends(Rewrite.Variant.of("c2"))).isTrue()
    }
}

class TimeFormatTest {
    @Test
    fun `+when asks for each command's time and reads it back in order`() {
        val body = JSONObject(Rewrite.body("gemma", "x", "now", Rewrite.Variant.of("v2+when"), emptyList()))
        assertThat(body.getJSONObject("response_format").toString()).contains("\"when\"")
        assertThat(body.getJSONArray("messages").getJSONObject(0).getString("content")).contains("\"kind\": \"at\"")
        val content = """{"commands": [{"text": "Set an alarm for 7:15 am", "when": {"kind": "at", "day": {"relative": 1}, "time": {"hour": 7, "minute": 15, "meridiem": "am", "part": null}, "source": "kal subah sava saat"}}, {"text": "Turn on the flashlight", "when": null}]}"""
        assertThat(Rewrite.parse(content)).containsExactly("Set an alarm for 7:15 am", "Turn on the flashlight").inOrder()
        val whens = Rewrite.whens(content)
        assertThat(whens).hasSize(2)
        assertThat(whens[0]!!.getJSONObject("day").getInt("relative")).isEqualTo(1)
        assertThat(whens[1]).isNull()
        val reply = JSONObject(Rewritten(listOf("a", "b"), "x", "", "m", 1, null, whens = whens).toJson())
        assertThat(reply.getJSONArray("when").getJSONObject(0).getInt("v")).isEqualTo(TimeFormat.VERSION)
        assertThat(reply.getJSONArray("when").isNull(1)).isTrue()
    }

    @Test
    fun `without +when nothing changes in the reply`() {
        assertThat(JSONObject(Rewritten(listOf("a"), "x", "", "m", 1, null).toJson()).has("when")).isFalse()
        assertThat(Rewrite.Variant.of("c2+when").`when`).isTrue()
    }

    @Test
    fun `schema-call enforces one shape per tool, and reads calls with their time`() {
        val tools = org.json.JSONArray("""[{"name": "set_alarm", "description": "Set an alarm", "parameters": {"type": "object", "properties": {"time": {"type": "string"}}, "required": ["time"]}}]""")
        val format = SchemaCall.format(tools, rewrite = true)
        assertThat(format).contains("\"enum\": [\"set_alarm\"]")
        assertThat(format).contains("\"commands\"")
        assertThat(SchemaCall.format(tools, rewrite = false)).doesNotContain("\"commands\"")
        val reply = SchemaCall.reply("""{"calls": [{"tool": "set_alarm", "arguments": {"time": "7:15 am"}, "when": {"kind": "at", "day": null, "time": {"hour": 7, "minute": 15, "meridiem": "am", "part": null}, "source": "7:15 am"}}]}""")
        assertThat(reply.getJSONArray("calls").getJSONObject(0).getJSONObject("when").getInt("v")).isEqualTo(1)
        assertThat(reply.getJSONArray("commands").length()).isEqualTo(0)
    }
}

class TimedShapeTest {
    @Test
    fun `timed puts a trimmed when only in the timed tools`() {
        val tools = org.json.JSONArray("""[{"name": "set_timer", "description": "t", "parameters": {"type": "object", "properties": {}}}, {"name": "set_flashlight", "description": "f", "parameters": {"type": "object", "properties": {}}}]""")
        val format = SchemaCall.format(tools, rewrite = false, timed = true)
        assertThat(format).contains(TimeFormat.TIMER)
        assertThat(format).contains(""""enum": ["set_flashlight"]}, "arguments": {"type":"object","properties":{}}}, "required": ["tool", "arguments"]""")
        assertThat(TimeFormat.forTool("set_alarm")).doesNotContain("part")
        assertThat(TimeFormat.forTool("open_app")).isNull()
        assertThat(Rewrite.datesInCode(Rewrite.Variant.of("c2+when"))).isFalse()
        assertThat(Rewrite.datesInCode(Rewrite.Variant.of("c2"))).isTrue()
    }
}

class TimeAsksTest {
    private fun at(relative: Int?) = org.json.JSONObject().put("kind", "at").put("day", relative?.let { org.json.JSONObject().put("relative", it) } ?: org.json.JSONObject.NULL)

    @Test
    fun `parso asks unless it came back as the day after tomorrow, and never corrects`() {
        assertThat(SchemaCall.asks("parso shaam 6 baje", "add_reminder", at(1))).containsExactly("parso is not the day after tomorrow")
        assertThat(SchemaCall.asks("parso shaam 6 baje", "add_reminder", at(2))).isEmpty()
        assertThat(SchemaCall.asks("kal shaam 6 baje", "add_reminder", at(1))).isEmpty()
    }

    @Test
    fun `a reminder asked to repeat asks when it came back without one`() {
        assertThat(SchemaCall.asks("every Monday at 8 remind me", "add_reminder", at(null))).containsExactly("a repeat was asked for but not given")
        assertThat(SchemaCall.asks("har roz 8 baje yaad dilana", "add_reminder", org.json.JSONObject().put("kind", "every"))).isEmpty()
        assertThat(SchemaCall.asks("remind me daily", "set_alarm", at(null))).isEmpty()
    }
}
