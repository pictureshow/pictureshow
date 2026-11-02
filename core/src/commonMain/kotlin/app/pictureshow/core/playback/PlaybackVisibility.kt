/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Native stopped activities require visibility; a background browser tab may use another platform policy. */
object PlaybackVisibility {
    fun playWhenReady(requested: Boolean, foregroundRequired: Boolean, foreground: Boolean, pauseLatched: Boolean): Boolean =
        requested && !pauseLatched && (!foregroundRequired || foreground)
}
