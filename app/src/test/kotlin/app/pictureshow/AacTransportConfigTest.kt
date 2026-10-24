/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Generated transport streams exercise the actual ADTS reader in both TS modes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AacTransportConfigTest {
    @Test
    fun hlsAndProgressiveCopiesAndReencodedSurroundHaveIdenticalAudioSpecificConfig() {
        for (fixture in listOf("copied-audio-preroll.ts", "aac-fdk-surround.ts", "aac-stereo.ts")) {
            val channels = if (fixture == "aac-stereo.ts") 2 else 6
            val expected = byteArrayOf(0x11, if (channels == 6) 0xb0.toByte() else 0x90.toByte())
            for (mode in listOf(TsExtractor.MODE_HLS, TsExtractor.MODE_SINGLE_PMT)) {
                val audio = extract(fixture, mode)
                assertEquals(MimeTypes.AUDIO_AAC, audio.sampleMimeType, fixture)
                assertEquals(48000, audio.sampleRate, fixture)
                assertEquals(channels, audio.channelCount, fixture)
                assertEquals("mp4a.40.2", audio.codecs, fixture)
                assertContentEquals(expected, audio.initializationData.single(), "$fixture mode=$mode")
            }
        }
    }

    private fun extract(fixture: String, mode: Int): Format {
        val data = requireNotNull(javaClass.getResourceAsStream("/playback/$fixture")).use { it.readBytes() }
        val input = FakeExtractorInput.Builder().setData(data).build()
        val output = FakeExtractorOutput()
        val extractor = TsExtractor(
            mode,
            0,
            DefaultSubtitleParserFactory(),
            TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(),
            112800
        )
        extractor.init(output)
        try {
            val position = PositionHolder()
            while (true) {
                when (extractor.read(input, position)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> input.setPosition(position.position.toInt())
                }
            }
            val audio = (0 until output.trackOutputs.size()).map { output.trackOutputs.valueAt(it) }
                .single { it.lastFormat?.sampleMimeType == MimeTypes.AUDIO_AAC }
            assertTrue(audio.sampleCount > 0)
            return requireNotNull(audio.lastFormat)
        } finally {
            extractor.release()
        }
    }
}
