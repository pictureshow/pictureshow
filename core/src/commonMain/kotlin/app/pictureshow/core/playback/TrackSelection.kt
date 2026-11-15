/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Compare against the active selection when the serialized pick is processed, not a panel snapshot.
 * Readiness, delivery codec and audio recovery do not turn an unchanged track into a new stream.
 */
object TrackSelection {
    /** Audio cannot be Off. Negative legacy selections use the server/default/first track; silent files stay -1. */
    fun audioIndex(streams: List<MediaStream>, selected: Int?, serverDefault: Int?): Int =
        selected?.takeIf { it >= 0 } ?: serverDefault?.takeIf { it >= 0 }
            ?: streams.firstOrNull { it.type == MediaStreamType.Audio && it.isDefault }?.index
            ?: streams.firstOrNull { it.type == MediaStreamType.Audio }?.index ?: -1

    fun unchanged(currentIndex: Int, pickedIndex: Int): Boolean = currentIndex == pickedIndex
}
