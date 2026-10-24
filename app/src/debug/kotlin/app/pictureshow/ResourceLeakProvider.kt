/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only VM guard and shell checkpoint. No account data or network access. */
class ResourceLeakProvider : ContentProvider() {
    private val violations = AtomicInteger()

    override fun onCreate(): Boolean {
        val policy = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= 28) {
            policy.penaltyListener({ it.run() }) { violation ->
                val count = violations.incrementAndGet()
                Log.e("PictureshowLeaks", "VM leak violation #$count", violation)
            }
        }
        StrictMode.setVmPolicy(policy.build())
        Log.i("PictureshowLeaks", "VM leak guard installed")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not enforce the manifest's read/write permissions.
        requireNotNull(context).enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, "Debug leak checkpoint requires DUMP")
        val result = Bundle()
        when (method) {
            "collect" -> collect()
            "closed-canary" -> {
                FileInputStream("/dev/null").use { it.read() }
                collect()
            }
            "self-test" -> {
                abandonCanary()
                collect()
            }
            "counts" -> Unit
            "threads" -> result.putString(
                "threads",
                Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n\n") { (thread, stack) ->
                    "${thread.name} [${thread.state}]\n" + stack.joinToString("\n") { "    at $it" }
                }
            )
            else -> throw IllegalArgumentException("Use collect, counts, threads, closed-canary or self-test")
        }
        return result.apply { putInt("violations", violations.get()) }
    }

    private fun collect() {
        // A second collection includes objects made unreachable by the first finalizer pass.
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
    }

    @Suppress("UNUSED_VARIABLE")
    private fun abandonCanary() {
        // Deliberately unclosed: verifies the actual platform/VM path, not a synthetic callback.
        val canary = FileInputStream("/dev/null")
    }

    override fun getType(uri: Uri): String? = null
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
