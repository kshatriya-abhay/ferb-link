package dev.abhay.ferblink

/**
 * How Ferb asks Ferb Link for an answer: one `ContentResolver.call` on [AUTHORITY], no binding.
 * Ferb keeps a copy of these names (it can't depend on this app's code).
 *
 * ```
 * val result = contentResolver.call(LinkContract.AUTHORITY, LinkContract.METHOD_COMPLETE, null, bundleOf(
 *     LinkContract.EXTRA_REQUEST to "set a timer for 5 minutes",
 *     LinkContract.EXTRA_TOOLS to toolsJson,   // the JSON array of schemas Needle 3 gets
 *     LinkContract.EXTRA_FACTS to facts,       // optional: "date: …" lines, added to the system prompt
 *     LinkContract.EXTRA_TIMEOUT_MS to 10_000, // optional: how long to wait for the answer
 * ))
 * val reply = NeedleReply.parse(result!!.getString(LinkContract.RESULT_REPLY)!!)
 * ```
 *
 * The call blocks until the laptop answers or the timeout passes, so make it off the main thread.
 * The reply is shaped like Needle 3's (`success`, `function_calls`, `confidence`, `error`), plus
 * `first` and `mean` (other stand-ins for a confidence, as in Ferb's bench), `text`, `model`, `ms`
 * and `source: "laptop"`. On any failure (off, not set up, laptop unreachable) `success` is false,
 * `error` says why in a few lowercase words for a chat bubble ("timed out", "can't reach your
 * computer"), `error_detail` in a sentence, and `error_kind` is one of [ERROR_KINDS], so Ferb can answer with
 * Needle 3 instead and word its own notice.
 *
 * Versions: added optional extras, reply fields, error kinds and methods keep [VERSION]; a changed
 * meaning, unit, default or a removed field bumps it. Unknown extras are ignored; callers should
 * ignore reply fields they don't know. An older Ferb Link throws `IllegalArgumentException` for a
 * method it doesn't have.
 *
 * Only apps signed with the same key as Ferb Link may call it (and `adb shell` in debug builds).
 * Ferb also needs `<queries><package android:name="dev.abhay.ferblink" /></queries>` to see it.
 */
object LinkContract {
    const val AUTHORITY = "dev.abhay.ferblink.link"

    /** Bumped when a method, extra or reply field changes meaning (not for additions). */
    const val VERSION = 1

    /**
     * No network: whether Ferb Link is on and has a server, the model it last saw (empty if none
     * yet), and whether that server last said it takes audio (for [METHOD_TRANSCRIBE]). Returns
     * [RESULT_VERSION], [RESULT_ENABLED], [RESULT_CONFIGURED], [RESULT_MODEL], [RESULT_AUDIO], and
     * [RESULT_MODEL_STATE] once a state has been seen, [RESULT_JEV], and [RESULT_JEV_OFF_REASON] when it applies.
     */
    const val METHOD_STATUS = "status"

    /**
     * Asks the laptop's model. Takes [EXTRA_REQUEST], [EXTRA_TOOLS], [EXTRA_FACTS], [EXTRA_TIMEOUT_MS];
     * returns [RESULT_REPLY], [RESULT_VERSION].
     */
    const val METHOD_COMPLETE = "complete"

    /**
     * Whether the laptop answers now: its `/health`, models and `/props`, [CHECK_TIMEOUT_MS] each.
     * Doesn't wake the model. Returns [RESULT_VERSION], [RESULT_REACHABLE], [RESULT_MODEL],
     * [RESULT_AUDIO], [RESULT_MODEL_STATE] (live when reachable, else the last known), and [RESULT_ERROR], [RESULT_ERROR_DETAIL] and [RESULT_ERROR_KIND] when it isn't reachable.
     */
    const val METHOD_CHECK = "check"

    /**
     * Returns at once ([RESULT_VERSION]) and, in the background, loads the laptop's model if it's
     * asleep (2 s, sometimes over 10), so a request soon after doesn't wait for it. Ferb calls it
     * when the user starts recording. Does nothing when Ferb Link is off or not set up.
     */
    const val METHOD_WAKE = "wake"

    /**
     * Writes down a spoken request. Takes [EXTRA_AUDIO], [EXTRA_AUDIO_MIME], [EXTRA_TIMEOUT_MS]
     * ([TRANSCRIBE_TIMEOUT_MS] without it), [EXTRA_HINTS]; returns [RESULT_REPLY], [RESULT_VERSION].
     * The reply: `success`, `heard` (what was said, Hindi in Latin letters, to show the user),
     * `text` (the same request as a plain English command, to route and send with [METHOD_COMPLETE];
     * the same as `heard` when it was said in English), `model`, `ms`, `source: "laptop"`, and
     * `error`, `error_detail` and `error_kind` on failure. No speech (silence, noise: [SpeechCheck] or the model says so) gives success with empty
     * `heard` and `text`.
     */
    const val METHOD_TRANSCRIBE = "transcribe"

