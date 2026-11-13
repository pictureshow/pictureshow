/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.staticCompositionLocalOf

typealias PlaybackStartOrigin = app.pictureshow.core.playback.PlaybackStartOrigin

/** Navigation supplies provenance. An automatically selected following episode uses its own saved resume. */
internal val LocalPlaybackStartOrigin = staticCompositionLocalOf { PlaybackStartOrigin.SavedResume }

internal fun playbackStartOrigin(savedResume: Boolean): PlaybackStartOrigin =
    if (savedResume) PlaybackStartOrigin.SavedResume else PlaybackStartOrigin.ExplicitPosition
