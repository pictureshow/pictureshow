package app.pictureshow.core.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The server's DeviceProfile (Jellyfin 12.0 schema); field and enum spellings match the server exactly. */
@Serializable
data class DeviceProfile(
    @SerialName("Name") val name: String? = null,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int? = null,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Int? = null,
    @SerialName("MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Int? = null,
    @SerialName("MaxStaticMusicBitrate") val maxStaticMusicBitrate: Int? = null,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile> = emptyList(),
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile> = emptyList(),
    @SerialName("ContainerProfiles") val containerProfiles: List<ContainerProfile> = emptyList(),
    @SerialName("CodecProfiles") val codecProfiles: List<CodecProfile> = emptyList(),
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile> = emptyList()
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container") val container: String,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("Type") val type: DlnaProfileType
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: DlnaProfileType,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: MediaStreamProtocol,
    @SerialName("EstimateContentLength") val estimateContentLength: Boolean = false,
    @SerialName("EnableMpegtsM2TsMode") val enableMpegtsM2TsMode: Boolean = false,
    @SerialName("TranscodeSeekInfo") val transcodeSeekInfo: TranscodeSeekInfo = TranscodeSeekInfo.Auto,
    @SerialName("CopyTimestamps") val copyTimestamps: Boolean = false,
    @SerialName("Context") val context: EncodingContext = EncodingContext.Streaming,
    @SerialName("EnableSubtitlesInManifest") val enableSubtitlesInManifest: Boolean = false,
    @SerialName("MaxAudioChannels") val maxAudioChannels: String? = null,
    @SerialName("MinSegments") val minSegments: Int = 0,
    @SerialName("SegmentLength") val segmentLength: Int = 0,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean? = false,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
    @SerialName("EnableAudioVbrEncoding") val enableAudioVbrEncoding: Boolean = true
)

@Serializable
data class ContainerProfile(
    @SerialName("Type") val type: DlnaProfileType,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
    @SerialName("Container") val container: String? = null,
    @SerialName("SubContainer") val subContainer: String? = null
)

@Serializable
data class CodecProfile(
    @SerialName("Type") val type: CodecType,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
    @SerialName("ApplyConditions") val applyConditions: List<ProfileCondition> = emptyList(),
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("SubContainer") val subContainer: String? = null
)

@Serializable
data class ProfileCondition(
    @SerialName("Condition") val condition: ProfileConditionType,
    @SerialName("Property") val property: ProfileConditionValue,
    @SerialName("Value") val value: String? = null,
    @SerialName("IsRequired") val isRequired: Boolean = false
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String? = null,
    @SerialName("Method") val method: SubtitleDeliveryMethod,
    @SerialName("DidlMode") val didlMode: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Container") val container: String? = null
)

@Serializable
enum class DlnaProfileType { Audio, Video, Photo, Subtitle, Lyric }

@Serializable
enum class CodecType { Video, VideoAudio, Audio }

@Serializable
enum class EncodingContext { Streaming, Static }

@Serializable
enum class TranscodeSeekInfo { Auto, Bytes }

@Serializable
enum class MediaStreamProtocol {
    @SerialName("http")
    Http,

    @SerialName("hls")
    Hls
}

@Serializable
enum class SubtitleDeliveryMethod { Encode, Embed, External, Hls, Drop }

@Serializable
enum class ProfileConditionType { Equals, NotEquals, LessThanEqual, GreaterThanEqual, EqualsAny }

@Serializable
enum class ProfileConditionValue {
    AudioChannels,
    AudioBitrate,
    AudioProfile,
    Width,
    Height,
    Has64BitOffsets,
    PacketLength,
    VideoBitDepth,
    VideoBitrate,
    VideoFramerate,
    VideoLevel,
    VideoProfile,
    VideoTimestamp,
    IsAnamorphic,
    RefFrames,
    NumAudioStreams,
    NumVideoStreams,
    IsSecondaryAudio,
    VideoCodecTag,
    IsAvc,
    IsInterlaced,
    AudioSampleRate,
    AudioBitDepth,
    VideoRangeType,
    NumStreams,
    VideoRotation
}

/** The server's VideoRangeType, derived per stream in MediaStream.GetVideoColorRange. */
@Serializable
enum class VideoRangeType {
    Unknown,
    SDR,
    HDR10,
    HLG,
    DOVI,
    DOVIWithHDR10,
    DOVIWithHLG,
    DOVIWithSDR,
    DOVIWithEL,
    DOVIWithHDR10Plus,
    DOVIWithELHDR10Plus,
    DOVIInvalid,
    HDR10Plus
}

/** JSON settings for the playback protocol: unknown fields and future enum values never fail a response. */
val PlaybackJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
