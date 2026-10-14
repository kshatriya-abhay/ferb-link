package dev.abhay.ferblink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.core.os.bundleOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ferb's way in ([LinkContract]): `call` only, no data. Each call runs on a binder thread, so a
 * request can block on the network until the laptop answers. The answers are [LinkCalls]'; this
 * checks the caller and turns Bundles into maps and back.
 */
class LinkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = checkCaller()
        val arrived = System.nanoTime()
        Log.i(TAG, "$method from $caller")
        val calls = LinkCalls(LinkSettings(context!!), background, BuildConfig.DEBUG, trace = trace)
        return bundleOf(*calls.answer(method, extras.toMap(), caller).toList().toTypedArray())
            .also { Log.i(TAG, "$method answered in ${(System.nanoTime() - arrived) / 1_000_000} ms") }
    }

    @Suppress("DEPRECATION") // the typed getters would need each extra's type; LinkCalls checks them
    private fun Bundle?.toMap(): Map<String, Any?> = this?.keySet()?.associateWith { get(it) }.orEmpty()

    /** Throws unless [LinkCalls.callerAllowed]. Returns the caller's package name, for the recent list. */
    private fun checkCaller(): String {
        val uid = Binder.getCallingUid()
        val packages = context!!.packageManager
        val allowed = LinkCalls.callerAllowed(uid, Process.myUid(), BuildConfig.DEBUG) {
            packages.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH
        }
        if (!allowed) throw SecurityException("Only apps signed with Ferb Link's key may use it")
        return packages.getNameForUid(uid) ?: "uid $uid"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        /** `adb logcat -s FerbLink`: each call's arrival and time, and each HTTP request's timings. */
        const val TAG = "FerbLink"
        val trace: (String) -> Unit = { Log.i(TAG, it) }

        /** For work a call doesn't wait for: waking the model, reading `/props`. */
        val background: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
