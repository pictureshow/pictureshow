/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WatchNextTest {
    private val account = Account(Server("server", "Cinema", "https://media.example.test/base", "12"), User("user", "Sam"), "secret-token")
    private val item = MediaItem(
        "0123456789abcdef0123456789abcdef", "Arrival", "Episode", runTimeTicks = 90_000_000_000,
        seriesName = "Voyage", indexNumber = 3, parentIndexNumber = 2, imageTags = mapOf("Primary" to "tag"),
        userData = ItemUserData(playbackPositionTicks = 123_450_000)
    )

    private class FakeProvider : WatchProvider {
        val programs = linkedMapOf<Long, WatchNextProgram>()
        val hidden = mutableSetOf<Long>()
        var sequence = 0L
        override fun rows() = programs.map { (id, p) ->
            WatchRow(id, p.internalProviderId!!, id !in hidden, p.lastPlaybackPositionMillis, p.lastEngagementTimeUtcMillis)
        }
        override fun insert(program: WatchNextProgram) {
            programs[++sequence] = program
        }
        override fun update(id: Long, program: WatchNextProgram) {
            programs[id] = program
        }
        override fun delete(id: Long) {
            programs.remove(id)
        }
    }
    private class Dismissals : WatchDismissals {
        val keys = mutableSetOf<String>()
        override fun contains(key: String) = key in keys
        override fun add(key: String) {
            keys += key
        }
    }

    @Test fun episodeMapsMetadataArtworkAndAnExplicitResumeIntent() {
        val p = watchProgram(account, item, false, 1000)
        assertEquals("Voyage", p.title)
        assertEquals("Arrival", p.episodeTitle)
        assertEquals("2", p.seasonNumber)
        assertEquals("3", p.episodeNumber)
        assertEquals(9_000_000, p.durationMillis)
        assertEquals(12_345, p.lastPlaybackPositionMillis)
        assertEquals(1000, p.lastEngagementTimeUtcMillis)
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, p.watchNextType)
        val intent = Intent.parseUri(p.intentUri.toString(), Intent.URI_INTENT_SCHEME)
        assertEquals("app.pictureshow", intent.`package`)
        assertEquals(ParsedPlaybackEntry.Play(PlaybackEntry(item.id, 12_345)), parsePlaybackEntry(intent))
        assertTrue(p.posterArtUri.toString().startsWith("https://media.example.test/base/Items/"))
        assertFalse(p.toContentValues().toString().contains(account.accessToken))
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT, watchProgram(account, item, true, 1000).watchNextType)
    }

    @Test fun addsDeduplicatesUpdatesPositionAndRemovesFinishedOrAbsentItems() {
        val provider = FakeProvider()
        val sync = WatchNextSync(provider, Dismissals())
        sync.replace(account, listOf(item), listOf(item), 1000)
        assertEquals(1, provider.programs.size)
        val row = provider.programs.keys.single()
        sync.replace(account, listOf(item.copy(userData = ItemUserData(999_000_000))), emptyList(), 2000)
        assertEquals(setOf(row), provider.programs.keys)
        assertEquals(99_900, provider.programs[row]!!.lastPlaybackPositionMillis)
        sync.replace(account, listOf(item.copy(userData = ItemUserData(played = true))), emptyList(), 3000)
        assertTrue(provider.programs.isEmpty())
        sync.replace(account, emptyList(), listOf(item), 4000)
        assertEquals(1, provider.programs.size)
        sync.replace(account, emptyList(), emptyList(), 5000)
        assertTrue(provider.programs.isEmpty())
    }

    @Test fun launcherDismissalSurvivesAbsenceAndSyncRecreationButIsAccountScoped() {
        val provider = FakeProvider()
        val dismissed = Dismissals()
        val sync = WatchNextSync(provider, dismissed)
        sync.replace(account, listOf(item), emptyList(), 1000)
        provider.hidden += provider.programs.keys.single()
        sync.replace(account, listOf(item), emptyList(), 2000)
        sync.replace(account, emptyList(), emptyList(), 3000)
        WatchNextSync(provider, dismissed).replace(account, listOf(item), emptyList(), 4000)
        assertTrue(provider.programs.isEmpty())
        sync.replace(account.copy(user = User("other", "Other")), listOf(item), emptyList(), 5000)
        assertEquals(1, provider.programs.size)
    }

    @Test fun refreshDoesNotInventNewEngagementAndLimitsIntegerMillis() {
        val provider = FakeProvider()
        val sync = WatchNextSync(provider, Dismissals())
        sync.replace(account, listOf(item), emptyList(), 1000)
        sync.replace(account, listOf(item), emptyList(), 2000)
        assertEquals(1000, provider.programs.values.single().lastEngagementTimeUtcMillis)
        val huge = watchProgram(
            account,
            item.copy(
                runTimeTicks = Long.MAX_VALUE,
                userData = ItemUserData(Long.MAX_VALUE)
            ),
            false,
            1
        )
        assertFalse(huge.toContentValues().containsKey(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS))
        assertEquals(Int.MAX_VALUE, huge.lastPlaybackPositionMillis)
    }

    @Test fun persistentDismissalsSurviveAStoreRecreation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val key = watchAccountKey(account) + ":" + item.id
        StoredWatchDismissals(context).add(key)
        assertTrue(StoredWatchDismissals(context).contains(key))
        val other = account.copy(user = User("other", "Other"))
        assertFalse(StoredWatchDismissals(context).contains(watchAccountKey(other) + ":" + item.id))
    }

    @Test fun switchingUserReplacesRowsAndSignOutClearsThem() {
        val provider = FakeProvider()
        val sync = WatchNextSync(provider, Dismissals())
        sync.replace(account, listOf(item), emptyList(), 1000)
        val original = provider.programs.keys.single()
        sync.replace(account.copy(user = User("other", "Other")), emptyList(), listOf(item), 2000)
        assertEquals(1, provider.programs.size)
        assertFalse(original in provider.programs)
        sync.replace(null, emptyList(), emptyList(), 3000)
        assertTrue(provider.programs.isEmpty())
    }
}
