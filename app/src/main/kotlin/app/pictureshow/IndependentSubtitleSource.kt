/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Optional text has its own lifetime: publish tracks immediately, load only while selected,
 * and never contribute a buffering position, discontinuity, or error to the A/V period.
 * Parsed cues stay in the period for local seeks and off/on; releasing playback drops them.
 */
internal class IndependentSubtitleSource(
    private val config: MediaItem.SubtitleConfiguration,
    private val dataSources: DataSource.Factory,
    private val onLoaded: (Int, Int) -> Unit = { _, _ -> }
) : BaseMediaSource() {
    private val item = MediaItem.fromUri(config.uri)
    private val inputFormat = Format.Builder()
        .setId(config.id).setSampleMimeType(config.mimeType).setLanguage(config.language)
        .setSelectionFlags(config.selectionFlags).setRoleFlags(config.roleFlags).setLabel(config.label).build()
    private val parsers = DefaultSubtitleParserFactory()
    private val outputFormat = inputFormat.buildUpon()
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(config.mimeType)
        .setCueReplacementBehavior(parsers.getCueReplacementBehavior(inputFormat)).setSubsampleOffsetUs(0).build()

    override fun getMediaItem(): MediaItem = item
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(SinglePeriodTimeline(C.TIME_UNSET, true, false, false, null, item))
    }
    override fun maybeThrowSourceInfoRefreshError() = Unit
    override fun releaseSourceInternal() = Unit
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = Period()
    override fun releasePeriod(mediaPeriod: MediaPeriod) = (mediaPeriod as Period).release()

    private data class Sample(val timeUs: Long, val bytes: ByteArray)

    private inner class Period :
        MediaPeriod,
        Loader.Callback<SubtitleLoad> {
        private val tracks = TrackGroupArray(TrackGroup(outputFormat))
        private val loader = Loader("PictureshowSubtitle")
        private val streams = mutableListOf<Stream>()
        private var samples: List<Sample>? = null
        private var released = false

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = callback.onPrepared(this)
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = tracks
        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            sampleStreams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            for (i in selections.indices) {
                if (sampleStreams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                    streams.remove(sampleStreams[i])
                    sampleStreams[i] = null
                }
                if (sampleStreams[i] == null && selections[i] != null) {
                    sampleStreams[i] = Stream(positionUs).also(streams::add)
                    streamResetFlags[i] = true
                }
            }
            if (streams.isEmpty()) {
                if (loader.isLoading) loader.cancelLoading()
            } else {
                startLoading()
            }
            return positionUs
        }

        private fun startLoading() {
            if (!released && streams.isNotEmpty() && samples == null && !loader.isLoading) {
                loader.startLoading(SubtitleLoad(), this, Int.MAX_VALUE)
            }
        }

        override fun onLoadCompleted(loadable: SubtitleLoad, elapsedRealtimeMs: Long, loadDurationMs: Long) {
            samples = loadable.samples
            if (!released && streams.isNotEmpty()) {
                config.id?.substringAfterLast("server-subtitle:", "")?.toIntOrNull()?.let { onLoaded(it, loadable.attempts) }
            }
            // A seek/selection made while waiting chooses from the completed file on its own clock.
            streams.forEach { it.rewind(it.seekUs) }
        }
        override fun onLoadCanceled(loadable: SubtitleLoad, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean) {
            if (!released) startLoading() // Handles off/on while cancellation is still in flight.
        }
        override fun onLoadError(
            loadable: SubtitleLoad,
            elapsedRealtimeMs: Long,
            loadDurationMs: Long,
            error: IOException,
            errorCount: Int
        ): Loader.LoadErrorAction =
            Loader.createRetryAction(false, app.pictureshow.core.playback.ExternalSubtitleLoading.retryDelayMs(errorCount))

        fun release() {
            released = true
            streams.clear()
            samples = null
            loader.release()
        }
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long {
            streams.forEach { it.rewind(positionUs) }
            return positionUs
        }
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

        // Loading is driven by selection, independently of A/V load-control thresholds.
        override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit

        private inner class Stream(var seekUs: Long) : SampleStream {
            private var sentFormat = false
            private var index = 0
            fun rewind(positionUs: Long) {
                seekUs = positionUs
                // Include earlier samples: ASS/TTML replace/merge semantics can depend on them,
                // and TextRenderer resolves the cue active at the current playback timestamp.
                index = 0
            }
            override fun isReady(): Boolean = true
            override fun maybeThrowError() = Unit
            override fun skipData(positionUs: Long): Int = 0
            override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
                if (!sentFormat || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                    holder.format = outputFormat
                    sentFormat = true
                    return C.RESULT_FORMAT_READ
                }
                val available = samples ?: return C.RESULT_NOTHING_READ
                if (index == available.size) {
                    buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    val sample = available[index]
                    buffer.timeUs = sample.timeUs
                    buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
                    if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                        buffer.ensureSpaceForWrite(sample.bytes.size)
                        requireNotNull(buffer.data).put(sample.bytes)
                    }
                    if (readFlags and SampleStream.FLAG_PEEK == 0) index++
                }
                return C.RESULT_BUFFER_READ
            }
        }
    }

    private inner class SubtitleLoad : Loader.Loadable {
        @Volatile private var canceled = false
        var samples: List<Sample> = emptyList()
        var attempts: Int = 0
            private set
        override fun cancelLoad() {
            canceled = true
        }
        override fun load() {
            attempts++
            val data = dataSources.createDataSource()
            val parser = parsers.create(inputFormat)
            try {
                data.open(DataSpec(config.uri))
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (!canceled) {
                    val count = data.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (bytes.size() + count > MAX_SUBTITLE_BYTES) throw IOException("Subtitle exceeds memory limit")
                    bytes.write(buffer, 0, count)
                }
                if (canceled) return
                val encoded = mutableListOf<Sample>()
                val encoder = CueEncoder()
                parser.parse(bytes.toByteArray(), SubtitleParser.OutputOptions.allCues()) { cues ->
                    encoded += Sample(cues.startTimeUs, encoder.encode(cues.cues, cues.durationUs))
                }
                samples = encoded.sortedBy { it.timeUs }
            } catch (error: RuntimeException) {
                // Malformed optional text must have the same isolation as a network failure.
                throw IOException("Subtitle could not be parsed", error)
            } finally {
                parser.reset()
                data.close()
            }
        }
    }

    companion object {
        private const val MAX_SUBTITLE_BYTES = 16 * 1024 * 1024

        fun merge(
            primary: MediaSource,
            item: MediaItem,
            dataSources: DataSource.Factory,
            onLoaded: (Int, Int) -> Unit = { _, _ -> }
        ): MediaSource {
            val subtitles = requireNotNull(item.localConfiguration).subtitleConfigurations.map {
                IndependentSubtitleSource(it, dataSources, onLoaded)
            }
            return if (subtitles.isEmpty()) primary else MergingMediaSource(primary, *subtitles.toTypedArray())
        }
    }
}
