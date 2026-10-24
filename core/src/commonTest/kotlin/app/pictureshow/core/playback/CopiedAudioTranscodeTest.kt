/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopiedAudioTranscodeTest {
    private fun decision(rate: Double = 60.0, extra: String = "") = PlayDecision(
        "item-copy",
        MediaSourceInfo(
            id = "source-copy",
            runTimeTicks = 2_400_000_000,
            transcodingSubProtocol = MediaStreamProtocol.Hls,
            transcodingContainer = "ts",
            defaultAudioStreamIndex = 1,
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Video, codec = "av1", index = 0, realFrameRate = rate),
                MediaStream(type = MediaStreamType.Audio, codec = "aac", index = 1, channels = 6)
            )
        ),
        PlayMethod.Transcode,
        "https://example.test/Videos/item-copy/master.m3u8?VideoCodec=h264&AudioCodec=aac&MaxFramerate=30&" +
            "AudioStreamIndex=1&MaxAudioChannels=6&VideoBitrate=8000000&$extra",
        emptyMap(),
        "encode-copy"
    )

    @Test
    fun reducedRateCopiedSurroundGetsRealClockTransportWithoutAnAudioEncode() {
        val decision = decision(extra = "AllowAudioStreamCopy=true")
        assertTrue(TranscodeSeek.required(decision))
        val url = Url(TranscodeSeek.url(decision, 126_345))
        assertEquals("/Videos/item-copy/stream.ts", url.encodedPath)
        assertEquals("true", url.parameters["AllowAudioStreamCopy"])
        assertEquals("6", url.parameters["MaxAudioChannels"])
        assertEquals("aac", url.parameters["AudioCodec"])
        assertEquals("1263450000", url.parameters["StartTimeTicks"])
        assertEquals("8000000", url.parameters["VideoBitrate"])
    }

    @Test
    fun omittedCopyFlagKeepsTheServerCopyDefaultAndOrdinaryHlsStaysHls() {
        assertTrue(TranscodeSeek.required(decision()))
        assertFalse(TranscodeSeek.required(decision(rate = 30.0)))
        assertFalse(Url(TranscodeSeek.url(decision(), 0)).parameters["AllowAudioStreamCopy"].equals("false", true))
    }
}
