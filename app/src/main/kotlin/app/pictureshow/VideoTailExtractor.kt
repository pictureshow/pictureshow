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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/** Actual extracted video timestamps, independent of container runtime and subtitle duration. */
internal class VideoTail {
    @Volatile var lastSampleUs: Long = C.TIME_UNSET
        private set

    @Volatile var durationUs: Long = C.TIME_UNSET

    @Volatile var extracted = false
        private set

    fun reset() {
        extracted = false
        lastSampleUs = C.TIME_UNSET
    }

    fun sample(timeUs: Long) {
        lastSampleUs = if (lastSampleUs == C.TIME_UNSET) timeUs else maxOf(lastSampleUs, timeUs)
    }

    fun complete() {
        extracted = true
    }
}

internal class VideoTailExtractorsFactory(
    private val tail: VideoTail,
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {
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
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map { extractor ->
        object : ForwardingExtractor(extractor) {
            override fun init(output: ExtractorOutput) {
                super.init(object : ForwardingExtractorOutput(output) {
                    override fun seekMap(seekMap: SeekMap) {
                        tail.durationUs = seekMap.durationUs
                        super.seekMap(seekMap)
                    }
                    override fun track(id: Int, type: Int): TrackOutput {
                        val track = output.track(id, type)
                        if (type != C.TRACK_TYPE_VIDEO) return track
                        return object : ForwardingTrackOutput(track) {
                            override fun sampleMetadata(
                                timeUs: Long,
                                flags: Int,
                                size: Int,
                                offset: Int,
                                cryptoData: TrackOutput.CryptoData?
                            ) {
                                tail.sample(timeUs)
                                super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                            }
                        }
                    }
                })
            }
            override fun seek(position: Long, timeUs: Long) {
                tail.reset()
                super.seek(position, timeUs)
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = super.read(input, seekPosition).also {
                if (it == Extractor.RESULT_END_OF_INPUT) tail.complete()
            }
        }
    }.toTypedArray()
}