    /**
     * A second opinion from TypeSafe's Jev, a cloud model, on which tool [EXTRA_REQUEST] wants:
     * one choice over [EXTRA_TOOLS] (only their names and descriptions are sent) plus "none".
     * Off unless the user turned it on in Ferb Link and gave a key ([RESULT_JEV]). Takes
     * [EXTRA_REQUEST], [EXTRA_TOOLS], [EXTRA_TIMEOUT_MS] ([DECIDE_TIMEOUT_MS] without it, at most
     * [MAX_DECIDE_TIMEOUT_MS]); returns [RESULT_REPLY], [RESULT_VERSION]. The reply: `success`,
     * `choice` (a tool's name or "none"), `probabilities` (each option's), `options` (in the
     * order asked), `model`, `ms`, `source: "jev"`, and `error`, `error_detail`, `error_kind` on
     * failure. Each call costs money: at its spending cap Jev turns itself off (`off`, and
     * [RESULT_JEV_OFF_REASON] [JEV_OFF_CAP] in status).
     */
    const val METHOD_DECIDE = "decide"

    const val EXTRA_REQUEST = "request"
    const val EXTRA_TOOLS = "tools"
    const val EXTRA_FACTS = "facts"
    /** An Int (a Long is taken too): the answer timeout in ms, clamped to [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS]; 30 s without it. */
    const val EXTRA_TIMEOUT_MS = "timeout_ms"
    /**
     * A ByteArray: the recording, at most [MAX_AUDIO_BYTES] and [MAX_AUDIO_SECONDS]. Opus in OGG,
     * AAC, or anything Android decodes; WAV is sent on as it is.
     */
    const val EXTRA_AUDIO = "audio"
    /** The recording's type, such as "audio/ogg" or "audio/mp4"; only "audio/wav" changes anything. */
    const val EXTRA_AUDIO_MIME = "audio_mime"
    /** Reserved: a String array of names and words to listen for. Ignored for now. */
    const val EXTRA_HINTS = "hints"

    const val RESULT_VERSION = "version"
    const val RESULT_ENABLED = "enabled"
    const val RESULT_CONFIGURED = "configured"
    const val RESULT_MODEL = "model"
    const val RESULT_REACHABLE = "reachable"
    const val RESULT_ERROR = "error"
    const val RESULT_ERROR_KIND = "error_kind"
    /** The reason in a sentence, next to [RESULT_ERROR]'s few words (in `check`'s result and in replies). */
    const val RESULT_ERROR_DETAIL = "error_detail"
    const val RESULT_REPLY = "reply"
    /** Boolean: the server last said it takes audio (`mise run server:mode full` on the laptop). */
    const val RESULT_AUDIO = "audio"
    /**
     * Whether the laptop's model is ready: [MODEL_LOADED], [MODEL_ASLEEP] (also while it loads) or
     * [MODEL_UNLOADED]. "loaded" only if seen loaded within [LOADED_FOR_MS]: the server puts it to
     * sleep after idling without telling the phone. Missing when no state has been seen yet.
     * Anything but loaded can take far longer to answer (a cold load), so Ferb waits longer.
     */
    const val RESULT_MODEL_STATE = "model_state"
    /** Boolean, in status: Jev is on and has a key, so [METHOD_DECIDE] may answer. */
    const val RESULT_JEV = "jev"
    /** In status, when Jev turned itself off: [JEV_OFF_CAP], it reached its spending cap. Missing otherwise. */
    const val RESULT_JEV_OFF_REASON = "jev_off_reason"
    const val JEV_OFF_CAP = "cap"
    const val MODEL_LOADED = "loaded"
    const val MODEL_ASLEEP = "asleep"
    const val MODEL_UNLOADED = "unloaded"
    /** The server sleeps after 300 s idle (`SLEEP_IDLE` in server/server.conf); a margin below that. */
    const val LOADED_FOR_MS = 270_000L

    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    const val CHECK_TIMEOUT_MS = 2_000
    const val TRANSCRIBE_TIMEOUT_MS = 15_000
    /** Jev's answer is a second opinion: past this, Ferb goes ahead without it. */
    const val DECIDE_TIMEOUT_MS = 3_000
    const val MAX_DECIDE_TIMEOUT_MS = 10_000
    const val MAX_AUDIO_BYTES = 512 * 1024
    const val MAX_AUDIO_SECONDS = 30

    /** `error_kind` values ([ErrorKind]); treat one you don't know as "server". */
    val ERROR_KINDS = ErrorKind.entries.map { it.wire }
}
