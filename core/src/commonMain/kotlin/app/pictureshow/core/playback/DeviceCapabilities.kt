package app.pictureshow.core.playback

/**
 * What a device can decode and show, filled in by each platform from its own APIs (on Android: MediaCodecList,
 * Display.HdrCapabilities and AudioManager/AudioCapabilities) and turned into a server device profile by
 * [DeviceProfileBuilder]. Names follow the server's (ffprobe's) spelling so the builder can pass them through.
 */
data class DeviceCapabilities(
    val name: String,
    val videoDecoders: List<VideoDecoder>,
    val display: DisplayCapabilities,
    val audioDecoders: List<AudioDecoder>,
    val audioOutput: AudioOutput,
    val settings: PlaybackSettings = PlaybackSettings()
)

/** Video codecs by the codec name the server reports for a stream. */
enum class VideoCodec(val serverName: String) {
    H264("h264"),
    HEVC("hevc"),
    VP8("vp8"),
    VP9("vp9"),
    AV1("av1"),
    MPEG2("mpeg2video"),
    MPEG4("mpeg4"),
    VC1("vc1")
}

enum class HdrFormat {
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION
}

/** A codec level as written in its specification, for example H.264 level 5.1 or HEVC level 5.1. */
data class CodecLevel(val major: Int, val minor: Int = 0)

/**
 * One decoder for one codec.
 *
 * @property profiles Profile names as the server reports them for a stream (ffprobe): "High", "Main 10", "Profile 2".
 * @property maxFrameRate Frame rate the decoder sustains at 1920x1080 and below.
 * @property maxFrameRateAtMaxSize Frame rate it sustains at [maxWidth]x[maxHeight] (often lower, e.g. 4K30 H.264).
 * @property hdrFormats HDR formats the decoder outputs. For Dolby Vision, include [HdrFormat.DOLBY_VISION] here on the
 *   decoder of the base-layer codec (HEVC or AV1) when the device has a Dolby Vision decoder, with its profiles in
 *   [dolbyVisionProfiles].
 * @property hardware False for software decoders; they are used for a codec only when no hardware decoder exists.
 */
data class VideoDecoder(
    val codec: VideoCodec,
    val profiles: Set<String>,
    val maxLevel: CodecLevel? = null,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Double,
    val maxFrameRateAtMaxSize: Double = maxFrameRate,
    val maxBitDepth: Int = 8,
    val hdrFormats: Set<HdrFormat> = emptySet(),
    val dolbyVisionProfiles: Set<Int> = emptySet(),
    val hardware: Boolean = true,
    /** A real-time envelope established by a performance point or measured achievable frame rates, not size support. */
    val sustainablePerformance: VideoPerformancePoint? = null,
    /** Exact Android profile/level/depth/HDR tuples; aggregate fields above are only summaries when this is populated. */
    val profileCapabilities: List<VideoProfileCapability> = emptyList()
)

/** One profile on one decoder. Its level, depth and HDR support must not be transferred to another profile. */
data class VideoProfileCapability(
    val profile: String,
    val maxLevel: CodecLevel?,
    val maxBitDepth: Int,
    val hdrFormats: Set<HdrFormat> = emptySet()
)

/** One conservative rectangular real-time envelope. Values outside it require transcoding. */
data class VideoPerformancePoint(val width: Int, val height: Int, val frameRate: Double)

data class DisplayCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxRefreshRate: Double,
    val hdrFormats: Set<HdrFormat> = emptySet()
)

/** Audio codecs by the codec names the server reports for a stream. */
enum class AudioCodec(val serverNames: List<String>) {
    AAC(listOf("aac")),
    AC3(listOf("ac3")),
    EAC3(listOf("eac3")),
    TRUEHD(listOf("truehd")),
    DTS(listOf("dts")),
    FLAC(listOf("flac")),
    ALAC(listOf("alac")),
    OPUS(listOf("opus")),
    VORBIS(listOf("vorbis")),
    MP3(listOf("mp3")),
    MP2(listOf("mp2")),

    /** Uncompressed PCM needs no decoder; the platform lists it with the channel count its sink accepts. */
    PCM(listOf("pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_f32le", "pcm_u8"))
}

/** A decoder for [codec] that accepts up to [maxChannels] input channels (it downmixes to the output itself). */
data class AudioDecoder(val codec: AudioCodec, val maxChannels: Int, val hardware: Boolean = true)

/** Encoded formats the current audio route accepts without decoding (HDMI/S/PDIF passthrough). */
enum class PassthroughEncoding(val codec: AudioCodec, val maxChannels: Int) {
    AC3(AudioCodec.AC3, 6),
    EAC3(AudioCodec.EAC3, 8),
    EAC3_JOC(AudioCodec.EAC3, 8),

    /** DTS core only: a DTS-HD stream still passes its 5.1 core. */
    DTS(AudioCodec.DTS, 6),
    DTS_HD(AudioCodec.DTS, 8),
    TRUEHD(AudioCodec.TRUEHD, 8)
}

data class AudioOutput(val pcmChannels: Int, val passthrough: Set<PassthroughEncoding> = emptySet())

/**
 * User settings that bear on the profile.
 *
 * @property maxStreamingBitrate Positive ceiling in bits per second. Always explicit: omission invokes the server's
 *   8 Mbps default. The 200 Mbps default accommodates LAN UHD remuxes; server remote limits still apply.
 */
data class PlaybackSettings(val maxStreamingBitrate: Int = DEFAULT_MAX_STREAMING_BITRATE, val passthroughEnabled: Boolean = false) {
    init {
        require(maxStreamingBitrate > 0) { "The streaming bitrate ceiling must be positive." }
    }

    companion object {
        const val DEFAULT_MAX_STREAMING_BITRATE: Int = 200_000_000
    }
}
