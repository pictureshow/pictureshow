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

/** needsConnection: rows this download can't play, listed so the viewer can be offered the server (OFF-05). */
internal data class PlayerTrackMenu(
    val streams: List<MediaStream>,
    val choices: List<Pair<Int, String>>,
    val needsConnection: Set<Int> = emptySet()
)

internal data class PlayerTrackMenus(val audio: PlayerTrackMenu, val subtitles: PlayerTrackMenu) {
    operator fun get(type: MediaStreamType): PlayerTrackMenu = if (type == MediaStreamType.Audio) audio else subtitles
}

/** Each metadata generation owns its result, so an obsolete worker cannot publish into a newer source. */
internal class PlayerTrackMenuPreparation(val sourceId: String?, val mediaStreams: List<MediaStream>) {
    var menus by mutableStateOf<PlayerTrackMenus?>(null)
        internal set
}

internal const val NEEDS_CONNECTION = "needs a connection"

internal fun preparePlayerTrackMenus(mediaStreams: List<MediaStream>, undownloaded: List<MediaStream> = emptyList()): PlayerTrackMenus {
    fun menu(type: MediaStreamType): PlayerTrackMenu {
        val streams = mediaStreams.filter { it.type == type }
        val missing = undownloaded.filter { it.type == type }
        val needed = missing.map { it.index }.toSet()
        // A caption whose file never finished is listed once, as what it is.
        val local = streams.filter { it.index !in needed }
        val labels = trackLabels(local + missing)
        val rows = (local + missing).mapIndexed { i, stream ->
            stream.index to if (stream.index in needed) "${labels[i]} · $NEEDS_CONNECTION" else labels[i]
        }
        return PlayerTrackMenu(streams, listOf(-1 to "Off") + rows, needed)
    }
    return PlayerTrackMenus(menu(MediaStreamType.Audio), menu(MediaStreamType.Subtitle))
}

/** Prepare both menus when metadata arrives, independently of chooser, position, focus or selection. */
@Composable
internal fun rememberPlayerTrackMenus(
    sourceId: String?,
    mediaStreams: List<MediaStream>,
    undownloaded: List<MediaStream> = emptyList()
): PlayerTrackMenuPreparation {
    val preparation = remember(sourceId, mediaStreams) { PlayerTrackMenuPreparation(sourceId, mediaStreams) }
    LaunchedEffect(preparation) {
        preparation.menus = withContext(Dispatchers.Default) { preparePlayerTrackMenus(mediaStreams, undownloaded) }
    }
    return preparation
}
