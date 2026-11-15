/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineServerReach
import app.pictureshow.core.resumeLabel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** P-1 and P-11 of the 2026-11-15 phone review: a downloaded title's own page plays the download, and leaving it says where progress went. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h1600dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineDetailTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("server", "Server", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val mib = 1024L * 1024

    private fun entry(phase: OfflinePhase, id: String = "movie:original", itemId: String = "movie", bytes: Long = 106 * mib) = OfflineEntry(
        id = id,
        itemId = itemId,
        kind = OfflineMediaKind.movie,
        preset = OfflinePreset.original,
        labels = OfflineLabels("A Film"),
        resources = listOf(OfflineResource("media", OfflineResourceRole.media, "media.mkv", bytes = bytes, complete = true)),
        job = OfflineJob(phase)
    )

    private fun library(vararg entries: OfflineEntry, progress: Map<String, OfflineLiveProgress> = emptyMap()) =
        OfflineLibrary(OfflineManifest(entries = entries.toList()), progress = progress)

    @Test fun aReadyDownloadSaysDownloadedWithItsSize() {
        val shown = touchDetailOffline(library(entry(OfflinePhase.ready)), "movie", OfflineNetwork.unmetered)!!
        assertTrue(shown.ready)
        assertEquals("Downloaded · 106 MB", shown.status)
    }

    @Test fun aTransferShowsLiveProgressInTheDownloadsScreensWords() {
        val running = entry(OfflinePhase.transferring)
        val shown = touchDetailOffline(
            library(running, progress = mapOf(running.id to OfflineLiveProgress(53 * mib, 106 * mib))),
            "movie",
            OfflineNetwork.unmetered
        )!!
        assertEquals(false, shown.ready)
        assertEquals("Downloading · 50% of 106 MB", shown.status)
        assertEquals(0.5f, shown.fraction)
    }

    @Test fun aReadyCopyWinsOverANewerAttemptAndARemovalIsGone() {
        val ready = entry(OfflinePhase.ready)
        val retry = entry(OfflinePhase.failed, id = "movie:video720").copy(createdSequence = 5)
        assertTrue(touchDetailOffline(library(retry, ready), "movie", OfflineNetwork.unmetered)!!.ready)
        assertNull(touchDetailOffline(library(entry(OfflinePhase.deleting)), "movie", OfflineNetwork.unmetered))
        assertNull(touchDetailOffline(library(ready), "other", OfflineNetwork.unmetered))
    }

    @Test fun reachIsUnreachableOnlyWhenNothingAnswered() {
        val base = CatalogDetailState(account, MediaItem("movie", "A Film", "Movie"), loading = false)
        assertEquals(OfflineServerReach.unreachable, touchDetailServerReach(OfflineNetwork.none, base))
        assertEquals(OfflineServerReach.unreachable, touchDetailServerReach(OfflineNetwork.unmetered, base.copy(readFailed = true, readUnreachable = true)))
        // A server that answered with an error is there; its actions say what it said.
        assertEquals(OfflineServerReach.unknown, touchDetailServerReach(OfflineNetwork.unmetered, base.copy(readFailed = true)))
        assertEquals(OfflineServerReach.reachable, touchDetailServerReach(OfflineNetwork.unmetered, base.copy(readSucceeded = true)))
    }

    @Test fun aConnectionThatNeverHappenedIsNotBlamedOnTheServersStream() {
        assertEquals(PLAYBACK_SERVER_UNREACHED, playbackStartFailureCopy(ClientError(ErrorKind.NETWORK, "Could not reach the server.")))
        assertEquals("Forbidden.", playbackStartFailureCopy(ClientError(ErrorKind.SERVER, "Forbidden.", 403)))
    }

    @Test fun theDetailPagesStartTravelsInTheRouteAndDownloadsKeepsItsOwn() {
        val ready = entry(OfflinePhase.ready)
        assertEquals("offline-player?entry=movie%3Aoriginal", offlinePlayerRoute(ready))
        assertEquals("offline-player?entry=movie%3Aoriginal&start=41000", offlinePlayerRoute(ready, 41_000))
    }

    @Test fun settlingReportsOnlyTheNewestResultForATitle() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val results = mutableListOf<OfflineSettleResult>()
        val done = CompletableDeferred<Unit>()
        val settler = OfflineViewingSettler(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { _, item ->
            synchronized(calls) { calls += item }
            if (calls.size == 1) gate.await()
            if (calls.size == 1) OfflineSettleResult.Failed else OfflineSettleResult.Settled
        }
        // Stop, then the completion: two settles in order, one result for the viewer.
        settler.ended(account, "movie") { synchronized(results) { results += it } }
        settler.ended(account, "movie") {
            synchronized(results) { results += it }
            done.complete(Unit)
        }
        gate.complete(Unit)
        withTimeout(5_000) { done.await() }
        assertEquals(listOf("movie", "movie"), calls)
        assertEquals(listOf(OfflineSettleResult.Settled), results)
        assertEquals(2L, settler.revision.value)
    }

    private class Taps {
        var local = mutableListOf<Boolean>()
        var streamed = 0
        var removed = 0
    }

    private fun detail(state: CatalogDetailState, server: OfflineServerReach, startTicks: Long, taps: Taps) {
        val offline = touchDetailOffline(library(entry(OfflinePhase.ready)), "movie", OfflineNetwork.unmetered)
        val download = TouchDetailDownload(
            offline,
            server,
            TouchDetailLocal(startTicks) { taps.local += it },
            remove = { taps.removed++ },
            openDownloads = {}
        )
        compose.setContent {
            MaterialTheme {
                TouchCatalogDetail(state, {}, {}, {}, {}, {}, { _, _ -> taps.streamed++ }, {}, onDownload = { _, _, _ -> }, download = download)
            }
        }
    }

    // The plane: the read never got an answer, and the page's Resume plays the copy on the phone from its own place.
    @Test fun withTheServerOutOfReachResumeAndPlayFromStartUseTheDownload() {
        val taps = Taps()
        val offlineRead = CatalogDetailState(
            account,
            MediaItem("movie", "A Film", "Movie", userData = ItemUserData(playbackPositionTicks = 100_000_000)),
            loading = false,
            error = "Could not reach the server. Check your connection and try again.",
            readFailed = true,
            readUnreachable = true
        )
        detail(offlineRead, OfflineServerReach.unreachable, 410_000_000, taps)
        compose.onNodeWithText("Downloaded · 106 MB").assertExists()
        compose.onNodeWithTag("touch:detail:download").assertDoesNotExist()
        compose.onNodeWithText(resumeLabel(410_000_000)).assertIsEnabled().performClick()
        compose.onNodeWithText("Play from start").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), taps.local)
        assertEquals(0, taps.streamed)
        compose.onNodeWithText("Your download still plays.", substring = true).assertExists()
    }

    @Test fun serverOnlyActionsExplainThemselvesWhenTheServerIsOutOfReach() {
        val taps = Taps()
        val state = CatalogDetailState(account, MediaItem("movie", "A Film", "Movie"), loading = false, readSucceeded = true)
        detail(state, OfflineServerReach.unreachable, 0, taps)
        compose.waitForIdle()
        // A notice clears after six seconds, and an idle wait would run the clock past it.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Play on TV").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("touch:detail:status").assertTextEquals(DETAIL_TV_NEEDS_SERVER)
        compose.onNodeWithTag("touch:detail:offline:stream").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("touch:detail:status").assertTextEquals(DETAIL_STREAM_NEEDS_SERVER)
        assertEquals(0, taps.streamed)
    }

    @Test fun streamingInsteadIsADeliberateChoiceThatStreams() {
        val taps = Taps()
        val state = CatalogDetailState(account, MediaItem("movie", "A Film", "Movie"), loading = false, readSucceeded = true)
        detail(state, OfflineServerReach.reachable, 0, taps)
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithTag("touch:detail:offline:stream").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), taps.local)
        assertEquals(1, taps.streamed)
    }

    @Test fun removingAsksFirstWithCancelFirst() {
        val taps = Taps()
        val state = CatalogDetailState(account, MediaItem("movie", "A Film", "Movie"), loading = false, readSucceeded = true)
        detail(state, OfflineServerReach.reachable, 0, taps)
        compose.onNodeWithTag("touch:detail:offline:remove").performScrollTo().performClick()
        compose.onNodeWithTag("touch:detail:offline:question").assertExists()
        compose.onNodeWithTag("touch:detail:offline:cancel").performClick()
        assertEquals(0, taps.removed)
        compose.onNodeWithTag("touch:detail:offline:remove").performScrollTo().performClick()
        compose.onNodeWithTag("touch:detail:offline:confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, taps.removed)
    }
}
