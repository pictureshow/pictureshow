/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Player
import androidx.media3.common.util.Util

/** Shared by the surface and focused Play/Pause control. */
internal fun Player.togglePlayback() {
    Util.handlePlayPauseButtonAction(this)
}
