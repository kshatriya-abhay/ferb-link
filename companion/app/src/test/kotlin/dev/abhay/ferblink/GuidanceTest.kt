package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuidanceTest {
    @Test
    fun `each failure a check can give has one line of guidance`() {
        for (kind in listOf(ErrorKind.Unreachable, ErrorKind.Timeout, ErrorKind.Auth, ErrorKind.Server, ErrorKind.BadReply)) {
            assertThat(Guidance.of(Failure(kind, "x"), vpnActive = true)).isNotNull()
        }
        for (kind in listOf(ErrorKind.Off, ErrorKind.NotSetUp, ErrorKind.BadRequest)) {
            assertThat(Guidance.of(Failure(kind, "x"), vpnActive = true)).isNull()
        }
        assertThat(Guidance.of(null, vpnActive = true)).isNull()
    }

    @Test
    fun `unreachable without a VPN points at Tailscale on the phone`() {
        val failure = Failure(ErrorKind.Unreachable, "Your computer doesn't answer")
        assertThat(Guidance.of(failure, vpnActive = false)).isEqualTo(R.string.guide_tailscale_off)
        assertThat(Guidance.of(failure, vpnActive = true)).isEqualTo(R.string.guide_unreachable)
        assertThat(Guidance.of(failure, vpnActive = null)).isEqualTo(R.string.guide_unreachable)
    }

    @Test
    fun `no line when the error's detail already says what to do`() {
        val noName = Failure(ErrorKind.Unreachable, ServerClient.NO_NAME)
        assertThat(Guidance.of(noName, vpnActive = false)).isNull()
        // With Tailscale up, the name is wrong or the computer is off the tailnet: that's new.
        assertThat(Guidance.of(noName, vpnActive = true)).isEqualTo(R.string.guide_unreachable)
        assertThat(Guidance.of(Failure(ErrorKind.Unreachable, ServerClient.HTTP_NEEDS_TAILSCALE_NAME), vpnActive = true)).isNull()
    }

    @Test
    fun `a loading model says wait, other server errors say look at the logs`() {
        assertThat(Guidance.of(Failure(ErrorKind.Server, "Your computer is loading the model", "model loading"), true)).isEqualTo(R.string.guide_loading)
        assertThat(Guidance.of(Failure(ErrorKind.Server, "Your computer answered 500", "server error 500"), true)).isEqualTo(R.string.guide_server)
    }

    @Test
    fun `the status card's hints follow what the computer can do`() {
        assertThat(Hint.forStatus(null)).isEmpty()
        assertThat(Hint.forStatus(Health(false, null, 0, Failure(ErrorKind.Timeout, "x")))).isEmpty()
        assertThat(Hint.forStatus(Health(true, "m", 0, null, ModelState.Loaded, audio = true))).containsExactly(Hint.Voice, Hint.Unload).inOrder()
        assertThat(Hint.forStatus(Health(true, "m", 0, null, ModelState.Asleep, audio = false))).containsExactly(Hint.TextOnly, Hint.Unload).inOrder()
        assertThat(Hint.forStatus(Health(true, "m", 0, null, ModelState.Unloaded, audio = null))).isEmpty()
    }
}
