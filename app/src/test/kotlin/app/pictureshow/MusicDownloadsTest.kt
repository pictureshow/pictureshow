/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineCollection
import app.pictureshow.core.offline.OfflineCollectionKind
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.offlineEntryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class MusicDownloadsTest {
    private val account = Account(Server("s", "Test", "https://music.example/", "12"), User("v", "Viewer"), "token")

    private fun track(id: String, size: Long? = 10_000_000, canDownload: Boolean? = null) = OfflineApiItem(
        id,
        "Song $id",
        "Audio",
        canDownload = canDownload,
        sources = listOf(OfflineApiSource("s$id", "etag", "flac", size))
    )

    private fun entry(id: String, phase: OfflinePhase = OfflinePhase.ready, kind: OfflineMediaKind = OfflineMediaKind.audio) = OfflineEntry(
        offlineEntryId(id, OfflinePreset.original),
        id,
        kind,
        preset = OfflinePreset.original,
        runtimeTicks = 1_000,
        labels = OfflineLabels("Song $id", album = "Album", artist = "Artist"),
        job = OfflineJob(phase)
    )

    private fun library(vararg entries: OfflineEntry, collections: List<OfflineCollection> = emptyList(), cap: Long? = null) =
        OfflineLibrary(OfflineManifest(entries = entries.toList(), collections = collections, budget = OfflineBudget(capBytes = cap)))

    @Test fun downloadAsksWithTheCountAndSizeAndSaysWhatStaysStreaming() {
        val ask = musicDownloadQuestion(true, listOf(track("1"), track("2"), track("3", canDownload = false)), library(), "Album")
        assertIs<MusicDownloadAsk.Confirm>(ask)
        assertEquals(
            "Download 2 tracks (${offlineBytes(
                20_000_000
            )}) in their original quality? 1 track can't be downloaded and will stream as usual.",
            ask.text
        )
    }

    @Test fun anAccountWithoutDownloadRightsIsToldSoAndNothingIsAsked() {
        assertEquals(
            MusicDownloadAsk.Notice("Your account can't download from this server."),
            musicDownloadQuestion(false, listOf(track("1")), library(), "Album")
        )
    }

    /** OFF-06: the budget refuses before anything is queued, naming what's needed and what's left. */
    @Test fun anAlbumBiggerThanTheLimitIsRefusedWithBothSizes() {
        val ask = musicDownloadQuestion(true, listOf(track("1"), track("2")), library(cap = 15_000_000), "Album")
        assertIs<MusicDownloadAsk.Notice>(ask)
        assertEquals(
            "Album needs about ${offlineBytes(20_000_000)}, and your download limit has ${offlineBytes(15_000_000)} left. " +
                "Remove downloads or raise the limit in Downloads, then try again.",
            ask.text
        )
    }

    @Test fun tracksAlreadyDownloadedCostNothingAgainstTheLimit() {
        val ask = musicDownloadQuestion(true, listOf(track("1"), track("2")), library(entry("1"), cap = 15_000_000), "Album")
        assertIs<MusicDownloadAsk.Confirm>(ask)
    }

    @Test fun anAlbumPageSaysHowFarItsDownloadHasGot() {
        val album = OfflineCollection("al", OfflineCollectionKind.album, "Album", itemIds = listOf("1", "2"))
        val coming = musicDownloadStatus(
            library(entry("1"), entry("2", OfflinePhase.queued), collections = listOf(album)),
            "al",
            OfflineNetwork.unmetered,
            tv = false
        )!!
        assertEquals("Downloading · 1 of 2 tracks", coming.text)
        assertEquals(0.5f, coming.fraction)
        assertFalse(coming.ready)
        val done = musicDownloadStatus(
            library(entry("1"), entry("2"), collections = listOf(album)),
            "al",
            OfflineNetwork.unmetered,
            false
        )!!
        assertTrue(done.ready)
        assertTrue(done.text.startsWith("Downloaded · 2 tracks"), done.text)
        assertNull(musicDownloadStatus(library(entry("1")), "al", OfflineNetwork.unmetered, false))
    }

    /** MUS-10: Downloads' Play on a track plays its album, in the saved order, from that track, ready tracks only. */
    @Test fun playingADownloadedTrackPlaysItsAlbumInOrderFromThere() {
        val album = OfflineCollection("al", OfflineCollectionKind.album, "Album", itemIds = listOf("3", "1", "2"))
        val lib = library(entry("1"), entry("2", OfflinePhase.transferring), entry("3"), collections = listOf(album))
        val play = offlineMusicPlay(account, lib, entry("1")) { null }!!
        assertEquals(listOf("3", "1"), play.tracks.map { it.id })
        assertEquals(1, play.start)
        assertEquals("MusicAlbum:al", play.context)
        assertEquals("Album", play.contextName)
        assertNull(offlineMusicPlay(account, lib, entry("9", kind = OfflineMediaKind.movie)) { null })
    }

    /** A track two downloads share plays with the album or playlist row it was played from; without one, the newest holding it. */
    @Test fun aSharedTrackPlaysWithTheRowItWasPlayedFrom() {
        val album = OfflineCollection("al", OfflineCollectionKind.album, "Album", itemIds = listOf("1", "2"), createdSequence = 1)
        val playlist = OfflineCollection("pl", OfflineCollectionKind.playlist, "Mix", itemIds = listOf("1"), createdSequence = 2)
        val lib = library(entry("1"), entry("2"), collections = listOf(album, playlist))
        val fromAlbum = offlineMusicPlay(account, lib, entry("1"), "al") { null }!!
        assertEquals("MusicAlbum:al", fromAlbum.context)
        assertEquals(listOf("1", "2"), fromAlbum.tracks.map { it.id })
        assertEquals("Playlist:pl", offlineMusicPlay(account, lib, entry("1")) { null }!!.context)
        // An id that doesn't hold the track falls back the same way.
        assertEquals("Playlist:pl", offlineMusicPlay(account, lib, entry("1"), "other") { null }!!.context)
    }

    @Test fun aListenCountsAsFinishedOnlyAtTheEnd() {
        assertTrue(musicListenFinished(2_400_000_000, 2_400_000_000))
        assertTrue(musicListenFinished(2_395_000_000, 2_400_000_000))
        assertFalse(musicListenFinished(1_200_000_000, 2_400_000_000))
        assertFalse(musicListenFinished(1_200_000_000, null))
    }
}
