/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlayerTrackMenu(val streams: List<MediaStream>, val choices: List<Pair<Int, String>>)

internal data class PlayerTrackMenus(val audio: PlayerTrackMenu, val subtitles: PlayerTrackMenu) {
    operator fun get(type: MediaStreamType): PlayerTrackMenu = if (type == MediaStreamType.Audio) audio else subtitles
}

/** Each metadata generation owns its result, so an obsolete worker cannot publish into a newer source. */
internal class PlayerTrackMenuPreparation(val sourceId: String?, val mediaStreams: List<MediaStream>) {
    var menus by mutableStateOf<PlayerTrackMenus?>(null)
        internal set
}

internal fun preparePlayerTrackMenus(mediaStreams: List<MediaStream>): PlayerTrackMenus {
    fun menu(type: MediaStreamType): PlayerTrackMenu {
        val streams = mediaStreams.filter { it.type == type }
        val labels = trackLabels(streams)
        return PlayerTrackMenu(streams, listOf(-1 to "Off") + streams.mapIndexed { i, stream -> stream.index to labels[i] })
    }
    return PlayerTrackMenus(menu(MediaStreamType.Audio), menu(MediaStreamType.Subtitle))
}

/** Prepare both menus when metadata arrives, independently of chooser, position, focus or selection. */
@Composable
internal fun rememberPlayerTrackMenus(sourceId: String?, mediaStreams: List<MediaStream>): PlayerTrackMenuPreparation {
    val preparation = remember(sourceId, mediaStreams) { PlayerTrackMenuPreparation(sourceId, mediaStreams) }
    LaunchedEffect(preparation) {
        preparation.menus = withContext(Dispatchers.Default) { preparePlayerTrackMenus(mediaStreams) }
    }
    return preparation
}
