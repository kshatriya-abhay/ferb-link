package dev.abhay.ferblink

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LinkViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = LinkSettings(application)

    data class State(
        val serverUrl: String,
        val apiKey: String,
        val enabled: Boolean,
        /** The fields differ from what's saved. */
        val edited: Boolean = false,
        val checking: Boolean = false,
        val health: Health? = null,
        val unloading: Boolean = false,
        /** Why the last unload failed. */
        val unloadError: String? = null,
        val trying: Boolean = false,
        val tried: Pair<String, Reply>? = null,
        /** An address and key are saved: until then the screen shows how to connect. */
        val configured: Boolean = false,
        /** A pairing link waiting for the user to confirm it, or why one didn't work. */
        val pairing: Pairing.Offer? = null,
        val pairingProblem: String? = null,
    )

    private val _state = MutableStateFlow(State(settings.serverUrl, settings.apiKey, settings.enabled, configured = settings.configured))
    val state: StateFlow<State> = _state.asStateFlow()
    val recent = RecentRequests.entries

    init {
        check()
    }

    fun editUrl(url: String) = _state.update { it.copy(serverUrl = url, edited = true, health = null) }
    fun editKey(key: String) = _state.update { it.copy(apiKey = key, edited = true, health = null) }

    /** Saves the address and key, then checks them. */
    fun save() {
        settings.serverUrl = _state.value.serverUrl
        settings.apiKey = _state.value.apiKey
        _state.update { it.copy(serverUrl = settings.serverUrl, apiKey = settings.apiKey, edited = false, configured = settings.configured) }
        check()
    }

    /** A ferblink://pair link arrived: ask before using it (any app can open one). */
    fun offerPairing(link: String) = when (val result = Pairing.parse(link)) {
        is Pairing.Result.Ok -> _state.update { it.copy(pairing = result.offer, pairingProblem = null) }
        is Pairing.Result.Bad -> _state.update { it.copy(pairing = null, pairingProblem = result.reason) }
    }

    /** The user confirmed the pairing: save its address and key, turn Ferb Link on, and check. */
    fun acceptPairing() {
        val offer = _state.value.pairing ?: return
        settings.serverUrl = offer.url
        settings.apiKey = offer.key
        settings.enabled = true
        _state.update {
            it.copy(serverUrl = settings.serverUrl, apiKey = settings.apiKey, enabled = true, edited = false, configured = settings.configured, pairing = null, health = null)
        }
        check()
    }

    fun dismissPairing() = _state.update { it.copy(pairing = null, pairingProblem = null) }

    /** Drops unsaved edits to the address and key. */
    fun cancelEdit() = _state.update { it.copy(serverUrl = settings.serverUrl, apiKey = settings.apiKey, edited = false) }

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        _state.update { it.copy(enabled = enabled) }
    }

    fun check() {
        if (!settings.configured) return
        _state.update { it.copy(checking = true, unloadError = null) }
        viewModelScope.launch {
            val health = withContext(Dispatchers.IO) { settings.client(null).health() }
            health.model?.let { settings.lastModel = it }
            health.audio?.let { settings.audio = it }
            _state.update { it.copy(checking = false, health = health) }
        }
    }

    /** Stops the model on the laptop, freeing its memory, then checks again to show its state. */
    fun unload() {
        if (!settings.configured) return
        _state.update { it.copy(unloading = true, unloadError = null) }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) { settings.client(null).unload() }
            _state.update { it.copy(unloading = false) }
            check()
            _state.update { it.copy(unloadError = failure?.message) }
        }
    }

    /** Sends [request] to the laptop with [SAMPLE_TOOLS], as Ferb would. */
    fun tryRequest(request: String) {
        if (!settings.configured || request.isBlank()) return
        _state.update { it.copy(trying = true) }
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { settings.client(null).complete(request, SAMPLE_TOOLS) }
            if (reply.failure == null) settings.lastModel = reply.model
            RecentRequests.add("this app", request, reply)
            _state.update { it.copy(trying = false, tried = request to reply) }
            check() // the request may have woken or loaded the model
        }
    }

    companion object {
        /** A few of Ferb's tools, simplified, to try the server without Ferb. */
        val SAMPLE_TOOLS = """
            [
              {"name": "set_timer", "description": "Start a countdown timer for a number of minutes",
               "parameters": {"type": "object", "properties": {"minutes": {"type": "integer", "description": "Length in minutes"}}, "required": ["minutes"]}},
              {"name": "set_alarm", "description": "Set an alarm for a time of day",
               "parameters": {"type": "object", "properties": {"time": {"type": "string", "description": "The time, e.g. 6:30 am"}}, "required": ["time"]}},
              {"name": "flashlight", "description": "Turn the flashlight on or off",
               "parameters": {"type": "object", "properties": {"on": {"type": "boolean"}}, "required": ["on"]}}
            ]
        """.trimIndent()
    }
}
