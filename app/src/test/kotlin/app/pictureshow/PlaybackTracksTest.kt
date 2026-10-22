/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30], application = android.app.Application::class)
class PlaybackTracksTest {
    private fun group(vararg formats: Format) = Tracks.Group(
        TrackGroup(*formats),
        false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        BooleanArray(formats.size)
    )

    @Test
    fun metadataFindsReorderedTracksDespiteDifferentContainerIds() {
        val english = MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", channels = 2, language = "eng")
        val french = english.copy(index = 4, language = "fra")
        val tracks = group(
            Format.Builder().setId("99").setSampleMimeType("audio/mp4a-latm").setChannelCount(2).setLanguage("fr").build(),
            Format.Builder().setId("2").setSampleMimeType("audio/mp4a-latm").setChannelCount(2).setLanguage("en").build()
        )
        assertEquals(1, PlaybackTracks.find(listOf(english, french), english, listOf(tracks))?.second)
        assertEquals(0, PlaybackTracks.find(listOf(english, french), french, listOf(tracks))?.second)
    }

    @Test
    fun audioExceedingRendererCapabilitiesRequiresNewServerDecision() {
        val requested = MediaStream(type = MediaStreamType.Audio, index = 0, codec = "aac")
        val tracks = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()),
            false,
            intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES),
            booleanArrayOf(false)
        )
        assertNull(PlaybackTracks.find(listOf(requested), requested, listOf(tracks)))
    }

    @Test
    fun ambiguousMetadataNeverFallsBackToALowerScoringOrdinal() {
        val requested = MediaStream(type = MediaStreamType.Audio, index = 0, language = "en", codec = "dts", channels = 6)
        val tracks = group(
            Format.Builder().setSampleMimeType("audio/mp4a-latm").setChannelCount(2).setLanguage("en").build(),
            Format.Builder().setSampleMimeType("audio/vnd.dts").setChannelCount(6).setLanguage("en").build(),
            Format.Builder().setSampleMimeType("audio/vnd.dts").setChannelCount(6).setLanguage("en").build()
        )
        assertNull(PlaybackTracks.find(listOf(requested), requested, listOf(tracks)))
    }

    @Test
    fun titleDistinguishesCommentaryAndExternalSubtitleUsesExplicitId() {
        val main = MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", title = "Main")
        val commentary = main.copy(index = 2, title = "Commentary")
        val tracks = group(
            Format.Builder().setSampleMimeType("audio/aac").setLabel("Commentary").setLanguage("en").build(),
            Format.Builder().setSampleMimeType("audio/aac").setLabel("Main").setLanguage("en").build()
        )
        assertEquals(0, PlaybackTracks.find(listOf(main, commentary), commentary, listOf(tracks))?.second)
        val subtitle = MediaStream(type = MediaStreamType.Subtitle, index = 7, isExternal = true)
        val external = group(Format.Builder().setId("merged:server-subtitle:7").setSampleMimeType("text/vtt").build())
        assertSame(external, PlaybackTracks.find(listOf(subtitle), subtitle, listOf(external))?.first)
        val extracted = subtitle.copy(isExternal = false, deliveryMethod = app.pictureshow.core.playback.SubtitleDeliveryMethod.External)
        assertSame(external, PlaybackTracks.find(listOf(extracted), extracted, listOf(external))?.first)
    }
}
