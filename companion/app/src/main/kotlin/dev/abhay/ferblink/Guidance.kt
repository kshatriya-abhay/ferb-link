package dev.abhay.ferblink

import androidx.annotation.StringRes

/**
 * What to do about a failed check, in a sentence with one action, under the status card's error.
 * Only when it adds an action the error's detail doesn't already give: never the same thing twice.
 * [vpnActive] is whether this phone has a VPN up (Tailscale is one); null when unknown.
 */
object Guidance {
    @StringRes
    fun of(failure: Failure?, vpnActive: Boolean?): Int? = when (failure?.kind) {
        null, ErrorKind.Off, ErrorKind.NotSetUp, ErrorKind.BadRequest -> null
        ErrorKind.Unreachable -> when {
            // "Is Tailscale on?" with no VPN up, or "plain HTTP needs the Tailscale name": said already.
            failure.message == ServerClient.HTTP_NEEDS_TAILSCALE_NAME -> null
            vpnActive == false && failure.message == ServerClient.NO_NAME -> null
            vpnActive == false -> R.string.guide_tailscale_off
            else -> R.string.guide_unreachable
        }
        ErrorKind.Timeout -> R.string.guide_timeout
        ErrorKind.Auth -> R.string.guide_auth
        ErrorKind.Server ->
            if (failure.short == "model loading") R.string.guide_loading
            else R.string.guide_server
        ErrorKind.BadReply -> R.string.guide_bad_reply
    }
}

/**
 * First-use hints: one line each, shown once under what they explain, until "Got it"
 * (companion/README.md, "Help on the screen").
 */
enum class Hint(@StringRes val text: Int) {
    Switch(R.string.hint_switch),
    Voice(R.string.hint_voice),
    TextOnly(R.string.hint_text_only),
    Unload(R.string.hint_unload),
    Try(R.string.hint_try);

    companion object {
        /** The hints the status card can show for [health], in order: voice or text-only, then Unload. */
        fun forStatus(health: Health?): List<Hint> {
            if (health == null || !health.ok) return emptyList()
            return listOfNotNull(
                when (health.audio) { true -> Voice; false -> TextOnly; null -> null },
                Unload.takeIf { health.state == ModelState.Loaded || health.state == ModelState.Asleep },
            )
        }
    }
}
