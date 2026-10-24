/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class
)

package app.pictureshow

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import java.io.IOException

/**
 * FFmpeg's copied audio may start before the accurate video seek. TS's first-PES origin would
 * move the picture, captions and absolute player clock by that preroll. Anchor all extracted
 * tracks to the first video sample instead. Negative audio timestamps retain A/V sync and let
 * Media3 discard decode-only preroll before rendering, without an audio re-encode.
 * Each replacement has its own extractor; this never adjusts an HLS segment or an external caption.
 */
internal class VideoClockExtractor(extractor: Extractor, private val diagnostic: (String) -> Unit = {}) : ForwardingExtractor(extractor) {
    private var originUs: Long? = null
    private var waitingBytes = 0L
    private val tracks = mutableMapOf<Int, ClockTrack>()

    override fun init(output: ExtractorOutput) {
        super.init(object : ForwardingExtractorOutput(output) {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { ClockTrack(output.track(id, type), type) }
            override fun seekMap(seekMap: SeekMap) {
                // A PCR byte map describes the unadjusted stream. All user seeks replace the encode;
                // a near-tail residual is discarded forward by ClippingMediaSource on the same clock.
                output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // Progressive replacements are read forward even when an HTTP cache supplies a length.
        // Suppress TsExtractor's PCR duration scan (which otherwise seeks to the response tail).
        val result = super.read(
            object : ForwardingExtractorInput(input) {
                override fun getLength(): Long = C.LENGTH_UNSET.toLong()
            },
            seekPosition
        )
        if (result == Extractor.RESULT_END_OF_INPUT && originUs == null) throw IOException("Video clock missing at end of stream")
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        originUs = null
        waitingBytes = 0
        tracks.values.forEach { it.reset() }
        super.seek(position, timeUs)
    }

    private inner class ClockTrack(private val output: TrackOutput, private val type: Int) : ForwardingTrackOutput(output) {
        private var written = 0L
        private val pending = mutableListOf<Sample>()

        fun reset() {
            written = 0
            pending.clear()
        }

        private fun added(count: Int) {
            if (count <= 0) return
            written += count
            if (originUs == null) {
                waitingBytes += count
                // Bound malformed/audio-only input while waiting for video. Fail as a source error,
                // rather than consuming arbitrary memory or reporting a successful viewing end.
                if (waitingBytes > 16 * 1024 * 1024) throw IOException("Video clock missing from stream prefix")
            }
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            output.sampleData(input, length, allowEndOfInput, sampleDataPart).also(::added)

        override fun sampleData(data: ParsableByteArray, length: Int) = sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            output.sampleData(data, length, sampleDataPart)
            added(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val sample = Sample(timeUs, flags, size, written - offset, cryptoData)
            if (originUs == null && type == C.TRACK_TYPE_VIDEO) {
                originUs = timeUs
                diagnostic("originUs=$timeUs prefixBytes=$waitingBytes")
                tracks.values.forEach { it.flush() }
            }
            if (originUs == null) {
                if (pending.size >= 4096) throw IOException("Video clock missing from stream prefix")
                pending += sample
            } else {
                emit(sample)
            }
        }

        fun flush() {
            pending.forEach(::emit)
            pending.clear()
        }

        private fun emit(sample: Sample) {
            output.sampleMetadata(
                sample.timeUs - requireNotNull(originUs),
                sample.flags,
                sample.size,
                Math.toIntExact(written - sample.end),
                sample.cryptoData
            )
        }
    }

    private data class Sample(val timeUs: Long, val flags: Int, val size: Int, val end: Long, val cryptoData: TrackOutput.CryptoData?)
}

internal class VideoClockExtractorsFactory(private val diagnostic: (String) -> Unit) : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    override fun setSubtitleParserFactory(subtitleParserFactory: androidx.media3.extractor.text.SubtitleParser.Factory): ExtractorsFactory =
        apply {
            delegate.setSubtitleParserFactory(subtitleParserFactory)
        }
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
        apply {
            delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        }
    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        delegate.setParseHagcMetadata(parseHagcMetadata)
    }
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))
    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { if (it is TsExtractor) VideoClockExtractor(it, diagnostic) else it }.toTypedArray()
}
