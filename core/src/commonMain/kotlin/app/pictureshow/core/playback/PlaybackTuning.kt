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
    val matchFrameRate: Boolean = false
) {
    /** Reject malformed persisted values individually, preserving other accepted preferences. */
    fun normalized() = copy(
        remoteBitrateMbps = remoteBitrateMbps?.takeIf { it in PlaybackPreferences.BITRATES } ?: if (remoteBitrateMbps == null) null else 20,
        meteredBitrateMbps =
        meteredBitrateMbps?.takeIf { it in PlaybackPreferences.BITRATES } ?: if (meteredBitrateMbps == null) null else 4,
        maxAudioChannels = maxAudioChannels?.takeIf { it in listOf(2, 6, 8) },
        rewindSeconds = rewindSeconds.takeIf { it in listOf(0, 5, 10, 15, 30) } ?: 0
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
        val budget = (estimatedBandwidth / 5 * 4).coerceAtLeast(1)
        val rung = PlaybackPreferences.BITRATES.filterNotNull().sortedDescending()
            .firstOrNull { it.toLong() * 1_000_000 <= budget }?.times(1_000_000)
            ?: minOf(budget, 4_000_000L).toInt()
        return minOf(ceiling, rung)
    }

    /** Apply only to a new resume, never to replacement preparation or a fresh From start. */
    fun resumedPositionMs(savedPositionMs: Long): Long =
        (savedPositionMs.coerceAtLeast(0) - normalized().rewindSeconds.toLong() * 1_000).coerceAtLeast(0)

    /** A running remaining-time clock is not proof the stream reached physical end. */
    fun canAutomaticallyAdvance(ended: Boolean): Boolean = ended || postCredits == PostCredits.NextEpisode

    /** Keep-scenes prevents the credits card from completing an item before its physical end. */
    fun allowEarlyNext(hasPostCreditsScene: Boolean = true): Boolean = postCredits == PostCredits.NextEpisode || !hasPostCreditsScene
}
