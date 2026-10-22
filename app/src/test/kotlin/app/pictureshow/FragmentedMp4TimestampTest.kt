/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic boxes and dummy sample bytes isolate extraction; no captured media is included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FragmentedMp4TimestampTest {
    @Test
    fun negativeVersionOneDecodeTimeIsRejectedBeforeTheOpusDecoder() {
        val error = assertFailsWith<IllegalStateException> { extract(decodeTime = -48) }
        assertEquals("Top bit not zero: -48", error.message)
    }

    @Test
    fun otherwiseIdenticalNonnegativeDecodeTimeExtractsTheSample() {
        val output = extract(decodeTime = 480)
        val audio = output.trackOutputs.valueAt(0)
        assertEquals(1, audio.sampleCount)
        assertEquals(10_000L, audio.getSampleTimeUs(0))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), audio.getSampleData(0))
        assertEquals(MimeTypes.AUDIO_OPUS, audio.lastFormat!!.sampleMimeType)
    }

    private fun extract(decodeTime: Long): FakeExtractorOutput {
        // Supply the initialized audio track directly so the only changed field is the fragment's decode time.
        val track = Track.Builder().setId(1).setType(C.TRACK_TYPE_AUDIO).setTimescale(48_000)
            .setMovieTimescale(1_000).setFormat(
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS)
                    .setSampleRate(48_000).setChannelCount(2).build()
            ).build()
        val extractor = FragmentedMp4Extractor(DefaultSubtitleParserFactory(), 0, null, track, emptyList(), null)
        val output = FakeExtractorOutput()
        val input = FakeExtractorInput.Builder().setData(fragment(decodeTime)).build()
        extractor.init(output)
        try {
            val position = PositionHolder()
            while (true) {
                when (extractor.read(input, position)) {
                    Extractor.RESULT_END_OF_INPUT -> return output
                    Extractor.RESULT_SEEK -> input.setPosition(position.position.toInt())
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun fragment(decodeTime: Long): ByteArray {
        val header = box("tfhd", ints(0x020018, 1, 960, 4))
        val timestamp = box("tfdt", ints(0x01000000) + ByteBuffer.allocate(8).putLong(decodeTime).array())
        fun moof(dataOffset: Int) = box(
            "moof",
            box("mfhd", ints(0, 1)) +
                box("traf", header + timestamp + box("trun", ints(1, 1, dataOffset)))
        )
        val offset = moof(0).size + 8
        return moof(offset) + box("mdat", byteArrayOf(1, 2, 3, 4))
    }

    private fun box(type: String, payload: ByteArray): ByteArray = ints(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply {
        values.forEach { putInt(it) }
    }.array()
}
