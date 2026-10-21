package app.pictureshow.core.playback

/**
 * Three devices the profile builder must serve. The projector comes from a capability snapshot of a real device
 * (Amlogic T972, Android 11); the other two from public specifications of their device classes.
 */
object CapabilityFixtures {
    /**
     * A 1080p60 projector: 4K-capable hardware decoders for H.264, HEVC and VP9, a Dolby Vision decoder but a display
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
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.DOLBY_VISION)
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

    private fun softwareDecoder(codec: VideoCodec, size: Int) =
        VideoDecoder(codec, profiles = setOf("Main"), maxWidth = size, maxHeight = size, maxFrameRate = 30.0, hardware = false)

    val all: Map<String, DeviceCapabilities> = linkedMapOf(
        "projector" to projector,
        "basic-stick" to basicStick,
        "dolby-vision-streamer" to dolbyVisionStreamer
    )
}
