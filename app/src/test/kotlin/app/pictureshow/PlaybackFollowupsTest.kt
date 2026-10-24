/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Independent report ordering and parent reads: a pre-acknowledgement result must never unlock details. */
class PlaybackFollowupsTest {
    private val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
    private val series = MediaItem("series", type = "Series")
    private val season = MediaItem("season", type = "Season", seriesId = series.id)
    private val episode = MediaItem("episode", type = "Episode", seriesId = series.id, seasonId = season.id)
    private fun session(service: CatalogService = CatalogModelsTest.FakeCatalog()) = CatalogSession(
        service,
        AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
    )

    @Test
    fun completingEitherSameItemReportCannotReleaseTheOtherOrItsParents() {
        for (firstToFinish in listOf(0, 1)) {
            val catalog = session()
            val reports = listOf(Any(), Any())
            reports.forEach { catalog.playbackPending(episode.id, it, setOf(series.id, season.id)) }
            catalog.refresh(episode.id, reports[firstToFinish])
            assertEquals(setOf(episode.id, series.id, season.id), catalog.changes.value.pendingPlayback)
            // A duplicate callback and an ordinary stop from a third viewing own no pending report.
            catalog.refresh(episode.id, reports[firstToFinish])
            catalog.refresh(episode.id, Any())
            assertEquals(setOf(episode.id, series.id, season.id), catalog.changes.value.pendingPlayback)
            catalog.refresh(episode.id, reports[1 - firstToFinish])
            assertTrue(catalog.changes.value.pendingPlayback.isEmpty())
        }
    }

    @Test
    fun ordinaryReplayStopCannotReleaseAnEarlierNaturalEnd() {
        val catalog = session()
        val natural = Any()
        catalog.playbackPending(episode.id, natural)
        catalog.refresh(episode.id, Any())
        assertEquals(setOf(episode.id), catalog.changes.value.pendingPlayback)
        catalog.refresh(episode.id, natural)
        assertTrue(catalog.changes.value.pendingPlayback.isEmpty())
    }

    @Test
    fun seriesAndSeasonHideStaleRowsUntilPostReportEpisodeReadFinishes() = parentScenario(failRead = false)

    @Test
    fun failedPostReportEpisodeReadKeepsParentsGuardedUntilRetry() = parentScenario(failRead = true)

    private fun parentScenario(failRead: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val episodeRead = CompletableDeferred<Unit>()
        var held = false
        var failed = failRead
        var watched = false
        val service = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun item(account: Account, id: String) = ClientResult.Success(if (id == series.id) series else season)
            override suspend fun nextUp(account: Account, seriesId: String?) =
                ClientResult.Success(ItemPage(if (watched) emptyList() else listOf(episode)))
            override suspend fun seasons(account: Account, seriesId: String) = ClientResult.Success(ItemPage(listOf(season)))
            override suspend fun episodes(account: Account, seriesId: String, seasonId: String): ClientResult<ItemPage> {
                if (held) episodeRead.await()
                return if (held && failed) {
                    ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Try again."))
                } else {
                    ClientResult.Success(ItemPage(listOf(episode.copy(userData = ItemUserData(played = watched)))))
                }
            }
        }
        val catalog = session(service)
        val models = mutableListOf<CatalogDetailViewModel>()
        val store = ViewModelStore()
        fun open(item: MediaItem): CatalogDetailViewModel = CatalogDetailViewModel(account, item, catalog).also {
            store.put("model-${models.size}", it)
            models += it
        }
        try {
            open(series)
            open(season)
            runCurrent()
            assertTrue(models.all { it.state.value.episodes.single().id == episode.id })
            val report = Any()
            catalog.playbackPending(episode.id, report, setOf(series.id, season.id))
            runCurrent()
            assertTrue(models.all { it.state.value.playbackPending }, "Retained parent pages must withhold stale rows and play actions")
            open(series)
            open(season)
            assertTrue(models.all { it.state.value.playbackPending }, "New parents must be guarded on their first frame")
            runCurrent()
            assertTrue(models.all { it.state.value.playbackPending }, "Pre-report reads cannot unlock a parent")
            held = true
            watched = true
            catalog.refresh(episode.id, report)
            runCurrent()
            assertTrue(
                models.all {
                    it.state.value.playbackPending
                },
                "Reading the parent header is insufficient; episode rows are still blocked"
            )
            episodeRead.complete(Unit)
            runCurrent()
            if (failRead) {
                assertTrue(models.all { it.state.value.playbackPending && it.state.value.error != null })
                failed = false
                models.forEach { it.reload() }
                runCurrent()
            }
            assertTrue(models.all { !it.state.value.playbackPending && it.state.value.episodes.single().userData.played })
            assertTrue(models.filter { it.state.value.item?.type == "Series" }.all { it.state.value.nextUp.isEmpty() })
            assertFalse(catalog.playbackUnresolved(series.id))
            assertFalse(catalog.playbackUnresolved(season.id))
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
