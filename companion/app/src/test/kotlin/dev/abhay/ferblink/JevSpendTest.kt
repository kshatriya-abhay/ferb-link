package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executor

/** The phone's Jev spend: every call counted, and Jev off by itself at the cap, sending nothing more. */
class JevSpendTest {
    private lateinit var server: HttpServer
    private var calls = 0

    private inner class State : LinkState {
        override val enabled = true
        override val configured = true
        override var lastModel = ""
        override var audio = false
        override var modelState = ""
        override var modelStateAt = 0L
        override fun client(trace: ((String) -> Unit)?) = ServerClient("http://127.0.0.1:9", "key")
        override var jevOn = true
        override val jevConfigured = true
        override var jevOffReason = ""
        override var jevSpentUsd = 0.0
        override var jevCapUsd = 1_000 * JevClient.USD_PER_INPUT_TOKEN * 2.5
        override fun jev(trace: ((String) -> Unit)?) = JevClient("key", "http://127.0.0.1:${server.address.port}")
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/systemone") { exchange ->
            calls++
            exchange.requestBody.readAllBytes()
            val body = JSONObject().put("model", "jev-1.13.0")
                .put("answers", JSONObject().put("tool", JSONObject().put("choice", "none").put("probabilities", JSONObject().put("none", 1.0))))
                .put("usage", JSONObject().put("input_tokens", 1_000)).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `each call adds to the spend, and at the cap Jev turns itself off and says why`() {
        val state = State()
        val calls = LinkCalls(state, Executor { it.run() }, debug = false)
        fun decide() = JSONObject(calls.answer("decide", mapOf("request" to "hello", "tools" to "[]"), "test")["reply"] as String)

        assertThat(decide().getBoolean("success")).isTrue()
        assertThat(state.jevSpentUsd).isWithin(1e-12).of(1_000 * JevClient.USD_PER_INPUT_TOKEN)
        assertThat(decide().getBoolean("success")).isTrue()
        assertThat(state.jevOn).isTrue()
        // The third call passes the cap (2.5 calls' worth): it's answered, then Jev turns off.
        assertThat(decide().getBoolean("success")).isTrue()
        assertThat(state.jevOn).isFalse()
        assertThat(state.jevOffReason).isEqualTo("cap")
        assertThat(this.calls).isEqualTo(3)

        val refused = decide()
        assertThat(refused.getString("error_kind")).isEqualTo("off")
        assertThat(refused.getString("error_detail")).contains("spending limit")
        assertThat(this.calls).isEqualTo(3)

        val status = calls.answer("status", emptyMap(), "test")
        assertThat(status["jev"]).isEqualTo(false)
        assertThat(status["jev_off_reason"]).isEqualTo("cap")
    }
}
