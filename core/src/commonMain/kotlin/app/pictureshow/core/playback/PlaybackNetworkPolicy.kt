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

    /** Media3's own threshold for resuming after a stall: with at least this much buffered ahead, nothing is about to stop. */
    const val LOW_BUFFER_MS = 5_000L

    /**
     * PLAY-26: the player is getting at least [speed] seconds of media per wall-clock second, so the connection keeps up
     * whatever a throughput sample says. A buffered end that moved backwards (a seek, a replaced source) or a
     * non-positive window is no evidence of a slow connection either.
     */
    fun keepsUp(bufferedGainMs: Long, elapsedMs: Long, speed: Float = 1f): Boolean =
        elapsedMs <= 0 || bufferedGainMs < 0 || bufferedGainMs >= elapsedMs * speed.coerceAtLeast(0.1f)

    /**
     * PLAY-26: a throughput sample counts toward a reduction only while the player is really short of data: less than
     * [LOW_BUFFER_MS] buffered ahead and the buffered end not keeping up since the previous sample. One active-read
     * window is noisy (a busy CPU slows reads); the start-up fill, a healthy buffer, or a decoder slower than the network
     * never lower a stream the connection is carrying.
     */
    fun fallingBehind(bufferedAheadMs: Long, bufferedGainMs: Long, elapsedMs: Long, speed: Float = 1f): Boolean =
        bufferedAheadMs < LOW_BUFFER_MS && !keepsUp(bufferedGainMs, elapsedMs, speed)

    /** A quality restart re-prepares the stream (9.5 s on a slow device in leg 194), so a shorter shortage costs less to sit out. */
    const val SUSTAINED_SHORTAGE_MS = 10_000L

    /**
     * PLAY-26: the player has been short of data ([fallingBehind] on every sample) for [shortageMs] without a break. A
     * start-up stall of a few seconds rides out on its own; only a shortage of [SUSTAINED_SHORTAGE_MS] or more lowers quality.
     */
    fun shortageSustained(shortageMs: Long): Boolean = shortageMs >= SUSTAINED_SHORTAGE_MS

    /**
     * PLAY-26: an automatic [cap] replaces the playing stream only when the cap is below the stream's bitrate and the
     * measured rate's 80% budget can't carry that bitrate either. Ladder rounding alone (8 Mbps measured, 5.7 Mbps file,
     * next rung down 4 Mbps) never interrupts a stream the connection is carrying.
     */
    fun reroutes(cap: Int, estimate: Long, streamBitrate: Int?): Boolean =
        PlaybackQualityPresentation.affectsStream(cap, streamBitrate) && estimate > 0 && estimate / 5 * 4 < streamBitrate!!
}
