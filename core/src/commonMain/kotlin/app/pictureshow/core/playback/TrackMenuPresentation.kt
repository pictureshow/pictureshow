/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * PLAY-08: a track menu with nothing to choose says so, instead of a Subtitles menu holding only "Off" (TV review T-10).
 * [tracks] counts every listed track of that type, including a download's captions that need a connection. Null when
 * there is at least one track, and for types that have no menu.
 */
fun trackMenuEmptyNotice(type: MediaStreamType, tracks: Int): String? = when {
    tracks > 0 -> null
    type == MediaStreamType.Subtitle -> "This video has no subtitles."
    type == MediaStreamType.Audio -> "This video has no audio tracks."
    else -> null
}
