/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

/** Shared exit path for remote Stop, Back and a natural end without a following episode. */
internal fun finishPlayback(session: PlaybackSession?, onExit: () -> Unit) {
    session?.close()
    onExit()
}
