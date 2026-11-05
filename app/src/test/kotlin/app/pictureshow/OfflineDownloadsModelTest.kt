/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
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
    private fun job(phase: OfflinePhase, failure: String? = null, cap: Boolean = false) = OfflineJob(phase, failure = failure, pausedByCap = cap)

    @Test
    fun `percent appears only with a known total`() {
        val known = offlineStatus(job(OfflinePhase.transferring), OfflineLiveProgress(50, 200), true, OfflineNetwork.unmetered, 0)
        assertTrue(known, known.startsWith("25%"))
        val unknown = offlineStatus(job(OfflinePhase.transferring), OfflineLiveProgress(2048, null), true, OfflineNetwork.unmetered, 0)
        assertFalse(unknown, unknown.contains("%"))
        assertTrue(unknown, unknown.contains("2 KB received"))
    }

    @Test
    fun `every phase says something true and offers what it can do`() {
        val phases = OfflinePhase.entries
        phases.forEach { assertTrue(it.name, offlineStatus(job(it), null, true, OfflineNetwork.none, 10).isNotBlank()) }
        assertEquals(listOf(OfflineAction.Play, OfflineAction.Delete), offlineActions(job(OfflinePhase.ready), true))
        assertEquals(listOf(OfflineAction.Retry, OfflineAction.Delete), offlineActions(job(OfflinePhase.failed), true))
        assertEquals(listOf(OfflineAction.Redownload, OfflineAction.Delete), offlineActions(job(OfflinePhase.missing), true))
        assertEquals(listOf(OfflineAction.Resume, OfflineAction.Delete), offlineActions(job(OfflinePhase.paused), true))
        assertTrue(offlineActions(job(OfflinePhase.deleting), true).isEmpty())
        phases.filter { it != OfflinePhase.ready }.forEach { assertFalse(it.name, OfflineAction.Play in offlineActions(job(it), true)) }
    }

    @Test
    fun `waiting names what it waits for`() {
        assertEquals("Waiting for Wi-Fi", offlineStatus(job(OfflinePhase.waitingNetwork), null, true, OfflineNetwork.metered, 0))
        assertEquals("Waiting for a connection", offlineStatus(job(OfflinePhase.waitingNetwork), null, true, OfflineNetwork.none, 0))
        assertTrue(OfflineAction.AllowCellular in offlineActions(job(OfflinePhase.waitingNetwork), true))
        assertFalse(OfflineAction.AllowCellular in offlineActions(job(OfflinePhase.waitingNetwork).copy(cellularAllowed = true), true))
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
            entries = listOf(entry("a", OfflinePhase.ready), entry("b", OfflinePhase.paused), entry("c", OfflinePhase.failed), entry("d", OfflinePhase.missing))
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
}
