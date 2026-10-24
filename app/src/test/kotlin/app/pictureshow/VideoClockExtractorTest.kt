/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class VideoClockExtractorTest {
    @Test
    fun realTsWithReorderedVideoAndCopiedAacUsesVideoZeroAndDiscardsAudioBeforeTheTarget() {
        // Original generated test pattern, 60->30 fps, AAC6 copied across a 733 ms accurate seek.
        // The TS mux starts audio at 1.400 s and video PTS at 2.133 s, with reordered video DTS.
        val bytes = requireNotNull(javaClass.getResourceAsStream("/playback/copied-audio-preroll.ts")).use { it.readBytes() }
        val source = ByteArrayDataSource(bytes)
        source.open(DataSpec(android.net.Uri.parse("memory:test")))
        val input = DefaultExtractorInput(source, 0, bytes.size.toLong())
        val output = Captured()
        val audioQueue = SampleQueue.createWithoutDrm(DefaultAllocator(true, 1024))
        // This models a clipped/reset queue. Fresh-open renderer discard is covered by CopiedAudioOutputTest.
        audioQueue.setStartTimeUs(0)
        val extractor = VideoClockExtractor(TsExtractor(DefaultSubtitleParserFactory()))
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val captured = output.track(id, type)
                if (type != C.TRACK_TYPE_AUDIO) return captured
                return object : androidx.media3.extractor.ForwardingTrackOutput(audioQueue) {
                    override fun format(format: Format) {
                        super.format(format)
                        captured.format(format)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                        output.audio.times += timeUs
                    }
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
        try {
            val seek = PositionHolder()
            var result = Extractor.RESULT_CONTINUE
            var iterations = 0
            while (result != Extractor.RESULT_END_OF_INPUT && iterations++ < 10_000) {
                result = extractor.read(input, seek)
                assertFalse(result == Extractor.RESULT_SEEK, "A server-seek stream must not seek to a PCR byte offset")
            }
            assertEquals(Extractor.RESULT_END_OF_INPUT, result)
            assertEquals(0L, output.video.times.first(), "Video PTS, rather than the first audio PES or video DTS, is zero")
            assertEquals(-733_000L, output.audio.times.first())
            assertEquals(MimeTypes.AUDIO_AAC, output.audio.mime)
            assertEquals(13_666L, audioQueue.firstTimestampUs, "A clipped Media3 SampleQueue must discard the copied preroll")
            assertFalse(output.seekable)
        } finally {
            extractor.release()
            audioQueue.release()
            source.close()
        }
    }

    @Test
    fun audioPrerollDoesNotSetTheClockAndDeferredBytesKeepTheirSampleBoundaries() {
        val input = Samples()
        val output = Captured()
        VideoClockExtractor(input).init(output)
        input.audio.sampleData(ParsableByteArray(byteArrayOf(10, 11, 20, 21, 30, 31)), 6)
        input.audio.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 2, 4, null)
        input.audio.sampleMetadata(720_000, C.BUFFER_FLAG_KEY_FRAME, 2, 2, null)
        // More bytes can arrive after the metadata but before video establishes the shared origin.
        input.audio.sampleData(ParsableByteArray(byteArrayOf(40, 41)), 2)
        assertEquals(emptyList(), output.audio.times)
        input.video.sampleMetadata(731_000, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        input.audio.sampleMetadata(741_333, C.BUFFER_FLAG_KEY_FRAME, 2, 0, null)
        input.video.sampleMetadata(764_333, 0, 0, 0, null)
        assertEquals(listOf(-731_000L, -11_000L, 10_333L), output.audio.times)
        assertEquals(listOf(listOf<Byte>(10, 11), listOf<Byte>(20, 21), listOf<Byte>(40, 41)), output.audio.samples)
        assertEquals(listOf(0L, 33_333L), output.video.times)
        assertEquals("audio/mp4a-latm", output.audio.mime)
        assertFalse(output.seekable, "The replacement controller owns seeking, not a PCR byte map")
    }

    @Test
    fun newReadFromZeroReestablishesTheOriginInsteadOfReusingOldPreroll() {
        val input = Samples()
        val output = Captured()
        val extractor = VideoClockExtractor(input)
        extractor.init(output)
        input.video.sampleMetadata(731_000, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        extractor.seek(0, 0)
        input.audio.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        input.video.sampleMetadata(190_000, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)
        assertEquals(listOf(0L, 0L), output.video.times)
        assertEquals(listOf(-190_000L), output.audio.times)
    }

    @Test
    fun anEmptyOrTruncatedVideoStreamCannotBecomeSuccessfulAudioOnlyEnd() {
        val input = Samples()
        val extractor = VideoClockExtractor(input)
        extractor.init(Captured())
        // The delegate does not read input; null is supplied by a Java proxy solely at this port.
        val empty = java.lang.reflect.Proxy.newProxyInstance(
            ExtractorInput::class.java.classLoader,
            arrayOf(ExtractorInput::class.java)
        ) { _, _, _ -> throw AssertionError("No input read expected") } as ExtractorInput
        assertFailsWith<IOException> { extractor.read(empty, PositionHolder()) }
    }

    private class Samples : Extractor {
        lateinit var audio: TrackOutput
        lateinit var video: TrackOutput
        override fun init(output: ExtractorOutput) {
            audio = output.track(1, C.TRACK_TYPE_AUDIO)
            video = output.track(2, C.TRACK_TYPE_VIDEO)
            audio.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(6).build())
            video.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
            output.seekMap(SeekMap.Unseekable(60_000_000))
            output.endTracks()
        }
        override fun sniff(input: ExtractorInput) = true
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private class Captured : ExtractorOutput {
        val audio = Track()
        val video = Track()
        var seekable = true
        override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) audio else video
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            seekable = seekMap.isSeekable || seekMap.durationUs != C.TIME_UNSET
        }
    }

    private class Track : TrackOutput {
        val times = mutableListOf<Long>()
        val samples = mutableListOf<List<Byte>>()
        val bytes = mutableListOf<Byte>()
        var mime: String? = null
        override fun format(format: Format) {
            mime = format.sampleMimeType
        }
        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            throw AssertionError("Unexpected reader")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            bytes.addAll(buffer.toList())
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times += timeUs
            samples += bytes.subList(bytes.size - offset - size, bytes.size - offset).toList()
        }
    }
}
