/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Independent regression gate: reduced-rate HLS must not use its synthetic segment timeline. */
class TranscodeSeekTest {
    private val originalUrl = "https://example.test/media/Videos/item-1/master.m3u8?" +
        "MediaSourceId=source-1&PlaySessionId=session-1&VideoCodec=h264&AudioCodec=aac&" +
        "MaxFramerate=30&MaxWidth=1920&VideoBitrate=8000000&SubtitleMethod=External&" +
        "AudioStreamIndex=1&SubtitleStreamIndex=3&TranscodeReasons=VideoCodecNotSupported%2CContainerNotSupported"

    private fun source(
        realRate: Double? = 60.0,
        averageRate: Double? = null,
        durationTicks: Long? = 600_000_000L,
        infinite: Boolean = false,
        container: String = "ts",
        protocol: MediaStreamProtocol = MediaStreamProtocol.Hls
    ) = MediaSourceInfo(
        id = "source-1",
        runTimeTicks = durationTicks,
        isInfiniteStream = infinite,
        supportsTranscoding = true,
        transcodingSubProtocol = protocol,
        transcodingContainer = container,
        mediaStreams = listOf(MediaStream(type = MediaStreamType.Video, realFrameRate = realRate, averageFrameRate = averageRate))
    )

    private fun decision(source: MediaSourceInfo = source(), url: String = originalUrl, method: PlayMethod = PlayMethod.Transcode) =
        PlayDecision("item-1", source, method, url, mapOf("Authorization" to "header-only"), "session-1")

    @Test
    fun highRateSourceWithLowerOutputCapRequiresFreshTargetStreams() {
        assertTrue(TranscodeSeek.required(decision()))
        assertTrue(TranscodeSeek.required(decision(source(realRate = 59.94))))
        assertTrue(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30", "MaxFramerate=14"))))
    }

    @Test
    fun unchangedAndOrdinaryFrameRatesKeepTheirCurrentTransport() {
        assertFalse(TranscodeSeek.required(decision(source(realRate = 30.0))))
        assertFalse(TranscodeSeek.required(decision(source(realRate = 24.0))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30", "MaxFramerate=60"))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30", "MaxFramerate=120"))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30&", ""))))
    }

    @Test
    fun explicitFramerateTakesPrecedenceOverMaximumJustAsTheEncoderDoes() {
        assertTrue(TranscodeSeek.required(decision(url = "$originalUrl&Framerate=24")))
        assertFalse(TranscodeSeek.required(decision(url = "$originalUrl&Framerate=60")))
    }

    @Test
    fun averageRateCanSupplyMissingRealRateButDoesNotOverrideKnownRealRate() {
        assertTrue(TranscodeSeek.required(decision(source(realRate = null, averageRate = 60.0))))
        assertFalse(TranscodeSeek.required(decision(source(realRate = 24.0, averageRate = 60.0))))
        assertFalse(TranscodeSeek.required(decision(source(realRate = null, averageRate = null))))
    }

    @Test
    fun missingAndInvalidFrameRatesDoNotGuessARepair() {
        assertFalse(TranscodeSeek.required(decision(source(realRate = 0.0))))
        assertFalse(TranscodeSeek.required(decision(source(realRate = -60.0))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30", "MaxFramerate=unknown"))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("MaxFramerate=30", "MaxFramerate=0"))))
    }

    @Test
    fun directPlayDirectStreamAndVideoCopyKeepTheirCurrentTransport() {
        assertFalse(TranscodeSeek.required(decision(method = PlayMethod.DirectPlay)))
        assertFalse(TranscodeSeek.required(decision(method = PlayMethod.DirectStream)))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("VideoCodec=h264", "VideoCodec=copy"))))
    }

    @Test
    fun liveUnknownDurationAndOtherContainersKeepTheirCurrentTransport() {
        assertFalse(TranscodeSeek.required(decision(source(infinite = true))))
        assertFalse(TranscodeSeek.required(decision(source().copy(liveStreamId = "live-1"))))
        assertFalse(TranscodeSeek.required(decision(source(durationTicks = null))))
        assertFalse(TranscodeSeek.required(decision(source(durationTicks = 0))))
        assertFalse(TranscodeSeek.required(decision(source(container = "mp4"))))
        assertFalse(TranscodeSeek.required(decision(source(protocol = MediaStreamProtocol.Http))))
    }

