/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackNetwork { Home, Remote, Metered }

@Serializable
enum class SubtitleBurnIn { Always, Automatic, ImageOnly }

@Serializable
enum class CapabilityOverride { Detected, Force, Disable }

@Serializable
enum class PostCredits { KeepScenes, NextEpisode }

/**
 * PLAY-30: ask "Are you still watching?" instead of starting the next episode once this many episodes have played in a
 * row, or this many minutes have passed, without a button press. Either threshold is enough.
 */
@Serializable
enum class StillWatching(val episodes: Int, val minutes: Int) {
    Off(0, 0),
    TwoEpisodes(2, 60),
    ThreeEpisodes(3, 90),
    FiveEpisodes(5, 150),
    EightEpisodes(8, 240)
}

/** Portable viewer choices. Detection is retained separately; overrides are a request to try a capability. */
@Serializable
data class PlaybackTuning(
    val remoteBitrateMbps: Int? = 20,
    val meteredBitrateMbps: Int? = 4,
    val autoAdjustQuality: Boolean = true,
    val subtitleBurnIn: SubtitleBurnIn = SubtitleBurnIn.Automatic,
    val disabledPassthrough: Set<PassthroughEncoding> = emptySet(),
    val downmix: Boolean = false,
    val maxAudioChannels: Int? = null,
    val hdr: CapabilityOverride = CapabilityOverride.Detected,
    val dolbyVision: CapabilityOverride = CapabilityOverride.Detected,
    val preferDolbyVision: Boolean = false,
    val rewindSeconds: Int = 0,
    val postCredits: PostCredits = PostCredits.KeepScenes,
    val matchFrameRate: Boolean = false,
    /** PLAY-30: seconds the next-episode card counts during the credits; 0 never starts the next episode by itself. */
    val nextEpisodeCountdownSeconds: Int = 10,
    val stillWatching: StillWatching = StillWatching.ThreeEpisodes,
    /** PLAY-60: which player opens streamed video. */
    val engine: PlaybackEngineChoice = PlaybackEngineChoice.Automatic
) {
    /** Reject malformed persisted values individually, preserving other accepted preferences. */
    fun normalized() = copy(
        remoteBitrateMbps = remoteBitrateMbps?.takeIf { it in PlaybackPreferences.BITRATES } ?: if (remoteBitrateMbps == null) null else 20,
        meteredBitrateMbps =
        meteredBitrateMbps?.takeIf { it in PlaybackPreferences.BITRATES } ?: if (meteredBitrateMbps == null) null else 4,
        maxAudioChannels = maxAudioChannels?.takeIf { it in listOf(2, 6, 8) },
        rewindSeconds = rewindSeconds.takeIf { it in listOf(0, 5, 10, 15, 30) } ?: 0,
        nextEpisodeCountdownSeconds = nextEpisodeCountdownSeconds.takeIf { it in NEXT_EPISODE_COUNTDOWNS } ?: 10
    )

    /** Explicit overrides suspend automatic reduction for this viewing, but never remove the server ceiling. */
    fun bitrate(homeMbps: Int?, network: PlaybackNetwork, estimatedBandwidth: Long? = null, qualityOverrideMbps: Int? = null): Int {
        val override = qualityOverrideMbps?.takeIf { it in PlaybackPreferences.BITRATES }
        val configured = override ?: when (network) {
            PlaybackNetwork.Home -> homeMbps
            PlaybackNetwork.Remote -> normalized().remoteBitrateMbps
            PlaybackNetwork.Metered -> normalized().meteredBitrateMbps
        }
        val ceiling = configured?.times(1_000_000) ?: PlaybackSettings.DEFAULT_MAX_STREAMING_BITRATE
        if (override != null || !autoAdjustQuality || estimatedBandwidth == null || estimatedBandwidth <= 0) return ceiling
        // Leave room for VBR peaks and protocol overhead. Do not raise quality during an active viewing.
        if (estimatedBandwidth >= ceiling) return ceiling
        return automaticCap(ceiling, estimatedBandwidth)
    }

    companion object {
        const val AUTO_FLOOR = 4_000_000
        val NEXT_EPISODE_COUNTDOWNS = listOf(0, 5, 10, 15, 20, 30)

        /** Pick an offered rung, retaining the configured ceiling even if it is below the Auto floor. */
        fun automaticCap(ceiling: Int, estimate: Long): Int {
            if (estimate <= 0 || estimate >= ceiling) return ceiling
            val budget = estimate / 5 * 4
            val rung = PlaybackPreferences.BITRATES.filterNotNull().sortedDescending()
                .firstOrNull { it.toLong() * 1_000_000 <= budget }?.times(1_000_000) ?: AUTO_FLOOR
            return minOf(ceiling, rung)
        }
    }

    /** Apply only to a new resume, never to replacement preparation or a fresh From start. */
    fun resumedPositionMs(savedPositionMs: Long): Long =
        (savedPositionMs.coerceAtLeast(0) - normalized().rewindSeconds.toLong() * 1_000).coerceAtLeast(0)

    /** A running remaining-time clock is not proof the stream reached physical end. */
    fun canAutomaticallyAdvance(ended: Boolean): Boolean =
        normalized().nextEpisodeCountdownSeconds > 0 && (ended || postCredits == PostCredits.NextEpisode)

    /** Keep-scenes prevents the credits card from completing an item before its physical end. */
    fun allowEarlyNext(hasPostCreditsScene: Boolean = true): Boolean = postCredits == PostCredits.NextEpisode || !hasPostCreditsScene
}
