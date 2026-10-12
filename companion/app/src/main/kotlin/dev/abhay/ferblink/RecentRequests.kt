package dev.abhay.ferblink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last requests sent to the laptop, newest first, so the user can see what left the phone.
 * Kept in memory only: gone when the process ends.
 */
object RecentRequests {
    /** A request and its [reply], or a recording ([request] says how long) and its [transcript]. */
    data class Entry(val at: Long, val caller: String, val request: String, val reply: Reply? = null, val transcript: Transcript? = null)

    private const val KEEP = 30

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(caller: String, request: String, reply: Reply) = add(Entry(System.currentTimeMillis(), caller, request, reply = reply))

    fun addVoice(caller: String, recording: String, transcript: Transcript) =
        add(Entry(System.currentTimeMillis(), caller, recording, transcript = transcript))

    private fun add(entry: Entry) = _entries.update { (listOf(entry) + it).take(KEEP) }
}
