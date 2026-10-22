package app.pictureshow.core.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /Items/{itemId}/PlaybackInfo` (PlaybackInfoDto). */
@Serializable
data class PlaybackInfoRequest(
    @SerialName("UserId") val userId: String? = null,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int? = null,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("MaxAudioChannels") val maxAudioChannels: Int? = null,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile? = null,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = false,
    @SerialName("AlwaysBurnInSubtitleWhenTranscoding") val alwaysBurnInSubtitleWhenTranscoding: Boolean = false
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: PlaybackErrorCode? = null
)

@Serializable
enum class PlaybackErrorCode { NotAllowed, NoCompatibleStream, RateLimitExceeded }

/** The fields of MediaSourceInfo the client uses; the rest are ignored on decode. */
@Serializable
data class MediaSourceInfo(
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("ETag") val eTag: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("IsInfiniteStream") val isInfiniteStream: Boolean = false,
    @SerialName("RequiresOpening") val requiresOpening: Boolean = false,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String?> = emptyMap(),
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("TranscodingSubProtocol") val transcodingSubProtocol: MediaStreamProtocol? = null,
    @SerialName("TranscodingContainer") val transcodingContainer: String? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null
) {
    /** TranscodingUrl carries the token as `ApiKey`; it is redacted here so a logged source cannot leak it. */
    override fun toString(): String = "MediaSourceInfo(id=$id, container=$container, protocol=$protocol, " +
        "supportsDirectPlay=$supportsDirectPlay, supportsDirectStream=$supportsDirectStream, " +
        "supportsTranscoding=$supportsTranscoding, transcodingUrl=${transcodingUrl?.let(Redaction::url)}, " +
        "transcodingSubProtocol=$transcodingSubProtocol, mediaStreams=$mediaStreams)"
}

@Serializable
enum class MediaStreamType { Audio, Video, Subtitle, EmbeddedImage, Data, Lyric }

/** The fields of MediaStream the client uses. */
@Serializable
data class MediaStream(
    @SerialName("Type") val type: MediaStreamType? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("CodecTag") val codecTag: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Level") val level: Double? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("VideoRangeType") val videoRangeType: VideoRangeType? = null,
    @SerialName("DvProfile") val dvProfile: Int? = null,
    @SerialName("DvBlSignalCompatibilityId") val dvBlSignalCompatibilityId: Int? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsExternalUrl") val isExternalUrl: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: SubtitleDeliveryMethod? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null
) {
    /** A subtitle DeliveryUrl carries the token as `ApiKey`; it is redacted here. */
    override fun toString(): String = "MediaStream(type=$type, index=$index, codec=$codec, profile=$profile, level=$level, " +
        "bitDepth=$bitDepth, channels=$channels, width=$width, height=$height, videoRangeType=$videoRangeType, " +
        "deliveryMethod=$deliveryMethod, deliveryUrl=${deliveryUrl?.let(Redaction::url)})"
}

@Serializable
enum class PlayMethod { Transcode, DirectStream, DirectPlay }

@Serializable
enum class RepeatMode { RepeatNone, RepeatAll, RepeatOne }

@Serializable
enum class PlaybackOrder { Default, Shuffle }

/** Body of `POST /Sessions/Playing` (PlaybackStartInfo) and `/Sessions/Playing/Progress` (PlaybackProgressInfo). */
@Serializable
data class PlaybackProgressInfo(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("PlayMethod") val playMethod: PlayMethod,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("PlaybackStartTimeTicks") val playbackStartTimeTicks: Long? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("RepeatMode") val repeatMode: RepeatMode = RepeatMode.RepeatNone,
    @SerialName("PlaybackOrder") val playbackOrder: PlaybackOrder = PlaybackOrder.Default
)

/** Body of `POST /Sessions/Playing/Stopped` (PlaybackStopInfo). */
@Serializable
data class PlaybackStopInfo(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("Failed") val failed: Boolean = false
)
