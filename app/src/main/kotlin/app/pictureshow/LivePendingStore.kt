/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import app.pictureshow.core.livetv.LivePendingWrites
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A crash during a create leaves a durable reconciliation marker, never permission to submit a second timer. */
internal class LivePendingStore(context: Context) : LivePendingWrites {
    private val preferences = context.applicationContext.getSharedPreferences("live-pending", Context.MODE_PRIVATE)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) { preferences.getBoolean(hash(key), false) }
    override suspend fun add(key: String) {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean(hash(key), true).commit()) { "Could not save recording reconciliation state." }
        }
    }
    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            check(preferences.edit().remove(hash(key)).commit()) { "Could not clear recording reconciliation state." }
        }
    }
}
