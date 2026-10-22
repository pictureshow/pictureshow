/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.test.utils.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SubtitleDelayTest {
    @Test
    fun parsedAndLegacyCaptionsAppearAndDisappearAtDelayedTimes() {
        for (legacy in listOf(false, true)) for (offset in listOf(1000, -500)) {
            Fixture(legacy, offset).use { fixture ->
                fixture.renderAt(0)
                fixture.assertText("")
                fixture.renderAt(1000 + offset - 1)
                fixture.assertText("")
                fixture.renderAt(1000 + offset)
                fixture.assertText("A made-up caption")
                fixture.renderAt(2000 + offset - 1)
                fixture.assertText("A made-up caption")
                fixture.renderAt(2000 + offset)
                fixture.assertText("")
            }
        }
    }

    @Test
    fun changingDelayAndReseekingRestoresPreviouslyDiscardedCuesForBothParsePaths() {
        for (legacy in listOf(false, true)) Fixture(legacy, -500).use { fixture ->
            fixture.renderAt(500)
            fixture.assertText("A made-up caption")
            fixture.renderAt(1500)
            fixture.assertText("")
            fixture.delay.set(1000)
            fixture.seekTo(1500)
            fixture.assertText("")
            fixture.renderAt(2000)
            fixture.assertText("A made-up caption")
            fixture.seekTo(4000)
            fixture.assertText("")
        }
    }

    @Test
    fun delayIsBoundedQuantizedAndPlaybackScoped() {
        val delay = SubtitleDelay()
        assertEquals(0, delay.milliseconds)
        assertTrue(delay.set(50_000))
        assertEquals(10_000, delay.milliseconds)
        assertFalse(delay.set(50_001))
        assertTrue(delay.set(-999))
        assertEquals(-900, delay.milliseconds)
        assertTrue(delay.set(-50_000))
        assertEquals(-10_000, delay.milliseconds)
        assertEquals(0, SubtitleDelay().milliseconds)
    }

    private class Fixture(legacy: Boolean, offset: Int) : AutoCloseable {
        val delay = SubtitleDelay().apply { set(offset) }
        private val clock = FakeClock(0)
        private var positionMs = 0L
        private val outputs = mutableListOf<String>()
        private val format = Format.Builder().setSampleMimeType(
            if (legacy) MimeTypes.APPLICATION_SUBRIP else MimeTypes.APPLICATION_MEDIA3_CUES
        ).setSubsampleOffsetUs(0).build()
        private val stream = CueStream(
            format,
            if (legacy) 0 else 1_000_000,
            if (legacy) "1\n00:00:01,000 --> 00:00:02,000\nA made-up caption\n\n".toByteArray()
            else CueEncoder().encode(listOf(Cue.Builder().setText("A made-up caption").build()), 1_000_000)
        )
        private val textRenderer = TextRenderer({ group -> outputs += group.cues.joinToString { it.text.toString() } }, null).apply {
            @Suppress("DEPRECATION")
            experimentalSetLegacyDecodingEnabled(true)
        }
        private val renderer = SubtitleDelayRenderer(textRenderer, delay)
        private val legacy = legacy

        init {
            renderer.init(0, PlayerId.UNSET, clock)
            renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, 0, false, false, 0, 0, MediaPeriodId("test"))
            renderer.start()
            renderAt(0)
        }

        fun renderAt(milliseconds: Int) {
            clock.advanceTime((milliseconds - positionMs).coerceAtLeast(0))
            positionMs = milliseconds.toLong()
            repeat(8) { renderer.render(positionMs * 1000, clock.elapsedRealtime() * 1000) }
            if (legacy && stream.consumed) {
                val deadline = System.nanoTime() + 2_000_000_000
                while (!hasDecodedSubtitle() && System.nanoTime() < deadline) {
                    Thread.sleep(1) // Wait only for the real asynchronous SubRip decoder worker.
                    renderer.render(positionMs * 1000, clock.elapsedRealtime() * 1000)
                }
                renderer.render(positionMs * 1000, clock.elapsedRealtime() * 1000)
            }
        }

        fun seekTo(milliseconds: Int) {
            stream.rewind()
            renderer.resetPosition(milliseconds.toLong() * 1000, true)
            positionMs = milliseconds.toLong()
            renderAt(milliseconds)
        }

        private fun hasDecodedSubtitle(): Boolean = listOf("subtitle", "nextSubtitle").any { name ->
            TextRenderer::class.java.getDeclaredField(name).let { field ->
                field.isAccessible = true
                field.get(textRenderer) != null
            }
        }

        fun assertText(expected: String) = assertEquals(expected, outputs.lastOrNull().orEmpty())

        override fun close() {
            renderer.stop()
            renderer.disable()
            renderer.reset()
        }
    }

    private class CueStream(private val format: Format, private val timeUs: Long, private val bytes: ByteArray) : SampleStream {
        private var formatSent = false
        private var sampleSent = false
        val consumed: Boolean get() = sampleSent
        fun rewind() { formatSent = false; sampleSent = false }
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (!formatSent || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
                holder.format = format
                if (readFlags and SampleStream.FLAG_PEEK == 0) formatSent = true
                return C.RESULT_FORMAT_READ
            }
            if (sampleSent) return C.RESULT_NOTHING_READ
            buffer.timeUs = timeUs
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(bytes.size)
                buffer.data!!.put(bytes)
            }
            if (readFlags and SampleStream.FLAG_PEEK == 0) sampleSent = true
            return C.RESULT_BUFFER_READ
        }
    }
}
