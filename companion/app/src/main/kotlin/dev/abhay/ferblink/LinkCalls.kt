package dev.abhay.ferblink

import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** What [LinkCalls] needs from [LinkSettings], so tests can pass their own. */
interface LinkState {
    val enabled: Boolean
    val configured: Boolean
    var lastModel: String
    var audio: Boolean
    /** The model's state as last seen ([LinkContract.RESULT_MODEL_STATE]'s words; empty if none yet), and when. */
    var modelState: String
    var modelStateAt: Long
    fun client(trace: ((String) -> Unit)? = null): ServerClient
}

/**
 * [LinkProvider]'s answers to [LinkContract]'s methods, with no Android in them: extras in and the
 * result out as maps of a Bundle's keys and values, so the contract fixtures (`contract/v1/`) can
 * be checked in JVM tests. [decode] turns a recording into WAV ([AudioDecoder.toWav] on the phone).
 */
class LinkCalls(
    private val state: LinkState,
    private val background: Executor,
    private val debug: Boolean,
    private val decode: (ByteArray, String?) -> ByteArray = AudioDecoder::toWav,
    private val trace: ((String) -> Unit)? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun answer(method: String, extras: Map<String, Any?>, caller: String): Map<String, Any?> = when (method) {
        LinkContract.METHOD_STATUS -> buildMap {
            put(LinkContract.RESULT_VERSION, LinkContract.VERSION)
            put(LinkContract.RESULT_ENABLED, state.enabled)
            put(LinkContract.RESULT_CONFIGURED, state.configured)
            put(LinkContract.RESULT_MODEL, state.lastModel)
            put(LinkContract.RESULT_AUDIO, state.audio)
            state.modelStateNow(now())?.let { put(LinkContract.RESULT_MODEL_STATE, it) }
        }
        LinkContract.METHOD_COMPLETE -> {
            val request = (extras[LinkContract.EXTRA_REQUEST] as? String).orEmpty()
            val tools = extras[LinkContract.EXTRA_TOOLS] as? String ?: "[]"
            val facts = extras[LinkContract.EXTRA_FACTS] as? String
            val reply = when {
                !state.enabled -> Reply.failed(ErrorKind.Off, "Ferb Link is off")
                !state.configured -> Reply.failed(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                request.isBlank() -> Reply.failed(ErrorKind.BadRequest, "No request", short = "bad request: no request")
                // Hindi amounts and times worked out, as for spoken requests (Amounts.forRequest).
                else -> state.client(trace).complete(Amounts.forRequest(request), tools, facts, timeoutMs(extras)).also {
                    if (it.failure == null) {
                        state.lastModel = it.model
                        state.saw(ModelState.Loaded, now())
                        refreshAudio()
                    }
                    RecentRequests.add(caller, request, it)
                }
            }
            mapOf(LinkContract.RESULT_VERSION to LinkContract.VERSION, LinkContract.RESULT_REPLY to reply.toJson())
        }
        LinkContract.METHOD_CHECK -> {
            val failure = when {
                !state.enabled -> Failure(ErrorKind.Off, "Ferb Link is off")
                !state.configured -> Failure(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                else -> null
            }
            val health = failure?.let { Health(false, null, 0, it) } ?: state.client(trace).health(LinkContract.CHECK_TIMEOUT_MS)
            health.model?.let { state.lastModel = it }
            health.audio?.let { state.audio = it }
            health.state?.let { state.saw(it, now()) }
            buildMap {
                put(LinkContract.RESULT_VERSION, LinkContract.VERSION)
                put(LinkContract.RESULT_REACHABLE, health.ok)
                put(LinkContract.RESULT_MODEL, health.model ?: "")
                put(LinkContract.RESULT_AUDIO, state.audio)
                state.modelStateNow(now())?.let { put(LinkContract.RESULT_MODEL_STATE, it) }
                health.failure?.let {
                    put(LinkContract.RESULT_ERROR, it.short)
                    put(LinkContract.RESULT_ERROR_DETAIL, it.message)
                    put(LinkContract.RESULT_ERROR_KIND, it.kind.wire)
                }
            }
        }
        LinkContract.METHOD_WAKE -> {
            if (state.enabled && state.configured && waking.compareAndSet(false, true)) {
                background.execute {
                    try {
                        state.client(trace).wake(LinkContract.TRANSCRIBE_TIMEOUT_MS * 2)?.audio?.let { state.audio = it }
                    } finally {
                        waking.set(false)
                    }
                }
            }
            mapOf(LinkContract.RESULT_VERSION to LinkContract.VERSION)
        }
        LinkContract.METHOD_TRANSCRIBE -> {
            // `adb shell content call` can't pass a ByteArray, so debug builds also take base64 text.
            val audio = extras[LinkContract.EXTRA_AUDIO] as? ByteArray
                ?: (extras[DEBUG_AUDIO_BASE64] as? String)?.takeIf { debug }?.let { Base64.getDecoder().decode(it) }
            val mime = extras[LinkContract.EXTRA_AUDIO_MIME] as? String
            var recording = "Recording"
            val transcript = when {
                !state.enabled -> Transcript.failed(ErrorKind.Off, "Ferb Link is off")
                !state.configured -> Transcript.failed(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                audio == null || audio.isEmpty() -> Transcript.failed(ErrorKind.BadRequest, "No recording", short = "bad request: no audio")
                audio.size > LinkContract.MAX_AUDIO_BYTES -> Transcript.failed(ErrorKind.BadRequest, "The recording is over ${LinkContract.MAX_AUDIO_BYTES / 1024} KB", short = "bad request: recording too big")
                else -> try {
                    val wav = decode(audio, mime)
                    recording = "Recording, %.1f s".format(AudioDecoder.seconds(wav))
                    Voice.transcribe(state.client(trace), wav, timeoutMs(extras) ?: LinkContract.TRANSCRIBE_TIMEOUT_MS).also {
                        if (it.failure == null) {
                            state.lastModel = it.model
                            // Silence is answered on the phone: the model wasn't asked.
                            if (it.model.isNotEmpty()) state.saw(ModelState.Loaded, now())
                        }
                        // A server in text mode turns audio down: stop offering the mic.
                        else if (it.failure.kind == ErrorKind.Server) refreshAudio()
                    }
                } catch (e: AudioDecoder.Unreadable) {
                    Transcript.failed(ErrorKind.BadRequest, e.message ?: "Couldn't read the recording", short = "bad request: unreadable recording")
                }
            }
            if (audio != null && audio.isNotEmpty()) RecentRequests.addVoice(caller, recording, transcript)
            mapOf(LinkContract.RESULT_VERSION to LinkContract.VERSION, LinkContract.RESULT_REPLY to transcript.toJson())
        }
        else -> throw IllegalArgumentException("Unknown method $method")
    }

    /** Reads `/props` again in the background, so `status` notices the laptop switching modes. */
    private fun refreshAudio() = background.execute {
        state.client(trace).props(LinkContract.CHECK_TIMEOUT_MS)?.audio?.let { state.audio = it }
    }

    companion object {
        /** Debug builds only: the recording as base64 text, for trying `transcribe` from `adb shell`. */
        const val DEBUG_AUDIO_BASE64 = "audio_base64"

        /** `adb shell`'s user id (android.os.Process.SHELL_UID isn't public). */
        const val SHELL_UID = 2000

        private val waking = AtomicBoolean(false)

        /** The caller's answer timeout (an Int, or a Long), clamped; null without one. */
        fun timeoutMs(extras: Map<String, Any?>): Int? {
            val value = (extras[LinkContract.EXTRA_TIMEOUT_MS] as? Number)?.toLong() ?: return null
            return value.coerceIn(LinkContract.MIN_TIMEOUT_MS.toLong(), LinkContract.MAX_TIMEOUT_MS.toLong()).toInt()
        }

        /**
         * Only apps signed with this app's key (Ferb, built on the same machine), and `adb shell` in
         * debug builds for trying it by hand. [signatureMatches] is asked only when needed.
         */
        fun callerAllowed(uid: Int, myUid: Int, debug: Boolean, signatureMatches: () -> Boolean) =
            uid == myUid || (debug && uid == SHELL_UID) || signatureMatches()
    }
}

/** Notes the model's state, seen at [at] (ms). */
fun LinkState.saw(model: ModelState, at: Long) {
    modelState = when (model) {
        ModelState.Loaded -> LinkContract.MODEL_LOADED
        ModelState.Loading, ModelState.Asleep -> LinkContract.MODEL_ASLEEP
        ModelState.Unloaded -> LinkContract.MODEL_UNLOADED
    }
    modelStateAt = at
}

/** The last state seen, with "loaded" turned "asleep" once the server would have put it to sleep; null if none. */
fun LinkState.modelStateNow(now: Long): String? = modelState.ifEmpty { null }?.let {
    if (it == LinkContract.MODEL_LOADED && now - modelStateAt > LinkContract.LOADED_FOR_MS) LinkContract.MODEL_ASLEEP else it
}
