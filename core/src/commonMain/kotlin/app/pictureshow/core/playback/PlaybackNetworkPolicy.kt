/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Endpoint classification and conservative reduction guards, shared by every platform's network observer. */
object PlaybackNetworkPolicy {
    fun classify(metered: Boolean, localEndpoint: Boolean): PlaybackNetwork = when {
        metered -> PlaybackNetwork.Metered
        localEndpoint -> PlaybackNetwork.Home
        else -> PlaybackNetwork.Remote
    }

    /** IPv6 unique-local fc00::/7 is not covered by the legacy site-local predicate on every runtime. */
    fun isUniqueLocalAddress(address: ByteArray): Boolean = address.size == 16 && (address[0].toInt() and 0xfe) == 0xfc

    fun shouldReduce(currentCap: Int, proposedCap: Int, sampleCount: Int, sampleBytes: Long, elapsedSinceAdjustmentMs: Long): Boolean =
        currentCap > 0 &&
            proposedCap > 0 &&
            proposedCap < currentCap &&
            sampleCount >= 2 &&
            sampleBytes >= 131_072 &&
            elapsedSinceAdjustmentMs >= 30_000
}
