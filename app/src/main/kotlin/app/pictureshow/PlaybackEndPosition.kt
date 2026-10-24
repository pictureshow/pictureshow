/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.media3.common.Player

/** Renderer draining can advance the media clock beyond its timeline. Keep the physical end
 * if it is earlier (a damaged container can overstate duration), and use the declared stream timeline.
 */
internal fun playbackEndPosition(positionMs: Long, streamDurationMs: Long): Long = positionMs.coerceAtLeast(0).let { position ->
    if (streamDurationMs > 0) position.coerceAtMost(streamDurationMs) else position
}

/** A server-seek wrapper uses item time, while its decoder uses remainder time. Read both
 * values through the same player so a live or completed position cannot jump back to its seek offset.
 */
internal fun boundedPlaybackPosition(player: Player): PlaybackPosition = PlaybackPosition(
    playbackEndPosition(player.currentPosition, player.duration),
    !player.playWhenReady
)
