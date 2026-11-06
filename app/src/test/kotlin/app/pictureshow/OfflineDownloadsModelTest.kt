/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.offlineDecodeManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class OfflineDownloadsModelTest {
    private fun job(phase: OfflinePhase, failure: String? = null, cap: Boolean = false) =
        OfflineJob(phase, failure = failure, pausedByCap = cap)

    @Test
    fun `percent appears only with a known total`() {
        val known = offlineStatus(job(OfflinePhase.transferring), OfflineLiveProgress(50, 200), true, OfflineNetwork.unmetered, 0)
        assertTrue(known, known.startsWith("25%"))
        val unknown = offlineStatus(job(OfflinePhase.transferring), OfflineLiveProgress(2048, null), true, OfflineNetwork.unmetered, 0)
        assertFalse(unknown, unknown.contains("%"))
        assertTrue(unknown, unknown.contains("2 KB received"))
    }

    private fun meteredActions(job: OfflineJob) = offlineActions(job, true, OfflineNetwork.metered)

    @Test
    fun `every phase says something true and offers what it can do`() {
        val phases = OfflinePhase.entries
        phases.forEach { assertTrue(it.name, offlineStatus(job(it), null, true, OfflineNetwork.none, 10).isNotBlank()) }
        assertEquals(listOf(OfflineAction.Play, OfflineAction.Delete), meteredActions(job(OfflinePhase.ready)))
        assertEquals(listOf(OfflineAction.Retry, OfflineAction.Delete), meteredActions(job(OfflinePhase.failed)))
        assertEquals(listOf(OfflineAction.Redownload, OfflineAction.Delete), meteredActions(job(OfflinePhase.missing)))
        assertEquals(listOf(OfflineAction.Resume, OfflineAction.Delete), meteredActions(job(OfflinePhase.paused)))
        assertTrue(meteredActions(job(OfflinePhase.deleting)).isEmpty())
        phases.filter { it != OfflinePhase.ready }.forEach { assertFalse(it.name, OfflineAction.Play in meteredActions(job(it))) }
    }

    @Test
    fun `waiting names what it waits for`() {
        assertEquals("Waiting for Wi-Fi", offlineStatus(job(OfflinePhase.waitingNetwork), null, true, OfflineNetwork.metered, 0))
        val offline = offlineStatus(job(OfflinePhase.waitingNetwork), null, true, OfflineNetwork.none, 0)
        assertEquals("Waiting for a connection. It resumes once you're connected and Pictureshow is open.", offline)
        assertTrue(OfflineAction.AllowCellular in meteredActions(job(OfflinePhase.waitingNetwork)))
        assertFalse(OfflineAction.AllowCellular in meteredActions(job(OfflinePhase.waitingNetwork).copy(cellularAllowed = true)))
        assertTrue(offlineStatus(job(OfflinePhase.paused, cap = true), null, true, OfflineNetwork.none, 0).contains("limit"))
    }

    @Test
    fun `a ready title lists what wasn't downloaded`() {
        val item = OfflineApiItem(
            "i",
            "Movie",
            "Movie",
            sources = listOf(
                OfflineApiSource(
                    "s",
                    streams = listOf(
                        OfflineApiStream(1, "Audio", language = "eng", displayTitle = "English"),
                        OfflineApiStream(2, "Audio", language = "spa", displayTitle = "Spanish"),
                        OfflineApiStream(3, "Audio", language = "fra", displayTitle = "French"),
                        OfflineApiStream(4, "Subtitle", language = "eng", displayTitle = "English SRT", isText = true, isExternal = true),
                        OfflineApiStream(5, "Subtitle", language = "fra", displayTitle = "PGS", isText = false)
                    )
                )
            )
        )
        val entry = offlineBuildEntry(item, OfflineChoice("i", OfflinePreset.video720, listOf(2, 1), listOf(4)), 1)
        val muxed = entry.tracks.first { it.serverIndex == 1 }
        assertNull("the lowest selected audio stays in the media file", muxed.resourceId)
        assertEquals("audio-2", entry.tracks.first { it.serverIndex == 2 }.resourceId)
        assertFalse(entry.tracks.first { it.serverIndex == 3 }.available)
        assertEquals("subtitle-4", entry.tracks.first { it.serverIndex == 4 }.resourceId)
        assertFalse(entry.tracks.first { it.serverIndex == 5 }.available)
        assertEquals(1, entry.resources.first { it.id == "media" }.serverStreamIndex)
        val source = offlineSourceInfo(entry, 1) { java.io.File("/x/$it") }
        assertEquals(listOf(1, 2, 4), source.mediaStreams.map { it.index })
        assertTrue(source.mediaStreams.last().deliveryUrl!!.startsWith("file:"))
    }

    @Test
    fun `an original keeps every embedded track and saves external captions`() {
        val item = OfflineApiItem(
            "i",
            "Movie",
            "Movie",
            sources = listOf(
                OfflineApiSource(
                    "s",
                    container = "mkv",
                    streams = listOf(
                        OfflineApiStream(1, "Audio"),
                        OfflineApiStream(2, "Audio"),
                        OfflineApiStream(3, "Subtitle", isText = true),
                        OfflineApiStream(0, "Subtitle", isText = true, isExternal = true)
                    )
                )
            )
        )
        val entry = offlineBuildEntry(item, OfflineChoice("i", OfflinePreset.original), 1)
        assertEquals("media.mkv", entry.resources.first { it.id == "media" }.path)
        assertTrue(entry.tracks.all { it.available })
        assertEquals(setOf("media", "subtitle-0", "artwork"), entry.resources.map { it.id }.toSet())
        assertFalse(entry.resources.first { it.id == "artwork" }.required)
    }

    @Test
    fun `sections group ready, active and failed`() {
        fun entry(id: String, phase: OfflinePhase) = app.pictureshow.core.offline.OfflineEntry(
            id = id,
            itemId = id,
            kind = OfflineMediaKind.movie,
            preset = OfflinePreset.original,
            labels = OfflineLabels(id),
            job = job(phase)
        )
        val manifest = app.pictureshow.core.offline.OfflineManifest(
            entries = listOf(
                entry("a", OfflinePhase.ready),
                entry("b", OfflinePhase.paused),
                entry("c", OfflinePhase.failed),
                entry("d", OfflinePhase.missing)
            )
        )
        val sections = offlineSections(OfflineLibrary(manifest), OfflineNetwork.none)
        assertEquals(listOf("a"), sections.ready.map { it.title })
        assertEquals(listOf("b"), sections.active.map { it.title })
        assertEquals(listOf("c", "d"), sections.failed.map { it.title })
    }

    @Test
    fun `a viewer without rights is told so, and unqualified presets stay off`() {
        val item = OfflineApiItem("i", "Movie", "Movie", sources = listOf(OfflineApiSource("s", size = 10)))
        val denied = offlinePreviewChoices(OfflinePolicy(false, true, true, true), listOf(item))
        assertTrue(denied.none { it.available })
        val choices = offlinePreviewChoices(OfflinePolicy(true, true, false, true), listOf(item))
        assertTrue(choices.first { it.preset == OfflinePreset.original }.available)
        assertFalse(choices.first { it.preset == OfflinePreset.video720 }.available)
        assertEquals("no-conversion-right", choices.first { it.preset == OfflinePreset.video720 }.reason)
    }

    @Test
    fun `the Wi-Fi rule has only the override it says`() {
        assertFalse(offlineNetworkAllows(OfflineNetwork.none, false, true))
        assertTrue(offlineNetworkAllows(OfflineNetwork.unmetered, true, false))
        assertFalse(offlineNetworkAllows(OfflineNetwork.metered, true, false))
        assertTrue(offlineNetworkAllows(OfflineNetwork.metered, true, true))
        assertTrue(offlineNetworkAllows(OfflineNetwork.metered, false, false))
    }

    @Test
    fun `notifications are asked for once, and never after a yes`() {
        assertTrue(offlineAskForNotifications(granted = false, alreadyAsked = false))
        assertFalse(offlineAskForNotifications(granted = false, alreadyAsked = true))
        assertFalse(offlineAskForNotifications(granted = true, alreadyAsked = false))
    }

    @Test
    fun `a failed row never shows an internal word`() {
        val job = OfflineJob(phase = OfflinePhase.failed, failure = "gone")
        assertEquals("The server no longer has this file.", offlineStatus(job, null, false, OfflineNetwork.unmetered, 0))
        val other = job.copy(failure = "some-new-reason")
        assertEquals("The download stopped.", offlineStatus(other, null, false, OfflineNetwork.unmetered, 0))
    }

    private fun row(id: String, phase: OfflinePhase, failure: String? = null) = OfflineEntry(
        id = id,
        itemId = id,
        kind = OfflineMediaKind.movie,
        preset = OfflinePreset.video720,
        labels = OfflineLabels(id),
        job = job(phase, failure)
    )

    private fun library(vararg entries: OfflineEntry, flag: Boolean = false, recoverable: Boolean = false) =
        OfflineLibrary(OfflineManifest(entries = entries.toList()), needsPassword = flag, recoverable = recoverable)

    private fun track(type: String, language: String?, available: Boolean = false, index: Int = 0) =
        OfflineTrack(index, type, "$language - Dolby Digital - SUBRIP", language, available = available)

    @Test
    fun `not downloaded names each language once in plain words`() {
        assertEquals(
            "Not downloaded: Spanish audio; English, Spanish and French subtitles",
            offlineNotDownloadedSummary(
                listOf(
                    track("audio", "eng", available = true),
                    track("audio", "spa"),
                    track("subtitle", "eng"),
                    track("subtitle", "spa"),
                    track("subtitle", "eng"),
                    track("subtitle", "fre")
                )
            )
        )
        assertNull(offlineNotDownloadedSummary(listOf(track("audio", "eng", available = true))))
    }

    @Test
    fun `tracks with no language are counted rather than named`() {
        assertEquals("Not downloaded: 1 audio track", offlineNotDownloadedSummary(listOf(track("audio", null))))
        val unnamed = listOf(track("subtitle", "und"), track("subtitle", null))
        assertEquals("Not downloaded: 2 subtitle tracks", offlineNotDownloadedSummary(unnamed))
        val partly = listOf(track("subtitle", "ger"), track("subtitle", "zzz"))
        assertEquals("Not downloaded: German and other subtitles", offlineNotDownloadedSummary(partly))
    }

    @Test
    fun `an entry saved before this wording still gets the summary and keeps its raw labels`() {
        val old = offlineDecodeManifest(
            """{"schema":1,"entries":[{"id":"a","itemId":"a","kind":"movie","preset":"video720","labels":{"title":"A"},
            "tracks":[{"serverIndex":2,"type":"audio","label":"Spanish - Dolby Digital - Mono","language":"spa","available":false}]}]}"""
        )!!
        val row = offlineSections(OfflineLibrary(old), OfflineNetwork.unmetered).active.single()
        assertEquals("Not downloaded: Spanish audio", row.notDownloadedSummary)
        assertEquals(listOf("Spanish - Dolby Digital - Mono (audio)"), row.notDownloaded)
    }

    @Test
    fun `the mobile data override appears only on a metered network`() {
        val waiting = job(OfflinePhase.waitingNetwork)
        assertTrue(OfflineAction.AllowCellular in offlineActions(waiting, true, OfflineNetwork.metered))
        assertFalse(OfflineAction.AllowCellular in offlineActions(waiting, true, OfflineNetwork.none))
        assertFalse(OfflineAction.AllowCellular in offlineActions(waiting, true, OfflineNetwork.unmetered))
        assertFalse(OfflineAction.AllowCellular in offlineActions(waiting, false, OfflineNetwork.metered))
    }

    @Test
    fun `a TV talks about its own connections`() {
        assertEquals("Use this connection", offlineActionLabel(OfflineAction.AllowCellular, tv = true))
        assertEquals("Use mobile data", offlineActionLabel(OfflineAction.AllowCellular))
        val waiting = job(OfflinePhase.waitingNetwork)
        assertEquals("Waiting for Wi-Fi or Ethernet", offlineStatus(waiting, null, true, OfflineNetwork.metered, 0, tv = true))
        assertEquals("Waiting for Wi-Fi", offlineStatus(waiting, null, true, OfflineNetwork.metered, 0))
    }

    @Test
    fun `a row waiting for a password says so and offers only Delete`() {
        val waiting = job(OfflinePhase.failed, "needs-password")
        assertEquals("Waiting for your password", offlineStatus(waiting, null, true, OfflineNetwork.unmetered, 0))
        assertEquals(listOf(OfflineAction.Delete), offlineActions(waiting, true, OfflineNetwork.unmetered))
        assertFalse(offlineStatusIsError(waiting))
        val failed = offlineActions(job(OfflinePhase.failed, "network"), true, OfflineNetwork.none)
        assertEquals(listOf(OfflineAction.Retry, OfflineAction.Delete), failed)
    }

    @Test
    fun `either the flag or a job asks for the password`() {
        assertFalse(library(row("a", OfflinePhase.failed, "network")).passwordNeeded())
        assertTrue(library(row("a", OfflinePhase.ready), flag = true).passwordNeeded())
        // A network error during the attempt clears the flag and leaves the job waiting.
        assertTrue(library(row("a", OfflinePhase.failed, "needs-password")).passwordNeeded())
    }

    @Test
    fun `first focus is the password, then the top row's primary action, then Storage`() {
        fun sections(vararg entries: OfflineEntry) = offlineSections(library(*entries), OfflineNetwork.metered)
        assertEquals(OfflineFirstFocus.Password, offlineFirstFocus(true, sections(row("a", OfflinePhase.ready))))
        assertEquals(OfflineFirstFocus.Row("a", OfflineAction.Play), offlineFirstFocus(false, sections(row("a", OfflinePhase.ready))))
        assertEquals(
            OfflineFirstFocus.Row("b", OfflineAction.Retry),
            offlineFirstFocus(false, sections(row("a", OfflinePhase.ready), row("b", OfflinePhase.failed, "network")))
        )
        assertEquals(
            "OK on arrival never spends metered data",
            OfflineFirstFocus.Row("c", OfflineAction.Pause),
            offlineFirstFocus(false, sections(row("c", OfflinePhase.waitingNetwork)))
        )
        assertEquals(
            OfflineFirstFocus.Row("e", OfflineAction.Play),
            offlineFirstFocus(false, sections(row("d", OfflinePhase.deleting), row("e", OfflinePhase.ready)))
        )
        assertEquals(OfflineFirstFocus.Storage, offlineFirstFocus(false, sections()))
    }

    @Test
    fun `needs attention comes first`() {
        val sections = offlineSections(
            library(row("a", OfflinePhase.ready), row("b", OfflinePhase.paused), row("c", OfflinePhase.failed, "network")),
            OfflineNetwork.none
        )
        assertEquals(listOf("Needs attention", "In progress", "Ready to watch"), sections.ordered.map { it.first })
    }

    @Test
    fun `the current limit is always a choice`() {
        val gib = 1024L * 1024 * 1024
        val limit = gib * 6 / 5
        assertEquals(listOf(limit, 2 * gib, 5 * gib), offlineCapChoices(listOf(2 * gib, 5 * gib), limit))
        assertEquals(listOf(2 * gib, 5 * gib), offlineCapChoices(listOf(2 * gib, 5 * gib), 2 * gib))
        assertEquals(listOf(2 * gib), offlineCapChoices(listOf(2 * gib), null))
    }

    @Test
    fun `work with no total to show moves, and work with one doesn't`() {
        assertTrue(offlineIndeterminate(job(OfflinePhase.preparing), null))
        assertTrue(offlineIndeterminate(job(OfflinePhase.verifying), null))
        assertTrue(offlineIndeterminate(job(OfflinePhase.transferring), null))
        assertTrue(offlineIndeterminate(job(OfflinePhase.transferring), OfflineLiveProgress(5, null)))
        assertFalse(offlineIndeterminate(job(OfflinePhase.transferring), OfflineLiveProgress(5, 10)))
        assertFalse(offlineIndeterminate(job(OfflinePhase.queued), null))
    }

    @Test
    fun `only real failures take the error tone`() {
        assertTrue(offlineStatusIsError(job(OfflinePhase.failed, "network")))
        assertTrue(offlineStatusIsError(job(OfflinePhase.missing)))
        assertFalse(offlineStatusIsError(job(OfflinePhase.paused)))
    }

    @Test
    fun `the conflict notice says what happened to the viewer's place`() {
        assertEquals(
            "Scope: your place changed on the server while you were offline, so the server's place was kept.",
            offlineConflictText("Scope")
        )
    }

    @Test
    fun `only waiting for storage offers the storage screen`() {
        assertTrue(offlineOffersStorage(job(OfflinePhase.waitingStorage)))
        assertFalse(offlineOffersStorage(job(OfflinePhase.waitingNetwork)))
    }

    @Test
    fun `Home offers downloads only when the server is out of reach and nothing else is on the page`() {
        val ready = library(row("a", OfflinePhase.ready))
        val failed = CatalogHomeState(loading = false, error = "x", unreachable = true)
        assertTrue(offlineHomeOffersDownloads(failed, ready))
        assertFalse("server said no", offlineHomeOffersDownloads(failed.copy(unreachable = false), ready))
        assertFalse("nothing saved", offlineHomeOffersDownloads(failed, library()))
        assertFalse("still downloading", offlineHomeOffersDownloads(failed, library(row("a", OfflinePhase.paused))))
        assertFalse("no library", offlineHomeOffersDownloads(failed, null))
        assertFalse("unreadable list", offlineHomeOffersDownloads(failed, library(row("a", OfflinePhase.ready), recoverable = true)))
        val rows = listOf(HomeRow("r", "R", listOf(MediaItem("m", "M", "Movie"))))
        assertFalse("partial load", offlineHomeOffersDownloads(failed.copy(rows = rows), ready))
    }

    @Test
    fun `only a network failure counts as unreachable`() {
        val network = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "x"))
        val refused = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE, "x"))
        assertTrue(catalogUnreachable(listOf(ClientResult.Success(1), network)))
        assertFalse(catalogUnreachable(listOf(ClientResult.Success(1), refused)))
    }
}
