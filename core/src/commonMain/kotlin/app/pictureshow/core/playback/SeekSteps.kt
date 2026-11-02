/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** An unacknowledged viewing target survives decoder replacement and remains the base of the next step. */
object SeekSteps {
    fun target(positionMs: Long, pendingMs: Long?, deltaMs: Long, durationMs: Long): Long =
        ((pendingMs ?: positionMs).coerceIn(0, durationMs.coerceAtLeast(0)) + deltaMs)
            .coerceIn(0, durationMs.coerceAtLeast(0))
}
