/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem as MediaThreeItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import java.io.File

/** The local file's side of a normal player: same session, same controls, no server (OFF-11). */
internal class OfflinePlaybackBinding(val gateway: PlaybackGateway, private val sidecars: List<File>) {
    fun output(context: Context, server: Server, title: String): Media3PlaybackOutput =
        Media3PlaybackOutput(context, server, title, DefaultDataSource.Factory(context), sourceWrapper = {
            OfflineMergingFactory(context, it, sidecars)
        })
}

/** Provided by the local player route only; online playback reads null and nothing changes for it. */
internal val LocalOfflinePlayback = staticCompositionLocalOf<OfflinePlaybackBinding?> { null }

/** A converted second language lives in its own file; to the player it's one more audio track. */
internal class OfflineMergingFactory(
    private val context: Context,
    private val base: MediaSource.Factory,
    private val sidecars: List<File>
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { base.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        base.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = base.supportedTypes

    override fun createMediaSource(mediaItem: MediaThreeItem): MediaSource {
        val main = base.createMediaSource(mediaItem)
        if (sidecars.isEmpty()) return main
        val local = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
        val audio = sidecars.map { local.createMediaSource(MediaThreeItem.fromUri(Uri.fromFile(it))) }
        return MergingMediaSource(true, false, main, *audio.toTypedArray())
    }
}

/** Reports become outbox records. Nothing here ever opens a socket. */
internal class OfflinePlaybackGateway(
    private val entry: OfflineEntry,
    private val mediaFile: File,
    private val resourceFile: (String) -> File?,
    private val progress: OfflineProgressStore,
    private val sequence: Long,
    private val onExit: () -> Unit
) : PlaybackGateway {
    override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> = ClientResult.Success(
        PlayOutcome.Play(
            PlayDecision(
                entry.itemId,
                offlineSourceInfo(entry, mediaFile.length(), resourceFile),
                PlayMethod.DirectPlay,
                Uri.fromFile(mediaFile).toString(),
                emptyMap(),
                null
            )
        )
    )

    override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> = ClientResult.Success(Unit)

    override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
        progress.viewing(entry.itemId, sequence, info.positionTicks)
        return ClientResult.Success(Unit)
    }

    override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
        progress.viewing(entry.itemId, sequence, info.positionTicks)
        onExit()
        return ClientResult.Success(Unit)
    }

    override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> = ClientResult.Success(Unit)

    override suspend fun played(): ClientResult<Unit> {
        progress.completed(entry.itemId, sequence)
        // Stopped ran first and its sync found nothing to say about the ending.
        onExit()
        return ClientResult.Success(Unit)
    }
}

/** The player reads tracks from here exactly as it reads a server's. Tracks that weren't downloaded aren't offered. */
internal fun offlineSourceInfo(entry: OfflineEntry, size: Long, resourceFile: (String) -> File?): MediaSourceInfo {
    fun stream(track: OfflineTrack): MediaStream {
        val sidecar = track.resourceId?.let { id -> entry.resources.firstOrNull { it.id == id } }
        val isSubtitle = track.type == "subtitle"
        val external = isSubtitle && sidecar != null
        return MediaStream(
            type = if (isSubtitle) MediaStreamType.Subtitle else MediaStreamType.Audio,
            index = track.serverIndex,
            codec = track.codec,
            language = track.language,
            displayTitle = track.label,
            title = track.label,
            isExternal = external,
            isDefault = track.isDefault,
            isForced = track.isForced,
            isTextSubtitleStream = external,
            deliveryMethod = if (isSubtitle) (if (external) SubtitleDeliveryMethod.External else SubtitleDeliveryMethod.Embed) else null,
            deliveryUrl = if (external) resourceFile(sidecar.id)?.let { Uri.fromFile(it).toString() } else null
        )
    }
    return MediaSourceInfo(
        id = entry.sourceId ?: entry.itemId,
        container = entry.container,
        size = size,
        runTimeTicks = entry.runtimeTicks,
        supportsDirectPlay = true,
        mediaStreams = entry.tracks.filter { it.available }.sortedBy { it.serverIndex }.map(::stream)
    )
}

/** Just enough of an item for the player's title, and for its queue to know it's alone. */
internal fun OfflineEntry.playerItem(): MediaItem = MediaItem(
    id = itemId,
    name = labels.title,
    type = if (kind == OfflineMediaKind.episode) {
        "Episode"
    } else if (kind == OfflineMediaKind.audio) {
        "Audio"
    } else {
        "Movie"
    },
    overview = labels.overview,
    productionYear = labels.year,
    runTimeTicks = runtimeTicks,
    seriesName = labels.seriesName,
    seriesId = labels.seriesId,
    seasonId = labels.seasonId,
    indexNumber = labels.episodeNumber,
    parentIndexNumber = labels.seasonNumber
)

/** Sidecar audio in the order the player must see it: after the muxed track, by server index. */
internal fun OfflineEntry.sidecarAudio(file: (String) -> File): List<File> = tracks
    .filter { it.type == "audio" && it.available && it.resourceId != null }
    .sortedBy { it.serverIndex }
    .mapNotNull { track ->
        resources.firstOrNull { it.id == track.resourceId && it.role == OfflineResourceRole.audio }?.let { file(it.path) }
    }
