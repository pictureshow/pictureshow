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
            // Jellyfin 12 sends this whenever no direct-play profile matched, not only after a failure.
            "directplayerror" -> "This device can't play the file as it is, so the server provides a stream it can play."
            else -> "The server reported an additional conversion requirement that this client does not recognize."
        }
    }

    /** PLAY-26: what Playback info leads with. [remedies] are shared; a platform adds its own for a video limit. */
    /** [method] and [heading] follow [kind], so a Transcode that only repackages reads as a direct stream. */
    data class Explanation(val kind: String, val method: String, val heading: String?, val summary: String, val remedies: List<String>)

    private val videoWhat: List<Pair<Set<String>, String>> = listOf(
        setOf("videocodecnotsupported") to "video format",
        setOf("videorangetypenotsupported") to "HDR format",
        setOf("videoprofilenotsupported", "videolevelnotsupported", "videobitdepthnotsupported", "refframesnotsupported") to
            "video encoding",
        setOf("videoresolutionnotsupported") to "resolution",
        setOf("videoframeratenotsupported") to "frame rate",
        setOf("interlacedvideonotsupported") to "interlaced video",
        setOf("anamorphicvideonotsupported") to "non-square pixels",
        setOf("videorotationnotsupported") to "rotation"
    )
    private val bitrateCodes = setOf("containerbitrateexceedslimit", "videobitratenotsupported", "audiobitratenotsupported")
    private val audioFormatCodes = setOf(
        "audiocodecnotsupported",
        "audioprofilenotsupported",
        "audiosampleratenotsupported",
        "audiobitdepthnotsupported",
        "secondaryaudionotsupported"
    )

    /**
     * Reasons that only change the package. The server re-encodes a stream only when a reason says that stream must
     * change; Jellyfin 12 sends DirectPlayError alone for a file no direct-play profile matched, with both streams copied.
     */
    private val repackageCodes = setOf("directplayerror", "containernotsupported", "videocodectagnotsupported")

    fun explanation(
        decision: PlayDecision,
        subtitleIndex: Int,
        device: String = ConversionNotice.DEVICE,
        qualityMbps: Int? = null,
        videoFormat: String? = null
    ): Explanation {
        val codes = decision.transcodeReasons.map { it.lowercase() }.toSet()
        val label = method(decision)
        val video = videoWhat.firstOrNull { (set) -> set.any(codes::contains) }?.second
        val kind = when {
            label == "Direct play" -> "direct"
            label != "Transcode" -> "remux"
            !videoCopied(decision) && video != null -> "video"
            // Burning subtitles in always re-encodes the picture, and Jellyfin often reports it without
            // SubtitleCodecNotSupported (an image subtitle on the web), so the delivery method alone decides.
            subtitle(decision.mediaSource, subtitleIndex) == "Burned in" -> "burn-in"
            codes.any(bitrateCodes::contains) -> "bitrate"
            codes.any(audioFormatCodes::contains) -> "audio"
            "audiochannelsnotsupported" in codes -> "audio-channels"
            codes.isNotEmpty() && codes.all(repackageCodes::contains) -> "remux"
            else -> "unexplained"
        }
        val summary = when (kind) {
            "direct" -> "${device.replaceFirstChar { it.uppercase() }} is playing the file directly, with no conversion."
            "remux" -> "The server is repackaging this video for $device without converting it, so the quality is unchanged."
            "video" -> {
                val format = if (video == "video format" && videoFormat != null) " ($videoFormat)" else ""
                "The server is converting this video because $device can't play its $video$format."
            }
            "burn-in" -> "The server is converting this video to burn the selected subtitles into the picture."
            "bitrate" -> if (qualityMbps != null && qualityMbps > 0) {
                "The server is converting this video to keep it under your $qualityMbps Mbps quality limit."
            } else {
                "The server is converting this video to keep it under a bitrate limit."
            }
            "audio" -> "The server is converting this video's sound into a format $device can play."
            "audio-channels" -> {
                val target = when (parameter(decision, "TranscodingMaxAudioChannels")) {
                    "1" -> "mono"
                    "2" -> "stereo"
                    "6" -> "5.1"
                    "8" -> "7.1"
                    else -> "fewer channels"
                }
                "The server is mixing this video's sound down to $target for $device, and nothing needs fixing."
            }
            else -> "The server is converting this video and did not say why."
        }
        val audioTracks = playableOtherAudioTracks(decision)
        val remedies = when (kind) {
            "burn-in" -> listOf("Turn subtitles off, or choose a text subtitle track, so they don't need to be burned in.")
            "bitrate" -> listOf("Raise Max streaming bitrate in Settings to try the original quality.")
            // A remedy that names another track needs one that plays without conversion.
            "audio" -> if (audioTracks >= 1) {
                listOf("Choose another audio track in Audio; one that $device can play needs no conversion.")
            } else {
                emptyList()
            }
            else -> emptyList()
        }
        val (shownMethod, heading) = when {
            kind == "direct" -> "Direct play" to null
            kind == "remux" -> "Direct stream (repackaged)" to "Why the server repackages"
            else -> "Transcode" to "Why the server converts"
        }
        return Explanation(kind, shownMethod, heading, summary, remedies)
    }

    /**
     * How many of the source's other audio tracks the server would copy as they are. The transcoding URL names the
     * formats its stream carries (`AudioCodec`) and the track being converted (`AudioStreamIndex`); a track in another
     * format is converted too, so advice to switch tracks needs one in a carried format. No list, no advice.
     */
    fun playableOtherAudioTracks(decision: PlayDecision): Int {
        fun name(codec: String?) = codec.orEmpty().trim().lowercase().let { if (it == "dca") "dts" else it }
        val carried = parameter(decision, "AudioCodec").orEmpty().split(',').map(::name)
            .filter { it.isNotEmpty() && it != "copy" }.toSet()
        val selected = parameter(decision, "AudioStreamIndex")?.toIntOrNull()
        return decision.mediaSource.mediaStreams.count {
            it.type == MediaStreamType.Audio && it.index != selected && name(it.codec) in carried
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
