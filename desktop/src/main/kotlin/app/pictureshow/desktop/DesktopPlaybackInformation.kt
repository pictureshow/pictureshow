/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.playback.*
import kotlinx.serialization.json.*

/** Only allowlisted mpv properties enter UI state; never URLs, filenames or arbitrary log lines. */
data class DesktopReceivedFormats(
    val videoCodec: String? = null, val width: Int? = null, val height: Int? = null,
    val transfer: String? = null, val primaries: String? = null, val pixelFormat: String? = null,
    val audioCodec: String? = null, val audioChannels: Int? = null, val audioSampleRate: Int? = null,
    val hwdec: String? = null, val output: HdrOutput = HdrOutput.Unknown,
    val audioDecoder: String? = null
)

private fun mpvInfoToken(value: String?): String? = value?.takeIf { it.matches(Regex("[a-zA-Z0-9_.+-]{1,40}")) }

internal fun receivedFormats(get: (String) -> String?, output: HdrOutput): DesktopReceivedFormats {
    fun token(key: String) = mpvInfoToken(get(key))
    return DesktopReceivedFormats(
        token("current-tracks/video/codec"), get("video-params/w")?.toIntOrNull(), get("video-params/h")?.toIntOrNull(),
        token("video-params/gamma"), token("video-params/primaries"), token("video-params/pixelformat"),
        token("current-tracks/audio/codec"), get("audio-params/channel-count")?.toIntOrNull(), get("audio-params/samplerate")?.toIntOrNull(),
        token("hwdec-current"), output, token("current-tracks/audio/decoder")
    )
}

/** Parse current native receipts. Target options, max luminance and potential EDR alone prove nothing. */
internal fun nativeHdrOutput(platform: String, receipt: String?): HdrOutput = runCatching {
    val d = Json.parseToJsonElement(receipt ?: return HdrOutput.Unknown).jsonObject
    fun n(key: String) = d[key]?.jsonPrimitive?.doubleOrNull
    fun b(key: String) = d[key]?.jsonPrimitive?.booleanOrNull
    when (platform) {
        "mac" -> when {
            b("wantsEDR") != true || n("screenMaxEDR") == null -> HdrOutput.Unknown
            n("screenMaxEDR")!! > 1.0 -> HdrOutput.HdrEdR
            else -> HdrOutput.Sdr
        }
        "windows" -> when {
            n("SetColorSpace1HRESULT") != 0.0 || ((n("CheckColorSpaceSupport")?.toInt() ?: 0) and 1) == 0 -> HdrOutput.Unknown
            n("outputColorSpace") == 12.0 -> HdrOutput.HdrScRgb // DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020
            n("outputColorSpace") == 0.0 -> HdrOutput.Sdr
            else -> HdrOutput.Unknown
        }
        else -> HdrOutput.Unknown
    }
}.getOrDefault(HdrOutput.Unknown)

internal data class PlaybackInfoSection(
    val title: String, val lines: List<String> = emptyList(),
    val fields: List<Pair<String, String>> = emptyList(), val note: String? = null
)

internal fun playbackInfoSections(p: DesktopPlaybackState, qualityMbps: Int?): List<PlaybackInfoSection> {
    val source = p.decision.mediaSource
    val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Video }
    val audio = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio && it.index == p.audioIndex }
    val f = p.received
    val explanation = PlaybackInformation.explanation(p.decision, p.subtitleIndex, "this computer", qualityMbps, PlaybackInformation.codec(video?.codec))
    fun number(n: Int?, suffix: String) = n?.takeIf { it > 0 }?.let { "$it $suffix" } ?: "Not reported"
    return buildList {
        add(PlaybackInfoSection(explanation.method, listOf(explanation.summary) + explanation.remedies))
        if (p.decision.method == PlayMethod.Transcode) add(PlaybackInfoSection(explanation.heading ?: "Why the server converts", PlaybackInformation.reasons(p.decision, qualityMbps)))
        add(PlaybackInfoSection("Display output", listOf(PlaybackFormatInformation.display(f.transfer, f.output)),
            note = "Reported by the operating system and mpv, not measured from the screen."))
        add(PlaybackInfoSection("Source · server metadata", fields = listOf(
            "Container" to PlaybackInformation.codec(source.container),
            "Total bitrate" to PlaybackInformation.bitrate(source.bitrate),
            "Video" to "${PlaybackInformation.codec(video?.codec)} · ${PlaybackInformation.resolution(video?.width, video?.height)}",
            "Source range" to PlaybackFormatInformation.range(video?.videoRangeType, video?.dvProfile),
            "Video bitrate" to PlaybackInformation.bitrate(video?.bitRate),
            "Profile" to PlaybackFormatInformation.profile(video?.profile),
            "Audio" to if (p.audioIndex < 0) "Off" else "${PlaybackInformation.codec(audio?.codec)} · ${number(audio?.channels, "channels")} · ${PlaybackInformation.bitrate(audio?.bitRate)}",
            "Subtitles" to PlaybackInformation.subtitle(source, p.subtitleIndex)
        ), note = if (p.decision.method == PlayMethod.DirectPlay) null else
            "Server metadata may describe the converted stream. Received formats below come from mpv."))
        add(PlaybackInfoSection("Received by this computer", fields = listOf(
            "Video" to "${PlaybackInformation.codec(f.videoCodec)} · ${PlaybackInformation.resolution(f.width, f.height)}",
            "Range" to PlaybackFormatInformation.transfer(f.transfer),
            "Primaries" to PlaybackInformation.codec(f.primaries),
            "Frame format" to (mpvInfoToken(f.pixelFormat) ?: "Not reported"),
            "Audio" to if (p.audioIndex < 0) "Off" else "${PlaybackInformation.codec(f.audioCodec)} · ${number(f.audioChannels, "channels")} · ${number(f.audioSampleRate, "Hz")}",
            "Video decoder" to PlaybackFormatInformation.decoder(f.hwdec),
            "Audio decoder" to if (p.audioIndex < 0) "Off" else mpvInfoToken(f.audioDecoder)?.let { "mpv · $it" } ?: "Not reported"
        )))
    }
}

internal fun waylandHdrOutput(hdr: Boolean, liveReceipt: String): HdrOutput =
    if (hdr && liveReceipt.lineSequence().any { it.startsWith("image_description=ready;") } && !liveReceipt.contains("image_description=FAILED")) HdrOutput.WaylandPq else HdrOutput.Unknown
