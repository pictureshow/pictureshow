/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.control

import app.pictureshow.core.Server
import app.pictureshow.core.playback.CodecProfile
import app.pictureshow.core.playback.CodecType
import app.pictureshow.core.playback.DeviceProfile
import app.pictureshow.core.playback.DirectPlayProfile
import app.pictureshow.core.playback.DlnaProfileType
import app.pictureshow.core.playback.EncodingContext
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.ProfileCondition
import app.pictureshow.core.playback.ProfileConditionType
import app.pictureshow.core.playback.ProfileConditionValue
import app.pictureshow.core.playback.Redaction
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import app.pictureshow.core.playback.SubtitleProfile
import app.pictureshow.core.playback.TranscodingProfile

/**
 * What the receiver is asked to decode. It can't be queried on the Default Media Receiver, so this starts from the
 * subset Google lists for every Cast device (developers.google.com/cast/docs/media). It is the one table to change
 * when measured receiver data widens it. The phone's own decoders never appear here (CAST-02b).
 */
object CastReceiverFormats {
    val directVideoContainers = listOf("mp4", "m4v")
    val directVideoCodecs = listOf("h264")
    val directAudioCodecs = listOf("aac", "mp3")
    val transcodeContainer = "ts"
    val transcodeVideoCodec = "h264"
    val transcodeAudioCodec = "aac"
    const val MAX_AUDIO_CHANNELS = "2"
    const val MAX_WIDTH = "1920"
    const val MAX_HEIGHT = "1080"
    const val MAX_H264_LEVEL = "41"
    const val MAX_BITRATE = 20_000_000
    const val SUBTITLE_FORMAT = "vtt"
}

fun castDeviceProfile(): DeviceProfile = with(CastReceiverFormats) {
    DeviceProfile(
        name = "Pictureshow Cast receiver",
        maxStreamingBitrate = MAX_BITRATE,
        maxStaticBitrate = MAX_BITRATE,
        directPlayProfiles = listOf(
            DirectPlayProfile(
                container = directVideoContainers.joinToString(","),
                audioCodec = directAudioCodecs.joinToString(","),
                videoCodec = directVideoCodecs.joinToString(","),
                type = DlnaProfileType.Video
            )
        ),
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = transcodeContainer,
                type = DlnaProfileType.Video,
                videoCodec = transcodeVideoCodec,
                audioCodec = transcodeAudioCodec,
                protocol = MediaStreamProtocol.Hls,
                context = EncodingContext.Streaming,
                maxAudioChannels = MAX_AUDIO_CHANNELS,
                minSegments = 1,
                breakOnNonKeyFrames = true
            )
        ),
        codecProfiles = listOf(
            CodecProfile(
                type = CodecType.Video,
                codec = "h264",
                conditions = listOf(
                    ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Width, MAX_WIDTH, true),
                    ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.Height, MAX_HEIGHT, true),
                    ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.VideoLevel, MAX_H264_LEVEL, true),
                    ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.VideoBitDepth, "8", true),
                    ProfileCondition(ProfileConditionType.Equals, ProfileConditionValue.VideoRangeType, "SDR", true)
                )
            ),
            CodecProfile(
                type = CodecType.VideoAudio,
                conditions = listOf(
                    ProfileCondition(ProfileConditionType.LessThanEqual, ProfileConditionValue.AudioChannels, MAX_AUDIO_CHANNELS, true)
                )
            )
        ),
        subtitleProfiles = listOf(SubtitleProfile(format = SUBTITLE_FORMAT, method = SubtitleDeliveryMethod.External))
    )
}

/**
 * The address the receiver opens. It can't send our Authorization header, so the token rides in the query: a known
 * limit of this receiver (CTRL-10), to be replaced by a receiver session of its own under CAST-06. Log it only through
 * [Redaction.url].
 */
fun castMediaUrl(decision: PlayDecision, token: String): String = withToken(Redaction.stripApiKey(decision.url), token)

private fun withToken(url: String, token: String): String {
    val fragment = url.indexOf('#').let { if (it < 0) url.length else it }
    val separator = if ('?' in url.substring(0, fragment)) "&" else "?"
    return url.substring(0, fragment) + separator + "ApiKey=" + PlayDecisions.encode(token) + url.substring(fragment)
}

/** The receiver needs the content type up front; it can't take it from a file name on these URLs. */
fun castContentType(decision: PlayDecision): String = when {
    decision.method == PlayMethod.Transcode -> "application/x-mpegURL"

    else -> when (decision.mediaSource.container?.substringBefore(',')?.lowercase()) {
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        else -> "video/mp4"
    }
}

data class CastSubtitle(val trackId: Int, val streamIndex: Int, val label: String, val language: String?, val url: String)

/**
 * Sidecar WebVTT the receiver fetches itself. Only text subtitles convert to it. A picture subtitle needs burn-in, which
 * means a new LOAD, so it isn't offered: a track the receiver can't show mustn't be able to read as selected (CAST-02c).
 */
fun castSubtitles(server: Server, decision: PlayDecision, token: String): List<CastSubtitle> {
    val source = decision.mediaSourceId ?: return emptyList()
    return decision.mediaSource.mediaStreams
        .filter { it.type == MediaStreamType.Subtitle && it.isTextSubtitleStream && !it.isExternalUrl }
        .map { stream ->
            val path = "/Videos/" + PlayDecisions.encode(decision.itemId) + "/" + PlayDecisions.encode(source) +
                "/Subtitles/" + stream.index + "/0/Stream.vtt"
            CastSubtitle(
                trackId = castTrackId(stream.index),
                streamIndex = stream.index,
                label = stream.displayTitle ?: stream.title ?: stream.language ?: "Subtitle ${stream.index}",
                language = stream.language,
                url = withToken(PlayDecisions.resolve(server, path), token)
            )
        }
}

/** The SDK reserves no id, but a positive one keeps "no track" unambiguous. */
fun castTrackId(streamIndex: Int): Int = streamIndex + 1
