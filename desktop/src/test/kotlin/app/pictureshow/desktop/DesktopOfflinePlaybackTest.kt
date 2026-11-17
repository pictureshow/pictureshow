/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.ItemUserData
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResource
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopOfflinePlaybackTest {
    private val dir = kotlin.io.path.createTempDirectory("offline-play").toFile()
    private val media = File(dir, "media.mp4").apply { writeBytes(ByteArray(10)) }
    private fun file(path: String) = File(dir, path)

    /** A 1080p conversion: English muxed, French as a sidecar, German left behind; one WebVTT sidecar and one skipped. */
    private val converted = OfflineEntry(
        "e1", "item-1", OfflineMediaKind.episode, sourceId = "src", container = "mp4", preset = OfflinePreset.video1080, runtimeTicks = 600_000_000,
        labels = OfflineLabels("Pilot", seriesName = "Show", seriesId = "series", seasonId = "season", seasonNumber = 1, episodeNumber = 2),
        resources = listOf(
            OfflineResource("media", OfflineResourceRole.media, "media.mp4", complete = true),
            OfflineResource("audio-3", OfflineResourceRole.audio, "audio-3.m4a", complete = true),
            OfflineResource("sub-5", OfflineResourceRole.subtitle, "sub-5.vtt", complete = true)
        ),
        tracks = listOf(
            OfflineTrack(3, "audio", "French", "fre", resourceId = "audio-3"),
            OfflineTrack(1, "audio", "English", "eng", isDefault = true),
            OfflineTrack(2, "audio", "German", "ger", available = false),
            OfflineTrack(5, "subtitle", "English SDH", "eng", resourceId = "sub-5"),
            OfflineTrack(6, "subtitle", "Spanish", "spa", available = false)
        ),
        job = OfflineJob(phase = OfflinePhase.ready)
    )

    @Test
    fun `a local decision is the file itself, with no headers, session or server`() {
        val decision = desktopLocalDecision(converted, media, ::file)
        assertEquals(PlayMethod.DirectPlay, decision.method)
        assertEquals(PlaybackEngine.Mpv, decision.engine)
        assertTrue(decision.headers.isEmpty())
        assertNull(decision.playSessionId)
        assertEquals(media.canonicalFile, decision.localFile()!!.canonicalFile)
        assertEquals("item-1", decision.itemId)
    }

    @Test
    fun `only downloaded tracks are offered, muxed audio first then sidecars as mpv numbers them`() {
        val streams = desktopLocalDecision(converted, media, ::file).mediaSource.mediaStreams
        assertEquals(listOf(1, 3), streams.filter { it.type == MediaStreamType.Audio }.map { it.index })
        assertEquals(listOf(5), streams.filter { it.type == MediaStreamType.Subtitle }.map { it.index })
        val french = streams.first { it.index == 3 }
        assertFalse(french.isExternal, "a sidecar audio track is numbered with the file's own")
        assertEquals(file("audio-3.m4a").canonicalFile, french.localFile()!!.canonicalFile)
        assertTrue(streams.first { it.index == 5 }.isExternal)
    }

    @Test
    fun `mpv is told the sidecar audio files and the chosen audio by its position`() {
        val streams = desktopLocalDecision(converted, media, ::file).mediaSource.mediaStreams
        val french = desktopLocalTracks(streams, audio = 3, subtitle = -1)
        assertEquals(listOf(file("audio-3.m4a").canonicalFile), french.audioFiles.map { it.canonicalFile })
        assertEquals("2", french.aid)
        assertEquals("no", french.sid)
        assertNull(french.subtitleFile)
        assertEquals("1", desktopLocalTracks(streams, audio = 1, subtitle = -1).aid)
        assertEquals("auto", desktopLocalTracks(streams, audio = 99, subtitle = -1).aid)
    }

    @Test
    fun `a downloaded subtitle is a file to add, an embedded one an ordinal`() {
        val streams = desktopLocalDecision(converted, media, ::file).mediaSource.mediaStreams
        val (sid, sidecar) = desktopLocalSubtitle(streams, 5)
        assertEquals("no", sid)
        assertEquals(file("sub-5.vtt").canonicalFile, sidecar!!.canonicalFile)
        val original = converted.copy(
            preset = OfflinePreset.original,
            resources = listOf(OfflineResource("media", OfflineResourceRole.media, "media.mkv", complete = true)),
            tracks = listOf(OfflineTrack(1, "audio", "English"), OfflineTrack(4, "subtitle", "PGS"), OfflineTrack(7, "subtitle", "ASS"))
        )
        val embedded = desktopLocalDecision(original, media, ::file).mediaSource.mediaStreams
        assertEquals("2" to null, desktopLocalSubtitle(embedded, 7))
        assertEquals("no" to null, desktopLocalSubtitle(embedded, -1))
    }

    @Test
    fun `the player item carries the series facts next-episode needs and the place to start`() {
        val item = converted.playerItem(ItemUserData(playbackPositionTicks = 5))
        assertEquals("Episode", item.type)
        assertEquals("series", item.seriesId)
        assertEquals(2, item.indexNumber)
        assertEquals(1, item.parentIndexNumber)
        assertEquals(5, item.userData.playbackPositionTicks)
    }

    @Test
    fun `only a ready video download has media to play`() {
        assertEquals("media.mp4", converted.readyMedia())
        assertNull(converted.copy(job = OfflineJob(phase = OfflinePhase.transferring)).readyMedia())
        assertNull(converted.copy(kind = OfflineMediaKind.audio).readyMedia())
    }

    @Test
    fun `a remote decision is never mistaken for a local file`() {
        val local = desktopLocalDecision(converted, media, ::file)
        val remote = app.pictureshow.core.playback.PlayDecision(local.itemId, local.mediaSource, PlayMethod.DirectPlay,
            "https://server.example/Videos/1/stream", emptyMap(), "session")
        assertNull(remote.localFile())
    }

    @Test
    fun `audio picks are capped and never empty, subtitle picks are capped`() {
        assertEquals(setOf(1), desktopToggleAudio(setOf(1), 1))
        assertEquals(setOf(1, 2), desktopToggleAudio(setOf(1), 2))
        assertEquals(setOf(1, 2), desktopToggleAudio(setOf(1, 2), 3))
        assertEquals(emptySet(), desktopToggleSubtitle(setOf(4), 4))
        assertEquals(setOf(4, 5), desktopToggleSubtitle(setOf(4, 5), 6))
    }
}
