package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PairDialogTest {
    private val offer = Pairing.Offer("http://laptop.example.ts.net", "Zx3-q_9vT2mBn8Lr4kWp1sYd6HfGc0aE")

    @Test
    fun `the dialog warns only when a different saved address would be replaced`() {
        val saved = LinkViewModel.State("http://old.example.ts.net", "key", enabled = true, configured = true, pairing = offer)
        assertThat(saved.replacing).isEqualTo("http://old.example.ts.net")
        assertThat(saved.copy(serverUrl = offer.url).replacing).isNull()
        assertThat(saved.copy(configured = false).replacing).isNull()
        assertThat(saved.copy(pairing = null).replacing).isNull()
    }
}
