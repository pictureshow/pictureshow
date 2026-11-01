/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Only viewer choices may update the server's per-item remembered tracks.
 * Null is omitted on the wire; automatic defaults/preferences remain playback intent only.
 */
object TrackSelectionReporting {
    fun index(selectedIndex: Int?, chosenByViewer: Boolean): Int? = selectedIndex.takeIf { chosenByViewer }
}
