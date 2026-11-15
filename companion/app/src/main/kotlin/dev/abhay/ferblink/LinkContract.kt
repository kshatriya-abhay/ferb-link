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
     * [RESULT_MODEL_STATE] once a state has been seen, [RESULT_JEV], [RESULT_JEV_OFF_REASON] when it
     * applies, and [RESULT_JSON_SCHEMA].
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
     * TypeSafe's Jev, a cloud model, picks which tool [EXTRA_REQUEST] wants (Ferb's "Use Jev to select the tool"):
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

    /**
     * A typed request (Hinglish, as Ferb sends it) as plain English commands, one per action, for
     * Needle 3, or none when it asks for nothing. The laptop's model writes them with [Rewrite]'s
     * v2 prompt (on the held-out suite v2.1 ran one wrong action; Ferb now sends its own prompt, so
     * this is only for an older Ferb); Hindi amounts are worked out in code first. Takes
     * [EXTRA_REQUEST], [EXTRA_NOW] (optional: the phone's clock without it), [EXTRA_TIMEOUT_MS];
     * returns [RESULT_REPLY], [RESULT_VERSION]. The reply: `success`, `commands` (a list of
     * strings, empty when nothing is asked), `invented` (numbers and names in the commands that
     * the request didn't hold: don't run those), `model`, `ms`, `source: "laptop"`, and `error`,
     * `error_detail`, `error_kind` on failure, as for [METHOD_COMPLETE].
     *
     * With [EXTRA_INSTRUCTIONS] it runs the caller's own prompt instead (Ferb's GeminiRewrite, so
     * Gemma and Gemini Nano share one prompt): the instructions as the system message, the request
     * verbatim as the user message, greedy, at most [EXTRA_MAX_TOKENS] tokens, and no pre-pass
     * ([EXTRA_NOW] is ignored). `commands` then holds the first line as one command, or nothing
     * for NONE, and `invented` is always empty (Ferb's prompt writes Hindi amounts as numbers, so
     * the check would flag right answers); the reply adds `raw` (the model's text, for the caller's own parsing) and
     * `"instructions": true`. A first line cut off at the token cap fails ("answer cut off",
     * bad_reply), so half a command never runs. An older Ferb Link ignores the extra and answers with v2, without
     * that echo. Every instructed reply has `truncated`: whether the model stopped at the token cap.
     *
     * With [EXTRA_JSON_SCHEMA] too, the answer is JSON in that schema (llama-server turns it into a
     * grammar): the reply adds `"schema": true` and `output`, the parsed JSON object, and
     * `commands` stays empty (Ferb reads `output`). An answer that hit the token cap always fails
     * ("answer cut off", `truncated: true`), as cut JSON is half an answer; one that isn't a JSON
     * object is bad_reply ("not JSON"). Ferb Link doesn't check `output` against the schema. A
     * schema llama-server can't use is bad_request ("bad request: schema not supported"). An
     * older Ferb Link ignores the extra, with no `schema` echo and no [RESULT_JSON_SCHEMA] in
     * status: Ferb checks status first and uses a line prompt then.
     */
    const val METHOD_REWRITE = "rewrite"

    const val EXTRA_REQUEST = "request"
    const val EXTRA_TOOLS = "tools"
    const val EXTRA_FACTS = "facts"
    /** For [METHOD_REWRITE]: when "now" is, to work out "kal" and weekdays, as "2026-10-16T09:00 (Friday)". */
    const val EXTRA_NOW = "now"
    /** A String, for [METHOD_REWRITE]: the caller's system prompt, at most [MAX_INSTRUCTIONS_CHARS]; blank counts as absent. */
    const val EXTRA_INSTRUCTIONS = "instructions"
    /**
     * An Int, with [EXTRA_INSTRUCTIONS]: the answer's token cap, clamped to 1..[MAX_REWRITE_TOKENS];
     * [REWRITE_MAX_TOKENS] without it ([REWRITE_JSON_MAX_TOKENS] with [EXTRA_JSON_SCHEMA]).
     */
    const val EXTRA_MAX_TOKENS = "max_tokens"
    /**
     * A String, with [EXTRA_INSTRUCTIONS]: a JSON Schema (a JSON object, at most
     * [MAX_JSON_SCHEMA_CHARS]) for the answer; blank counts as absent. Without instructions, or not a
     * JSON object, it's bad_request. Its default token cap is [REWRITE_JSON_MAX_TOKENS].
     */
    const val EXTRA_JSON_SCHEMA = "json_schema"
    const val MAX_JSON_SCHEMA_CHARS = 8_000
    const val MAX_INSTRUCTIONS_CHARS = 16_000
    /**
     * [EXTRA_REQUEST]'s length cap for complete, rewrite and decide: longer is bad_request. A
     * request with instructions that still overflows the model's context is bad_request too.
     */
    const val MAX_REQUEST_CHARS = 2_000
    const val REWRITE_MAX_TOKENS = 40
    /** With [EXTRA_JSON_SCHEMA]: braces and key names cost tokens, and Devanagari content several a word. */
    const val REWRITE_JSON_MAX_TOKENS = 160
    const val MAX_REWRITE_TOKENS = 512
    /** An Int (a Long is taken too): the answer timeout in ms, clamped to [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS]; [DEFAULT_TIMEOUT_MS] without it. */
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
    /** Boolean, in status: [METHOD_REWRITE] takes [EXTRA_JSON_SCHEMA]. Missing in an older Ferb Link. */
    const val RESULT_JSON_SCHEMA = "json_schema"
    /**
     * Whether the laptop's model is ready: [MODEL_LOADED], [MODEL_ASLEEP] (also while it loads) or
     * [MODEL_UNLOADED]. "loaded" only if seen loaded within the server's idle time less
     * [LOADED_MARGIN_MS]: the server puts it to sleep after idling without telling the phone. Ferb
     * Link reads that time from the server (`SLEEP_IDLE` in server/server.conf, which run.sh passes as
     * `--sleep-idle-seconds`); until it has, [DEFAULT_SLEEP_IDLE_SECONDS]. Missing when no state has been seen yet.
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
    /** How long before the server's sleep "loaded" stops being reported. */
    const val LOADED_MARGIN_MS = 30_000L
    /** server.conf's default `SLEEP_IDLE`, used only until the server has been seen and said its own. */
    const val DEFAULT_SLEEP_IDLE_SECONDS = 300

    /** Without [EXTRA_TIMEOUT_MS], for complete and rewrite (transcribe and decide have their own). */
    const val DEFAULT_TIMEOUT_MS = 30_000
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    const val CHECK_TIMEOUT_MS = 2_000
    const val TRANSCRIBE_TIMEOUT_MS = 15_000
    /** Without [EXTRA_TIMEOUT_MS]; Ferb sends its own, and goes ahead without Jev past it. */
    const val DECIDE_TIMEOUT_MS = 3_000
    const val MAX_DECIDE_TIMEOUT_MS = 10_000
    const val MAX_AUDIO_BYTES = 512 * 1024
    const val MAX_AUDIO_SECONDS = 30

    /** `error_kind` values ([ErrorKind]); treat one you don't know as "server". */
    val ERROR_KINDS = ErrorKind.entries.map { it.wire }
}
