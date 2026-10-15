package dev.abhay.ferblink

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Hints show once: "Got it" is remembered across a restart (a new view model reads it back). No screen. */
@RunWith(AndroidJUnit4::class)
class HintsTest {
    @get:Rule val settings = SavedSettings()

    private fun viewModel(): LinkViewModel {
        lateinit var model: LinkViewModel
        // A view model starts on the main thread (its check() launches there).
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = LinkViewModel(settings.context.applicationContext as Application) }
        return model
    }

    @Test
    fun everyHintShowsUntilGotIt_andStaysDismissedAfterARestart() {
        val first = viewModel()
        assertThat(first.state.value.hints).containsExactlyElementsIn(Hint.entries)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            first.dismissHint(Hint.Switch)
            first.dismissHint(Hint.Unload)
        }
        assertThat(first.state.value.hints).containsExactly(Hint.Voice, Hint.TextOnly, Hint.Try)

        assertThat(viewModel().state.value.hints).containsExactly(Hint.Voice, Hint.TextOnly, Hint.Try)
        assertThat(LinkSettings(settings.context).hintsSeen).containsExactly("Switch", "Unload")
    }
}
