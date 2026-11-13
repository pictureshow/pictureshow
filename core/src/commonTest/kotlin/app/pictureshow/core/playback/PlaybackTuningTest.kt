/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PlaybackTuningTest {
    private val account = Account(Server("server", "Fixture", "https://example.test", "12.0.0"), User("viewer", "Viewer"), "fixture-token")

    @Test
    fun networkCapsApplyToTheWireAndExplicitOverrideSuspendsBandwidthReduction() = runTest {
        val bodies = mutableListOf<PlaybackInfoRequest>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    bodies += PlaybackJson.decodeFromString<PlaybackInfoRequest>(request.body.toByteArray().decodeToString())
                    respond(
                        """{"MediaSources":[],"PlaySessionId":"session"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val client = PlaybackClient(http, ClientIdentity("Fixture", "fixture"))
            val preferences = PlaybackPreferences(bitrateMbps = 80, tuning = PlaybackTuning(remoteBitrateMbps = 10, meteredBitrateMbps = 4))
            for (network in PlaybackNetwork.entries) {
                client.prepare(account, "item", CapabilityFixtures.projector.copy(settings = preferences.deviceSettings(network)))
            }
            client.prepare(
                account,
                "item",
                CapabilityFixtures.projector.copy(
                    settings = preferences.deviceSettings(PlaybackNetwork.Home, estimatedBandwidth = 9_000_000)
                )
            )
            client.prepare(
                account,
                "item",
                CapabilityFixtures.projector.copy(
                    settings = preferences.deviceSettings(PlaybackNetwork.Home, estimatedBandwidth = 9_000_000, qualityOverrideMbps = 40)
                )
            )
            client.prepare(
                account,
                "item",
                CapabilityFixtures.projector.copy(
                    settings = preferences.copy(
                        tuning = preferences.tuning.copy(remoteBitrateMbps = 20)
                    ).deviceSettings(PlaybackNetwork.Remote, estimatedBandwidth = 18_000_000)
                )
            )
            assertTrue(PlaybackNetworkPolicy.shouldReduce(20_000_000, 14_400_000, 2, 131_072, 30_000))
            assertFalse(PlaybackNetworkPolicy.shouldReduce(20_000_000, 20_000_000, 2, 131_072, 30_000))
            assertEquals(listOf(80, 10, 4, 4, 40, 10), bodies.map { it.maxStreamingBitrate / 1_000_000 })
            bodies.forEach { assertEquals(it.maxStreamingBitrate, it.deviceProfile?.maxStreamingBitrate) }
        } finally {
            http.close()
        }
    }

    @Test
    fun burnPoliciesChangeActualRequestAndCannotAdvertiseEmbeddedImageForImageOnly() {
        fun request(policy: SubtitleBurnIn) = PlaybackClient.playbackInfoRequest(
            account,
            CapabilityFixtures.projector.copy(settings = PlaybackSettings(tuning = PlaybackTuning(subtitleBurnIn = policy))),
            PlaybackRequest(subtitleStreamIndex = 2)
        )
        val automatic = request(SubtitleBurnIn.Automatic)
        val image = request(SubtitleBurnIn.ImageOnly)
        val always = request(SubtitleBurnIn.Always)
        assertTrue(automatic.deviceProfile!!.subtitleProfiles.any { it.format == "pgssub" && it.method == SubtitleDeliveryMethod.Embed })
        assertFalse(image.deviceProfile!!.subtitleProfiles.any { it.format == "pgssub" && it.method != SubtitleDeliveryMethod.Encode })
        assertTrue(image.deviceProfile.subtitleProfiles.any { it.format == "ass" && it.method == SubtitleDeliveryMethod.External })
        assertTrue(always.deviceProfile!!.subtitleProfiles.all { it.method == SubtitleDeliveryMethod.Encode })
        val wire = PlaybackJson.encodeToJsonElement(PlaybackInfoRequest.serializer(), always).jsonObject
        assertTrue(wire.getValue("AlwaysBurnInSubtitleWhenTranscoding").jsonPrimitive.boolean)
        assertFalse(automatic.alwaysBurnInSubtitleWhenTranscoding)
    }

    @Test
    fun outputPermissionCannotInventPassthroughAndDownmixConstrainsBodyAndProfile() {
        val baseline = CapabilityFixtures.dolbyVisionStreamer
        fun caps(tuning: PlaybackTuning) = baseline.copy(settings = baseline.settings.copy(tuning = tuning))
        val denyTruehd = PlaybackCapabilityPolicy.effective(caps(PlaybackTuning(disabledPassthrough = setOf(PassthroughEncoding.TRUEHD))))
        assertFalse(PassthroughEncoding.TRUEHD in denyTruehd.audioOutput.passthrough)
        assertTrue(PassthroughEncoding.DTS in denyTruehd.audioOutput.passthrough)
        assertTrue(PassthroughEncoding.TRUEHD in baseline.audioOutput.passthrough)
        val onlyStereo = caps(PlaybackTuning(downmix = true))
        val effective = PlaybackCapabilityPolicy.effective(onlyStereo)
        assertTrue(effective.audioOutput.passthrough.isEmpty())
        assertTrue(effective.audioDecoders.all { it.maxChannels <= 2 })
        val request = PlaybackClient.playbackInfoRequest(account, onlyStereo, PlaybackRequest())
        val wire = PlaybackJson.encodeToJsonElement(PlaybackInfoRequest.serializer(), request).jsonObject
        assertEquals(2, wire.getValue("MaxAudioChannels").jsonPrimitive.int)
        assertTrue(request.deviceProfile!!.transcodingProfiles.all { it.maxAudioChannels == "2" })
        assertTrue(
            request.deviceProfile.codecProfiles.filter { it.type == CodecType.VideoAudio }.all { profile ->
                profile.conditions.all { it.property != ProfileConditionValue.AudioChannels || it.value == "2" }
            }
        )
        val noSink = PlaybackCapabilityPolicy.effective(
            CapabilityFixtures.basicStick.copy(settings = PlaybackSettings(passthroughEnabled = true))
        )
        assertTrue(noSink.audioOutput.passthrough.isEmpty())
        val sinkOnly = CapabilityFixtures.basicStick.copy(
            audioOutput = AudioOutput(2, setOf(PassthroughEncoding.TRUEHD)),
            settings = PlaybackSettings(passthroughEnabled = true)
        )
        val denied = sinkOnly.copy(
            settings = sinkOnly.settings.copy(
                tuning = PlaybackTuning(
                    disabledPassthrough = setOf(PassthroughEncoding.TRUEHD)
                )
            )
        )
        fun advertisedTruehd(capabilities: DeviceCapabilities): Boolean {
            val body = PlaybackClient.playbackInfoRequest(account, capabilities, PlaybackRequest())
            return body.deviceProfile!!.directPlayProfiles.any { "truehd" in it.audioCodec.orEmpty().split(',') }
        }
        assertTrue(advertisedTruehd(sinkOnly))
        assertFalse(advertisedTruehd(denied))
        val channelLimit = PlaybackClient.playbackInfoRequest(account, caps(PlaybackTuning(maxAudioChannels = 6)), PlaybackRequest())
        assertEquals(6, channelLimit.maxAudioChannels)
        assertTrue(channelLimit.deviceProfile!!.transcodingProfiles.all { it.maxAudioChannels == "6" })
    }

    @Test
    fun hdrOverridesAffectNegotiationWithoutAlteringDetection() {
        val detected = CapabilityFixtures.dolbyVisionStreamer
        fun profile(tuning: PlaybackTuning) = DeviceProfileBuilder.build(detected.copy(settings = detected.settings.copy(tuning = tuning)))
        fun ranges(profile: DeviceProfile) = profile.codecProfiles.filter { it.codec == "hevc" }
            .flatMap { it.conditions }.first { it.property == ProfileConditionValue.VideoRangeType }.value!!.split('|').toSet()
        val original = ranges(profile(PlaybackTuning()))
        assertTrue("DOVI" in original && "HDR10Plus" in original)
        assertFalse("DOVI" in ranges(profile(PlaybackTuning(dolbyVision = CapabilityOverride.Disable))))
        assertFalse("HDR10" in ranges(profile(PlaybackTuning(hdr = CapabilityOverride.Disable))))
        assertFalse("HDR10Plus" in ranges(profile(PlaybackTuning(preferDolbyVision = true))))
        val projector = CapabilityFixtures.projector
        val force = projector.copy(settings = projector.settings.copy(tuning = PlaybackTuning(dolbyVision = CapabilityOverride.Force)))
        assertTrue("DOVI" in ranges(DeviceProfileBuilder.build(force)))
        assertFalse(HdrFormat.DOLBY_VISION in projector.display.hdrFormats)
        assertTrue(HdrFormat.DOLBY_VISION in detected.display.hdrFormats)
    }

    @Test
    fun resumeClampsAndKeepScenesNeverAllowsEarlyCompletion() {
        assertEquals(0L, PlaybackTuning(rewindSeconds = 30).resumedPositionMs(20_000))
        assertEquals(90_000L, PlaybackTuning(rewindSeconds = 10).resumedPositionMs(100_000))
        assertFalse(PlaybackTuning().allowEarlyNext())
        assertTrue(PlaybackTuning(postCredits = PostCredits.NextEpisode).allowEarlyNext())
        assertTrue(PlaybackTuning().allowEarlyNext(hasPostCreditsScene = false))
    }

    @Test
    fun forcedSubtitleBurnInNoticeGivesAnActionThatCanActuallyAvoidBurnIn() {
        val source =
            MediaSourceInfo(
                mediaStreams = listOf(
                    MediaStream(index = 2, type = MediaStreamType.Subtitle, codec = "srt", deliveryMethod = SubtitleDeliveryMethod.Encode)
                )
            )
        val decision =
            PlayDecision(
                "item",
                source,
                PlayMethod.Transcode,
                "https://example.test/play",
                emptyMap(),
                "session",
                listOf("SubtitleCodecNotSupported")
            )
        assertEquals(
            "Set subtitle burn-in to Automatic in Playback settings, or turn subtitles off.",
            ConversionNotice.of(decision, 2, tuning = PlaybackTuning(subtitleBurnIn = SubtitleBurnIn.Always))?.fix
        )
        assertEquals(ConversionNotice.SUBTITLE_FIX, ConversionNotice.of(decision, 2)?.fix)
        val videoAndRange = PlayDecision(
            "item",
            source,
            PlayMethod.Transcode,
            "https://example.test/play",
            emptyMap(),
            "session",
            listOf("VideoCodecNotSupported", "VideoRangeTypeNotSupported")
        )
        assertEquals(
            "This video format cannot play on this device.",
            ConversionNotice.of(videoAndRange, 2, tuning = PlaybackTuning(hdr = CapabilityOverride.Disable))?.reason
        )
    }
}
