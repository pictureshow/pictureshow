package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProfileBuilderTest {
    private val projector = DeviceProfileBuilder.build(CapabilityFixtures.projector)
    private val stick = DeviceProfileBuilder.build(CapabilityFixtures.basicStick)
    private val streamer = DeviceProfileBuilder.build(CapabilityFixtures.dolbyVisionStreamer)

    private fun DeviceProfile.video(codec: String): CodecProfile =
        codecProfiles.single { it.type == CodecType.Video && it.codec == codec && it.applyConditions.isEmpty() }

    private fun DeviceProfile.audioLimit(codec: String, container: String? = null): Int? = codecProfiles
        .singleOrNull { it.type == CodecType.VideoAudio && it.codec == codec && it.container == container }
        ?.conditions?.single { it.property == ProfileConditionValue.AudioChannels }?.value?.toInt()

    private fun CodecProfile.value(property: ProfileConditionValue): String? = conditions.singleOrNull { it.property == property }?.value

    private fun CodecProfile.rangeTypes(): List<String> = value(ProfileConditionValue.VideoRangeType)!!.split('|')

    private fun DeviceProfile.directPlay(container: String): DirectPlayProfile? =
        directPlayProfiles.singleOrNull { it.container == container }

    private fun combo(
        container: String = "mkv",
        video: String = "hevc",
        profile: String = "Main",
        bitDepth: Int = 8,
        range: String = "SDR",
        audio: String = "aac",
        channels: Int = 2,
        subtitles: List<String> = emptyList(),
        width: Int? = null,
        height: Int? = null,
        frameRate: Double? = null
    ) = Combo(container, video, profile, bitDepth, range, audio, channels, subtitles, width, height, frameRate)

    // The E-AC-3 / MPEG-TS rule (google/ExoPlayer#9507).

    @Test
    fun eac3AboveSixChannelsIsNeverAllowedInTs() {
        for (profile in listOf(projector, streamer)) {
            assertEquals(6, profile.audioLimit("eac3", "ts,mpegts"))
            assertEquals(8, profile.audioLimit("eac3"))
            val ts71 = combo(container = "ts", video = "h264", profile = "High", audio = "eac3", channels = 8)
            assertTrue("AudioChannelsNotSupported" in ProfileEvaluator.directPlayFailures(profile, ts71))
            assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, ts71.copy(audioChannels = 6)))
            assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, ts71.copy(container = "mkv")))
        }
    }

    @Test
    fun eac3CanOnlyBeCopiedIntoTheFmp4TranscodeAtSevenOne() {
        for (profile in listOf(projector, streamer)) {
            val (mp4, ts) = profile.transcodingProfiles
            assertEquals("mp4", mp4.container)
            assertEquals(MediaStreamProtocol.Hls, mp4.protocol)
            assertTrue("eac3" in mp4.audioCodec.split(','))
            assertEquals("ts", ts.container)
            // The server checks codec profiles against the transcoding container before copying audio, so the TS
            // limit above also stops a 7.1 E-AC-3 copy into TS segments.
            assertTrue("eac3" in ts.audioCodec.split(','))
            assertEquals(6, profile.audioLimit("eac3", "ts,mpegts"))
        }
    }

    @Test
    fun noTsRuleWhenEac3IsNotPlayable() {
        assertNull(stick.audioLimit("eac3"))
        assertNull(stick.audioLimit("eac3", "ts,mpegts"))
        assertFalse(stick.directPlayProfiles.any { "eac3" in it.audioCodec.orEmpty().split(',') })
    }

    // HDR range types per display.

    @Test
    fun projectorAllowsOnlyWhatItsHdr10AndHlgDisplayShows() {
        assertEquals(
            listOf("SDR", "HDR10", "HLG", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus"),
            projector.video("hevc").rangeTypes()
        )
        assertEquals(listOf("SDR", "HDR10", "HLG"), projector.video("vp9").rangeTypes())
        assertEquals(listOf("SDR"), projector.video("h264").rangeTypes())
        val hevc = projector.video("hevc").rangeTypes()
        for (missing in listOf("DOVI", "DOVIWithEL", "DOVIWithELHDR10Plus", "DOVIInvalid", "HDR10Plus")) {
            assertFalse(missing in hevc, missing)
        }
    }

    @Test
    fun dolbyVisionProfile5IsTranscodedOnTheProjectorButProfile8Plays() {
        val p5 = combo(profile = "Main 10", bitDepth = 10, range = "DOVI", audio = "eac3", channels = 6)
        assertEquals(setOf("VideoRangeTypeNotSupported"), ProfileEvaluator.directPlayFailures(projector, p5))
        val p86 = p5.copy(rangeType = "DOVIInvalid")
        assertEquals(setOf("VideoRangeTypeNotSupported"), ProfileEvaluator.directPlayFailures(projector, p86))
        for (range in listOf("DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG", "DOVIWithSDR", "HDR10", "HDR10Plus", "HLG")) {
            assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, p5.copy(rangeType = range)), range)
        }
    }

    @Test
    fun sdrStickAllowsSdrAndTheSdrBaseLayerOnly() {
        assertEquals(listOf("SDR", "DOVIWithSDR"), stick.video("hevc").rangeTypes())
        assertEquals(listOf("SDR"), stick.video("h264").rangeTypes())
    }

    @Test
    fun dolbyVisionStreamerAllowsDolbyVisionWithoutBaseLayerButNotEnhancementLayerOrInvalid() {
        val hevc = streamer.video("hevc").rangeTypes()
        assertEquals(
            listOf("SDR", "HDR10", "HLG", "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus", "HDR10Plus"),
            hevc
        )
        // AV1 decoder without Dolby Vision on a DV display: base layers only.
        assertEquals(
            listOf("SDR", "HDR10", "HLG", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus", "HDR10Plus"),
            streamer.video("av1").rangeTypes()
        )
    }

    @Test
    fun hdrDecoderOnSdrDisplayGetsSdrOnly() {
        val sdrDisplay = CapabilityFixtures.dolbyVisionStreamer.copy(display = DisplayCapabilities(3840, 2160, 60.0))
        assertEquals(listOf("SDR", "DOVIWithSDR"), DeviceProfileBuilder.build(sdrDisplay).video("hevc").rangeTypes())
    }

    // Bit depth and HEVC profiles.

    @Test
    fun tenBitHevcOnlyWithAMain10Decoder() {
        assertEquals("8", stick.video("hevc").value(ProfileConditionValue.VideoBitDepth))
        assertEquals("main", stick.video("hevc").value(ProfileConditionValue.VideoProfile))
        val main10 = combo(profile = "Main 10", bitDepth = 10)
        assertEquals(
            setOf("VideoBitDepthNotSupported", "VideoProfileNotSupported"),
            ProfileEvaluator.directPlayFailures(stick, main10)
        )
        assertEquals("10", projector.video("hevc").value(ProfileConditionValue.VideoBitDepth))
        assertEquals("main|main 10", projector.video("hevc").value(ProfileConditionValue.VideoProfile))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, main10))
    }

    @Test
    fun levelsAreInServerUnits() {
        assertEquals("51", projector.video("h264").value(ProfileConditionValue.VideoLevel))
        assertNull(projector.video("hevc").value(ProfileConditionValue.VideoLevel))
        assertEquals("42", stick.video("h264").value(ProfileConditionValue.VideoLevel))
        assertEquals("123", stick.video("hevc").value(ProfileConditionValue.VideoLevel))
        assertEquals("13", streamer.video("av1").value(ProfileConditionValue.VideoLevel))
        assertNull(projector.video("vp9").value(ProfileConditionValue.VideoLevel))
    }

    @Test
    fun h264AboveFullHdIsLimitedToItsFrameRateAtMaxSize() {
        val above = projector.codecProfiles.single { it.codec == "h264" && it.applyConditions.isNotEmpty() }
        assertEquals("1921", above.applyConditions.single { it.property == ProfileConditionValue.Width }.value)
        assertEquals("30", above.value(ProfileConditionValue.VideoFramerate))
        val uhd60 = combo(video = "h264", profile = "High", width = 3840, height = 2160, frameRate = 60.0)
        assertEquals(setOf("VideoFramerateNotSupported"), ProfileEvaluator.directPlayFailures(projector, uhd60))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, uhd60.copy(frameRate = 24.0)))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, uhd60.copy(width = 1920, height = 1080)))
        assertTrue(projector.codecProfiles.any { it.codec == "hevc" && it.applyConditions.isNotEmpty() })
    }

    @Test
    fun resolutionAboveTheDecoderIsRefused() {
        val uhd = combo(width = 3840, height = 2160)
        assertEquals(setOf("VideoResolutionNotSupported"), ProfileEvaluator.directPlayFailures(stick, uhd))
    }

    // Codecs and containers.

    @Test
    fun containersListOnlyWhatMedia3DemuxesAndTheDeviceDecodes() {
        assertEquals(
            listOf("mkv", "webm", "mp4,m4v,mov", "ts,mpegts", "avi", "mpeg,mpg", "flv"),
            projector.directPlayProfiles.map { it.container }
        )
        assertEquals("h264,hevc,vp8,vp9,mpeg2video,mpeg4", projector.directPlay("mkv")!!.videoCodec)
        assertEquals("h264,hevc,vp9,av1", streamer.directPlay("mp4,m4v,mov")!!.videoCodec)
        assertEquals("h264,hevc", stick.directPlay("mkv")!!.videoCodec)
        assertEquals("aac,ac3,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_u8", stick.directPlay("mkv")!!.audioCodec)
        // No VP8/VP9/AV1 decoder and no Opus/Vorbis decoder on the stick: no WebM at all.
        assertNull(stick.directPlay("webm"))
        assertFalse(projector.directPlayProfiles.any { "m2ts" in it.container })
        assertTrue(projector.directPlayProfiles.all { it.type == DlnaProfileType.Video })
    }

    @Test
    fun av1IsTranscodedWhereThereIsNoAv1Decoder() {
        val av1 = combo(video = "av1", audio = "opus")
        assertEquals(setOf("VideoCodecNotSupported"), ProfileEvaluator.directPlayFailures(projector, av1))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(streamer, av1))
        assertFalse("av1" in projector.transcodingProfiles.first().videoCodec.split(','))
    }

    @Test
    fun softwareDecodersOnlyFillGaps() {
        // The projector's software H.264 decoder (2048x2048, 30 fps) must not shrink the hardware decoder's limits.
        assertEquals("3840", projector.video("h264").value(ProfileConditionValue.Width))
        assertEquals("60", projector.video("h264").value(ProfileConditionValue.VideoFramerate))
        // VP8 has only a software decoder, so it is listed.
        assertNotNull(projector.codecProfiles.singleOrNull { it.codec == "vp8" })
    }

    @Test
    fun softwareAv1NeedsRealTimeEvidenceAndStaysInsideIt() {
        val decoder = VideoDecoder(
            VideoCodec.AV1,
            setOf("Main"),
            CodecLevel(5, 1),
            4096,
            4096,
            120.0,
            maxBitDepth = 8,
            hardware = false
        )
        fun profile(decoder: VideoDecoder) = DeviceProfileBuilder.build(
            CapabilityFixtures.projector.copy(videoDecoders = CapabilityFixtures.projector.videoDecoders + decoder)
        )
        assertFalse(profile(decoder).directPlayProfiles.any { "av1" in it.videoCodec.orEmpty().split(',') })
        val measured = profile(decoder.copy(sustainablePerformance = VideoPerformancePoint(1280, 720, 30.0)))
        val source = combo(video = "av1", profile = "Main", audio = "opus", width = 1280, height = 720, frameRate = 30.0)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(measured, source))
        assertEquals(
            setOf("VideoResolutionNotSupported"),
            ProfileEvaluator.directPlayFailures(measured, source.copy(width = 1920, height = 1080))
        )
        assertEquals(setOf("VideoFramerateNotSupported"), ProfileEvaluator.directPlayFailures(measured, source.copy(frameRate = 60.0)))
        assertEquals(setOf("VideoBitDepthNotSupported"), ProfileEvaluator.directPlayFailures(measured, source.copy(bitDepth = 10)))
        assertTrue("VideoResolutionNotSupported" in ProfileEvaluator.directPlayFailures(measured, source.copy(width = null)))
        assertTrue("VideoFramerateNotSupported" in ProfileEvaluator.directPlayFailures(measured, source.copy(frameRate = null)))
        val scoped = measured.codecProfiles.single { it.codec == "av1" && it.applyConditions.isNotEmpty() }
        assertEquals(
            setOf(ProfileConditionValue.Width, ProfileConditionValue.Height, ProfileConditionValue.VideoFramerate),
            scoped.applyConditions.map { it.property }.toSet()
        )
    }

    @Test
    fun mislabeledHevcLevelOnlyRelaxesInside1080p30OnAProven4k60Engine() {
        val source = combo(profile = "Main 10", bitDepth = 10, width = 1920, height = 1080, frameRate = 24.0).copy(level = 186)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, source))
        for (outside in listOf(
            source.copy(width = 3840, height = 2160),
            source.copy(frameRate = 60.0),
            source.copy(height = 1440),
            source.copy(width = null),
            source.copy(height = null),
            source.copy(frameRate = null)
        )) {
            assertTrue("VideoLevelNotSupported" in ProfileEvaluator.directPlayFailures(projector, outside), outside.toString())
        }
        assertTrue("VideoLevelNotSupported" in ProfileEvaluator.directPlayFailures(stick, source))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(projector, source.copy(width = 3840, height = 2160, level = 153)))
        assertEquals(setOf("VideoLevelNotSupported"), ProfileEvaluator.directPlayFailures(projector, source.copy(level = 189)))
        assertTrue("VideoProfileNotSupported" in ProfileEvaluator.directPlayFailures(projector, source.copy(videoProfile = "Main 12")))
    }

    @Test
    fun distinctProfileLevelDepthAndHdrTuplesStayTogether() {
        val decoder = VideoDecoder(
            VideoCodec.HEVC, setOf("Main", "Main 10"), CodecLevel(5, 1),
            3840, 2160, 60.0, maxFrameRateAtMaxSize = 60.0, maxBitDepth = 10,
            hdrFormats = setOf(HdrFormat.HDR10),
            profileCapabilities = listOf(
                VideoProfileCapability("Main", CodecLevel(5, 1), 8),
                VideoProfileCapability("Main 10", CodecLevel(4, 1), 10, setOf(HdrFormat.HDR10))
            )
        )
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.projector.copy(videoDecoders = listOf(decoder)))
        val main = combo(profile = "Main", width = 1920, height = 1080, frameRate = 24.0).copy(level = 153)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, main))
        assertTrue("VideoLevelNotSupported" in ProfileEvaluator.directPlayFailures(profile, main.copy(videoProfile = "Main 10")))
        assertEquals(
            emptySet(),
            ProfileEvaluator.directPlayFailures(
                profile,
                main.copy(videoProfile = "Main 10", bitDepth = 10, level = 123)
            )
        )
        assertTrue("VideoBitDepthNotSupported" in ProfileEvaluator.directPlayFailures(profile, main.copy(bitDepth = 10)))
        assertTrue("VideoRangeTypeNotSupported" in ProfileEvaluator.directPlayFailures(profile, main.copy(rangeType = "HDR10")))
        assertEquals(
            emptySet(),
            ProfileEvaluator.directPlayFailures(
                profile,
                main.copy(videoProfile = "Main 10", bitDepth = 10, level = 123, rangeType = "HDR10")
            )
        )
    }

    @Test
    fun av1VariantsSharingMainNameCannotCombineTenBitDepthWithTheEightBitLevel() {
        val decoder = VideoDecoder(
            VideoCodec.AV1, setOf("Main"), CodecLevel(5, 1), 3840, 2160, 60.0,
            maxBitDepth = 10, hdrFormats = setOf(HdrFormat.HDR10),
            profileCapabilities = listOf(
                VideoProfileCapability("Main", CodecLevel(5, 1), 8),
                VideoProfileCapability("Main", CodecLevel(4, 1), 10, setOf(HdrFormat.HDR10))
            )
        )
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.projector.copy(videoDecoders = listOf(decoder)))
        val source = combo(video = "av1", profile = "Main", width = 1920, height = 1080, frameRate = 24.0).copy(level = 13)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, source))
        assertTrue("VideoBitDepthNotSupported" in ProfileEvaluator.directPlayFailures(profile, source.copy(bitDepth = 10)))
        // Same server profile: the selected real 8-bit envelope conservatively forgoes the other variant's 10-bit support.
        assertTrue("VideoBitDepthNotSupported" in ProfileEvaluator.directPlayFailures(profile, source.copy(bitDepth = 10, level = 9)))
    }

    @Test
    fun hdrVariantLevelCannotRaiseTheSamePhysicalDecodersSdrCeiling() {
        val sdr = VideoProfileCapability("Main 10", CodecLevel(4, 1), 10, setOf(HdrFormat.HDR10, HdrFormat.HLG))
        val hdrPlus = VideoProfileCapability(
            "Main 10",
            CodecLevel(5, 1),
            10,
            setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS)
        )
        val decoder = VideoDecoder(
            VideoCodec.HEVC, setOf("Main 10"), CodecLevel(5, 1), 3840, 2160, 60.0,
            maxBitDepth = 10, hdrFormats = hdrPlus.hdrFormats, profileCapabilities = listOf(sdr, hdrPlus)
        )
        fun profile(decoders: List<VideoDecoder>) = DeviceProfileBuilder.build(
            CapabilityFixtures.dolbyVisionStreamer.copy(videoDecoders = decoders)
        )
        val onePhysicalDecoder = profile(listOf(decoder))
        val source = combo(profile = "Main 10", bitDepth = 10, width = 1920, height = 1080, frameRate = 24.0).copy(level = 153)
        assertEquals(setOf("VideoLevelNotSupported"), ProfileEvaluator.directPlayFailures(onePhysicalDecoder, source))
        assertEquals(setOf("VideoLevelNotSupported"), ProfileEvaluator.directPlayFailures(onePhysicalDecoder, source.copy(level = 186)))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(onePhysicalDecoder, source.copy(level = 123)))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(onePhysicalDecoder, source.copy(level = 123, rangeType = "HDR10Plus")))
        assertEquals(
            setOf("VideoLevelNotSupported"),
            ProfileEvaluator.directPlayFailures(
                onePhysicalDecoder,
                source.copy(rangeType = "HDR10Plus")
            )
        )
        // The minimum is local to one physical decoder, never taken across separate decoder reports.
        val separateDecoders = profile(
            listOf(
                decoder.copy(profileCapabilities = listOf(sdr)),
                decoder.copy(profileCapabilities = listOf(hdrPlus))
            )
        )
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(separateDecoders, source))
    }

    @Test
    fun complementaryHardwareDecodersDoNotManufactureACombinedEnvelope() {
        val large = VideoDecoder(
            VideoCodec.HEVC,
            setOf("Main"),
            CodecLevel(5, 1),
            3840,
            2160,
            30.0,
            maxBitDepth = 8
        )
        val deep = VideoDecoder(
            VideoCodec.HEVC,
            setOf("Main 10"),
            CodecLevel(4, 1),
            1920,
            1080,
            60.0,
            maxBitDepth = 10,
            hdrFormats = setOf(HdrFormat.HDR10)
        )
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.projector.copy(videoDecoders = listOf(large, deep)))
        val uhd = combo(profile = "Main", width = 3840, height = 2160, frameRate = 30.0).copy(level = 153)
        val fullHd = uhd.copy(videoProfile = "Main 10", bitDepth = 10, width = 1920, height = 1080, frameRate = 60.0, level = 123)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, uhd))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, fullHd))
        assertTrue("VideoResolutionNotSupported" in ProfileEvaluator.directPlayFailures(profile, fullHd.copy(width = 3840, height = 2160)))
        assertTrue("VideoFramerateNotSupported" in ProfileEvaluator.directPlayFailures(profile, uhd.copy(frameRate = 60.0)))
        assertTrue("VideoBitDepthNotSupported" in ProfileEvaluator.directPlayFailures(profile, uhd.copy(bitDepth = 10)))
    }

    @Test
    fun incomparableDecodersForOneProfileSelectARealEnvelope() {
        val large = VideoDecoder(VideoCodec.H264, setOf("High"), CodecLevel(5, 1), 3840, 2160, 30.0)
        val fast = large.copy(maxWidth = 1920, maxHeight = 1080, maxFrameRate = 60.0)
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.projector.copy(videoDecoders = listOf(large, fast)))
        val source = combo(video = "h264", profile = "High", width = 3840, height = 2160, frameRate = 30.0)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, source))
        assertTrue("VideoFramerateNotSupported" in ProfileEvaluator.directPlayFailures(profile, source.copy(frameRate = 60.0)))
        assertTrue(
            "VideoFramerateNotSupported" in ProfileEvaluator.directPlayFailures(
                profile,
                source.copy(width = 1920, height = 1080, frameRate = 60.0)
            )
        )
    }

    @Test
    fun missingDecoderProfilesUseConservativePerCodecDefaults() {
        val expected = mapOf(
            VideoCodec.H264 to "baseline|constrained baseline",
            VideoCodec.HEVC to "main",
            VideoCodec.VP8 to "0",
            VideoCodec.VP9 to "profile 0",
            VideoCodec.AV1 to "main",
            VideoCodec.MPEG2 to "simple",
            VideoCodec.MPEG4 to "simple profile",
            VideoCodec.VC1 to "simple"
        )
        for ((codec, profiles) in expected) {
            val decoder = VideoDecoder(
                codec,
                emptySet(),
                maxWidth = 1920,
                maxHeight = 1080,
                maxFrameRate = 30.0,
                maxBitDepth = 12,
                hdrFormats = HdrFormat.entries.toSet(),
                dolbyVisionProfiles = setOf(5, 7, 8)
            )
            val profile = DeviceProfileBuilder.build(CapabilityFixtures.dolbyVisionStreamer.copy(videoDecoders = listOf(decoder)))
            val constraints = profile.video(codec.serverName)
            assertEquals(profiles, constraints.value(ProfileConditionValue.VideoProfile), codec.name)
            assertEquals("8", constraints.value(ProfileConditionValue.VideoBitDepth), codec.name)
            // Compatible SDR DV base layers are still allowed, but no native DV or HDR claim survives unknown profiles.
            assertFalse("DOVI" in constraints.rangeTypes(), codec.name)
            assertFalse("HDR10" in constraints.rangeTypes(), codec.name)
            val source = combo(video = codec.serverName, profile = "Unsupported profile")
            assertTrue("VideoProfileNotSupported" in ProfileEvaluator.directPlayFailures(profile, source), codec.name)
        }
    }

    @Test
    fun unknownProfileLevelsCannotRaiseTheFallbackProfilesMinimumCeiling() {
        val minimumLevels = mapOf(VideoCodec.H264 to 10, VideoCodec.HEVC to 30, VideoCodec.AV1 to 0)
        for ((codec, expected) in minimumLevels) {
            for (reported in listOf(CodecLevel(6, 2), null)) {
                val decoder = VideoDecoder(codec, emptySet(), reported, 3840, 2160, 60.0)
                val profile = DeviceProfileBuilder.build(CapabilityFixtures.dolbyVisionStreamer.copy(videoDecoders = listOf(decoder)))
                assertEquals(expected.toString(), profile.video(codec.serverName).value(ProfileConditionValue.VideoLevel), codec.name)
                val name = if (codec == VideoCodec.H264) "Baseline" else "Main"
                val source = combo(video = codec.serverName, profile = name, width = 176, height = 144, frameRate = 15.0)
                assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, source.copy(level = expected)), codec.name)
                assertEquals(
                    setOf("VideoLevelNotSupported"),
                    ProfileEvaluator.directPlayFailures(profile, source.copy(level = expected + 1)),
                    codec.name
                )
                if (codec == VideoCodec.HEVC) {
                    assertEquals(setOf("VideoLevelNotSupported"), ProfileEvaluator.directPlayFailures(profile, source.copy(level = 186)))
                }
            }
        }
    }

    @Test
    fun missingProfilesCannotLoosenAnExplicitAlternativeOrSuppressItsRealClaims() {
        val unknown = VideoDecoder(
            VideoCodec.HEVC,
            emptySet(),
            maxWidth = 3840,
            maxHeight = 2160,
            maxFrameRate = 60.0,
            maxBitDepth = 12,
            hdrFormats = HdrFormat.entries.toSet()
        )
        val explicit = VideoDecoder(
            VideoCodec.HEVC,
            setOf("Main 10"),
            maxWidth = 1920,
            maxHeight = 1080,
            maxFrameRate = 30.0,
            maxBitDepth = 10,
            hdrFormats = setOf(HdrFormat.HDR10)
        )
        val profile = DeviceProfileBuilder.build(
            CapabilityFixtures.dolbyVisionStreamer.copy(videoDecoders = listOf(unknown, explicit))
        )
        val main10 = combo(profile = "Main 10", bitDepth = 10, range = "HDR10", width = 1920, height = 1080, frameRate = 24.0)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, main10))
        assertTrue("VideoResolutionNotSupported" in ProfileEvaluator.directPlayFailures(profile, main10.copy(width = 3840, height = 2160)))
        assertTrue("VideoProfileNotSupported" in ProfileEvaluator.directPlayFailures(profile, main10.copy(videoProfile = "Rext")))
        val main = main10.copy(videoProfile = "Main", bitDepth = 8, rangeType = "SDR", width = 3840, height = 2160)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, main))
        assertTrue("VideoBitDepthNotSupported" in ProfileEvaluator.directPlayFailures(profile, main.copy(bitDepth = 10)))
    }

    // Audio channels with and without passthrough.

    @Test
    fun channelLimitsFollowDecodersWithoutPassthrough() {
        assertEquals(8, stick.audioLimit("aac"))
        assertEquals(6, stick.audioLimit("ac3"))
        assertNull(stick.audioLimit("dts"))
        assertNull(stick.audioLimit("truehd"))
        assertEquals("2", stick.transcodingProfiles.first().maxAudioChannels)
        val ac3Surround = combo(video = "h264", profile = "High", audio = "ac3", channels = 8)
        assertEquals(setOf("AudioChannelsNotSupported"), ProfileEvaluator.directPlayFailures(stick, ac3Surround))
    }

    @Test
    fun passthroughAddsCodecsAndChannelsOnlyWhenEnabled() {
        val receiver = CapabilityFixtures.basicStick.copy(
            audioOutput = AudioOutput(pcmChannels = 2, passthrough = setOf(PassthroughEncoding.DTS, PassthroughEncoding.TRUEHD)),
            settings = PlaybackSettings(passthroughEnabled = true)
        )
        val on = DeviceProfileBuilder.build(receiver)
        assertEquals(6, on.audioLimit("dts"))
        assertEquals(8, on.audioLimit("truehd"))
        assertTrue("dts" in on.directPlay("mkv")!!.audioCodec!!.split(','))
        assertEquals("8", on.transcodingProfiles.first().maxAudioChannels)

        val off = DeviceProfileBuilder.build(receiver.copy(settings = PlaybackSettings(passthroughEnabled = false)))
        assertNull(off.audioLimit("dts"))
        assertNull(off.audioLimit("truehd"))
        assertFalse("dts" in off.directPlay("mkv")!!.audioCodec!!.split(','))
        assertEquals("2", off.transcodingProfiles.first().maxAudioChannels)
    }

    @Test
    fun passthroughTranscodesToEac3First() {
        assertTrue(streamer.transcodingProfiles.first().audioCodec.startsWith("eac3,ac3,aac"))
        assertTrue(projector.transcodingProfiles.first().audioCodec.startsWith("aac,ac3,eac3"))
        assertEquals("8", streamer.transcodingProfiles.first().maxAudioChannels)
    }

    @Test
    fun tsTranscodeKeepsOnlyCodecsTheServerPutsInTs() {
        val ts = streamer.transcodingProfiles.single { it.container == "ts" }
        assertEquals("h264,hevc", ts.videoCodec)
        assertEquals("eac3,ac3,aac,mp3", ts.audioCodec)
        val mp4 = streamer.transcodingProfiles.single { it.container == "mp4" }
        assertEquals("h264,hevc,av1,vp9", mp4.videoCodec)
        assertEquals("eac3,ac3,aac,mp3,opus,flac,dts,truehd", mp4.audioCodec)
    }

    @Test
    fun transcodeIsCappedAtTheDisplaySize() {
        val conditions = projector.transcodingProfiles.first().conditions.associate { it.property to it.value }
        assertEquals("1920", conditions[ProfileConditionValue.Width])
        assertEquals("1080", conditions[ProfileConditionValue.Height])
    }

    // Bitrate.

    @Test
    fun bitrateCapComesFromSettings() {
        assertEquals(120_000_000, streamer.maxStreamingBitrate)
        assertEquals(120_000_000, streamer.maxStaticBitrate)
        assertEquals(200_000_000, projector.maxStreamingBitrate)
        assertEquals(200_000_000, projector.maxStaticBitrate)
    }

    // Subtitles.

    @Test
    fun subtitlesEmbedWhereMedia3ReadsThemAndTextGoesExternal() {
        fun embeds(profile: DeviceProfile, format: String, container: String) = profile.subtitleProfiles.any {
            it.method == SubtitleDeliveryMethod.Embed && it.format == format && it.container == container
        }
        assertTrue(embeds(projector, "pgssub", "mkv"))
        assertTrue(embeds(projector, "ass", "mkv"))
        assertTrue(embeds(projector, "mov_text", "mp4,m4v,mov"))
        assertFalse(embeds(projector, "pgssub", "mp4,m4v,mov"))
        val external = projector.subtitleProfiles.filter { it.method == SubtitleDeliveryMethod.External }.map { it.format }
        assertTrue(external.containsAll(listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml")))
        assertFalse("pgssub" in external)
    }

    // The real library.

    @Test
    fun everyComboTheProjectorDirectPlayedStillDirectPlays() {
        assertEquals(147, LibraryCombos.directPlayedOnProjector.size)
        val failures = LibraryCombos.directPlayedOnProjector.associateWith { ProfileEvaluator.directPlayFailures(projector, it) }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), failures)
    }

    @Test
    fun everyProjectorTranscodeHasAReason() {
        val expected = mapOf(
            combo(video = "av1", audio = "opus") to setOf("VideoCodecNotSupported"),
            combo(container = "mp4", video = "av1", audio = "aac") to setOf("VideoCodecNotSupported"),
            combo(video = "av1", audio = "aac", channels = 6) to setOf("VideoCodecNotSupported"),
            combo(profile = "Main 10", bitDepth = 10, range = "DOVI", audio = "eac3", channels = 6, subtitles = listOf("subrip")) to
                setOf("VideoRangeTypeNotSupported"),
            combo(profile = "Main 10", bitDepth = 10, range = "DOVIInvalid", audio = "eac3", channels = 8, subtitles = listOf("pgssub")) to
                setOf("VideoRangeTypeNotSupported")
        )
        for ((source, reasons) in expected) {
            assertEquals(reasons, ProfileEvaluator.directPlayFailures(projector, source), source.toString())
        }
    }
}
