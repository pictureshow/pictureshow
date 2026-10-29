/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor

/** A final TS segment can declare an audio PID but contain no audio packets (and hence no format).
 * Prepare using the preceding segment, then let Media3 select tracks at the unchanged requested
 * position. No preroll reaches a renderer. This also covers seeks queued while preparing.
 */
internal class HlsPreparationSource(source: MediaSource, private val onBootstrap: (Long, Long) -> Unit) : WrappingMediaSource(source) {
    private var finalStartUs = Long.MAX_VALUE
    private var precedingStartUs = 0L

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        val manifest = newTimeline.getWindow(0, Timeline.Window()).manifest as? HlsManifest
        val playlist = manifest?.mediaPlaylist
        val segments = playlist?.segments.orEmpty()
        if (playlist?.hasEndTag == true && segments.size > 1) {
            finalStartUs = segments.last().relativeStartTimeUs
            precedingStartUs = segments[segments.lastIndex - 1].relativeStartTimeUs
        } else {
            finalStartUs = Long.MAX_VALUE
        }
        super.onChildSourceInfoRefreshed(newTimeline)
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        PreparingPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) = mediaSource.releasePeriod((mediaPeriod as PreparingPeriod).child)

    private inner class PreparingPeriod(val child: MediaPeriod) : MediaPeriod by child {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            val prepareUs = if (positionUs >= finalStartUs) precedingStartUs else positionUs
            if (prepareUs != positionUs) onBootstrap(positionUs, prepareUs)
            child.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@PreparingPeriod)
                    override fun onContinueLoadingRequested(source: MediaPeriod) = callback.onContinueLoadingRequested(this@PreparingPeriod)
                },
                prepareUs
            )
        }
    }

    companion object {
        fun create(
            item: MediaItem,
            dataSources: DataSource.Factory,
            mediaDataSources: DataSource.Factory = dataSources,
            onBootstrap: (Long, Long) -> Unit
        ): MediaSource {
            // Chunkless preparation would finish before the bootstrap segment supplies formats.
            val hls = HlsPreparationSource(
                HlsMediaSource.Factory(
                    androidx.media3.exoplayer.hls.HlsDataSourceFactory { type ->
                        // Playlists can wait for encoder startup; subtitles use their ordinary source.
                        if (type == androidx.media3.common.C.DATA_TYPE_MEDIA ||
                            type == androidx.media3.common.C.DATA_TYPE_MEDIA_INITIALIZATION
                        ) {
                            mediaDataSources.createDataSource()
                        } else {
                            dataSources.createDataSource()
                        }
                    }
                ).setAllowChunklessPreparation(false).createMediaSource(item),
                onBootstrap
            )
            val parsers = DefaultSubtitleParserFactory()
            val subtitles = requireNotNull(item.localConfiguration).subtitleConfigurations.map { config ->
                val format = Format.Builder()
                    .setId(config.id).setSampleMimeType(config.mimeType).setLanguage(config.language)
                    .setSelectionFlags(config.selectionFlags).setRoleFlags(config.roleFlags).setLabel(config.label).build()
                ProgressiveMediaSource.Factory(
                    dataSources,
                    ExtractorsFactory { arrayOf(SubtitleExtractor(parsers.create(format), format)) }
                ).setLoadOnlySelectedTracks(true)
                    .createMediaSource(MediaItem.fromUri(config.uri))
            }
            return if (subtitles.isEmpty()) hls else MergingMediaSource(hls, *subtitles.toTypedArray())
        }
    }
}
