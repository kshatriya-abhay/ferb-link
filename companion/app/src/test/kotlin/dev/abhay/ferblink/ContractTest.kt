package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.ln
import kotlin.random.Random

/**
 * The LinkContract fixtures (`contract/v1/`, one JSON file per case; Ferb keeps a copy): for each case, [LinkCalls]
 * given the fixture's `extras` returns exactly its `result`, against a fake llama-server. Ferb's
 * tests read the same files from the other side.
 *
 * A fixture is {"method", "extras", "result", "note"}. In `result`, `reply` is written as an object
 * (it travels as a JSON string); `ms`, `confidence`, `first` and `mean` are example values, compared
 * by type. The `audio` extra is {"bytes": "speech"} or {"bytes": "silence"}: a recording of that,
 * sent as a ByteArray. A method Ferb Link doesn't know has `"result": {"throws": "IllegalArgumentException"}`.
 * `"synthetic": true` marks a case this version can't produce (an older or newer Ferb Link), for
 * Ferb's side only.
 *
 * `-Pcontract.write=true` (`mise run companion:contract-write`) rewrites the files from what
 * [LinkCalls] returns, for a contract change; review the diff before committing it.
 */
class ContractTest {
    private lateinit var server: HttpServer
    private var chatReply: Pair<Int, String> = 200 to ""
    private var chatDelayMs = 0L
    @Volatile private var chatBody = ""
    private var jevReply: Pair<Int, String> = 200 to ""
    private var jevDelayMs = 0L

    private val url get() = "http://127.0.0.1:${server.address.port}"

    private class State(
        override var enabled: Boolean = true,
        var url: String = "",
        var key: String = "secret",
        override var lastModel: String = "gemma-4-e2b",
        override var audio: Boolean = true,
        override var modelState: String = LinkContract.MODEL_LOADED,
        override var modelStateAt: Long = NOW - 60_000,
        override var jevOn: Boolean = false,
        var jevKey: String = "",
        override var jevOffReason: String = "",
        override var jevSpentUsd: Double = 0.0,
        override var jevCapUsd: Double = 3.0,
    ) : LinkState {
        override val configured get() = url.isNotEmpty() && key.isNotEmpty()
        override val jevConfigured get() = jevKey.isNotEmpty()
        override fun client(trace: ((String) -> Unit)?) = ServerClient(url, key, model = lastModel, trace = trace)
        // The fake TypeSafe is on the same test server as the fake llama-server.
        override fun jev(trace: ((String) -> Unit)?) = JevClient(jevKey, url.ifEmpty { CLOSED }, trace = trace)
        fun jevReady() { jevOn = true; jevKey = "ts-secret" }
    }

    /** One fixture: how the laptop is set up, and what Ferb sends. */
    private class Case(
        val name: String,
        val method: String,
        val note: String,
        val extras: Map<String, Any?> = emptyMap(),
        val setUp: ContractTest.(State) -> Unit = {},
    )

