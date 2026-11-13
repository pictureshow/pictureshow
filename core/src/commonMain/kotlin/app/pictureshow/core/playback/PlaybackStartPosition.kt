/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** A receiver/controller clock is an exact intent; only a saved resume point earns a rewind. */
enum class PlaybackStartOrigin { SavedResume, ExplicitPosition }

object PlaybackStartPosition {
    fun of(positionMs: Long, origin: PlaybackStartOrigin, tuning: PlaybackTuning): Long =
        if (origin == PlaybackStartOrigin.SavedResume) tuning.resumedPositionMs(positionMs) else positionMs.coerceAtLeast(0)
}
