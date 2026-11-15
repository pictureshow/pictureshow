/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.*
import androidx.compose.ui.input.key.Key
import kotlin.test.*

class DesktopPlaybackInformationTest {
    @Test fun readsActualSelectedCodecsAndNeverNegotiationCandidatesOrPrivateStrings() {
        val properties = mapOf("current-tracks/video/codec" to "hevc", "current-tracks/audio/codec" to "aac",
            "video-params/w" to "3840", "video-params/h" to "2160", "video-params/gamma" to "pq",
            "hwdec-current" to "no", "audio-params/channel-count" to "6", "audio-params/samplerate" to "48000",
            "video-params/primaries" to "https://private.invalid/token")
        val f = receivedFormats(properties::get, HdrOutput.HdrEdR)
        assertEquals("hevc", f.videoCodec); assertEquals("aac", f.audioCodec)
        assertEquals(3840, f.width); assertEquals(2160, f.height)
        assertEquals(6, f.audioChannels); assertEquals(48000, f.audioSampleRate)
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
        assertTrue(sections[2].lines.any { it.contains("HDR10") })
        val received = sections.first { it.title == "Received by mpv" }.lines.joinToString()
        assertTrue(received.contains("Video: Not reported")); assertTrue(received.contains("Audio: Not reported"))
        val all = sections.toString()
        assertFalse(all.contains("api_key")); assertFalse(all.contains("server.invalid")); assertFalse(all.contains("future-private"))
        assertEquals(PlayerKey.Info, playerKeyAction(Key.I, 0, false, false, false, false, true))
        assertNull(playerKeyAction(Key.I, 0, false, true, false, false, false))
        assertTrue(PLAYER_SHORTCUT_ROWS.contains("I" to "Playback info"))
    }
}
