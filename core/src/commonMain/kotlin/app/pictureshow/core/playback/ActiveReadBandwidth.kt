/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** A completed window of time actually spent reading network media, excluding loader/viewer pauses. */
data class ActiveReadBandwidthSample(val bitrate: Long, val bytes: Long)

/** Progressive transfers need samples before their one long HTTP response closes. */
class ActiveReadBandwidth {
    private var bytes = 0L
    private var nanos = 0L

    fun reset() {
        bytes = 0
        nanos = 0
    }

    /** Only successful reads count. No sample is flushed on EOF, failure, cancellation or close. */
    fun record(readBytes: Int, activeReadNanos: Long, network: Boolean, fullSpeed: Boolean): ActiveReadBandwidthSample? {
        if (!network || !fullSpeed) {
            reset()
            return null
        }
        if (readBytes <= 0 || activeReadNanos < 0) return null
        bytes += readBytes
        nanos += activeReadNanos
        if (bytes < 131_072 || nanos < 1_000_000_000) return null
        val sample = ActiveReadBandwidthSample((bytes.toDouble() * 8_000_000_000 / nanos).toLong().coerceAtLeast(1), bytes)
        reset()
        return sample
    }
}
