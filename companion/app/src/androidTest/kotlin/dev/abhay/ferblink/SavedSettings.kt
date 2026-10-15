package dev.abhay.ferblink

import android.content.Context
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * These tests run inside the installed app, on its real settings: this keeps a copy and puts it
 * back afterwards, so the phone stays paired. Each test starts with nothing saved.
 */
class SavedSettings : ExternalResource() {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
    private var saved: Map<String, *> = emptyMap<String, Any>()

    override fun before() {
        saved = prefs.all.toMap()
        prefs.edit(commit = true) { clear() }
    }

    override fun after() {
        prefs.edit(commit = true) {
            clear()
            for ((key, value) in saved) {
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value as Set<String>)
                }
            }
        }
    }
}
