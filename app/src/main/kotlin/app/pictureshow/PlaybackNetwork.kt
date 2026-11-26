/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.net.ConnectivityManager
import app.pictureshow.core.playback.PlaybackNetwork
import app.pictureshow.core.playback.PlaybackNetworkPolicy
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wi-Fi does not imply home: a public/reverse-proxy server remains remote on Wi-Fi. */
internal suspend fun playbackNetwork(context: Context, serverUrl: String): PlaybackNetwork = withContext(Dispatchers.IO) {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (connectivity?.isActiveNetworkMetered == true) return@withContext PlaybackNetwork.Metered
    val addresses = runCatching { InetAddress.getAllByName(URI(serverUrl).host).toList() }.getOrDefault(emptyList())
    classifyPlaybackNetwork(
        false,
        addresses.isNotEmpty() &&
            addresses.all {
                it.isLoopbackAddress ||
                    it.isLinkLocalAddress ||
                    it.isSiteLocalAddress ||
                    PlaybackNetworkPolicy.isUniqueLocalAddress(it.address)
            }
    )
}

/** Unknown/unresolved/public routes use the remote cap. Metering takes precedence over a local endpoint. */
internal fun classifyPlaybackNetwork(metered: Boolean, localEndpoint: Boolean): PlaybackNetwork =
    PlaybackNetworkPolicy.classify(metered, localEndpoint)

/**
 * Reduce at most once per 30 seconds, and require two substantial samples taken while the player was short of data
 * (PLAY-26 `fallingBehind`: under 5 s buffered ahead and the buffered end gaining less media time than wall time since
 * the previous sample). Never auto-raise within a viewing. The first sample of a viewing only sets the baseline.
 */
internal class BandwidthQualityGuard {
    private var samples = 0
    private var lastChangeMs: Long? = null
    private var previous: Pair<Long, Long>? = null

    /** [bufferedPositionMs] is null when no player reports one; the sample rule then stands alone. */
    fun reduction(
        currentCap: Int,
        estimatedBitrate: Long,
        bytes: Long,
        nowMs: Long,
        bufferedPositionMs: Long? = null,
        positionMs: Long = 0,
        speed: Float = 1f
    ): Long? {
        val baseline = previous
        val behind = if (bufferedPositionMs == null) {
            true
        } else {
            previous = nowMs to bufferedPositionMs
            baseline != null &&
                PlaybackNetworkPolicy.fallingBehind(
                    bufferedPositionMs - positionMs,
                    bufferedPositionMs - baseline.second,
                    nowMs - baseline.first,
                    speed
                )
        }
        val proposedCap = app.pictureshow.core.playback.PlaybackTuning.automaticCap(currentCap, estimatedBitrate)
        if (!behind || bytes < 131_072 || estimatedBitrate <= 0 || estimatedBitrate >= currentCap || proposedCap >= currentCap) {
            samples = 0
            return null
        }
        samples++
        if (!PlaybackNetworkPolicy.shouldReduce(
                currentCap,
                proposedCap,
                samples,
                bytes,
                lastChangeMs?.let {
                    nowMs - it
                } ?: Long.MAX_VALUE
            )
        ) {
            return null
        }
        samples = 0
        lastChangeMs = nowMs
        return estimatedBitrate
    }
}

/** The card can appear near physical end while KeepScenes still forbids its early countdown. */
internal fun playbackNextTriggerMs(durationMs: Long, segments: List<app.pictureshow.core.MediaSegment>, earlyNext: Boolean): Long? =
    if (earlyNext) {
        app.pictureshow.core.nextEpisodeTriggerMs(durationMs, segments)
    } else {
        durationMs.takeIf { it > 0 }?.let { (it - 30_000).coerceAtLeast(0) }
    }

internal fun qualityBitrateLabel(cap: Int): String = if (cap < 1_000) {
    "<1 kbps"
} else if (cap % 1_000_000 == 0) {
    "${cap / 1_000_000} Mbps"
} else {
    "${cap / 1_000} kbps"
}
