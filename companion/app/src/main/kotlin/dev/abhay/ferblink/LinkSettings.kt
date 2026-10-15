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

    override var modelState: String
        get() = prefs.getString(KEY_MODEL_STATE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_MODEL_STATE, value) }

    override var modelStateAt: Long
        get() = prefs.getLong(KEY_MODEL_STATE_AT, 0)
        set(value) = prefs.edit { putLong(KEY_MODEL_STATE_AT, value) }

    /** First-use hints the user has dismissed ([Hint] names). */
    var hintsSeen: Set<String>
        get() = prefs.getStringSet(KEY_HINTS_SEEN, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_HINTS_SEEN, value) }

    override fun client(trace: ((String) -> Unit)?) = ServerClient(serverUrl, apiKey, model = lastModel, trace = trace)

    /** Jev, TypeSafe's cloud model: off until the user turns it on (request text goes to TypeSafe). */
    override var jevOn: Boolean
        get() = prefs.getBoolean(KEY_JEV_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_JEV_ON, value) }

    /** The user's TypeSafe API key: entered on this screen only, never in a pairing link, never logged. */
    var jevKey: String
        get() = prefs.getString(KEY_JEV_KEY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_JEV_KEY, value.trim()) }

    override val jevConfigured: Boolean get() = jevKey.isNotEmpty()

    override var jevOffReason: String
        get() = prefs.getString(KEY_JEV_OFF_REASON, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_JEV_OFF_REASON, value) }

    /** Kept in billionths of a dollar: a call costs about 15,000–40,000. */
    override var jevSpentUsd: Double
        get() = prefs.getLong(KEY_JEV_SPENT_NANO, 0) / 1e9
        set(value) = prefs.edit { putLong(KEY_JEV_SPENT_NANO, Math.round(value * 1e9)) }

    override var jevCapUsd: Double
        get() = prefs.getString(KEY_JEV_CAP_USD, null)?.toDoubleOrNull() ?: DEFAULT_JEV_CAP_USD
        set(value) = prefs.edit { putString(KEY_JEV_CAP_USD, value.toString()) }

    override fun jev(trace: ((String) -> Unit)?) = JevClient(jevKey, trace = trace)

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_AUDIO = "audio"
        private const val KEY_HINTS_SEEN = "hints_seen"
        private const val KEY_MODEL_STATE = "model_state"
        private const val KEY_MODEL_STATE_AT = "model_state_at"
        private const val KEY_JEV_ON = "jev_on"
        private const val KEY_JEV_KEY = "jev_key"
        private const val KEY_JEV_OFF_REASON = "jev_off_reason"
        private const val KEY_JEV_SPENT_NANO = "jev_spent_nano_usd"
        private const val KEY_JEV_CAP_USD = "jev_cap_usd"

        /** Of the user's $5 for Jev in all, $1 went to the laptop's tests. */
        const val DEFAULT_JEV_CAP_USD = 3.0

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
