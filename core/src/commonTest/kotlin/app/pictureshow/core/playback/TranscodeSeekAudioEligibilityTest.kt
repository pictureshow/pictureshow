/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A seek repair must not introduce lossy audio encoding where the original HLS request could copy it. */
class TranscodeSeekAudioEligibilityTest {
    private fun audio(index: Int = 1, codec: String? = "aac", channels: Int? = 2) =
        MediaStream(type = MediaStreamType.Audio, index = index, codec = codec, channels = channels)

    private fun decision(
        audio: List<MediaStream> = listOf(audio()),
        parameters: String = "AudioCodec=aac,ac3,eac3&MaxAudioChannels=2",
        defaultAudio: Int? = 1,
        reasons: List<String> = emptyList()
    ): PlayDecision = PlayDecision(
        itemId = "item-1",
        mediaSource = MediaSourceInfo(
            id = "source-1",
            runTimeTicks = 6_000_000_000,
            transcodingContainer = "ts",
            transcodingSubProtocol = MediaStreamProtocol.Hls,
            defaultAudioStreamIndex = defaultAudio,
            mediaStreams = listOf(MediaStream(type = MediaStreamType.Video, realFrameRate = 60.0)) + audio
        ),
        method = PlayMethod.Transcode,
        url = "https://example.test/Videos/item-1/master.m3u8?VideoCodec=h264&MaxFramerate=30&$parameters",
        headers = emptyMap(),
        playSessionId = "session-1",
        transcodeReasons = reasons
    )

    @Test
    fun negotiatedAudioMetadataCanAlreadyDescribeTheEncodedOutput() {
        // The server rewrites the selected track to stereo in PlaybackInfo after deciding to downmix.
        assertTrue(TranscodeSeek.required(decision(reasons = listOf("VideoFramerateNotSupported", "AudioChannelsNotSupported"))))
        assertFalse(TranscodeSeek.required(decision(reasons = listOf("AudioCodecNotSupported"))))
        assertFalse(TranscodeSeek.required(decision(reasons = listOf("VideoFramerateNotSupported"))))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=copy", reasons = listOf("AudioChannelsNotSupported"))))
    }

    @Test
    fun compatibleAudioThatHlsCouldCopyKeepsTheExistingRoute() {
        assertFalse(TranscodeSeek.required(decision()))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=aac,ac3&MaxAudioChannels=6")))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=ac3,AAC&MaxAudioChannels=2")))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=aac&AllowAudioStreamCopy=true&EnableAutoStreamCopy=true")))
    }

    @Test
    fun explicitAlreadyDisabledAudioCopyAllowsRepairWithoutNewAudioEncoding() {
        assertTrue(TranscodeSeek.required(decision(parameters = "AudioCodec=aac&AllowAudioStreamCopy=false")))
        assertTrue(TranscodeSeek.required(decision(parameters = "AudioCodec=aac&EnableAutoStreamCopy=false")))
        assertTrue(TranscodeSeek.required(decision(parameters = "AudioCodec=aac&allowaudiostreamcopy=FALSE")))
    }

    @Test
    fun incompatibleSelectedAudioCodecAlreadyRequiresEncoding() {
        assertTrue(TranscodeSeek.required(decision(audio = listOf(audio(codec = "pcm_s16le")))))
        assertTrue(TranscodeSeek.required(decision(audio = listOf(audio(codec = "flac")))))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=copy")))
        assertFalse(
            TranscodeSeek.required(decision(audio = listOf(audio(channels = 6)), parameters = "AudioCodec=copy&MaxAudioChannels=2"))
        )
    }

    @Test
    fun unknownCodecTargetOrSelectedAudioDoesNotAssumeEncodingIsNecessary() {
        assertFalse(TranscodeSeek.required(decision(audio = listOf(audio(codec = null)))))
        assertFalse(TranscodeSeek.required(decision(audio = listOf(audio(codec = "")))))
        assertFalse(TranscodeSeek.required(decision(parameters = "MaxAudioChannels=2")))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=")))
        assertFalse(TranscodeSeek.required(decision(parameters = "AudioCodec=aac&AudioStreamIndex=99")))
    }

    @Test
    fun knownChannelReductionAlreadyRequiresEncodingButEqualOrUnknownChannelsDoNotProveIt() {
        assertTrue(TranscodeSeek.required(decision(audio = listOf(audio(channels = 6)))))
        assertFalse(TranscodeSeek.required(decision(audio = listOf(audio(channels = 2)))))
        assertFalse(TranscodeSeek.required(decision(audio = listOf(audio(channels = null)))))
        assertFalse(TranscodeSeek.required(decision(audio = listOf(audio(channels = 6)), parameters = "AudioCodec=aac")))
    }

    @Test
    fun negotiatedChannelLimitUsesTheServersPrecedence() {
        val surround = listOf(audio(channels = 6))
        assertTrue(TranscodeSeek.required(decision(surround, "AudioCodec=aac&TranscodingMaxAudioChannels=2")))
        assertTrue(TranscodeSeek.required(decision(surround, "AudioCodec=aac&AudioChannels=2")))
        assertFalse(TranscodeSeek.required(decision(surround, "AudioCodec=aac&MaxAudioChannels=8&AudioChannels=2")))
        assertTrue(TranscodeSeek.required(decision(surround, "AudioCodec=aac&MaxAudioChannels=8&aac-audiochannels=2")))
        assertFalse(TranscodeSeek.required(decision(surround, "AudioCodec=aac&MaxAudioChannels=2&aac-audiochannels=8")))
    }

    @Test
    fun selectedAudioIndexWinsOverTheDefaultAndUnselectedIncompatibleTracks() {
        val tracks = listOf(audio(index = 1, codec = "pcm_s16le"), audio(index = 2, codec = "aac"))
        assertFalse(TranscodeSeek.required(decision(tracks, "AudioCodec=aac&AudioStreamIndex=2", defaultAudio = 1)))
        assertTrue(TranscodeSeek.required(decision(tracks, "AudioCodec=aac&AudioStreamIndex=1", defaultAudio = 2)))
        assertFalse(TranscodeSeek.required(decision(tracks, "AudioCodec=aac", defaultAudio = 2)))
        assertTrue(TranscodeSeek.required(decision(tracks, "AudioCodec=aac", defaultAudio = 1)))
    }

    @Test
    fun silentSourcesNeedNoAudioTradeoff() {
        assertTrue(TranscodeSeek.required(decision(audio = emptyList(), defaultAudio = null)))
    }
}
