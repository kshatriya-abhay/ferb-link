package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PairingTest {
    private val key = "Zx3-q_9vT2mBn8Lr4kWp1sYd6HfGc0aE"

    @Test
    fun `a link from server pair gives the address and key`() {
        val result = Pairing.parse(Pairing.link("http://laptop.example.ts.net", key))
        val offer = (result as Pairing.Result.Ok).offer
        assertThat(offer.url).isEqualTo("http://laptop.example.ts.net")
        assertThat(offer.key).isEqualTo(key)
        assertThat(offer.host).isEqualTo("laptop.example.ts.net")
        assertThat(offer.toString()).doesNotContain(key)
    }

    @Test
    fun `the shell's encoding is read too, and a trailing slash is dropped`() {
        // jq's @uri, as server/pair-link.sh writes it.
        val result = Pairing.parse("ferblink://pair?url=http%3A%2F%2Flaptop.example.ts.net%2F&key=$key&v=2")
        assertThat((result as Pairing.Result.Ok).offer.url).isEqualTo("http://laptop.example.ts.net")
        assertThat(Pairing.parse("FERBLINK://PAIR?url=https://models.example.com&key=$key")).isInstanceOf(Pairing.Result.Ok::class.java)
    }

    @Test
    fun `an address Ferb Link wouldn't save is refused`() {
        for (url in listOf("http://100.101.102.103:8080", "http://evil.example.com", "ftp://laptop.example.ts.net", "javascript:alert(1)")) {
            assertThat(Pairing.parse(Pairing.link(url, key))).isInstanceOf(Pairing.Result.Bad::class.java)
        }
        assertThat(Pairing.parse("ferblink://pair?key=$key")).isEqualTo(Pairing.Result.Bad(R.string.pair_no_address))
    }

    @Test
    fun `a missing, short or odd key is refused`() {
        for (bad in listOf("", "short", "has spaces in it right here", "x".repeat(300))) {
            assertThat(Pairing.parse(Pairing.link("http://laptop.example.ts.net", bad))).isEqualTo(Pairing.Result.Bad(R.string.pair_no_key))
        }
    }

    @Test
    fun `other links aren't pairing links`() {
        for (link in listOf("ferblink://other?url=http://laptop.example.ts.net&key=$key", "https://pair?url=x&key=$key", "not a link at all")) {
            assertThat(Pairing.parse(link)).isEqualTo(Pairing.Result.Bad(R.string.pair_not_a_link))
        }
        assertThat(Pairing.isPairingLink("ferblink://pair?x")).isTrue()
        assertThat(Pairing.isPairingLink("https://example.com")).isFalse()
        assertThat(Pairing.isPairingLink(null)).isFalse()
    }
}
