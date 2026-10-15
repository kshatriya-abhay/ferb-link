package dev.abhay.ferblink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A ferblink://pair link reaching Ferb Link, as `companion:pair` and a camera app send it, and
 * Paste link's clipboard read. These open Ferb Link's screen: run them with the phone free.
 */
@RunWith(AndroidJUnit4::class)
class PairingLinkTest {
    @get:Rule val settings = SavedSettings()

    private val key = "Zx3-q_9vT2mBn8Lr4kWp1sYd6HfGc0aE"
    private val link = Pairing.link("http://laptop.example.ts.net", key)

    private fun open(link: String) = ActivityScenario.launch<MainActivity>(
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(settings.context, MainActivity::class.java),
    )

    private fun ActivityScenario<MainActivity>.state(): LinkViewModel.State {
        lateinit var state: LinkViewModel.State
        onActivity { state = ViewModelProvider(it)[LinkViewModel::class.java].state.value }
        return state
    }

    @Test
    fun aPairingLinkIsOffered_notSaved() {
        open(link).use { scenario ->
            val state = scenario.state()
            assertThat(state.pairing?.host).isEqualTo("laptop.example.ts.net")
            assertThat(state.replacing).isNull()
            assertThat(LinkSettings(settings.context).configured).isFalse()
        }
    }

    @Test
    fun aBadLinkIsRefused() {
        open(Pairing.link("http://evil.example.com", key)).use { scenario ->
            assertThat(scenario.state().pairing).isNull()
            assertThat(scenario.state().pairingProblem).isNotNull()
        }
    }

    @Test
    fun aLinkToAnotherComputerSaysWhatItReplaces() {
        LinkSettings(settings.context).apply { serverUrl = "http://old.example.ts.net"; apiKey = key }
        open(link).use { scenario ->
            assertThat(scenario.state().replacing).isEqualTo("http://old.example.ts.net")
        }
    }

    @Test
    fun pasteLinkReadsTheClipboard() {
        open("ferblink://pair").use { scenario ->
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                val before = clipboard.primaryClip
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("link", link))
                    assertThat(clipboardText(activity)).isEqualTo(link)
                } finally {
                    if (before != null) clipboard.setPrimaryClip(before) else clipboard.clearPrimaryClip()
                }
            }
        }
    }
}
