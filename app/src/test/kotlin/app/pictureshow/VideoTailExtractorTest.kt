/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoTailExtractorTest {
    @Test
    fun extractionEofPublishesVideoTailAndSeekInvalidatesItWithoutChangingSamples() {
        val tail = VideoTail()
        var downstreamTimeUs = -1L
        val track = Proxy.newProxyInstance(TrackOutput::class.java.classLoader, arrayOf(TrackOutput::class.java)) { _, method, args ->
            if (method.name == "sampleMetadata") downstreamTimeUs = args!![0] as Long
            null
        } as TrackOutput
        val output = Proxy.newProxyInstance(ExtractorOutput::class.java.classLoader, arrayOf(ExtractorOutput::class.java)) { _, method, _ ->
            if (method.name == "track") track else null
        } as ExtractorOutput
        val input = Proxy.newProxyInstance(ExtractorInput::class.java.classLoader, arrayOf(ExtractorInput::class.java)) { _, _, _ ->
            null
        } as ExtractorInput
        var extractorOutput: ExtractorOutput? = null
        var eof = false
        val extractor = object : Extractor {
            override fun sniff(input: ExtractorInput) = true
            override fun init(output: ExtractorOutput) {
                extractorOutput = output
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                if (eof) return Extractor.RESULT_END_OF_INPUT
                val target = requireNotNull(extractorOutput)
                target.seekMap(SeekMap.Unseekable(100_000_000))
                target.track(1, C.TRACK_TYPE_VIDEO).sampleMetadata(90_000_000, 0, 0, 0, null)
                target.track(1, C.TRACK_TYPE_VIDEO).sampleMetadata(89_000_000, 0, 0, 0, null)
                target.track(2, C.TRACK_TYPE_TEXT).sampleMetadata(900_000_000, 0, 0, 0, null)
                eof = true
                return Extractor.RESULT_CONTINUE
            }
            override fun seek(position: Long, timeUs: Long) {
                eof = false
            }
            override fun release() = Unit
        }
        val wrapped = VideoTailExtractorsFactory(tail, ExtractorsFactory { arrayOf(extractor) }).createExtractors().single()
        wrapped.init(output)
        wrapped.read(input, PositionHolder())
        assertFalse(tail.extracted, "A consumed HTTP body alone does not mean extraction is complete")
        assertEquals(90_000_000L, tail.lastSampleUs, "B-frame ordering and subtitle duration do not move the video tail")
        assertEquals(100_000_000L, tail.durationUs)
        assertEquals(900_000_000L, downstreamTimeUs, "The observer forwards every sample unchanged")
        wrapped.read(input, PositionHolder())
        assertTrue(tail.extracted)
        wrapped.seek(0, 0)
        assertFalse(tail.extracted)
        assertEquals(C.TIME_UNSET, tail.lastSampleUs)
    }
}
