/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType

internal data class PlayerTrackMenu(val streams: List<MediaStream>, val choices: List<Pair<Int, String>>)

/** Playback position, input focus and selection do not change the server's track descriptions. */
@Composable
internal fun rememberPlayerTrackMenu(mediaStreams: List<MediaStream>, type: MediaStreamType): PlayerTrackMenu =
    remember(mediaStreams, type) {
        val streams = mediaStreams.filter { it.type == type }
        val labels = trackLabels(streams)
        PlayerTrackMenu(streams, listOf(-1 to "Off") + streams.mapIndexed { i, stream -> stream.index to labels[i] })
    }
