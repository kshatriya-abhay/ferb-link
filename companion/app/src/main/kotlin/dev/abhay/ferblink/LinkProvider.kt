package dev.abhay.ferblink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ferb's way in ([LinkContract]): `call` only, no data. Each call runs on a binder thread, so a
 * request can block on the network until the laptop answers.
 */
class LinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = checkCaller()
        val settings = LinkSettings(context!!)
        return when (method) {
            LinkContract.METHOD_STATUS -> bundleOf(
                LinkContract.RESULT_VERSION to LinkContract.VERSION,
                LinkContract.RESULT_ENABLED to settings.enabled,
                LinkContract.RESULT_CONFIGURED to settings.configured,
                LinkContract.RESULT_MODEL to settings.lastModel,
                LinkContract.RESULT_AUDIO to settings.audio,
            )
            LinkContract.METHOD_COMPLETE -> {
                val request = extras?.getString(LinkContract.EXTRA_REQUEST).orEmpty()
                val tools = extras?.getString(LinkContract.EXTRA_TOOLS) ?: "[]"
                val facts = extras?.getString(LinkContract.EXTRA_FACTS)
                val reply = when {
                    !settings.enabled -> Reply.failed(ErrorKind.Off, "Ferb Link is off")
                    !settings.configured -> Reply.failed(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                    request.isBlank() -> Reply.failed(ErrorKind.BadRequest, "No request", short = "bad request: no request")
                    else -> settings.client().complete(request, tools, facts, timeoutMs(extras)).also {
                        if (it.failure == null) {
                            settings.lastModel = it.model
                            refreshAudio(settings)
                        }
                        RecentRequests.add(caller, request, it)
                    }
                }
                bundleOf(LinkContract.RESULT_VERSION to LinkContract.VERSION, LinkContract.RESULT_REPLY to reply.toJson())
            }
            LinkContract.METHOD_CHECK -> {
                val failure = when {
                    !settings.enabled -> Failure(ErrorKind.Off, "Ferb Link is off")
                    !settings.configured -> Failure(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                    else -> null
                }
                val health = failure?.let { Health(false, null, 0, it) } ?: settings.client().health(LinkContract.CHECK_TIMEOUT_MS)
                health.model?.let { settings.lastModel = it }
                health.audio?.let { settings.audio = it }
                bundleOf(
                    LinkContract.RESULT_VERSION to LinkContract.VERSION,
                    LinkContract.RESULT_REACHABLE to health.ok,
                    LinkContract.RESULT_MODEL to (health.model ?: ""),
                    LinkContract.RESULT_AUDIO to settings.audio,
                ).apply {
                    health.failure?.let {
                        putString(LinkContract.RESULT_ERROR, it.short)
                        putString(LinkContract.RESULT_ERROR_DETAIL, it.message)
                        putString(LinkContract.RESULT_ERROR_KIND, it.kind.wire)
                    }
                }
            }
            LinkContract.METHOD_WAKE -> {
                if (settings.enabled && settings.configured && waking.compareAndSet(false, true)) {
                    background.execute {
                        try {
                            settings.client().wake(LinkContract.TRANSCRIBE_TIMEOUT_MS * 2)?.audio?.let { settings.audio = it }
                        } finally {
                            waking.set(false)
                        }
                    }
                }
                bundleOf(LinkContract.RESULT_VERSION to LinkContract.VERSION)
            }
            LinkContract.METHOD_TRANSCRIBE -> {
                // `adb shell content call` can't pass a ByteArray, so debug builds also take base64 text.
                val audio = extras?.getByteArray(LinkContract.EXTRA_AUDIO)
                    ?: extras?.getString(DEBUG_AUDIO_BASE64)?.takeIf { BuildConfig.DEBUG }?.let { Base64.getDecoder().decode(it) }
                val mime = extras?.getString(LinkContract.EXTRA_AUDIO_MIME)
                var recording = "Recording"
                val transcript = when {
                    !settings.enabled -> Transcript.failed(ErrorKind.Off, "Ferb Link is off")
                    !settings.configured -> Transcript.failed(ErrorKind.NotSetUp, "Ferb Link has no server set up")
                    audio == null || audio.isEmpty() -> Transcript.failed(ErrorKind.BadRequest, "No recording", short = "bad request: no audio")
                    audio.size > LinkContract.MAX_AUDIO_BYTES -> Transcript.failed(ErrorKind.BadRequest, "The recording is over ${LinkContract.MAX_AUDIO_BYTES / 1024} KB", short = "bad request: recording too big")
                    else -> try {
                        val wav = AudioDecoder.toWav(audio, mime)
                        recording = "Recording, %.1f s".format(AudioDecoder.seconds(wav))
                        settings.client().transcribe(wav, timeoutMs(extras) ?: LinkContract.TRANSCRIBE_TIMEOUT_MS).also {
                            if (it.failure == null) settings.lastModel = it.model
                            // A server in text mode turns audio down: stop offering the mic.
                            else if (it.failure.kind == ErrorKind.Server) refreshAudio(settings)
                        }
                    } catch (e: AudioDecoder.Unreadable) {
                        Transcript.failed(ErrorKind.BadRequest, e.message ?: "Couldn't read the recording", short = "bad request: unreadable recording")
                    }
                }
                if (audio != null && audio.isNotEmpty()) RecentRequests.addVoice(caller, recording, transcript)
                bundleOf(LinkContract.RESULT_VERSION to LinkContract.VERSION, LinkContract.RESULT_REPLY to transcript.toJson())
            }
            else -> throw IllegalArgumentException("Unknown method $method")
        }
    }

    /** Reads `/props` again in the background, so `status` notices the laptop switching modes. */
    private fun refreshAudio(settings: LinkSettings) = background.execute {
        settings.client().props(LinkContract.CHECK_TIMEOUT_MS)?.audio?.let { settings.audio = it }
    }

    /** The caller's answer timeout (an Int, or a Long), clamped; null without one. */
    private fun timeoutMs(extras: Bundle?): Int? {
        @Suppress("DEPRECATION") // the typed get() would refuse a Long where an Int was expected
        val value = (extras?.get(LinkContract.EXTRA_TIMEOUT_MS) as? Number)?.toLong() ?: return null
        return value.coerceIn(LinkContract.MIN_TIMEOUT_MS.toLong(), LinkContract.MAX_TIMEOUT_MS.toLong()).toInt()
    }

    /**
     * Only apps signed with this app's key (Ferb, built on the same machine), and `adb shell` in
     * debug builds for trying it by hand. Returns the caller's package name, for the recent list.
     */
    private fun checkCaller(): String {
        val uid = Binder.getCallingUid()
        val packages = context!!.packageManager
        val allowed = uid == Process.myUid() ||
            (BuildConfig.DEBUG && uid == SHELL_UID) ||
            packages.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        if (!allowed) throw SecurityException("Only apps signed with Ferb Link's key may use it")
        return packages.getNameForUid(uid) ?: "uid $uid"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        /** `adb shell`'s user id (android.os.Process.SHELL_UID isn't public). */
        const val SHELL_UID = 2000

        /** Debug builds only: the recording as base64 text, for trying `transcribe` from `adb shell`. */
        const val DEBUG_AUDIO_BASE64 = "audio_base64"

        /** For work a call doesn't wait for: waking the model, reading `/props`. */
        val background: ExecutorService = Executors.newSingleThreadExecutor()
        val waking = AtomicBoolean(false)
    }
}
