/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlinx.serialization.Serializable

/** The player that opens a decision: the platform's own (Media3 on Android) or mpv. */
enum class PlaybackEngine { Media3, Mpv }

/** PLAY-60: the viewer's choice, in Settings and per title. */
@Serializable
enum class PlaybackEngineChoice { Automatic, Mpv }

/**
 * What decides whether mpv may play this viewing at all. mpv plays streamed video only: Live TV, downloads, SyncPlay
 * groups and music stay on the platform player, and so does any device whose mpv libraries didn't load.
 */
data class PlaybackEngineContext(
    val choice: PlaybackEngineChoice,
    val mpvAvailable: Boolean,
    val live: Boolean = false,
    val offline: Boolean = false,
    val syncPlay: Boolean = false,
    val audio: Boolean = false
) {
    val mpvEligible: Boolean get() = mpvAvailable && !live && !offline && !syncPlay && !audio
}

/**
 * PLAY-60. Automatic is the platform player unless the server would convert for it and every reason it gives is one mpv
 * removes (a container, codec or subtitle format it can't take); then the server is asked again with mpv's profile,
 * and mpv plays only if that answer doesn't convert. Reasons about bitrate, resolution, level, HDR or frame rate are
 * the display's or the network's limits, which mpv shares, so they never switch engines. Choosing mpv uses mpv for
 * every eligible viewing, converting or not.
 */
object PlaybackEngineRule {
    /** Jellyfin TranscodeReason names (compared lower-case) that mpv's FFmpeg demuxers and decoders remove. */
    val MPV_SOLVABLE: Set<String> = setOf(
        "containernotsupported",
        "videocodecnotsupported",
        "audiocodecnotsupported",
        "subtitlecodecnotsupported",
        "audioisexternal",
        "secondaryaudionotsupported",
        "audiochannelsnotsupported",
        "audioprofilenotsupported",
        "audiosampleratenotsupported",
        "audiobitdepthnotsupported",
        "audiobitratenotsupported",
        "videocodectagnotsupported",
        "anamorphicvideonotsupported",
        "unknownaudiostreaminfo"
    )

    /** The engine whose profile the first PlaybackInfo request carries. */
    fun first(context: PlaybackEngineContext): PlaybackEngine =
        if (context.choice == PlaybackEngineChoice.Mpv && context.mpvEligible) PlaybackEngine.Mpv else PlaybackEngine.Media3

    /** After the platform player's answer: whether to ask again with mpv's profile. */
    fun retryWithMpv(context: PlaybackEngineContext, method: PlayMethod, reasons: List<String>): Boolean =
        context.choice == PlaybackEngineChoice.Automatic &&
            context.mpvEligible &&
            method == PlayMethod.Transcode &&
            reasons.isNotEmpty() &&
            reasons.all { it.lowercase() in MPV_SOLVABLE }

    /** After mpv's answer to a retry: mpv plays only when the server no longer converts. */
    fun keepMpv(method: PlayMethod): Boolean = method != PlayMethod.Transcode

    /** The whole rule, for tests and platforms that negotiate in one place. */
    fun choose(
        context: PlaybackEngineContext,
        platformMethod: PlayMethod,
        platformReasons: List<String>,
        mpvMethod: PlayMethod?
    ): PlaybackEngine = when {
        first(context) == PlaybackEngine.Mpv -> PlaybackEngine.Mpv
        !retryWithMpv(context, platformMethod, platformReasons) -> PlaybackEngine.Media3
        mpvMethod != null && keepMpv(mpvMethod) -> PlaybackEngine.Mpv
        else -> PlaybackEngine.Media3
    }
}
