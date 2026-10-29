package dev.abhay.ferblink

import androidx.annotation.StringRes

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A pairing link, `ferblink://pair?url=…&key=…`: the computer's address and API key in one go, so
 * nobody types a 32-character key on a phone. `mise run server:pair` shows it as a QR code (the
 * camera app opens it), and `mise run companion:pair` sends it over adb.
 *
 * Any app or web page can open a ferblink:// link, so a link only ever offers: the user sees the
 * computer's name and confirms before anything is saved ([LinkViewModel.acceptPairing]).
 */
object Pairing {
    const val SCHEME = "ferblink"
    private const val HOST = "pair"

    /** What a link offers: an address that passed [LinkSettings.urlProblem], and a key. */
    data class Offer(val url: String, val key: String) {
        /** The computer's name, shown in full when asking. */
        val host: String get() = URI(url).host

        override fun toString() = "Offer($url, key hidden)"
    }

    sealed interface Result {
        data class Ok(val offer: Offer) : Result
        data class Bad(@StringRes val reason: Int) : Result
    }

    fun isPairingLink(link: String?) = link != null && link.startsWith("$SCHEME:", ignoreCase = true)

    fun parse(link: String): Result {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return Result.Bad(R.string.pair_not_a_link)
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) {
            return Result.Bad(R.string.pair_not_a_link)
        }
        val params = uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
            val (name, value) = part.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            decode(name) to decode(value)
        }.toMap()
        val url = params["url"]?.trim()?.trimEnd('/').orEmpty()
        val key = params["key"]?.trim().orEmpty()
        if (url.isEmpty()) return Result.Bad(R.string.pair_no_address)
        LinkSettings.urlProblem(url)?.let { return Result.Bad(it) }
        if (!KEY.matches(key)) return Result.Bad(R.string.pair_no_key)
        return Result.Ok(Offer(url, key))
    }

    /** The link for [url] and [key], as `server:pair` writes it. */
    fun link(url: String, key: String) = "$SCHEME://$HOST?url=${encode(url)}&key=${encode(key)}"

    // What `server:install` makes (24 random bytes, base64url) and any similar key: no spaces.
    private val KEY = Regex("[A-Za-z0-9._~+/=-]{16,256}")

    private fun decode(text: String) = runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault("")
    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)
}