    private val cases = listOf(
        Case("status", "status", "On, set up, last saw gemma-4-e2b, which takes audio."),
        Case("status-asleep", "status", "Last seen loaded over 270 s ago: the server has likely put it to sleep, so a request may wait for a cold load.") {
            it.modelStateAt = NOW - LinkContract.LOADED_FOR_MS - 1
        },
        Case("status-off", "status", "Turned off in Ferb Link.") { it.enabled = false },
        Case("status-not-set-up", "status", "No server set up yet: no model seen.") { it.url = ""; it.lastModel = ""; it.audio = false; it.modelState = "" },
        Case("check", "check", "The laptop answers, in full mode (takes audio)."),
        Case("check-unreachable", "check", "Nothing answers at the laptop's address.") { it.url = CLOSED },
        Case("check-auth", "check", "The laptop turns the API key down.") { it.key = "wrong" },
        Case("check-off", "check", "Turned off: no network, reachable false.") { it.enabled = false },
        Case("wake", "wake", "Returns at once; the model loads in the background if it's asleep."),
        Case("complete", "complete", "A typed Hinglish alarm: Ferb Link sends \"shaam 7:00 pm ka alarm lagao\" and the model calls set_alarm.",
            mapOf("request" to "shaam 7 baje ka alarm lagao", "tools" to TOOLS, "facts" to "date: 2026-10-14 (Wednesday)", "timeout_ms" to 10_000)) {
            chatReply = 200 to CALL
        },
        Case("complete-no-call", "complete", "The model answers in words and calls no tool.",
            mapOf("request" to "what's the capital of France", "tools" to TOOLS)) {
            chatReply = 200 to """{"model": "gemma-4-e2b", "choices": [{"message": {"content": "I can't do that."}}]}"""
        },
        Case("complete-off", "complete", "Turned off in Ferb Link: no network.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) { it.enabled = false },
        Case("complete-not_set_up", "complete", "No server set up.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) { it.url = "" },
        Case("complete-bad_request", "complete", "No request.", mapOf("request" to "", "tools" to TOOLS)),
        Case("complete-bad_request-tools", "complete", "Tools that aren't a JSON array.", mapOf("request" to "set a timer for 5 minutes", "tools" to "{}")),
        Case("complete-unreachable", "complete", "Nothing answers at the laptop's address.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) { it.url = CLOSED },
        Case("complete-timeout", "complete", "No answer within timeout_ms (1000, the least it takes).",
            mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS, "timeout_ms" to 500)) {
            chatReply = 200 to CALL
            chatDelayMs = 1_500
        },
        Case("complete-auth", "complete", "The laptop turns the API key down.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            it.key = "wrong"
            chatReply = 401 to """{"error": {"code": 401, "message": "Invalid API Key"}}"""
        },
        Case("complete-server", "complete", "The model is still loading (HTTP 503).", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            chatReply = 503 to """{"error": {"code": 503, "message": "Loading model"}}"""
        },
        Case("complete-bad_reply", "complete", "An answer that isn't the expected JSON.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            chatReply = 200 to "<html>not JSON</html>"
        },
        Case("transcribe", "transcribe", "Hinglish: heard in Latin letters, text in English, with sava saat worked out as 7:15 and subah as am.",
            mapOf("audio" to SPEECH, "audio_mime" to "audio/ogg")) {
            chatReply = 200 to transcript(hindi = true, heard = "subah sava saat baje utha dena", text = "Wake me up at 7 AM")
        },
        Case("transcribe-english", "transcribe", "English: text is what was heard.", mapOf("audio" to SPEECH, "audio_mime" to "audio/ogg", "timeout_ms" to 10_000)) {
            chatReply = 200 to transcript(hindi = false, heard = "set a timer for ten minutes", text = "Set a timer for 10 minutes")
        },
        Case("transcribe-no-speech", "transcribe", "Silence: success, with empty heard and text, and no request to the laptop.",
            mapOf("audio" to SILENCE, "audio_mime" to "audio/ogg")),
        Case("transcribe-bad_request", "transcribe", "No recording.", mapOf("audio_mime" to "audio/ogg")),
        Case("transcribe-server", "transcribe", "The server is in text mode and turns audio down.", mapOf("audio" to SPEECH, "audio_mime" to "audio/ogg")) {
            chatReply = 500 to """{"error": {"code": 500, "message": "audio input is not supported - hint: if this is unexpected, you may need to provide the mmproj"}}"""
        },
        Case("rewrite", "rewrite", "Typed Hinglish as one English command; sava saat worked out as 7:15 before the model.",
            mapOf("request" to "kal subah sava saat baje ka alarm laga do", "now" to "2026-10-16T09:00 (Friday)", "timeout_ms" to 10_000)) {
            chatReply = 200 to commands("Set an alarm for 7:15 am on Saturday 17 October")
        },
        Case("rewrite-two", "rewrite", "Two things asked: two commands, in order.", mapOf("request" to "torch on karo aur volume badhao", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 200 to commands("Turn on the flashlight", "Turn the volume up")
        },
        Case("rewrite-none", "rewrite", "Nothing asked: no commands.", mapOf("request" to "aaj mausam kaisa hai", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 200 to commands()
        },
        Case("rewrite-invented", "rewrite", "A time the request didn't hold: listed in invented, so Ferb doesn't run it.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 200 to commands("Set an alarm for 7 am")
        },
        Case("rewrite-off", "rewrite", "Turned off in Ferb Link: no network.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) { it.enabled = false },
        Case("rewrite-not_set_up", "rewrite", "No server set up.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) { it.url = "" },
        Case("rewrite-bad_request", "rewrite", "No request.", mapOf("request" to "", "now" to "2026-10-16T09:00 (Friday)")),
        Case("rewrite-unreachable", "rewrite", "Nothing answers at the laptop's address.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) { it.url = CLOSED },
        Case("rewrite-timeout", "rewrite", "No answer within timeout_ms (1000, the least it takes).", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)", "timeout_ms" to 1000)) {
            chatReply = 200 to commands("Set an alarm"); chatDelayMs = 1_500
        },
        Case("rewrite-auth", "rewrite", "The laptop turns the API key down.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 401 to """{"error": {"code": 401, "message": "Invalid API Key"}}"""
        },
        Case("rewrite-server", "rewrite", "The model is still loading (HTTP 503).", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 503 to """{"error": {"code": 503, "message": "Loading model"}}"""
        },
        Case("rewrite-bad_reply", "rewrite", "An answer that isn't the commands' JSON.", mapOf("request" to "alarm laga do", "now" to "2026-10-16T09:00 (Friday)")) {
            chatReply = 200 to """{"model": "gemma-4-e2b", "choices": [{"message": {"content": "Sure, I'll set it."}}]}"""
        },
        Case("rewrite-gemini", "rewrite", "With Ferb's own prompt (instructions): one command, and raw for Ferb's own parsing. No pre-pass: the request goes as it is.",
            mapOf("request" to "open the nfc settings", "instructions" to INSTRUCTIONS, "max_tokens" to 40, "timeout_ms" to 10_000)) {
            chatReply = 200 to timedAnswer("Open NFC settings")
        },
        Case("rewrite-gemini-none", "rewrite", "With Ferb's own prompt: NONE, so no commands.",
            mapOf("request" to "the alarm woke the baby", "instructions" to INSTRUCTIONS, "max_tokens" to 40)) {
            chatReply = 200 to timedAnswer("NONE")
        },
        Case("rewrite-gemini-bad_reply", "rewrite", "With Ferb's own prompt: an empty answer.",
            mapOf("request" to "alarm laga do", "instructions" to INSTRUCTIONS, "max_tokens" to 40)) {
            chatReply = 200 to timedAnswer("  \n ")
        },
        Case("rewrite-gemini-bad_request", "rewrite", "With Ferb's own prompt: no request.",
            mapOf("request" to "", "instructions" to INSTRUCTIONS, "max_tokens" to 40)),
        Case("complete-timed", "complete", "time_format 1: the alarm's time as structure (time v1), as said: no day said, no am/pm.",
            mapOf("request" to "wake me up at 6:45", "tools" to TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": [{"tool": "set_alarm", "arguments": {"time": "6:45"}, "when": {"kind": "at", "day": null, "time": {"hour": 6, "minute": 45, "meridiem": null}}}]}""")
        },
        Case("complete-timed-in", "complete", "time_format 1: after a length, never a worked-out clock time.",
            mapOf("request" to "alarm in 20 minutes", "tools" to TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": [{"tool": "set_alarm", "arguments": {"time": "in 20 minutes"}, "when": {"kind": "in", "length": {"days": 0, "hours": 0, "minutes": 20, "seconds": 0}}}]}""")
        },
        Case("complete-timed-every", "complete", "time_format 1: a repeat, period and weekdays.",
            mapOf("request" to "remind me every Monday at 8 to take out the bins", "tools" to REMINDER_TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": [{"tool": "add_reminder", "arguments": {"text": "take out the bins"}, "when": {"kind": "every", "period": "week", "on": ["monday"], "time": {"hour": 8, "minute": 0, "meridiem": null, "part": null}}}]}""")
        },
        Case("complete-timed-asks", "complete", "time_format 1: parso came back as tomorrow; Ferb Link's check asks (it never corrects).",
            mapOf("request" to "parso shaam 6 baje doctor ki yaad dilana", "tools" to REMINDER_TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": [{"tool": "add_reminder", "arguments": {"text": "doctor"}, "when": {"kind": "at", "day": {"relative": 1}, "time": {"hour": 6, "minute": 0, "meridiem": "pm", "part": "evening"}}}]}""")
        },
        Case("complete-timed-trimmed", "complete", "time_format 1: the answer's closing leaked into a string argument; it's cut there and trimmed is true, so Ferb asks.",
            mapOf("request" to "open maps", "tools" to APP_TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": [{"tool": "open_app", "arguments": {"app_name": "Maps}}]}```"}, "when": null}]}""")
        },
        Case("complete-timed-none", "complete", "time_format 1: no tool fits.", mapOf("request" to "what's the capital of Peru", "tools" to TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to timedAnswer("""{"calls": []}""")
        },
        Case("complete-timed-timeout", "complete", "time_format 1: no answer within timeout_ms (1000, the least it takes).",
            mapOf("request" to "wake me up at 6:45", "tools" to TOOLS, "time_format" to 1, "timeout_ms" to 1000)) {
            chatReply = 200 to timedAnswer("""{"calls": []}"""); chatDelayMs = 1_500
        },
        Case("complete-timed-bad_reply", "complete", "time_format 1: an answer that isn't the enforced JSON.",
            mapOf("request" to "wake me up at 6:45", "tools" to TOOLS, "time_format" to 1, "timeout_ms" to 6000)) {
            chatReply = 200 to """{"model": "gemma-4-e2b", "choices": [{"message": {"content": "Sure, alarm set."}}]}"""
        },
        Case("status-jev", "status", "Jev is on and has a key: decide may answer.") { it.jevReady() },
        Case("status-jev-cap", "status", "Jev turned itself off at its spending cap.") { it.jevOffReason = LinkContract.JEV_OFF_CAP },
        Case("decide", "decide", "Jev picks set_alarm; every option's probability, in the order asked (shuffled per request).",
            mapOf("request" to "shaam 7 baje ka alarm lagao", "tools" to TOOLS, "timeout_ms" to 3000)) {
            it.jevReady(); jevReply = jevAnswer("set_alarm", "set_alarm" to 0.97, "none" to 0.03)
        },
        Case("decide-none", "decide", "No tool fits: Jev says none.", mapOf("request" to "what's the capital of France", "tools" to TOOLS)) {
            it.jevReady(); jevReply = jevAnswer("none", "set_alarm" to 0.0, "none" to 1.0)
        },
        Case("decide-off", "decide", "Jev is off in Ferb Link (its default): nothing is sent.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)),
        Case("decide-cap", "decide", "Jev reached its spending cap and turned itself off: nothing is sent.",
            mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) { it.jevReady(); it.jevSpentUsd = 3.0 },
        Case("decide-not_set_up", "decide", "Jev is on but has no key.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) { it.jevOn = true },
        Case("decide-bad_request", "decide", "No request.", mapOf("request" to "", "tools" to TOOLS)) { it.jevReady() },
        Case("decide-auth", "decide", "TypeSafe turns the key down.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            it.jevReady(); jevReply = 401 to """{"error":{"message":"invalid key"}}"""
        },
        Case("decide-unreachable", "decide", "TypeSafe can't be reached.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            it.jevReady(); it.url = ""
        },
        Case("decide-timeout", "decide", "No answer within timeout_ms (1000, the least it takes).",
            mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS, "timeout_ms" to 1000)) {
            it.jevReady(); jevReply = jevAnswer("set_alarm", "set_alarm" to 1.0, "none" to 0.0); jevDelayMs = 1_500
        },
        Case("decide-server", "decide", "TypeSafe's rate limit (HTTP 429).", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            it.jevReady(); jevReply = 429 to """{"error":{"message":"rate limited"}}"""
        },
        Case("decide-bad_reply", "decide", "An answer that isn't Jev's JSON.", mapOf("request" to "set a timer for 5 minutes", "tools" to TOOLS)) {
            it.jevReady(); jevReply = 200 to "<html>gateway</html>"
        },
        Case("unknown-method", "summarize", "A method this Ferb Link doesn't have (an older one, for a newer Ferb)."),
    )

    private val dir = File(System.getProperty("contract.dir") ?: "../../contract/v1")
    private val write = System.getProperty("contract.write") == "true"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { it.answer(200, """{"status":"ok"}""") }
        server.createContext("/v1/models") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer secret") exchange.answer(401, """{"error":{"message":"Invalid API Key"}}""")
            else exchange.answer(200, """{"data":[{"id":"gemma-4-e2b","status":{"value":"loaded"}}]}""")
        }
        server.createContext("/props") { it.answer(200, """{"is_sleeping": false, "modalities": {"vision": true, "audio": true}}""") }
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(jevDelayMs)
            exchange.answer(jevReply.first, jevReply.second)
        }
        server.createContext("/v1/chat/completions") { exchange ->
            chatBody = String(exchange.requestBody.readAllBytes())
            Thread.sleep(chatDelayMs)
            exchange.answer(chatReply.first, chatReply.second)
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = runCatching { server.stop(0) }.let {}

    @Test
    fun `Ferb Link answers each fixture's extras with its result`() {
        if (write) dir.mkdirs()
        for (case in cases) {
            chatReply = 200 to ""
            chatDelayMs = 0
            jevReply = 200 to ""
            jevDelayMs = 0
            val state = State(url = url)
            case.setUp(this, state)
            val result = run(case, state)
            val file = File(dir, "${case.name}.json")
            if (write) {
                val fixture = JSONObject().put("method", case.method).put("extras", toJson(case.extras)).put("result", result).put("note", case.note)
                file.writeText(ordered(fixture) + "\n")
                continue
            }
            assertWithMessage("${file.path} is missing (mise run companion:contract-write)").that(file.exists()).isTrue()
            val fixture = JSONObject(file.readText())
            assertWithMessage("${case.name}: method").that(fixture.getString("method")).isEqualTo(case.method)
            assertWithMessage("${case.name}: extras").that(canonical(fixture.getJSONObject("extras"))).isEqualTo(canonical(toJson(case.extras)))
            assertSame(case.name, fixture.getJSONObject("result"), result)
        }
    }

    @Test
    fun `an instructed rewrite sends the instructions and request as they are, greedy and capped`() {
        assumeFalse("rewriting the fixtures", write)
        chatReply = 200 to timedAnswer("Command: \"Turn on the flashlight\"\nextra")
        val result = run(Case("x", "rewrite", "", mapOf("request" to "torch on karo", "instructions" to INSTRUCTIONS, "max_tokens" to 999, "now" to "2026-10-16T09:00 (Friday)")), State(url = url))
        val body = JSONObject(chatBody)
        val messages = body.getJSONArray("messages")
        assertThat(messages.length()).isEqualTo(2)
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo(INSTRUCTIONS)
        assertThat(messages.getJSONObject(1).getString("content")).isEqualTo("torch on karo")
        assertThat(body.getInt("temperature")).isEqualTo(0)
        assertThat(body.getInt("top_k")).isEqualTo(1)
        assertThat(body.getInt("max_tokens")).isEqualTo(LinkContract.MAX_REWRITE_TOKENS)
        assertThat(body.has("response_format")).isFalse()
        val reply = result.getJSONObject("reply")
        assertThat(reply.getJSONArray("commands").getString(0)).isEqualTo("Turn on the flashlight")
        assertThat(reply.getBoolean("instructions")).isTrue()
        assertThat(reply.getString("raw")).startsWith("Command:")

        val long = run(Case("x", "rewrite", "", mapOf("request" to "torch on karo", "instructions" to "x".repeat(LinkContract.MAX_INSTRUCTIONS_CHARS + 1))), State(url = url))
            .getJSONObject("reply")
        assertThat(long.getString("error_kind")).isEqualTo("bad_request")
        assertThat(long.getBoolean("instructions")).isTrue()
    }

    @Test
    fun `every fixture is a case here, or synthetic`() {
        assumeFalse("rewriting the fixtures", write)
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertThat(files).isNotEmpty()
        for (file in files) {
            val fixture = JSONObject(file.readText())
            if (fixture.optBoolean("synthetic")) continue
            assertWithMessage("${file.name} has no case in ContractTest").that(cases.map { "${it.name}.json" }).contains(file.name)
        }
    }

    @Test
    fun `every error kind has a fixture`() {
        assumeFalse("rewriting the fixtures", write)
        val kinds = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            val result = JSONObject(file.readText()).getJSONObject("result")
            result.optJSONObject("reply")?.optString("error_kind")?.ifEmpty { null } ?: result.optString("error_kind").ifEmpty { null }
        }.toSet()
        assertThat(kinds).containsExactlyElementsIn(LinkContract.ERROR_KINDS)
    }

    /** What the provider would put in its Bundle, as JSON: `reply` parsed, a throw as {"throws"}. */
    private fun run(case: Case, state: State): JSONObject {
        val extras = case.extras.mapValues { (_, value) -> if (value is Map<*, *>) recording(value["bytes"] as String) else value }
        val calls = LinkCalls(state, Executor { it.run() }, debug = false, decode = { audio, _ -> audio }, now = { NOW })
        val result = try {
            calls.answer(case.method, extras, "dev.abhay.assistant")
        } catch (e: IllegalArgumentException) {
            return JSONObject().put("throws", e.javaClass.simpleName)
        }
        return JSONObject().apply {
            result.forEach { (key, value) -> put(key, if (key == LinkContract.RESULT_REPLY) JSONObject(value as String) else value) }
        }
    }

    private fun assertSame(name: String, expected: JSONObject, actual: JSONObject) {
        assertWithMessage("$name: keys").that(actual.keyList()).containsExactlyElementsIn(expected.keyList())
        for (key in expected.keyList()) {
            val want = expected.get(key)
            val got = actual.get(key)
            when {
                want is JSONObject -> assertSame("$name.$key", want, got as JSONObject)
                key in BY_TYPE -> assertWithMessage("$name.$key is a number").that(got).isInstanceOf(Number::class.java)
                want is JSONArray -> assertWithMessage("$name.$key").that(canonical(got as JSONArray)).isEqualTo(canonical(want))
                want is Number -> assertWithMessage("$name.$key").that((got as Number).toDouble()).isEqualTo(want.toDouble())
                else -> assertWithMessage("$name.$key").that(got).isEqualTo(want)
            }
        }
    }

    private fun toJson(extras: Map<String, Any?>) = JSONObject().apply { extras.forEach { (k, v) -> put(k, if (v is Map<*, *>) JSONObject(v) else v) } }

    private fun HttpExchange.answer(code: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        /** A port nothing listens on. */
        const val CLOSED = "http://127.0.0.1:9"

        // A fixed clock: model_state ages against it.
        const val NOW = 1_800_000_000_000L
        val SPEECH = mapOf("bytes" to "speech")
        val SILENCE = mapOf("bytes" to "silence")
        val BY_TYPE = setOf("ms", "confidence", "first", "mean")

        const val TOOLS = """[{"name": "set_alarm", "description": "Set an alarm", "parameters": {"type": "object", "properties": {"time": {"type": "string", "description": "The time, such as 7:30 am"}}, "required": ["time"]}}]"""

        val CALL = """
            {"model": "gemma-4-e2b", "choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [
              {"type": "function", "function": {"name": "set_alarm", "arguments": "{\"time\": \"7:00 pm\"}"}}]},
             "logprobs": {"content": [{"token": "a", "logprob": ${ln(0.98)}}, {"token": "b", "logprob": ${ln(0.9)}}]}}]}
        """.trimIndent()

        fun transcript(hindi: Boolean, heard: String, text: String) =
            JSONObject().put("model", "gemma-4-e2b").put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content",
                """{"speech": true, "hindi": $hindi, "heard": "$heard", "text": "$text"}""")))).toString()

        /** 2 s of 16 kHz WAV: syllable-like bursts for "speech", zeros for "silence". */
        /** The laptop's model answering a time_format call: [content] (the enforced JSON) as the message's text. */
        fun timedAnswer(content: String) = JSONObject().put("model", "gemma-4-e2b")
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()

        /** A stand-in for Ferb's GeminiRewrite prompt, which Ferb sends itself. */
        const val INSTRUCTIONS = "Rewrite the user's request as one short English command for a phone assistant. If it asks for nothing, write NONE."

        const val REMINDER_TOOLS = """[{"name": "add_reminder", "description": "Remind the user of something at a time", "parameters": {"type": "object", "properties": {"text": {"type": "string", "description": "What to remind about"}}, "required": ["text"]}}]"""
        const val APP_TOOLS = """[{"name": "open_app", "description": "Open an app by its name", "parameters": {"type": "object", "properties": {"app_name": {"type": "string"}}, "required": ["app_name"]}}]"""

        /** What the laptop's model answers to a rewrite: {"commands": [...]} as the message's text. */
        fun commands(vararg commands: String) = JSONObject()
            .put("model", "gemma-4-e2b")
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONObject().put("commands", JSONArray(commands.toList())).toString()))))
            .toString()

        /** What TypeSafe answers for one Choice (its API reference), with usage. */
        fun jevAnswer(choice: String, vararg probabilities: Pair<String, Double>) = 200 to JSONObject()
            .put("model", "jev-1.13.0")
            .put("answers", JSONObject().put("tool", JSONObject().put("type", "choice").put("choice", choice)
                .put("probabilities", JSONObject(probabilities.toMap())).put("confidence", 0.9)))
            .put("usage", JSONObject().put("input_tokens", 370).put("output_tokens", 1))
            .toString()

        fun recording(kind: String): ByteArray {
            val random = Random(7)
            val samples = ShortArray(32_000) { i ->
                if (kind == "silence") 0 else (random.nextDouble(-1.0, 1.0) * if (i % 4_000 < 1_920) 12_000 else 600).toInt().toShort()
            }
            return AudioDecoder.wav(samples, 16_000)
        }

        fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

        /** [value] as text with its keys sorted, to compare JSON (Android's org.json has no `similar`). */
        fun canonical(value: Any): String = when (value) {
            is JSONObject -> value.keyList().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number -> value.toDouble().toString()
            else -> value.toString()
        }

        /** [fixture] with its keys in reading order (org.json on the JVM doesn't keep them). */
        fun ordered(fixture: JSONObject): String {
            val order = listOf("method", "synthetic", "note", "extras", "result")
            val parts = order.filter { fixture.has(it) }.map { key -> "  \"$key\": ${pretty(fixture.get(key), "  ")}" }
            return "{\n" + parts.joinToString(",\n") + "\n}"
        }

        private fun pretty(value: Any, indent: String): String = when (value) {
            is JSONObject -> if (value.length() == 0) "{}" else value.keyList().sorted().joinToString(",\n", "{\n", "\n$indent}") { key: String ->
                "$indent  ${JSONObject.quote(key)}: ${pretty(value.get(key), "$indent  ")}"
            }
            is String -> JSONObject.quote(value)
            else -> JSONObject.wrap(value).toString()
        }
    }
}
