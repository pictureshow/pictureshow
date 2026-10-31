/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.displayRuntimeMillis

/** Bound decoder overrun to the stream timeline, retaining physical EOS before an inflated timeline. */
fun playbackEndPosition(positionMs: Long, streamDurationMs: Long): Long = positionMs.coerceAtLeast(0).let { position ->
    if (streamDurationMs > 0) position.coerceAtMost(streamDurationMs) else position
}

/** At confirmed EOS, round only a plausible stream duration no more than one second beyond physical EOS. */
fun completedPlaybackPosition(positionMs: Long, durationMs: Long): Long {
    val bounded = playbackEndPosition(positionMs, durationMs)
    return if (displayRuntimeMillis(durationMs) != null && durationMs - bounded in 0..1_000) durationMs else bounded
}
