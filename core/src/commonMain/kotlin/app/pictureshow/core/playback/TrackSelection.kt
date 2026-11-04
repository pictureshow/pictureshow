/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Compare against the active selection when the serialized pick is processed, not a panel snapshot.
 * Readiness, delivery codec and audio recovery do not turn an unchanged track into a new stream.
 */
object TrackSelection {
    fun unchanged(currentIndex: Int, pickedIndex: Int): Boolean = currentIndex == pickedIndex
}
