package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

/** [JevClient] against a fake TypeSafe: what's sent, what's read back, and every failure. */
class JevClientTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private var answer: Pair<Int, String> = 200 to ""
    private var delayMs = 0L
    private var sent: JSONObject? = null
    private var sentRaw = ""
    private var auth: String? = null

    private val tools = """
        [{"name": "set_timer", "description": "Start a countdown timer", "parameters": {"type": "object", "properties": {"minutes": {"type": "integer"}}}},
         {"name": "set_alarm", "description": "Set an alarm for a time of day", "parameters": {"type": "object", "properties": {"time": {"type": "string"}}}}]
    """.trimIndent()

    private fun client(trace: ((String) -> Unit)? = null) = JevClient(KEY, "http://127.0.0.1:${server.address.port}", trace = trace)

    private fun choice(choice: String, vararg probabilities: Pair<String, Double>, usage: Boolean = true) = 200 to JSONObject()
        .put("model", "jev-1.13.0")
        .put("answers", JSONObject().put("tool", JSONObject().put("type", "choice").put("choice", choice).put("probabilities", JSONObject(probabilities.toMap())).put("confidence", 0.5)))
        .apply { if (usage) put("usage", JSONObject().put("input_tokens", 180).put("output_tokens", 1)) }
        .toString()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/systemone") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization")
            sentRaw = exchange.requestBody.readAllBytes().decodeToString()
            sent = JSONObject(sentRaw)
            Thread.sleep(delayMs)
            exchange.answer(answer.first, answer.second)
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `sends only the request and the tools' names and descriptions, with none, to the pinned model`() {
        answer = choice("set_timer", "set_timer" to 0.9, "set_alarm" to 0.07, "none" to 0.03)
        client().decide("set a timer for 5 minutes", tools)
        val body = sent!!
        assertThat(auth).isEqualTo("Bearer $KEY")
        assertThat(body.keys().asSequence().toList()).containsExactly("model", "state", "questions")
        assertThat(body.getString("model")).isEqualTo("jev-1.13.0")
        assertThat(body.getString("state")).isEqualTo("set a timer for 5 minutes")
        val question = body.getJSONObject("questions").getJSONObject("tool")
        assertThat(question.getString("type")).isEqualTo("choice")
        val criteria = question.getJSONObject("criteria")
        assertThat(criteria.keys().asSequence().toList()).containsExactly("set_timer", "set_alarm", "none")
        assertThat(criteria.getString("set_alarm")).isEqualTo("Set an alarm for a time of day")
        assertThat(sentRaw).doesNotContain("parameters")
        assertThat(sentRaw).doesNotContain("integer")
    }

    @Test
    fun `the options are shuffled per request, the same way every time, and sent in that order`() {
        val names = (1..15).map { "tool_$it" }
        val firsts = listOf("call mom", "set an alarm", "play music", "what's the weather", "turn on wifi", "open camera")
            .map { JevClient.options(it, names).first() }
        assertThat(firsts.toSet().size).isGreaterThan(1)
        assertThat(JevClient.options("Call Mom ", names)).isEqualTo(JevClient.options("call mom", names))
        assertThat(JevClient.options("call mom", names)).containsExactlyElementsIn(names + "none")

        val options = JevClient.options("x", listOf("b", "a"))
        val body = JevClient.body("x", mapOf("a" to "A", "b" to "B"), options)
        val order = Regex("\"(a|b|none)\":").findAll(body.substringAfter("criteria")).map { it.groupValues[1] }.toList()
        assertThat(order).isEqualTo(options)
    }

    @Test
    fun `an answer gives the choice, every option's probability in the asked order, and the tokens`() {
        answer = choice("set_alarm", "set_timer" to 0.1, "set_alarm" to 0.85, "none" to 0.05)
        val decision = client().decide("wake me at 7", tools)
        assertThat(decision.failure).isNull()
        assertThat(decision.choice).isEqualTo("set_alarm")
        assertThat(decision.probabilities.keys.toList()).isEqualTo(decision.options)
        assertThat(decision.probabilities["set_alarm"]).isEqualTo(0.85)
        assertThat(decision.model).isEqualTo("jev-1.13.0")
        assertThat(decision.billed).isEqualTo(JevClient.Billed(180, estimated = false))
        val json = JSONObject(decision.toJson())
        assertThat(json.getBoolean("success")).isTrue()
        assertThat(json.getString("source")).isEqualTo("jev")
        assertThat(json.getJSONArray("options").length()).isEqualTo(3)
    }

    @Test
    fun `without usage the cost is estimated from the body`() {
        answer = choice("none", "set_timer" to 0.1, "set_alarm" to 0.1, "none" to 0.8, usage = false)
        val decision = client().decide("hello", tools)
        assertThat(decision.billed.estimated).isTrue()
        assertThat(decision.billed.inputTokens).isEqualTo(JevClient.estimateTokens(sentRaw, 3))
    }

    @Test
    fun `each failure has its kind, and the key never reaches the trace`() {
        val lines = mutableListOf<String>()
        val client = client { lines += it }
        fun kind(code: Int, body: String = """{"error":{"message":"nope"}}"""): JevClient.Decision {
            answer = code to body
            return client.decide("set a timer", tools)
        }
        assertThat(kind(401).failure!!.kind).isEqualTo(ErrorKind.Auth)
        assertThat(kind(429).failure!!.short).isEqualTo("Jev rate limited")
        assertThat(kind(529).failure!!.short).isEqualTo("Jev busy")
        assertThat(kind(500).failure!!.kind).isEqualTo(ErrorKind.Server)
        assertThat(kind(422).failure!!.kind).isEqualTo(ErrorKind.BadRequest)
        assertThat(kind(200, "not json").failure!!.kind).isEqualTo(ErrorKind.BadReply)
        assertThat(kind(200, choice("delete_everything", "delete_everything" to 1.0).second).failure!!.kind).isEqualTo(ErrorKind.BadReply)
        assertThat(lines).isNotEmpty()
        assertThat(lines.joinToString()).doesNotContain(KEY)
        assertThat(lines.joinToString()).doesNotContain("set a timer")
    }

    @Test
    fun `slow, unreachable and bad input fail without blocking long`() {
        answer = choice("set_timer", "set_timer" to 1.0)
        delayMs = 2_000
        val slow = client().decide("set a timer", tools, timeoutMs = 1_000)
        assertThat(slow.failure!!.kind).isEqualTo(ErrorKind.Timeout)
        assertThat(slow.billed.estimated).isTrue()

        val closed = JevClient(KEY, "http://127.0.0.1:9").decide("set a timer", tools)
        assertThat(closed.failure!!.kind).isEqualTo(ErrorKind.Unreachable)
        assertThat(closed.billed.inputTokens).isEqualTo(0)

        assertThat(client().decide("set a timer", "{}").failure!!.kind).isEqualTo(ErrorKind.BadRequest)
        assertThat(client().decide(" ", tools).failure!!.kind).isEqualTo(ErrorKind.BadRequest)
        assertThat(client().decide("x", """[{"name": "none"}]""").failure!!.kind).isEqualTo(ErrorKind.BadRequest)
    }

    @Test
    fun `the ledger adds every call, keeps it across runs, and holds the tests to their cap`() {
        val file = folder.root.resolve("jev-spend.json")
        val ledger = JevLedger(file, capUsd = 0.001)
        assertThat(ledger.allows(0.001)).isTrue()
        ledger.record(JevClient.Billed(10_000, estimated = false))
        ledger.record(JevClient.Billed(5_000, estimated = true))
        ledger.endRun("smoke.jsonl", 2, 15_000, 15_000 * JevClient.USD_PER_INPUT_TOKEN, null)
        val again = JevLedger(file, capUsd = 0.001)
        assertThat(again.calls).isEqualTo(2)
        assertThat(again.inputTokens).isEqualTo(15_000)
        assertThat(again.usd).isWithin(1e-12).of(0.00063)
        assertThat(again.allows(0.0003)).isTrue()
        assertThat(again.allows(0.0004)).isFalse()
        assertThat(JSONObject(file.readText()).getJSONArray("runs").length()).isEqualTo(1)
    }

    private fun HttpExchange.answer(code: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val KEY = "ts_test_key_never_logged_0123456789"
    }
}
