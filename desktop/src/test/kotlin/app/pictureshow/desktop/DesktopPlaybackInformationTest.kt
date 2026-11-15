/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.*
import androidx.compose.ui.input.key.Key
import kotlin.test.*

class DesktopPlaybackInformationTest {
    @Test fun liveWaylandLogProvesOnlyCompositorAcceptanceAndOverlayScrollKeysStayInThePanel() {
        assertEquals(HdrOutput.WaylandPq, waylandHdrOutput(true, "image_description=ready; transfer=ST2084_PQ(11); primaries=BT2020(6)\n"))
        assertEquals(HdrOutput.Unknown, waylandHdrOutput(true, "image_description=FAILED\n"))
        assertEquals(HdrOutput.Unknown, waylandHdrOutput(false, "image_description=ready; transfer=ST2084_PQ(11)"))
        assertEquals(HdrOutput.Unknown, waylandHdrOutput(true, "PQ_BT2020_ready=1"))
        assertEquals(64, playbackInfoScrollTarget(Key.DirectionDown, 0, 1000))
        assertEquals(300, playbackInfoScrollTarget(Key.PageDown, 0, 1000))
        assertEquals(0, playbackInfoScrollTarget(Key.MoveHome, 700, 1000))
        assertEquals(1000, playbackInfoScrollTarget(Key.MoveEnd, 0, 1000))
        assertEquals(0, playbackInfoScrollTarget(Key.DirectionUp, 0, 1000))
        assertNull(playbackInfoScrollTarget(Key.DirectionRight, 0, 1000))
    }

    @Test fun readsActualSelectedCodecsAndNeverNegotiationCandidatesOrPrivateStrings() {
        val properties = mapOf("current-tracks/video/codec" to "hevc", "current-tracks/audio/codec" to "aac",
            "video-params/w" to "3840", "video-params/h" to "2160", "video-params/gamma" to "pq",
            "hwdec-current" to "no", "audio-params/channel-count" to "6", "audio-params/samplerate" to "48000",
            "video-params/primaries" to "https://private.invalid/token", "current-tracks/audio/decoder" to "aac")
        val f = receivedFormats(properties::get, HdrOutput.HdrEdR)
        assertEquals("hevc", f.videoCodec); assertEquals("aac", f.audioCodec)
        assertEquals(3840, f.width); assertEquals(2160, f.height)
        assertEquals(6, f.audioChannels); assertEquals(48000, f.audioSampleRate)
        assertEquals("aac", f.audioDecoder)
        assertNull(receivedFormats({ "https://private.invalid/token" }, HdrOutput.Unknown).audioDecoder)
        assertNull(f.primaries); assertEquals("Software (mpv)", PlaybackFormatInformation.decoder(f.hwdec))
        assertEquals(DesktopReceivedFormats(), receivedFormats({ null }, HdrOutput.Unknown))
    }

    @Test fun displayUsesCurrentNativeEvidenceNotPotentialOrTargetOptions() {
        assertEquals(HdrOutput.Sdr, nativeHdrOutput("mac", """{"wantsEDR":true,"screenMaxEDR":1,"screenPotentialEDR":4}"""))
        assertEquals(HdrOutput.HdrEdR, nativeHdrOutput("mac", """{"wantsEDR":true,"screenMaxEDR":2.3}"""))
        assertEquals(HdrOutput.Unknown, nativeHdrOutput("mac", """{"screenPotentialEDR":4}"""))
        assertEquals(HdrOutput.HdrScRgb, nativeHdrOutput("windows", """{"SetColorSpace1HRESULT":0,"CheckColorSpaceSupport":1,"outputColorSpace":12}"""))
        assertEquals(HdrOutput.Sdr, nativeHdrOutput("windows", """{"SetColorSpace1HRESULT":0,"CheckColorSpaceSupport":1,"outputColorSpace":0}"""))
        assertEquals(HdrOutput.Unknown, nativeHdrOutput("windows", """{"SetColorSpace1HRESULT":-1,"CheckColorSpaceSupport":1,"outputColorSpace":12}"""))
        assertEquals(HdrOutput.Unknown, nativeHdrOutput("windows", """{"outputMaxLuminance":1000}"""))
        assertEquals(HdrOutput.Unknown, nativeHdrOutput("mac", "malformed"))
    }

    @Test fun explanationPrecedesSeparateSourceAndReceivedSectionsWithoutGuessingOutput() {
        val d = PlayDecision("item", MediaSourceInfo(container = "mkv", mediaStreams = listOf(
            MediaStream(type = MediaStreamType.Video, codec = "hevc", videoRangeType = VideoRangeType.HDR10),
            MediaStream(type = MediaStreamType.Audio, index = 1, codec = "dts", channels = 6))),
            PlayMethod.Transcode, "https://server.invalid/video?VideoCodec=h264,hevc&api_key=private", emptyMap(), null,
            listOf("VideoCodecNotSupported", "future-private-reason"))
        val p = DesktopPlaybackState(MediaItem("item", "Film"), d, audioIndex = 1)
        val sections = playbackInfoSections(p, 4)
        assertEquals("Transcode", sections.first().title)
        assertEquals("Why the server converts", sections[1].title)
        assertTrue(sections.first { it.title == "Source · server metadata" }.fields.any { it.second.contains("HDR10") })
        val received = sections.first { it.title == "Received by this computer" }.fields.joinToString()
        assertTrue(received.contains("(Video, Not reported")); assertTrue(received.contains("(Audio, Not reported"))
        assertNotNull(sections.first { it.title == "Source · server metadata" }.note)
        val direct = playbackInfoSections(p.copy(decision = PlayDecision(d.itemId, d.mediaSource, PlayMethod.DirectPlay, d.url, d.headers, null)), null)
        assertNull(direct.first { it.title == "Source · server metadata" }.note)
        val unreportedDecoder = playbackInfoSections(p.copy(received = DesktopReceivedFormats(audioCodec = "aac")), null)
            .first { it.title == "Received by this computer" }.fields.toMap()
        assertEquals("Not reported", unreportedDecoder["Audio decoder"])
        val observedDecoder = playbackInfoSections(p.copy(received = DesktopReceivedFormats(audioCodec = "aac", audioDecoder = "aac")), null)
            .first { it.title == "Received by this computer" }.fields.toMap()
        assertEquals("mpv · aac", observedDecoder["Audio decoder"])
        assertEquals("Display output", direct[1].title)
        val engine = NativeDesktopPlayback(java.io.File(System.getProperty("java.io.tmpdir"), "playinfo-unit"))
        engine.informationQuality(4)
        val limited = PlayDecision(d.itemId, d.mediaSource, PlayMethod.Transcode, d.url, d.headers, null, listOf("ContainerBitrateExceedsLimit"))
        val nativeInfo = engine.informationSections(limited, DesktopReceivedFormats()).toString()
        assertTrue(nativeInfo.contains("4 Mbps quality limit"))
        assertTrue(nativeInfo.contains("Quality is capped at 4 Mbps"))
        engine.close()
        val all = sections.toString()
        assertFalse(all.contains("api_key")); assertFalse(all.contains("server.invalid")); assertFalse(all.contains("future-private"))
        assertEquals(PlayerKey.Info, playerKeyAction(Key.I, 0, false, false, false, false, true))
        assertNull(playerKeyAction(Key.I, 0, false, true, false, false, false))
        assertTrue(PLAYER_SHORTCUT_ROWS.contains("I" to "Playback info"))
    }
}
