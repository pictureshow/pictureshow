/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackInformationTest {
    private fun decision(method: PlayMethod, reasons: List<String>) = PlayDecision(
        "film",
        MediaSourceInfo(),
        method,
        "https://example.test/master.m3u8?api_key=private",
        emptyMap(),
        null,
        reasons
    )

    @Test fun absentDuplicateAndFutureReasonsRemainHonestAndNeverEchoRawServerText() {
        assertEquals(
            listOf("The server did not report why it is converting this stream."),
            PlaybackInformation.reasons(decision(PlayMethod.Transcode, emptyList()))
        )
        val reasons = PlaybackInformation.reasons(
            decision(
                PlayMethod.Transcode,
                listOf("AudioCodecNotSupported", "audiocodecnotsupported", "https://private.example.test/secret")
            )
        )
        assertEquals(2, reasons.size)
        assertFalse(reasons.joinToString().contains("private"))
        assertEquals(emptyList(), PlaybackInformation.reasons(decision(PlayMethod.DirectPlay, listOf("AudioCodecNotSupported"))))
    }

    @Test fun serverPerStreamBitrateIsDecodedAndInvalidMetadataIsNotInvented() {
        val source = PlaybackJson.decodeFromString<MediaSourceInfo>(
            """{"MediaStreams":[{"Type":"Audio","Index":1,"Codec":"aac","BitRate":128000}]}"""
        )
        assertEquals("128 kbps", PlaybackInformation.bitrate(source.mediaStreams.single().bitRate))
        assertEquals("Not reported", PlaybackInformation.resolution(1920, null))
        assertEquals("Not reported", PlaybackInformation.codec("https://private.example.test"))
    }

    @Test fun currentBitrateUsesUnchangedBytesRatherThanEncoderCeilings() {
        val source = MediaSourceInfo(
            bitrate = 8_000_000,
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Video, codec = "h264", bitRate = 3_000_000)
            )
        )
        fun stream(query: String) = PlayDecision(
            "film",
            source,
            PlayMethod.Transcode,
            "https://example.test/master.m3u8?$query",
            emptyMap(),
            null
        )
        assertEquals(3_000_000, PlaybackInformation.streamBitrate(stream("VideoCodec=copy&AudioCodec=aac&AudioBitrate=128000")))
        assertEquals(3_000_000, PlaybackInformation.streamBitrate(stream("VideoCodec=copy&AudioCodec=aac")))
        assertEquals(3_000_000, PlaybackInformation.streamBitrate(stream("VideoCodec=copy&AudioCodec=copy")))
        assertEquals(null, PlaybackInformation.streamBitrate(stream("VideoCodec=h264&VideoBitrate=2000000&AudioBitrate=128000")))
    }
}
