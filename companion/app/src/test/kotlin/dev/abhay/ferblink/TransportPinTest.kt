package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.net.InetSocketAddress

/** health's and unload's failures as they are, pinned before the transport has one failure style. */
class TransportPinTest {
    private fun server(vararg routes: Pair<String, Pair<Int, String>>, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        for ((path, answer) in routes) server.createContext(path) { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = answer.second.toByteArray()
            exchange.sendResponseHeaders(answer.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `health while the model loads, with an unreadable model list, and with nothing there`() {
        server("/health" to (503 to """{"error":{"message":"Loading model"}}""")) { url ->
            val health = ServerClient(url, "secret").health(2_000)
            assertThat(health.failure).isEqualTo(Failure(ErrorKind.Server, "Your computer is loading the model", "model loading"))
            assertThat(health.model).isNull()
        }
        server("/health" to (200 to """{"status":"ok"}"""), "/v1/models" to (200 to "not json")) { url ->
            val failure = ServerClient(url, "secret").health(2_000).failure!!
            assertThat(failure.kind).isEqualTo(ErrorKind.BadReply)
            assertThat(failure.message).startsWith("Couldn't read your computer's answer: ")
        }
        server("/health" to (200 to """{"status":"ok"}"""), "/v1/models" to (401 to """{"error":{"message":"Invalid API Key"}}""")) { url ->
            assertThat(ServerClient(url, "wrong").health(2_000).failure).isEqualTo(Failure(ErrorKind.Auth, "Your computer turned down the API key", ErrorKind.Auth.short))
        }
        val failure = ServerClient("http://127.0.0.1:9", "secret").health(2_000).failure!!
        assertThat(failure.kind).isEqualTo(ErrorKind.Unreachable)
        assertThat(failure.message).isEqualTo(LlamaHttp.SERVER_STOPPED)
    }

    @Test
    fun `unload with a wrong key, with nothing there, and with an unreadable answer`() {
        server("/models/unload" to (401 to """{"error":{"message":"Invalid API Key"}}""")) { url ->
            assertThat(ServerClient(url, "wrong", model = "gemma-4-e2b").unload(2_000)).isEqualTo(Failure(ErrorKind.Auth, "Your computer turned down the API key", ErrorKind.Auth.short))
        }
        assertThat(ServerClient("http://127.0.0.1:9", "secret", model = "gemma-4-e2b").unload(2_000)?.kind).isEqualTo(ErrorKind.Unreachable)
        server("/v1/models" to (200 to "not json")) { url ->
            val failure = ServerClient(url, "secret").unload(2_000)!!
            assertThat(failure.kind).isEqualTo(ErrorKind.BadReply)
            assertThat(failure.message).startsWith("Couldn't read your computer's answer: ")
        }
    }

    @Test
    fun `a check reads the model list once`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var listed = 0
        fun answer(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/health") { answer(it, """{"status":"ok"}""") }
        server.createContext("/v1/models") { listed++; answer(it, """{"data":[{"id":"gemma-4-e2b","status":{"value":"loaded"}}]}""") }
        server.createContext("/props") { answer(it, """{"is_sleeping": false, "modalities": {"audio": true}}""") }
        server.start()
        try {
            val health = ServerClient("http://127.0.0.1:${server.address.port}", "secret").health(2_000)
            assertThat(health.model).isEqualTo("gemma-4-e2b")
            assertThat(health.state).isEqualTo(ModelState.Loaded)
            assertThat(health.audio).isTrue()
            assertThat(listed).isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }
}
