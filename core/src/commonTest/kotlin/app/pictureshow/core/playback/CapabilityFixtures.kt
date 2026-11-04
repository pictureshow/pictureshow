package app.pictureshow.core.playback

/**
 * Three devices the profile builder must serve. The projector comes from a capability snapshot of a real device
 * (Amlogic T972, Android 11); the other two from public specifications of their device classes.
 */
object CapabilityFixtures {
    /**
     * A 1080p60 projector: 4K-capable hardware decoders for H.264, HEVC and VP9, no Dolby Vision decoder and a display
     * that reports HDR10 and HLG only, no AV1, built-in speaker (stereo PCM, no passthrough).
     * Levels follow from the decoders' blocks-per-second limits (H.264 972000 = level 5.1, HEVC 1944000 = 4K60 level 5.1).
     */
    val projector = DeviceCapabilities(
        name = "Projector 1080p HDR10",
        videoDecoders = listOf(
            VideoDecoder(
                codec = VideoCodec.H264,
                profiles = setOf("Constrained Baseline", "Baseline", "Main", "High"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxFrameRateAtMaxSize = 30.0
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main", "Main 10"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG)
            ),
            VideoDecoder(
                codec = VideoCodec.VP9,
                profiles = setOf("Profile 0", "Profile 2"),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG)
            ),
            VideoDecoder(VideoCodec.MPEG2, profiles = emptySet(), maxWidth = 1920, maxHeight = 1088, maxFrameRate = 60.0),
            VideoDecoder(VideoCodec.MPEG4, profiles = emptySet(), maxWidth = 1920, maxHeight = 1088, maxFrameRate = 60.0),
            VideoDecoder(VideoCodec.VC1, profiles = emptySet(), maxWidth = 1920, maxHeight = 1088, maxFrameRate = 60.0),
            VideoDecoder(VideoCodec.VP8, profiles = emptySet(), maxWidth = 1920, maxHeight = 1080, maxFrameRate = 60.0, hardware = false),
            // Software decoders that lose to the hardware ones above.
            softwareDecoder(VideoCodec.H264, 2048),
            softwareDecoder(VideoCodec.HEVC, 4096)
        ),
        display = DisplayCapabilities(1920, 1080, 60.0, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
        audioDecoders = listOf(
            AudioDecoder(AudioCodec.AAC, 8),
            AudioDecoder(AudioCodec.AC3, 6),
            AudioDecoder(AudioCodec.EAC3, 8),
            AudioDecoder(AudioCodec.DTS, 8),
            AudioDecoder(AudioCodec.OPUS, 8),
            AudioDecoder(AudioCodec.FLAC, 8),
            AudioDecoder(AudioCodec.MP3, 2),
            AudioDecoder(AudioCodec.VORBIS, 8),
            AudioDecoder(AudioCodec.ALAC, 8),
            AudioDecoder(AudioCodec.MP2, 2, hardware = false),
            AudioDecoder(AudioCodec.TRUEHD, 8, hardware = false)
        ),
        audioOutput = AudioOutput(pcmChannels = 2)
    )

    /** A basic 1080p stick: H.264 and 8-bit HEVC, SDR only, AAC and AC-3, stereo, no passthrough. */
    val basicStick = DeviceCapabilities(
        name = "Basic 1080p stick",
        videoDecoders = listOf(
            VideoDecoder(
                codec = VideoCodec.H264,
                profiles = setOf("Constrained Baseline", "Baseline", "Main", "High"),
                maxLevel = CodecLevel(4, 2),
                maxWidth = 1920,
                maxHeight = 1080,
                maxFrameRate = 60.0
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main"),
                maxLevel = CodecLevel(4, 1),
                maxWidth = 1920,
                maxHeight = 1080,
                maxFrameRate = 60.0
            )
        ),
        display = DisplayCapabilities(1920, 1080, 60.0),
        audioDecoders = listOf(AudioDecoder(AudioCodec.AAC, 8), AudioDecoder(AudioCodec.AC3, 6)),
        audioOutput = AudioOutput(pcmChannels = 2)
    )

    /**
     * A 4K Dolby Vision and Atmos streamer on an HDMI receiver (Shield / Google TV Streamer class): H.264, HEVC Main 10,
     * VP9 Profile 2 and AV1, Dolby Vision profiles 5 and 8, a 4K60 display with every HDR format, all six passthrough
     * encodings, 7.1 PCM, passthrough on, and a 120 Mbps streaming cap.
     */
    val dolbyVisionStreamer = DeviceCapabilities(
        name = "4K Dolby Vision streamer",
        videoDecoders = listOf(
            VideoDecoder(
                codec = VideoCodec.H264,
                profiles = setOf("Constrained Baseline", "Baseline", "Main", "High"),
                maxLevel = CodecLevel(5, 2),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main", "Main 10"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.HLG, HdrFormat.DOLBY_VISION),
                dolbyVisionProfiles = setOf(5, 8)
            ),
            VideoDecoder(
                codec = VideoCodec.VP9,
                profiles = setOf("Profile 0", "Profile 2"),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG)
            ),
            VideoDecoder(
                codec = VideoCodec.AV1,
                profiles = setOf("Main"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.HLG)
            )
        ),
        display = DisplayCapabilities(
            3840,
            2160,
            60.0,
            setOf(HdrFormat.HDR10, HdrFormat.HDR10_PLUS, HdrFormat.HLG, HdrFormat.DOLBY_VISION)
        ),
        audioDecoders = listOf(
            AudioDecoder(AudioCodec.AAC, 8),
            AudioDecoder(AudioCodec.AC3, 6),
            AudioDecoder(AudioCodec.EAC3, 8),
            AudioDecoder(AudioCodec.OPUS, 8),
            AudioDecoder(AudioCodec.FLAC, 8),
            AudioDecoder(AudioCodec.MP3, 2),
            AudioDecoder(AudioCodec.VORBIS, 8),
            AudioDecoder(AudioCodec.DTS, 8, hardware = false),
            AudioDecoder(AudioCodec.TRUEHD, 8, hardware = false)
        ),
        audioOutput = AudioOutput(pcmChannels = 8, passthrough = PassthroughEncoding.entries.toSet()),
        settings = PlaybackSettings(maxStreamingBitrate = 120_000_000, passthroughEnabled = true)
    )

