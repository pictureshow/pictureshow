/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.Server
import app.pictureshow.core.User
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowPausedAsyncTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 36], application = NoExternalStorageApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceProfileExportTest {
    private val pipeWriter = PausedExecutorService()

    @Before
    fun pausePipeWriter() {
        ShadowPausedAsyncTask.overrideExecutor(pipeWriter)
    }

    @After
    fun finishPipeWriter() {
        try {
            pipeWriter.runAll()
        } finally {
            pipeWriter.shutdownNow()
            ShadowPausedAsyncTask.reset()
        }
    }

    private val uri = Uri.parse("content://app.pictureshow.deviceprofile/profile.json")

    private fun install(): Application {
        val app = RuntimeEnvironment.getApplication()
        val provider = DeviceProfileExportProvider()
        provider.attachInfo(
            app,
            ProviderInfo().apply {
                authority = uri.authority
                exported = true
            }
        )
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
        return app
    }

    @Test
    fun resolverReadStreamsExactProfileWithoutExternalStorageOrAccountData() = runBlocking {
        val app = install()
        val preferences = PlaybackPreferencesStore(app.preferences)
        preferences.update { it.copy(bitrateMbps = 40) }
        val fixture =
            Account(
                Server("private-server", "Private Library", "https://private.example.test", "12"),
                User("private-user", "Private Person"),
                "fixture-token"
            )
        AccountStore(AccountPersistence(app.preferences)).remember(fixture)
        app.preferences.edit { it[stringPreferencesKey("device_id")] = "fixture-device-id" }
        val report = captureThisTv(app, preferences.read())
        val actual = app.contentResolver.openFileDescriptor(uri, "r")!!.let { descriptor ->
            // Robolectric implements createPipe() with a regular file, whose reader can reach EOF
            // before openPipeHelper's AsyncTask writes. Finish that writer before reading the shadow;
            // a real Android pipe blocks the reader until bytes arrive or the writer closes.
            pipeWriter.runAll()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        assertContentEquals(
            report.profileJson.toByteArray(Charsets.UTF_8),
            actual,
            "Expected profile:\n${report.profileJson}\nExported profile:\n${actual.toString(Charsets.UTF_8)}"
        )
        assertEquals("application/json", app.contentResolver.getType(uri))
        assertEquals(report.sharedText, thisTvShareIntent(report).getStringExtra(android.content.Intent.EXTRA_TEXT))
        listOf(
            "private-server",
            "Private Library",
            "https://private.example.test",
            "private-user",
            "Private Person",
            "fixture-token",
            "fixture-device-id"
        ).forEach {
            assertFalse(actual.toString(Charsets.UTF_8).contains(it), "Export leaked fixture value $it")
        }
    }

    @Test
    fun resolverRejectsWriteModesAndUnknownPaths() {
        val app = install()
        listOf("w", "rw", "rwt").forEach { mode ->
            assertFailsWith<FileNotFoundException> { app.contentResolver.openFileDescriptor(uri, mode) }
        }
        assertFailsWith<FileNotFoundException> {
            app.contentResolver.openFileDescriptor(Uri.parse("content://${uri.authority}/accounts"), "r")
        }
        assertFailsWith<UnsupportedOperationException> { app.contentResolver.insert(uri, android.content.ContentValues()) }
        assertFailsWith<UnsupportedOperationException> { app.contentResolver.delete(uri, null, null) }
        assertFailsWith<UnsupportedOperationException> { app.contentResolver.update(uri, android.content.ContentValues(), null, null) }
    }
}

class NoExternalStorageApplication : Application() {
    override fun getExternalFilesDir(type: String?): File? = error("ADB cannot read app-specific external storage")
}
