package dev.abhay.ferblink

import android.content.Context
import androidx.core.content.edit

/**
 * The laptop's address and API key, and whether Ferb Link answers Ferb at all. In the app's
 * private storage (no backups: `allowBackup="false"`), read afresh on every request.
 */
class LinkSettings(context: Context) : LinkState {
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_URL, value.trim()) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_API_KEY, value.trim()) }

    /** Off: Ferb's requests get "off" at once, without going to the laptop. */
    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    override val configured: Boolean get() = serverUrl.isNotEmpty() && apiKey.isNotEmpty()

    /** The model the server last named (a connection check or an answer), for `status`; empty if none yet. */
    override var lastModel: String
        get() = prefs.getString(KEY_LAST_MODEL, "").orEmpty()
        set(value) {
            if (value.isNotEmpty() && value != lastModel) prefs.edit { putString(KEY_LAST_MODEL, value) }
        }

    /** Whether the server last said it takes audio, for `status`: refreshed by checks, wakes and answers. */
    override var audio: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, false)
        set(value) {
            if (value != audio) prefs.edit { putBoolean(KEY_AUDIO, value) }
        }

    override fun client(trace: ((String) -> Unit)?) = ServerClient(serverUrl, apiKey, model = lastModel, trace = trace)

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_AUDIO = "audio"

        /**
         * Why [url] won't work, or null when it looks right. Plain HTTP is allowed only to
         * Tailscale names (`res/xml/network_security_config.xml`): the tailnet encrypts it.
         */
        fun urlProblem(url: String): String? {
            val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull()
                ?: return "Not a URL"
            val host = parsed.host ?: return "Starts with http:// and your computer's name"
            return when {
                parsed.scheme == "https" -> null
                parsed.scheme != "http" -> "Starts with http:// or https://"
                !host.endsWith(".ts.net") -> "Use your computer's full Tailscale name (….ts.net): plain HTTP only goes to the tailnet"
                else -> null
            }
        }
    }
}
