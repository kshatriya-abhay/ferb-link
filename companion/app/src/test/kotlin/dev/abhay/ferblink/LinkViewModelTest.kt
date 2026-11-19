package dev.abhay.ferblink

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The screen's view model, made as the app makes it on start: on a real (Robolectric) Context with
 * the settings already on the phone. 0.2 crashed on every start with a saved pairing (2026-11-19):
 * its init checked the computer before the property the check uses was set.
 */
@RunWith(RobolectricTestRunner::class)
class LinkViewModelTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    /** The settings as 0.1 left them after pairing (its keys, which 0.2 reads unchanged). */
    private fun paired() {
        app.getSharedPreferences("link", Context.MODE_PRIVATE).edit()
            .putString("server_url", "http://127.0.0.1:9")
            .putString("api_key", "test-key")
            .putBoolean("enabled", true)
            .putString("last_model", "gemma-4-e2b")
            .commit()
    }

    @Test
    fun startsWithASavedPairing() {
        paired()
        val vm = LinkViewModel(app)
        assertThat(vm.state.value.configured).isTrue()
        // The start's check is under way.
        assertThat(vm.state.value.checking).isTrue()
    }

    @Test
    fun startsWithNothingSaved() {
        val vm = LinkViewModel(app)
        assertThat(vm.state.value.configured).isFalse()
        assertThat(vm.state.value.checking).isFalse()
    }
}
