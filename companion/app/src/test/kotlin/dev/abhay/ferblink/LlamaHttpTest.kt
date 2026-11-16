package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.net.InetAddress

/** Plain HTTP goes out only inside the tailnet, so the key is never sent in the clear. */
class LlamaHttpTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `inside the tailnet is 100_64_0_0 slash 10, fd7a_115c_a1e0 slash 48, or this device`() {
        for (inside in listOf("100.64.0.1", "100.101.102.103", "100.127.255.255", "fd7a:115c:a1e0::1", "127.0.0.1", "::1")) {
            assertWithMessage(inside).that(LlamaHttp.insideTailnet(ip(inside))).isTrue()
        }
        for (outside in listOf("100.63.255.255", "100.128.0.1", "10.0.0.1", "192.168.1.5", "203.0.113.5", "fd7a:115c:a1e1::1", "2001:db8::1")) {
            assertWithMessage(outside).that(LlamaHttp.insideTailnet(ip(outside))).isFalse()
        }
    }

    @Test
    fun `a ts_net name that resolves outside the tailnet is refused before anything is sent`() {
        var looked = ""
        val http = LlamaHttp("http://laptop.example.ts.net", "secret", 3_000, 3_000, null, resolve = { name -> looked = name; arrayOf(ip("203.0.113.5")) })
        var built = false
        val exchange = http.exchange("/v1/chat/completions", 1_000) { built = true; "{}" }
        assertThat(looked).isEqualTo("laptop.example.ts.net")
        assertThat(built).isTrue()
        val failed = exchange as LlamaHttp.Exchange.Failed
        assertThat(failed.failure.kind).isEqualTo(ErrorKind.Unreachable)
        assertThat(failed.failure.message).isEqualTo(LlamaHttp.NOT_ON_TAILNET)
    }

    @Test
    fun `one answer outside the tailnet among inside ones is refused too`() {
        val http = LlamaHttp("http://laptop.example.ts.net", "secret", 3_000, 3_000, null, resolve = { arrayOf(ip("100.101.102.103"), ip("203.0.113.5")) })
        val failed = http.exchange("/v1/chat/completions", 1_000) { "{}" } as LlamaHttp.Exchange.Failed
        assertThat(failed.failure.message).isEqualTo(LlamaHttp.NOT_ON_TAILNET)
    }
}