    @Test
    fun onlyTheKnownVideoHlsEndpointIsRewritten() {
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("master.m3u8", "stream.m3u8"))))
        assertFalse(TranscodeSeek.required(decision(url = originalUrl.replace("master.m3u8", "stream.ts"))))
    }

    @Test
    fun serverStartKeepsItsFiveSecondTailAndNonnegativeBoundary() {
        val play = decision()
        assertEquals(0L, TranscodeSeek.startPositionMs(play, -100))
        assertEquals(0L, TranscodeSeek.startPositionMs(play, 0))
        assertEquals(12_345L, TranscodeSeek.startPositionMs(play, 12_345))
        assertEquals(55_000L, TranscodeSeek.startPositionMs(play, 59_900))
        assertEquals(55_000L, TranscodeSeek.startPositionMs(play, Long.MAX_VALUE))
        assertEquals(0L, TranscodeSeek.startPositionMs(decision(source(durationTicks = 30_000_000)), 2_000))
    }

    @Test
    fun progressiveUrlKeepsNegotiatedTracksQualityAndServerBasePath() {
        val before = Url(originalUrl)
        val after = Url(TranscodeSeek.url(decision(), 12_345))
        assertEquals("/media/Videos/item-1/stream.ts", after.encodedPath)
        assertEquals(before.host, after.host)
        assertEquals(before.protocol, after.protocol)
        for (key in listOf(
            "MediaSourceId", "PlaySessionId", "VideoCodec", "AudioCodec", "MaxFramerate", "MaxWidth", "VideoBitrate",
            "AudioStreamIndex", "SubtitleStreamIndex", "SubtitleMethod", "TranscodeReasons"
        )) {
            assertEquals(before.parameters[key], after.parameters[key], key)
        }
        assertEquals("123450000", after.parameters["StartTimeTicks"])
        assertEquals("false", after.parameters["Static"])
        assertEquals("false", after.parameters["CopyTimestamps"])
    }

    @Test
    fun omittedHlsSubtitleMethodRetainsTheExternalDeliveryDefault() {
        val play = decision(url = originalUrl.replace("SubtitleMethod=External&", ""))
        assertEquals("External", Url(TranscodeSeek.url(play, 10_000)).parameters["SubtitleMethod"])
    }

    @Test
    fun replacingAnOldSeekAndStaticFlagsDoesNotLeaveConflictingCaseVariants() {
        val play = decision(url = "$originalUrl&starttimeticks=900000000&Static=true&copytimestamps=true")
        val url = Url(TranscodeSeek.url(play, 20_000))
        fun values(name: String): List<String> = url.parameters.entries().filter { it.key.equals(name, true) }.flatMap { it.value }
        assertEquals(listOf("200000000"), values("StartTimeTicks"))
        assertEquals(listOf("false"), values("Static"))
        assertEquals(listOf("false"), values("CopyTimestamps"))
    }

    @Test
    fun progressiveUrlNeverAddsAuthenticationFromTheDecisionHeaders() {
        val url = TranscodeSeek.url(decision(url = "$originalUrl&ApiKey=legacy-test-token&api_key=another-test-token"), 0)
        assertFalse(url.contains("header-only"))
        assertFalse(url.contains("legacy-test-token"))
        assertFalse(url.contains("another-test-token"))
        assertFalse(Url(url).parameters.names().any { it.equals("ApiKey", true) || it.equals("api_key", true) })
    }

    @Test
    fun sourceFrameRatesSurvivePlaybackInfoDecoding() {
        val stream = Json.decodeFromString<MediaStream>("""{"Type":"Video","RealFrameRate":59.94,"AverageFrameRate":60.0}""")
        assertEquals(59.94, stream.realFrameRate)
        assertEquals(60.0, stream.averageFrameRate)
    }
}
