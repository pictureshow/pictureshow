/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.VideoRangeType

/**
 * The format badges a detail page shows for an item's default version, in a fixed order: resolution, dynamic range,
 * channel layout, spatial audio. Only what the server reported: VideoRangeType for the range, Channels/ChannelLayout for
 * the layout, AudioSpatialFormat (or, from servers before it existed, a Profile naming Atmos or DTS:X) for spatial audio.
 * SDR, stereo and unknown values add nothing; a badge that only says "ordinary" is noise.
 */
fun mediaBadges(source: MediaSourceInfo?): List<String> {
    if (source == null) return emptyList()
    val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Video && !it.isExternal }
    val audio = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio && it.index == source.defaultAudioStreamIndex }
        ?: source.mediaStreams.firstOrNull { it.type == MediaStreamType.Audio }
    return buildList {
        video?.let { stream ->
            // Scope and cropped 4K masters keep the width and lose height (3840x1600), so either axis counts.
            if ((stream.width ?: 0) >= 3200 || (stream.height ?: 0) >= 2000) add("4K")
            when (stream.videoRangeType) {
                VideoRangeType.DOVI, VideoRangeType.DOVIWithHDR10, VideoRangeType.DOVIWithHLG, VideoRangeType.DOVIWithSDR,
                VideoRangeType.DOVIWithEL, VideoRangeType.DOVIWithHDR10Plus, VideoRangeType.DOVIWithELHDR10Plus -> add("Dolby Vision")
                VideoRangeType.HDR10Plus -> add("HDR10+")
                VideoRangeType.HDR10 -> add("HDR10")
                VideoRangeType.HLG -> add("HLG")
                // DOVIInvalid: the server found unusable Dolby Vision metadata; claiming any range would be a guess.
                else -> Unit
            }
        }
        audio?.let { stream ->
            channelBadge(stream)?.let(::add)
            spatialBadge(stream)?.let(::add)
        }
    }
}

/** The badges for an item's default (first) version. */
fun mediaBadges(item: MediaItem): List<String> = mediaBadges(item.mediaSources.firstOrNull())

private fun channelBadge(stream: MediaStream): String? {
    val layout = stream.channelLayout?.lowercase().orEmpty()
    return when {
        layout.startsWith("7.1") || stream.channels == 8 -> "7.1"
        layout.startsWith("5.1") || stream.channels == 6 -> "5.1"
        else -> null
    }
}

private fun spatialBadge(stream: MediaStream): String? {
    val format = stream.audioSpatialFormat
    val profile = stream.profile.orEmpty()
    return when {
        format.equals("DolbyAtmos", ignoreCase = true) -> "Atmos"
        format.equals("DTSX", ignoreCase = true) -> "DTS:X"
        // A server that reports the field has decided, "None" included; only an older one falls back to the profile.
        format != null -> null
        profile.contains("Dolby Atmos", ignoreCase = true) -> "Atmos"
        profile.contains("DTS:X", ignoreCase = true) -> "DTS:X"
        else -> null
    }
}

/**
 * PLAY-17: on the page a viewing returned to after playing this film or episode to its natural end ([finished]), Play
 * reads "Play again", starts at the beginning, and Play from start isn't offered. A series or season keeps naming the
 * episode it plays; Stop and a SyncPlay group ending never set [finished].
 */
fun detailPlaysAgain(item: MediaItem, finished: Boolean): Boolean = finished && !item.isMissing && item.type !in setOf("Series", "Season")

/**
 * The primary detail action's words. A series or season names the episode Play will start ("Play S1:E1 · A Quiet
 * Morning", "Resume S1:E2 · Arrivals"), so nobody has to guess; a movie or episode keeps "Play" or "Resume from 12:34".
 */
fun detailPlayLabel(item: MediaItem, playItem: MediaItem?, finished: Boolean = false): String {
    // CAT-21: nothing plays an episode the library doesn't have; the primary says why instead.
    if (item.isMissing) return "Not in your library"
    if (detailPlaysAgain(item, finished)) return "Play again"
    val position = playItem?.userData?.playbackPositionTicks ?: 0
    if (playItem == null || item.type !in setOf("Series", "Season") || playItem.type != "Episode") {
        return if (position > 0) resumeLabel(position) else "Play"
    }
    val verb = if (position > 0) "Resume" else "Play"
    val name = playItem.name.takeIf { it.isNotBlank() && it != "Untitled" }
    return listOfNotNull("$verb ${episodeLabel(playItem)}", name).joinToString(" · ")
}
