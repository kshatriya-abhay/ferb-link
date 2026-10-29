package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.math.ln

/** ServerClient against a fake llama-server (the JDK's HTTP server) on localhost. */
class ServerClientTest {
    private lateinit var server: HttpServer
    private val received = mutableListOf<Pair<String, String>>() // path, body
    private var authorization: String? = null
    private var chatReply: Pair<Int, String> = 200 to ""
    private var chatDelayMs = 0L
    private var props = """{"is_sleeping": false, "modalities": {"vision": true, "audio": true}}"""
    private var modelStatus = "loaded"
    private val propsQueries = mutableListOf<String?>()
    private var unloads = 0

    private val url get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { it.answer(200, """{"status":"ok"}""") }
        server.createContext("/v1/models") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer secret") exchange.answer(401, """{"error":{"message":"Invalid API Key"}}""")
            else exchange.answer(200, """{"data":[{"id":"gemma-4-e2b","status":{"value":"$modelStatus"}}]}""")
        }
        server.createContext("/props") {
            propsQueries += it.requestURI.query
            it.answer(200, props)
        }
        // llama-server's router: unloading a model that isn't running is a 400.
        server.createContext("/models/unload") { exchange ->
            received += exchange.requestURI.path to exchange.requestBody.bufferedReader().readText()
            if (modelStatus == "loaded") {
                unloads++
                modelStatus = "unloaded"
                exchange.answer(200, """{"success":true}""")
            } else exchange.answer(400, """{"error":{"code":400,"message":"model is not running"}}""")
        }
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            received += exchange.requestURI.path to exchange.requestBody.bufferedReader().readText()
            Thread.sleep(chatDelayMs)
            exchange.answer(chatReply.first, chatReply.second)
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = runCatching { server.stop(0) }.let {}

    @Test
    fun `a tool call comes back with its arguments and confidence stand-ins`() {
        chatReply = 200 to """
            {"model": "gemma-4-e2b", "choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [
              {"type": "function", "function": {"name": "set_timer", "arguments": "{\"minutes\": 5}"}}]},
             "logprobs": {"content": [{"token": "a", "logprob": ${ln(0.9)}}, {"token": "b", "logprob": ${ln(0.5)}}, {"token": "c", "logprob": ${ln(1.0)}}]}}]}
        """.trimIndent()

        val reply = ServerClient(url, "secret").complete("set a timer for 5 minutes", TOOLS, facts = "date: 2026-10-11 (Sunday)")

        assertThat(reply.error).isNull()
        assertThat(reply.calls.map { it.signature() }).containsExactly("set_timer(minutes = 5)")
        assertThat(reply.confidence).isWithin(1e-9).of(0.5)
        assertThat(reply.first).isWithin(1e-9).of(0.9)
        assertThat(reply.mean).isWithin(1e-9).of(0.8)
        assertThat(reply.model).isEqualTo("gemma-4-e2b")
        assertThat(authorization).isEqualTo("Bearer secret")
    }

    @Test
    fun `the request is the bench's, with the facts after its instruction`() {
        chatReply = 200 to """{"choices": [{"message": {"content": "Hello"}}]}"""

        ServerClient("$url/", "secret").complete("hi", TOOLS, facts = "date: 2026-10-11 (Sunday)")

        val (path, body) = received.single()
        assertThat(path).isEqualTo("/v1/chat/completions")
        val sent = JSONObject(body)
        val messages = sent.getJSONArray("messages")
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo(ServerClient.SYSTEM + "\n\ndate: 2026-10-11 (Sunday)")
        assertThat(messages.getJSONObject(1).getString("content")).isEqualTo("hi")
        val tool = sent.getJSONArray("tools").getJSONObject(0)
        assertThat(tool.getString("type")).isEqualTo("function")
        assertThat(tool.getJSONObject("function").getString("name")).isEqualTo("set_timer")
        assertThat(sent.getInt("temperature")).isEqualTo(0)
        assertThat(sent.getBoolean("logprobs")).isTrue()
        assertThat(sent.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking")).isFalse()
    }

    @Test
    fun `no call gives the model's words and no calls`() {
        chatReply = 200 to """{"choices": [{"message": {"content": "I can't do that."}}]}"""

        val reply = ServerClient(url, "secret").complete("what's the capital of France", TOOLS)

        assertThat(reply.error).isNull()
        assertThat(reply.calls).isEmpty()
        assertThat(reply.text).isEqualTo("I can't do that.")
        assertThat(reply.confidence).isEqualTo(0.0)
    }

    @Test
    fun `arguments that aren't JSON are kept, not dropped`() {
        chatReply = 200 to """{"choices": [{"message": {"tool_calls": [{"function": {"name": "set_alarm", "arguments": "{time: 6"}}]}}]}"""

        val reply = ServerClient(url, "secret").complete("alarm at 6", TOOLS)

        assertThat(reply.calls.single().arguments.getString("_unparsed")).isEqualTo("{time: 6")
    }

    @Test
    fun `a wrong key says so`() {
        chatReply = 401 to """{"error": {"message": "Invalid API Key"}}"""

        val reply = ServerClient(url, "wrong").complete("hi", TOOLS)

        assertThat(reply.error).isEqualTo("key refused")
        assertThat(reply.errorDetail).isEqualTo("Your computer turned down the API key")
        val json = JSONObject(reply.toJson())
        assertThat(json.getBoolean("success")).isFalse()
        assertThat(json.getString("error_kind")).isEqualTo("auth")
    }

    @Test
    fun `other server errors are kind server`() {
        chatReply = 500 to """{"error": {"message": "boom"}}"""

        val reply = ServerClient(url, "secret").complete("hi", TOOLS)

        assertThat(reply.failure).isEqualTo(Failure(ErrorKind.Server, "Your computer answered 500: boom", "server error 500"))
        val json = JSONObject(reply.toJson())
        assertThat(json.getString("error")).isEqualTo("server error 500")
        assertThat(json.getString("error_detail")).isEqualTo("Your computer answered 500: boom")
    }

    @Test
    fun `short errors are lowercase one-liners with no full stop`() {
        for (kind in ErrorKind.entries) {
            assertThat(kind.short).isEqualTo(kind.short.lowercase())
            assertThat(kind.short).doesNotContain(".")
            assertThat(kind.short.length).isAtMost(30)
        }
    }

    @Test
    fun `an answer that isn't JSON is kind bad_reply`() {
        chatReply = 200 to "<html>not llama</html>"

        assertThat(ServerClient(url, "secret").complete("hi", TOOLS).failure?.kind).isEqualTo(ErrorKind.BadReply)
    }

    @Test
    fun `a slow answer times out at the call's timeout, as kind timeout`() {
        chatReply = 200 to """{"choices": [{"message": {"content": "late"}}]}"""
        chatDelayMs = 3_000

        val reply = ServerClient(url, "secret").complete("hi", TOOLS, timeoutMs = 1_000)

        assertThat(reply.failure?.kind).isEqualTo(ErrorKind.Timeout)
        assertThat(reply.ms).isLessThan(2_500)
    }

    @Test
    fun `a server that isn't there fails quickly with a reason`() {
        val port = server.address.port
        server.stop(0)

        val reply = ServerClient("http://127.0.0.1:$port", "secret", connectTimeoutMs = 500).complete("hi", TOOLS)

        assertThat(reply.error).isEqualTo("can't reach your computer")
        assertThat(reply.errorDetail).isEqualTo("Your computer doesn't answer. Is the server running?")
        assertThat(reply.failure?.kind).isEqualTo(ErrorKind.Unreachable)
    }

    @Test
    fun `an unknown name is kind unreachable`() {
        val reply = ServerClient("http://no-such-host.invalid", "secret").complete("hi", TOOLS)

        assertThat(reply.failure).isEqualTo(Failure(ErrorKind.Unreachable, "Can't find your computer's name. Is Tailscale on?"))
    }

    @Test
    fun `tools that aren't a JSON array are refused before sending`() {
        val reply = ServerClient(url, "secret").complete("hi", "{not json")

        assertThat(reply.error).isEqualTo("bad request: tools")
        assertThat(reply.errorDetail).startsWith("The tools aren't a JSON array")
        assertThat(reply.failure?.kind).isEqualTo(ErrorKind.BadRequest)
        assertThat(received).isEmpty()
    }

    @Test
    fun `health names the model, and a wrong key fails it`() {
        val good = ServerClient(url, "secret").health()
        assertThat(good.ok).isTrue()
        assertThat(good.model).isEqualTo("gemma-4-e2b")

        val bad = ServerClient(url, "wrong").health()
        assertThat(bad.ok).isFalse()
        assertThat(bad.message).isEqualTo("Your computer turned down the API key")
        assertThat(bad.failure?.kind).isEqualTo(ErrorKind.Auth)
    }

    @Test
    fun `the contract's error kinds are the ones Ferb expects`() {
        assertThat(LinkContract.ERROR_KINDS).containsExactly("off", "not_set_up", "bad_request", "unreachable", "timeout", "auth", "server", "bad_reply")
    }

    @Test
    fun `the reply reads like Needle 3's`() {
        val call = Call("set_timer", JSONObject().put("minutes", 5))
        val json = JSONObject(Reply(listOf(call), "", 0.5, 0.9, 0.8, "gemma-4-e2b", 120, null).toJson())

        assertThat(json.getBoolean("success")).isTrue()
        val first = json.getJSONArray("function_calls").getJSONObject(0)
        assertThat(first.getString("name")).isEqualTo("set_timer")
        assertThat(first.getJSONObject("arguments").getInt("minutes")).isEqualTo(5)
        assertThat(json.getDouble("confidence")).isEqualTo(0.5)
        assertThat(json.getString("source")).isEqualTo("laptop")
        assertThat(json.has("error")).isFalse()
        assertThat(json.has("error_kind")).isFalse()
    }

    @Test
    fun `plain HTTP is accepted only for Tailscale names`() {
        assertThat(LinkSettings.urlProblem("http://my-laptop.tail1234.ts.net")).isNull()
        assertThat(LinkSettings.urlProblem("https://example.com")).isNull()
        assertThat(LinkSettings.urlProblem("http://100.101.102.103:8080")).isEqualTo(R.string.url_needs_tailscale_name)
        assertThat(LinkSettings.urlProblem("http://my-laptop")).isEqualTo(R.string.url_needs_tailscale_name)
        assertThat(LinkSettings.urlProblem("my-laptop.tail1234.ts.net")).isNotNull()
    }

    @Test
    fun `health says whether the server takes audio`() {
        assertThat(ServerClient(url, "secret").health().audio).isTrue()
        props = """{"is_sleeping": true, "modalities": {"vision": false, "audio": false}}"""
        assertThat(ServerClient(url, "secret").health().audio).isFalse()
        assertThat(received).isEmpty() // checking never wakes the model
    }

    @Test
    fun `wake sends a request only when the model is asleep or unloaded`() {
        chatReply = 200 to """{"choices": [{"message": {"content": "Hi"}}]}"""
        assertThat(ServerClient(url, "secret").wake()).isEqualTo(Props(ModelState.Loaded, audio = true))
        assertThat(received).isEmpty()

        props = """{"is_sleeping": true, "modalities": {"audio": true}}"""
        assertThat(ServerClient(url, "secret").wake()?.state).isEqualTo(ModelState.Asleep)
        modelStatus = "unloaded"
        assertThat(ServerClient(url, "secret").wake()).isEqualTo(Props(ModelState.Unloaded, audio = null))
        assertThat(received.map { JSONObject(it.second).getInt("max_tokens") }).containsExactly(1, 1)
        assertThat(received.map { JSONObject(it.second).getString("model") }).containsExactly("gemma-4-e2b", "gemma-4-e2b")
    }

    @Test
    fun `props never loads the model`() {
        ServerClient(url, "secret").props()
        assertThat(propsQueries.single()).isEqualTo("model=gemma-4-e2b&autoload=false")
        modelStatus = "unloaded"
        assertThat(ServerClient(url, "secret").props()?.state).isEqualTo(ModelState.Unloaded)
        assertThat(propsQueries).hasSize(1) // an unloaded model's /props would load it
    }

    @Test
    fun `unload stops the model, and unloading again isn't an error`() {
        val client = ServerClient(url, "secret", model = "gemma-4-e2b")
        assertThat(client.unload()).isNull()
        assertThat(JSONObject(received.single().second).getString("model")).isEqualTo("gemma-4-e2b")
        assertThat(client.unload()).isNull()
        assertThat(unloads).isEqualTo(1)
        assertThat(ServerClient(url, "wrong").unload()?.kind).isEqualTo(ErrorKind.Auth)
    }

    @Test
    fun `requests name the model, looked up when not known`() {
        chatReply = 200 to """{"model": "gemma-4-e2b", "choices": [{"message": {"content": "Done"}}]}"""
        ServerClient(url, "secret").complete("hi", TOOLS)
        ServerClient(url, "secret", model = "other").complete("hi", TOOLS)
        assertThat(received.map { JSONObject(it.second).getString("model") }).containsExactly("gemma-4-e2b", "other")
    }

    @Test
    fun `a recording goes as WAV with the prompt, and comes back heard and in English`() {
        chatReply = 200 to """{"model": "gemma-4-e2b", "choices": [{"message": {"content":
            "{\"speech\": true, \"hindi\": true, \"heard\": \"subah 5 baje ka alarm laga do\", \"text\": \"Set an alarm for 5 am\"}"}}]}"""

        val transcript = ServerClient(url, "secret").transcribe(byteArrayOf(1, 2, 3))

        assertThat(transcript.heard).isEqualTo("subah 5 baje ka alarm laga do")
        assertThat(transcript.text).isEqualTo("Set an alarm for 5 am")
        val body = JSONObject(received.single().second)
        val messages = body.getJSONArray("messages")
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo(ServerClient.TRANSCRIBE)
        val audio = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio")
        assertThat(audio.getString("format")).isEqualTo("wav")
        assertThat(audio.getString("data")).isEqualTo("AQID")
        assertThat(body.getJSONObject("response_format").getString("type")).isEqualTo("json_schema")
        val json = JSONObject(transcript.toJson())
        assertThat(json.getBoolean("success")).isTrue()
        assertThat(json.getString("heard")).isEqualTo("subah 5 baje ka alarm laga do")
        assertThat(json.getString("text")).isEqualTo("Set an alarm for 5 am")
    }

    @Test
    fun `English speech keeps what was heard, not the model's rewording`() {
        chatReply = 200 to """{"choices": [{"message": {"content":
            "{\"speech\": true, \"hindi\": false, \"heard\": \"ask not what your country can do\", \"text\": \"Ask what your country can do\"}"}}]}"""

        val transcript = ServerClient(url, "secret").transcribe(byteArrayOf(1))

        assertThat(transcript.text).isEqualTo("ask not what your country can do")
    }

    @Test
    fun `no speech gives empty heard and text, whatever words the model wrote`() {
        chatReply = 200 to """{"choices": [{"message": {"content":
            "{\"speech\": false, \"hindi\": false, \"heard\": \"call mom\", \"text\": \"Call mom\"}"}}]}"""

        val json = JSONObject(ServerClient(url, "secret").transcribe(byteArrayOf(1)).toJson())

        assertThat(json.getBoolean("success")).isTrue()
        assertThat(json.getString("heard")).isEmpty()
        assertThat(json.getString("text")).isEmpty()
    }

    @Test
    fun `a Hindi word keeps the translation even when the model calls it English`() {
        chatReply = 200 to """{"choices": [{"message": {"content":
            "{\"speech\": true, \"hindi\": false, \"heard\": \"zoom kholo\", \"text\": \"Open zoom\"}"}}]}"""

        assertThat(ServerClient(url, "secret").transcribe(byteArrayOf(1)).text).isEqualTo("Open zoom")
        assertThat(ServerClient.soundsHindi("set a timer for ten minutes")).isFalse()
        assertThat(ServerClient.soundsHindi("Paytm kholo")).isTrue()
    }

    @Test
    fun `a transcript that isn't JSON is kind bad_reply`() {
        chatReply = 200 to """{"choices": [{"message": {"content": "set a timer"}}]}"""
        val json = JSONObject(ServerClient(url, "secret").transcribe(byteArrayOf(1)).toJson())
        assertThat(json.getBoolean("success")).isFalse()
        assertThat(json.getString("error_kind")).isEqualTo("bad_reply")
    }

    private fun HttpExchange.answer(code: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        val TOOLS = LinkViewModel.SAMPLE_TOOLS
    }
}
