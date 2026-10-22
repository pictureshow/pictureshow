/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WatchProviderTest {
    private class FakeTvProvider : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        var sequence = 0L
        val updates = mutableListOf<ContentValues>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = TvContractCompat.WatchNextPrograms.CONTENT_TYPE
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?
        ): MatrixCursor {
            val columns = WatchNextProgram.PROJECTION
            return MatrixCursor(columns).apply {
                rows.values.forEach { values -> addRow(columns.map { values[it] }.toTypedArray()) }
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = ++sequence
            rows[id] = ContentValues(values).apply {
                put("_id", id)
                put("package_name", "app.pictureshow")
                put("browsable", 1)
            }
            return ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updates += ContentValues(values)
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            row.putAll(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            if (rows.remove(ContentUris.parseId(uri)) == null) 0 else 1
    }

    @Test
    fun realAdapterReadsOnlyOwnRowsAndDoesNotOverwriteLauncherBrowsable() {
        val fake = FakeTvProvider()
        ShadowContentResolver.registerProviderInternal(TvContractCompat.AUTHORITY, fake)
        val provider = AndroidWatchProvider(RuntimeEnvironment.getApplication())
        val program = WatchNextProgram.Builder().setInternalProviderId("scope:item").setTitle("Fixture")
            .setLastPlaybackPositionMillis(10).setLastEngagementTimeUtcMillis(123).build()
        provider.insert(program)
        fake.rows[99] = ContentValues(fake.rows[1]).apply {
            put("_id", 99)
            put("package_name", "other.app")
        }
        assertEquals(listOf(WatchRow(1, "scope:item", true, 10, 123)), provider.rows())
        provider.update(1, WatchNextProgram.Builder(program).setLastPlaybackPositionMillis(20).build())
        assertFalse(fake.updates.single().containsKey("browsable"))
        fake.rows[1]!!.put("browsable", 0)
        assertFalse(provider.rows().single().browsable)
        provider.delete(1)
        assertTrue(provider.rows().isEmpty())
        assertEquals(setOf(99L), fake.rows.keys)
    }

    @Test
    @Config(sdk = [23])
    fun unsupportedApiNeverAccessesTvProvider() {
        assertFalse(supportsWatchNext(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun nonTvDeviceNeverAccessesTvProvider() {
        assertFalse(supportsWatchNext(RuntimeEnvironment.getApplication()))
    }
}
