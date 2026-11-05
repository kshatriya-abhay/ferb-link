package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class InstructedTest {
    @Test
    fun `a first line that ran into the cap is cut off`() {
        assertThat(Instructed.cutOff("Send a message to bhaiya: I", "length")).isTrue()
        assertThat(Instructed.cutOff("Command: \"Set an alarm for", "length")).isTrue()
    }

    @Test
    fun `a finished answer, a cut later line, or NONE is whole`() {
        assertThat(Instructed.cutOff("Send a message to bhaiya: I am late", "stop")).isFalse()
        assertThat(Instructed.cutOff("Send a message to bhaiya: I am late", null)).isFalse()
        assertThat(Instructed.cutOff("Turn on the flashlight\nThe user wants", "length")).isFalse()
        assertThat(Instructed.cutOff("NONE", "length")).isFalse()
        assertThat(Instructed.cutOff("torch -> none.", "length")).isFalse()
        assertThat(Instructed.cutOff("  ", "length")).isFalse()
    }

    @Test
    fun `a schema goes as response_format, and only when given`() {
        val schema = JSONObject("""{"type": "object", "properties": {"command": {"type": "string"}}}""")
        val body = JSONObject(Instructed.body("m", "x", "torch on", 40, schema))
        val format = body.getJSONObject("response_format")
        assertThat(format.getString("type")).isEqualTo("json_schema")
        assertThat(format.getJSONObject("json_schema").getJSONObject("schema").toString()).isEqualTo(schema.toString())
        assertThat(JSONObject(Instructed.body("m", "x", "torch on", 40)).has("response_format")).isFalse()
    }

    @Test
    fun `the token cap defaults higher with a schema, and is clamped either way`() {
        val schema = LinkContract.EXTRA_JSON_SCHEMA to """{"type": "object"}"""
        assertThat(Instructed.maxTokens(emptyMap())).isEqualTo(LinkContract.REWRITE_MAX_TOKENS)
        assertThat(Instructed.maxTokens(mapOf(schema))).isEqualTo(LinkContract.REWRITE_JSON_MAX_TOKENS)
        assertThat(Instructed.maxTokens(mapOf(schema, LinkContract.EXTRA_MAX_TOKENS to 900))).isEqualTo(512)
        assertThat(Instructed.maxTokens(mapOf(LinkContract.EXTRA_MAX_TOKENS to 0))).isEqualTo(1)
        assertThat(Instructed.maxTokens(mapOf(LinkContract.EXTRA_JSON_SCHEMA to "  "))).isEqualTo(LinkContract.REWRITE_MAX_TOKENS)
    }

    @Test
    fun `a schema must be a JSON object, and blank is none`() {
        assertThat(Instructed.schema(emptyMap())).isNull()
        assertThat(Instructed.schema(mapOf(LinkContract.EXTRA_JSON_SCHEMA to " "))).isNull()
        assertThat(Instructed.schema(mapOf(LinkContract.EXTRA_JSON_SCHEMA to """{"type": "object"}"""))!!.getString("type")).isEqualTo("object")
        for (bad in listOf("[1]", "{\"type\": ", "object")) {
            assertThat(runCatching { Instructed.schema(mapOf(LinkContract.EXTRA_JSON_SCHEMA to bad)) }.isFailure).isTrue()
        }
    }

    @Test
    fun `a JSON answer is one whole object`() {
        assertThat(Instructed.parseJson(" {\"command\": \"Turn on the flashlight\"}\n").getString("command")).isEqualTo("Turn on the flashlight")
        for (bad in listOf("Turn on the flashlight", "{\"command\": \"Turn on", "[{}]", "{} and more", "")) {
            assertThat(runCatching { Instructed.parseJson(bad) }.isFailure).isTrue()
        }
    }
}
