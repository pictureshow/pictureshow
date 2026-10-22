/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Read-only adb export, compiled into deviceTest only. Never consults accounts or external storage. */
class DeviceProfileExportProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        requireProfile(uri)
        return "application/json"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireProfile(uri)
        if (mode != "r") throw FileNotFoundException("The device profile is read-only")
        val app = requireNotNull(context).applicationContext as Application
        // Providers can be created before Application.onCreate/Koin. Use the application's same
        // DataStore delegate directly, and capture on IO before returning a successful descriptor.
        val bytes = try {
            runBlocking(Dispatchers.IO) {
                captureThisTv(app, PlaybackPreferencesStore(app.preferences).read()).profileJson.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            throw FileNotFoundException("Device capabilities unavailable; retry the export")
        }
        return openPipeHelper(uri, "application/json", null, bytes) { output, _, _, _, data ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data) }
            } catch (_: IOException) {
                // A disconnected adb reader needs no retry or background work.
            }
        }
    }

    private fun requireProfile(uri: Uri) {
        if (uri.scheme != "content" ||
            uri.authority != "${BuildConfig.APPLICATION_ID}.deviceprofile" ||
            uri.encodedPath != "/profile.json" ||
            uri.query != null ||
            uri.fragment != null
        ) {
            throw FileNotFoundException("Unknown device profile resource")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("Use a read-only file descriptor")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")
}
