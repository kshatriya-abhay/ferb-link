package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executor

/** LinkCalls' own rules; what each method returns is in ContractTest's fixtures. */
class LinkCallsTest {
    private class State(override var enabled: Boolean = true) : LinkState {
        override val configured = true
        override var lastModel = ""
        override var audio = false
        override var modelState = ""
        override var modelStateAt = 0L
        override var jevOn = false
        override val jevConfigured = false
        override var jevOffReason = ""
        override var jevSpentUsd = 0.0
        override val jevCapUsd = 3.0
        override fun jev(trace: ((String) -> Unit)?) = JevClient("key", "http://127.0.0.1:9")
        var clients = 0
        // A port nothing listens on: a call that reaches the network fails as unreachable.
        override fun client(trace: ((String) -> Unit)?): ServerClient { clients++; return ServerClient("http://127.0.0.1:9", "key") }
    }

    @Test
    fun `only the same app, same-key apps, and adb shell in debug builds may call`() {
        assertThat(LinkCalls.callerAllowed(10_100, 10_100, debug = false) { error("not asked") }).isTrue()
        assertThat(LinkCalls.callerAllowed(10_200, 10_100, debug = false) { true }).isTrue()
        assertThat(LinkCalls.callerAllowed(10_200, 10_100, debug = false) { false }).isFalse()
        assertThat(LinkCalls.callerAllowed(LinkCalls.SHELL_UID, 10_100, debug = true) { false }).isTrue()
        assertThat(LinkCalls.callerAllowed(LinkCalls.SHELL_UID, 10_100, debug = false) { false }).isFalse()
    }

    @Test
    fun `model_state says loaded only within the server's idle window, and loading counts as asleep`() {
        val state = State()
        assertThat(state.modelStateNow(1_000)).isNull()
        state.saw(ModelState.Loaded, at = 1_000)
        assertThat(state.modelStateNow(1_000 + LinkContract.LOADED_FOR_MS)).isEqualTo("loaded")
        assertThat(state.modelStateNow(1_001 + LinkContract.LOADED_FOR_MS)).isEqualTo("asleep")
        state.saw(ModelState.Loading, at = 1_000)
        assertThat(state.modelStateNow(1_000)).isEqualTo("asleep")
        state.saw(ModelState.Unloaded, at = 1_000)
        assertThat(state.modelStateNow(Long.MAX_VALUE)).isEqualTo("unloaded")
    }

    @Test
    fun `the timeout is clamped, and an Int or a Long is taken`() {
        assertThat(LinkCalls.timeoutMs(emptyMap())).isNull()
        assertThat(LinkCalls.timeoutMs(mapOf("timeout_ms" to 10))).isEqualTo(LinkContract.MIN_TIMEOUT_MS)
        assertThat(LinkCalls.timeoutMs(mapOf("timeout_ms" to 10_000L))).isEqualTo(10_000)
        assertThat(LinkCalls.timeoutMs(mapOf("timeout_ms" to 600_000))).isEqualTo(LinkContract.MAX_TIMEOUT_MS)
        assertThat(LinkCalls.timeoutMs(mapOf("timeout_ms" to "10000"))).isNull()
    }

    @Test
    fun `base64 audio is taken only in debug builds`() {
        val extras = mapOf(LinkCalls.DEBUG_AUDIO_BASE64 to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        var decoded: ByteArray? = null
        val decode = { audio: ByteArray, _: String? -> decoded = audio; ByteArray(0) }
        LinkCalls(State(), Executor { it.run() }, debug = false, decode = decode).answer("transcribe", extras, "shell")
        assertThat(decoded).isNull()
        LinkCalls(State(), Executor { it.run() }, debug = true, decode = decode).answer("transcribe", extras, "shell")
        assertThat(decoded).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `a recording over the limit or unreadable is a bad request, before the network`() {
        val state = State()
        val tooBig = mapOf("audio" to ByteArray(LinkContract.MAX_AUDIO_BYTES + 1))
        val reply = JSONObject(LinkCalls(state, Executor { it.run() }, debug = false).answer("transcribe", tooBig, "ferb")["reply"] as String)
        assertThat(reply.getString("error")).isEqualTo("bad request: recording too big")
        val unreadable = LinkCalls(state, Executor { it.run() }, debug = false, decode = { _, _ -> throw AudioDecoder.Unreadable("Not audio") })
        val reply2 = JSONObject(unreadable.answer("transcribe", mapOf("audio" to byteArrayOf(1)), "ferb")["reply"] as String)
        assertThat(reply2.getString("error")).isEqualTo("bad request: unreadable recording")
        assertThat(reply2.getString("error_detail")).isEqualTo("Not audio")
        assertThat(state.clients).isEqualTo(0)
    }

    @Test
    fun `wake goes to the laptop once at a time, and not when off`() {
        val queued = mutableListOf<Runnable>()
        val state = State()
        val calls = LinkCalls(state, Executor { queued += it }, debug = false)
        calls.answer("wake", emptyMap(), "ferb")
        calls.answer("wake", emptyMap(), "ferb")
        assertThat(queued).hasSize(1)
        queued.single().run()
        calls.answer("wake", emptyMap(), "ferb")
        assertThat(queued).hasSize(2)
        queued[1].run()
        state.enabled = false
        calls.answer("wake", emptyMap(), "ferb")
        assertThat(queued).hasSize(2)
    }

    @Test
    fun `extras of the wrong type are taken as missing`() {
        val reply = JSONObject(LinkCalls(State(), Executor { it.run() }, debug = false).answer("complete", mapOf("request" to 42), "ferb")["reply"] as String)
        assertThat(reply.getString("error_kind")).isEqualTo("bad_request")
    }
}
