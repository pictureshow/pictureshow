/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Positions are relative to a measured player window, never the guide's scheduled duration. */
data class ObservedLiveWindow(val offsetMs: Long, val durationMs: Long, val defaultPositionMs: Long, val seekable: Boolean) {
    val canTimeShift: Boolean get() = seekable && durationMs > 0
    fun contains(positionMs: Long): Boolean = canTimeShift && positionMs in 0..durationMs
    fun pausedPositionExpired(absolutePositionMs: Long?): Boolean = absolutePositionMs != null &&
        canTimeShift &&
        (absolutePositionMs < offsetMs || absolutePositionMs - offsetMs > durationMs)
    fun goLivePosition(): Long? = defaultPositionMs.takeIf { durationMs > 0 && it in 0..durationMs }
}
