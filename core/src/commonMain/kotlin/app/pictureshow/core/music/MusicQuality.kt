/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.DeviceProfile
import app.pictureshow.core.playback.DirectPlayProfile
import app.pictureshow.core.playback.DlnaProfileType
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.TranscodingProfile
import kotlinx.serialization.Serializable

/** "auto", "original", "256", "128". Anything else is treated as Auto. */
const val MUSIC_QUALITY_AUTO = "auto"
val MUSIC_QUALITIES = listOf(MUSIC_QUALITY_AUTO, "original", "256", "128")

private const val UNCAPPED = 320_000_000

/** Auto on a metered network asks for 256 kbps, and only a file above that converts. Everything else is what was picked. */
fun musicBitrateCeiling(quality: String, metered: Boolean): Int = when (quality) {
    "original" -> UNCAPPED
    "256" -> 256_000
    "128" -> 128_000
    else -> if (metered) 256_000 else UNCAPPED
}

private val lossless = setOf("flac", "alac", "ape", "wavpack", "tak", "truehd", "mlp")

fun audioCodecIsLossless(codec: String?): Boolean {
    val name = codec?.lowercase() ?: return false
    return name in lossless || name.startsWith("pcm_")
}

/** Containers a music file arrives in, and the codecs Media3 reads from each. A profile lists only those this device decodes. */
private val MUSIC_CONTAINERS: List<Pair<String, Set<AudioCodec>>> = listOf(
    "flac" to setOf(AudioCodec.FLAC),
    "mp3" to setOf(AudioCodec.MP3),
    "aac" to setOf(AudioCodec.AAC),
    "m4a,m4b,mp4,mov" to setOf(AudioCodec.AAC, AudioCodec.ALAC, AudioCodec.FLAC, AudioCodec.MP3, AudioCodec.OPUS),
    "ogg,oga" to setOf(AudioCodec.VORBIS, AudioCodec.OPUS, AudioCodec.FLAC),
    "opus" to setOf(AudioCodec.OPUS),
    "webm,webma" to setOf(AudioCodec.OPUS, AudioCodec.VORBIS),
    "wav" to setOf(AudioCodec.PCM),
    "mka,mkv" to setOf(AudioCodec.AAC, AudioCodec.ALAC, AudioCodec.FLAC, AudioCodec.MP3, AudioCodec.OPUS, AudioCodec.VORBIS, AudioCodec.PCM)
)

/**
 * The server converts only what this device can't decode, or what's above [ceiling]. Conversion is one audio stream in
 * HLS, so it seeks, and no profile here names a video codec, so it can't encode video (MUS-06).
 */
fun musicDeviceProfile(decoders: Set<AudioCodec>, ceiling: Int, name: String = "Pictureshow music"): DeviceProfile {
    val direct = MUSIC_CONTAINERS.mapNotNull { (containers, supported) ->
        val codecs = AudioCodec.entries.filter { it in supported && it in decoders }
        if (codecs.isEmpty()) {
            null
        } else {
            DirectPlayProfile(containers, codecs.flatMap { it.serverNames }.joinToString(","), type = DlnaProfileType.Audio)
        }
    }
    val conversion = if (AudioCodec.AAC in decoders) {
        listOf(
            TranscodingProfile(
                container = "ts",
                type = DlnaProfileType.Audio,
                videoCodec = "",
                audioCodec = "aac",
                protocol = MediaStreamProtocol.Hls,
                maxAudioChannels = "2",
                breakOnNonKeyFrames = true
            )
        )
    } else {
        emptyList()
    }
    return DeviceProfile(
        name = name,
        maxStreamingBitrate = ceiling,
        maxStaticBitrate = ceiling,
        musicStreamingTranscodingBitrate = ceiling.coerceAtMost(256_000),
        maxStaticMusicBitrate = ceiling,
        directPlayProfiles = direct,
        transcodingProfiles = conversion
    )
}

@Serializable
data class MusicOutput(
    val label: String,
    val lossless: Boolean,
    val converted: Boolean,
    /** Why the server converted, for the notice owed by the conversion rule; null when it didn't. */
    val reason: String? = null
)

private fun audioStream(source: MediaSourceInfo): MediaStream? = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio }

/**
 * What the listener is really getting. Lossless is claimed only for a lossless source played as it is over a route that
 * carries it: Bluetooth and Cast re-encode, so they never get the word (MUS-06). Route is "device", "wired", "usb",
 * "bluetooth", "cast" or "remote".
 */
fun musicOutputFacts(
    source: MediaSourceInfo,
    method: PlayMethod,
    route: String,
    ceiling: Int,
    reasons: List<String> = emptyList()
): MusicOutput {
    val stream = audioStream(source)
    val codec = stream?.codec?.uppercase().orEmpty().ifBlank { source.container?.uppercase().orEmpty() }
    val converted = method == PlayMethod.Transcode
    val lossyRoute = route in setOf("bluetooth", "cast", "remote")
    val sourceLossless = audioCodecIsLossless(stream?.codec)
    val lossless = !converted && sourceLossless && !lossyRoute
    val rate = ceiling.coerceAtMost(256_000) / 1000
    val label = buildString {
        if (converted) {
            append("Converted to AAC, up to $rate kbps (lossy)")
        } else {
            append(codec.ifBlank { "Original file" })
            if (lossless) {
                append(" (lossless)")
            } else if (sourceLossless) {
                append(if (route == "bluetooth") " sent over Bluetooth (lossy)" else " sent to another device")
            } else {
                append(" (original)")
            }
        }
    }
    val reason = if (!converted) {
        null
    } else if (reasons.any { "Bitrate" in it }) {
        "It's above the Music quality you picked. Change Music quality in Settings to hear the original."
    } else {
        // The next step is a copy the device plays as it is. A shape it can't take (channels, rate, depth) names the
        // shape; otherwise nothing here decodes the format, and FLAC keeps a lossless source lossless while AAC, the
        // format the server converts to, always plays.
        val name = codec.ifBlank { "this file" }
        val shape = listOf(
            "AudioChannelsNotSupported" to ("channel count" to "a stereo copy"),
            "AudioSampleRateNotSupported" to ("sample rate" to "a 48 kHz copy"),
            "AudioBitDepthNotSupported" to ("bit depth" to "a 16-bit copy")
        ).firstOrNull { (code) -> code in reasons }?.second
        val format = if (sourceLossless && stream?.codec?.lowercase() != "flac") "a FLAC copy" else "an AAC copy"
        val (why, copy) = shape?.let { (what, copy) -> "This device can't play $name at its $what" to copy }
            ?: ("This device can't decode $name" to format)
        "$why, so the server converts it. It still plays, and $copy on the server plays directly."
    }
    return MusicOutput(label, lossless, converted, reason)
}
