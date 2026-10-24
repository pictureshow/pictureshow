/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.playback.PlayDecision

internal fun playbackMetadata(item: MediaItem): MediaMetadata {
    val episode = item.type == "Episode"
    val subtitle = if (episode) {
        item.seriesName?.takeIf(String::isNotBlank) ?: "Episode"
    } else if (item.type == "Movie") {
        "Movie"
    } else {
        "Video"
    }
    val title = if (episode) listOfNotNull(episodeLabel(item), item.name).filter(String::isNotBlank).joinToString(" · ") else item.name
    return MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).setArtist(subtitle).setSubtitle(subtitle).build()
}

/** A metadata-only replacement preserves the URI, tracks and position, including when the transport is replaced. */
internal fun updatePlaybackMetadata(player: Player, item: MediaItem) {
    updatePlaybackMetadata(player, playbackMetadata(item))
}

internal fun updatePlaybackMetadata(player: Player, metadata: MediaMetadata) {
    val current = player.currentMediaItem ?: return
    if (current.mediaMetadata != metadata && player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
        player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(metadata).build())
    }
}

/** Source replacement stays delegated; metadata is ready before the system session publishes that source. */
internal class MetadataPlaybackOutput(private val output: PlaybackOutput, private val metadata: () -> MediaMetadata) :
    PlaybackOutput by output {
    override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
        PlaybackMediaSession.withMetadata(metadata()) {
            output.open(decision, positionMs, audioIndex, subtitleIndex, paused)
        }
    }
}