    /** Reported Android 11 decoder/display limits, captured 2026-10-23; no playback benchmark implied.
     * The simpler projector fixture above is retained for focused tests. This snapshot includes profile tuples,
     * duplicate software decoders and the connected route, so PlaybackInfo probes exercise the real builder path.
     */
    val reportedProjector = projector.copy(
        name = "Reported 1080p HDR projector",
        videoDecoders = listOf(
            VideoDecoder(
                codec = VideoCodec.H264,
                profiles = setOf("Baseline", "Constrained Baseline", "Main", "High"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 119.11764705882354,
                maxFrameRateAtMaxSize = 30.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main", "Main 10"),
                maxLevel = CodecLevel(5, 1),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 238.23529411764707,
                maxFrameRateAtMaxSize = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS),
                hardware = true,
                profileCapabilities = listOf(
                    VideoProfileCapability("Main", CodecLevel(5, 1), 8),
                    VideoProfileCapability("Main 10", CodecLevel(5, 1), 10, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
                    VideoProfileCapability("Main 10", CodecLevel(5, 1), 10, setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS))
                )
            ),
            VideoDecoder(
                codec = VideoCodec.MPEG2,
                profiles = setOf(),
                maxWidth = 1920,
                maxHeight = 1088,
                maxFrameRate = 60.0,
                maxFrameRateAtMaxSize = 60.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.MPEG4,
                profiles = setOf(),
                maxWidth = 896,
                maxHeight = 448,
                maxFrameRate = 60.0,
                maxFrameRateAtMaxSize = 60.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.VP8,
                profiles = setOf(),
                maxWidth = 2048,
                maxHeight = 1008,
                maxFrameRate = 66.13756613756614,
                maxFrameRateAtMaxSize = 62.00396825396825,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.VP9,
                profiles = setOf("Profile 0", "Profile 2"),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 238.23529411764707,
                maxFrameRateAtMaxSize = 60.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS),
                profileCapabilities = listOf(
                    VideoProfileCapability("Profile 0", null, 8),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS))
                ),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.VC1,
                profiles = setOf(),
                maxWidth = 1920,
                maxHeight = 1088,
                maxFrameRate = 960.0,
                maxFrameRateAtMaxSize = 960.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = true
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main"),
                maxLevel = CodecLevel(5, 2),
                maxWidth = 2048,
                maxHeight = 2048,
                maxFrameRate = 15.170370370370371,
                maxFrameRateAtMaxSize = 7.5,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.HEVC,
                profiles = setOf("Main"),
                maxLevel = CodecLevel(5, 2),
                maxWidth = 2048,
                maxHeight = 2048,
                maxFrameRate = 15.170370370370371,
                maxFrameRateAtMaxSize = 7.5,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.MPEG4,
                profiles = setOf(),
                maxWidth = 352,
                maxHeight = 288,
                maxFrameRate = 30.0,
                maxFrameRateAtMaxSize = 30.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.MPEG4,
                profiles = setOf(),
                maxWidth = 352,
                maxHeight = 288,
                maxFrameRate = 30.0,
                maxFrameRateAtMaxSize = 30.0,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.VP8,
                profiles = setOf(),
                maxWidth = 2048,
                maxHeight = 1024,
                maxFrameRate = 130.20833333333334,
                maxFrameRateAtMaxSize = 122.0703125,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.VP8,
                profiles = setOf(),
                maxWidth = 2048,
                maxHeight = 1024,
                maxFrameRate = 130.20833333333334,
                maxFrameRateAtMaxSize = 122.0703125,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.VP9,
                profiles = setOf("Profile 0", "Profile 2"),
                maxWidth = 1280,
                maxHeight = 720,
                maxFrameRate = 30.0,
                maxFrameRateAtMaxSize = 30.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS),
                profileCapabilities = listOf(
                    VideoProfileCapability("Profile 0", null, 8),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS))
                ),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.VP9,
                profiles = setOf("Profile 0", "Profile 2"),
                maxWidth = 1280,
                maxHeight = 720,
                maxFrameRate = 30.0,
                maxFrameRateAtMaxSize = 30.0,
                maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS),
                profileCapabilities = listOf(
                    VideoProfileCapability("Profile 0", null, 8),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
                    VideoProfileCapability("Profile 2", null, 10, setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.HDR10_PLUS))
                ),
                hardware = false
            ),
            VideoDecoder(
                codec = VideoCodec.H264,
                profiles = setOf("Constrained Baseline", "Baseline", "Main", "High"),
                maxLevel = CodecLevel(5, 2),
                maxWidth = 2048,
                maxHeight = 1008,
                maxFrameRate = 64.76190476190476,
                maxFrameRateAtMaxSize = 60.714285714285715,
                maxBitDepth = 8,
                hdrFormats = emptySet(),
                hardware = false
            )
        ),
        display = DisplayCapabilities(1920, 1080, 60.000003814697266, setOf(HdrFormat.HDR10, HdrFormat.HLG)),
        audioOutput = AudioOutput(
            2,
            setOf(
                PassthroughEncoding.AC3,
                PassthroughEncoding.EAC3,
                PassthroughEncoding.EAC3_JOC,
                PassthroughEncoding.DTS,
                PassthroughEncoding.DTS_HD
            )
        ),
        settings = PlaybackSettings(passthroughEnabled = true)
    )

    private fun softwareDecoder(codec: VideoCodec, size: Int) =
        VideoDecoder(codec, profiles = setOf("Main"), maxWidth = size, maxHeight = size, maxFrameRate = 30.0, hardware = false)

    val all: Map<String, DeviceCapabilities> = linkedMapOf(
        "projector" to projector,
        "reported-projector" to reportedProjector,
        "basic-stick" to basicStick,
        "dolby-vision-streamer" to dolbyVisionStreamer
    )
}
