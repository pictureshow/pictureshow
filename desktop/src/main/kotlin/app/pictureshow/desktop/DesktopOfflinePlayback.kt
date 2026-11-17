/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.MediaItem
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackEngine
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import java.io.File
import java.net.URI

/**
 * OFF-05/11: a download plays through the same player as a stream. Its decision points at the file on this computer, carries
 * no headers and no play session, so nothing in the viewing can reach the server; only the tracks that were downloaded are in
 * its source, and a sidecar's file is its delivery URL.
 */
internal fun desktopLocalDecision(entry: OfflineEntry, media: File, file: (String) -> File): PlayDecision {
    val source = MediaSourceInfo(
        id = entry.sourceId ?: entry.itemId,
        container = entry.container,
        size = media.length(),
        runTimeTicks = entry.runtimeTicks,
        supportsDirectPlay = true,
        mediaStreams = entry.tracks.filter { it.available }.sortedWith(localTrackOrder(entry)).map { entry.localStream(it, file) }
    )
    return PlayDecision(entry.itemId, source, PlayMethod.DirectPlay, media.toURI().toString(), emptyMap(), null, engine = PlaybackEngine.Mpv)
}

/** The muxed audio comes first, then sidecars, each by server index: the order mpv numbers them in once the sidecars are added. */
private fun localTrackOrder(entry: OfflineEntry) = compareBy<OfflineTrack>({ it.type }, { if (it.type == "audio" && it.resourceId != null) 1 else 0 }, { it.serverIndex })

private fun OfflineEntry.localStream(track: OfflineTrack, file: (String) -> File): MediaStream {
    val sidecar = track.resourceId?.let { id -> resources.firstOrNull { it.id == id } }
    val isSubtitle = track.type == "subtitle"
    val sidecarUrl = sidecar?.let { file(it.path).toURI().toString() }
    return MediaStream(
        type = if (isSubtitle) MediaStreamType.Subtitle else MediaStreamType.Audio,
        index = track.serverIndex,
        codec = track.codec,
        language = track.language,
        displayTitle = track.label,
        title = track.label,
        // A sidecar audio track is numbered with the file's own by mpv, so only a subtitle sidecar counts as external.
        isExternal = isSubtitle && sidecar != null,
        isDefault = track.isDefault,
        isForced = track.isForced,
        isTextSubtitleStream = isSubtitle && sidecar != null,
        deliveryMethod = if (isSubtitle) (if (sidecar != null) SubtitleDeliveryMethod.External else SubtitleDeliveryMethod.Embed) else null,
        deliveryUrl = sidecarUrl
    )
}

/** The file a local decision or a local sidecar points at; null for anything the server delivers. */
internal fun PlayDecision.localFile(): File? = url.localFileUrl()
internal fun MediaStream.localFile(): File? = deliveryUrl?.localFileUrl()
private fun String.localFileUrl(): File? = takeIf { it.startsWith("file:") }?.let { runCatching { File(URI(it)) }.getOrNull() }

/** What mpv is told for a local file: sidecar audio to load with it, and the starting aid/sid or the subtitle file to add. */
internal data class DesktopLocalTracks(val audioFiles: List<File>, val aid: String, val sid: String, val subtitleFile: File?)

internal fun desktopLocalTracks(streams: List<MediaStream>, audio: Int, subtitle: Int): DesktopLocalTracks {
    val audios = streams.filter { it.type == MediaStreamType.Audio }
    val aid = audios.indexOfFirst { it.index == audio }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "auto"
    val (sid, file) = desktopLocalSubtitle(streams, subtitle)
    return DesktopLocalTracks(audios.mapNotNull { it.localFile() }, aid, sid, file)
}

/** A local subtitle is an embedded ordinal, or a downloaded WebVTT file to add (then mpv selects it itself). */
internal fun desktopLocalSubtitle(streams: List<MediaStream>, index: Int): Pair<String, File?> {
    val selected = streams.firstOrNull { it.type == MediaStreamType.Subtitle && it.index == index } ?: return "no" to null
    selected.localFile()?.let { return "no" to it }
    val embedded = streams.filter { it.type == MediaStreamType.Subtitle && it.localFile() == null }
    return (embedded.indexOfFirst { it.index == index }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "no") to null
}

/** Just enough of an item for the player's title, its watched state and next-episode lookup. */
internal fun OfflineEntry.playerItem(place: ItemUserData = ItemUserData()): MediaItem = MediaItem(
    id = itemId,
    name = labels.title,
    type = when (kind) {
        OfflineMediaKind.episode -> "Episode"
        OfflineMediaKind.audio -> "Audio"
        OfflineMediaKind.movie -> "Movie"
    },
    overview = labels.overview,
    productionYear = labels.year,
    runTimeTicks = runtimeTicks,
    seriesName = labels.seriesName,
    seriesId = labels.seriesId,
    seasonId = labels.seasonId,
    indexNumber = labels.episodeNumber,
    parentIndexNumber = labels.seasonNumber,
    userData = place
)

/** The media file of a ready download, or null when it isn't ready or isn't video (music has its own player). */
internal fun OfflineEntry.readyMedia(): String? = takeIf { job.phase == OfflinePhase.ready && kind != OfflineMediaKind.audio }
    ?.resources?.firstOrNull { it.role == OfflineResourceRole.media }?.path
