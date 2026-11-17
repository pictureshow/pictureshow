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
    val audio: Boolean = false,
    /** mpv showed no picture for this viewing ([EngineFirstFrame]): the rest of it plays on the platform player. */
    val mpvFailed: Boolean = false
) {
    val mpvEligible: Boolean get() = mpvAvailable && !live && !offline && !syncPlay && !audio && !mpvFailed
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

/** What the first-frame watchdog does now. */
enum class FirstFrameAction { Wait, WithoutPassthrough, Platform }

/**
 * PLAY-61: a viewer who picks mpv never ends on a black screen. mpv can open a file and then never show a picture,
 * without an error: when its audio passthrough can't start it waits for audio for good and holds every decoded frame
 * (OBSERVED on the household projector with AC-3 and E-AC-3, on HEVC and any other video). Once the file has opened,
 * the watchdog counts from the moment the stream had [DATA_MS] of media buffered (the network has delivered; what's
 * missing is the player) and waits [READY_MS] from there, or [CEILING_MS] from the open whatever the buffer says.
 * Then mpv retries once at the same position with audio decoded instead of passed through; without passthrough, it
 * gives way to the built-in player with a notice.
 */
object EngineFirstFrame {
    const val DATA_MS: Long = 2_000
    const val READY_MS: Long = 8_000
    const val CEILING_MS: Long = 20_000

    /**
     * [dataReadyForMs]: how long the stream has held at least [DATA_MS] of buffered media, or null if it never has;
     * [loadedForMs]: how long since the file opened; [passthrough]: the selected audio track is passed through compressed.
     */
    fun action(loaded: Boolean, firstFrame: Boolean, dataReadyForMs: Long?, loadedForMs: Long, passthrough: Boolean): FirstFrameAction {
        if (!loaded || firstFrame) return FirstFrameAction.Wait
        val stalled = (dataReadyForMs != null && dataReadyForMs >= READY_MS) || loadedForMs >= CEILING_MS
        return when {
            !stalled -> FirstFrameAction.Wait
            passthrough -> FirstFrameAction.WithoutPassthrough
            else -> FirstFrameAction.Platform
        }
    }
}
