package dev.abhay.ferblink

/**
 * What to do about a failed check, in a sentence with one action, under the status card's error.
 * [vpnActive] is whether this phone has a VPN up (Tailscale is one); null when unknown.
 */
object Guidance {
    fun of(failure: Failure?, vpnActive: Boolean?): String? = when (failure?.kind) {
        null, ErrorKind.Off, ErrorKind.NotSetUp, ErrorKind.BadRequest -> null
        ErrorKind.Unreachable ->
            if (vpnActive == false) "Tailscale isn't connected on this phone. Turn it on, on the same tailnet as your computer."
            else "Check that your computer is on and awake, and that the server is on the tailnet: mise run server:status, then mise run server:tailnet on."
        ErrorKind.Timeout -> "If your computer just woke up, try again. Otherwise look at mise run server:logs on it."
        ErrorKind.Auth -> "The key doesn't match your computer's. Pair again: mise run companion:pair, or server:pair."
        ErrorKind.Server ->
            if (failure.short == "model loading") "Try again in a few seconds."
            else "Look at mise run server:logs on your computer."
        ErrorKind.BadReply -> "Something other than Ferb Link's server answered at this address. Check the address, or pair again."
    }
}

/**
 * First-use hints: one line each, shown once under what they explain, until "Got it". The README's
 * "Before the first release" list.
 */
enum class Hint(val text: String) {
    Switch("Ferb's requests go to your computer. When this is off, or the computer can't be reached, Ferb answers on the phone."),
    Voice("Your computer can hear: in Ferb, pick Computer under the chat box and tap the mic."),
    TextOnly("Voice needs the server in full mode: mise run server:mode full on your computer."),
    Unload("Unload frees your computer's GPU now. The next request starts the model again, in a few seconds."),
    Try("Sends a request with three sample tools: a timer, an alarm and the flashlight. Tap an answer for details.");

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
