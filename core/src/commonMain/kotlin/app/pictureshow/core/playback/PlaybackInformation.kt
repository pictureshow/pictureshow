/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import io.ktor.http.Url

/** Portable wording: no server URLs, raw reason strings, or guessed output codecs reach the panel. */
object PlaybackInformation {
    fun method(decision: PlayDecision): String = when (decision.method) {
        PlayMethod.DirectPlay -> "Direct play"
        PlayMethod.DirectStream -> "Direct stream (remux)"
        PlayMethod.Transcode -> if (parameter(decision, "VideoCodec") == "copy" && parameter(decision, "AudioCodec") == "copy") {
            "Direct stream (remux)"
        } else {
            "Transcode"
        }
    }

    fun subtitle(source: MediaSourceInfo, index: Int): String {
        if (index < 0) return "Off"
        val stream = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Subtitle && it.index == index }
            ?: return "Not reported"
        return when {
            stream.deliveryMethod == SubtitleDeliveryMethod.Encode -> "Burned in"
            stream.isExternal || stream.deliveryMethod == SubtitleDeliveryMethod.External -> "External"
            else -> "Embedded"
        }
    }

    fun bitrate(bits: Int?): String {
        if (bits == null || bits <= 0) return "Not reported"
        if (bits < 1_000) return "<1 kbps"
        if (bits < 1_000_000) return "${bits / 1_000} kbps"
        val hundredths = (bits.toLong() + 5_000) / 10_000
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} Mbps"
    }

    fun resolution(width: Int?, height: Int?): String =
        if (width != null && height != null && width > 0 && height > 0) "$width × $height" else "Not reported"

    fun codec(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,32}")) }?.uppercase() ?: "Not reported"

    fun reasons(
        decision: PlayDecision,
        qualityMbps: Int? = null,
        tuning: PlaybackTuning = PlaybackTuning(),
        activeBitrate: Int? = null
    ): List<String> {
        if (decision.method != PlayMethod.Transcode) return emptyList()
        return decision.transcodeReasons.map { reason(it, qualityMbps, tuning, activeBitrate) }.distinct().ifEmpty {
            listOf("The server did not report why it is converting this stream.")
        }
    }

    /** Keep unknown future codes honest, and never echo a raw server-supplied value. */
    fun reason(code: String, qualityMbps: Int? = null, tuning: PlaybackTuning = PlaybackTuning(), activeBitrate: Int? = null): String {
        val normalized = code.lowercase()
        if (normalized == "subtitlecodecnotsupported" && tuning.subtitleBurnIn == SubtitleBurnIn.Always) {
            return "Your subtitle burn-in setting requires conversion of the video. " +
                "Set subtitle burn-in to Automatic in Playback settings, or turn subtitles off."
        }
        if (normalized == "videorangetypenotsupported" &&
            (tuning.hdr == CapabilityOverride.Disable || tuning.dolbyVision == CapabilityOverride.Disable)
        ) {
            return "HDR or Dolby Vision is disabled in Playback settings. Restore detected support to try original video; " +
                "device or server limits may still require conversion."
        }
        if (normalized in setOf("containerbitrateexceedslimit", "videobitratenotsupported") && activeBitrate != null && activeBitrate > 0) {
            return "Quality is capped at ${bitrate(activeBitrate)} for this viewing. " +
                "Change player quality or network quality in Playback settings to try original video; " +
                "device or server limits may still require conversion."
        }
        return when (normalized) {
            "containernotsupported" ->
                "This device cannot open the file's container. The server repackages the stream into a " +
                    "compatible container."
            "videocodecnotsupported" ->
                "This device cannot decode this video format. The server converts the video. Try a " +
                    "version with a supported video codec."
            "audiocodecnotsupported" ->
                "This device cannot decode this audio format. The server converts the audio. Choose a " +
                    "compatible audio track if available."
            "subtitlecodecnotsupported" ->
                "This device cannot display this subtitle format. The server converts the subtitles or " +
                    "burns them into the video. Choose a text subtitle track or turn subtitles off to " +
                    "avoid burn-in."
            "audioisexternal" -> "The audio is in a separate file. The server combines it with the video."
            "secondaryaudionotsupported" ->
                "This device cannot play the selected secondary audio directly. Choose the default " +
                    "audio track to try direct playback."
            "streamcountexceedslimit" -> "The file contains more streams than this device supports. The server selects compatible streams."
            "videoprofilenotsupported" -> "This device does not support the video's codec profile. The server converts the video."
            "videorangetypenotsupported" ->
                "This device does not support this video's HDR or color range. The server converts the " +
                    "video. Try an SDR version if available."
            "videocodectagnotsupported" -> "This device does not recognize the video's codec tag. The server makes a compatible stream."
            "videolevelnotsupported" -> "This video exceeds the decoder's supported codec level. The server converts the video."
            "videoresolutionnotsupported" -> "This video's resolution exceeds this device's limit. The server reduces the resolution."
            "videobitdepthnotsupported" -> "This device cannot decode the video's bit depth. The server converts the video."
            "videoframeratenotsupported" -> "This video's frame rate exceeds this device's limit. The server reduces the frame rate."
            "videorotationnotsupported" -> "This device cannot apply the video's rotation. The server rotates the video."
            "refframesnotsupported" -> "This video needs more reference frames than the decoder supports. The server converts the video."
            "anamorphicvideonotsupported" ->
                "This device cannot display the video's non-square pixels correctly. The server " +
                    "converts the video."
            "interlacedvideonotsupported" -> "This device cannot display this interlaced video directly. The server deinterlaces it."
            "audiochannelsnotsupported" ->
                "The selected audio has more channels than the current output supports. The server " +
                    "converts the audio. Choose a track with fewer channels or check your audio output " +
                    "settings."
            "audioprofilenotsupported" ->
                "This device does not support the audio's codec profile. The server converts the " +
                    "audio. Choose another audio track if available."
            "audiosampleratenotsupported" -> "This device does not support the audio's sample rate. The server converts the audio."
            "audiobitdepthnotsupported" -> "This device does not support the audio's bit depth. The server converts the audio."
            "containerbitrateexceedslimit", "videobitratenotsupported" -> if (qualityMbps != null && qualityMbps > 0) {
                "Quality is capped at $qualityMbps Mbps in Settings. Raise the limit to try the original quality; device or server limits may still require conversion."
            } else {
                "This stream exceeds a device or server bitrate limit. The server lowers its bitrate. Check the server's streaming limits."
            }
            "audiobitratenotsupported" ->
                "The audio exceeds a supported bitrate limit. The server lowers its bitrate. Choose " +
                    "another audio track if available."
            "unknownvideostreaminfo" ->
                "The server could not identify the video's properties. Refresh this item's metadata on " +
                    "the server and try again."
            "unknownaudiostreaminfo" ->
                "The server could not identify the audio's properties. Refresh this item's metadata on " +
                    "the server and try again."
            "directplayerror" ->
                "Direct playback failed. The server is providing a converted stream. Try another " +
                    "version or check the device's codec support."
            else -> "The server reported an additional conversion requirement that this client does not recognize."
        }
    }

    /** An explicit `VideoCodec=copy` means the server repackages the video without re-encoding it. */
    internal fun videoCopied(decision: PlayDecision): Boolean = parameter(decision, "VideoCodec") == "copy"

    private fun parameter(decision: PlayDecision, name: String): String? = runCatching {
        Url(decision.url).parameters.entries().firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()?.lowercase()
    }.getOrNull()
}

/** A viewing starts at normal speed; media time and countdown wall time are never rescaled. */
object PlaybackSpeeds {
    val choices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    const val ENCODED_AUDIO = "Speed is limited to 1× while audio passthrough or encoded offload is active. Turn off " +
        "audio passthrough in Settings and reopen playback to use other speeds."
    const val TRANSCODE = "Faster speeds may buffer while the server converts."
    fun label(speed: Float): String = "${speed.toString().removeSuffix(".0")}×"
    fun allowed(speed: Float, encodedAudio: Boolean): Boolean = speed in choices && (!encodedAudio || speed == 1f)
}
