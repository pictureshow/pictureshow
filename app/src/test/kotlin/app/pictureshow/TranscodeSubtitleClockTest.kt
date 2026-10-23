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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class TranscodeSubtitleClockTest {
    @Test
    fun fullFileCuesAtRestartTargetRenderOnTheLocalVideoClockForBothParsePaths() {
        for (legacy in listOf(false, true)) {
            Fixture(legacy, 126_000, 126_000, SubtitleDelay()).use { fixture ->
                fixture.renderAt(0)
                fixture.assertText("A made-up caption")
                fixture.renderAt(999)
                fixture.assertText("A made-up caption")
                fixture.renderAt(1_000)
                fixture.assertText("")
            }
        }
    }

    @Test
    fun userDelayAddsToSourceOffsetRatherThanReplacingItForBothParsePaths() {
        for (legacy in listOf(false, true)) {
            val delay = SubtitleDelay().apply { set(1_000) }
            Fixture(legacy, 126_000, 126_000, delay).use { fixture ->
                fixture.renderAt(0)
                fixture.assertText("")
                fixture.renderAt(999)
                fixture.assertText("")
                fixture.renderAt(1_000)
                fixture.assertText("A made-up caption")
                fixture.renderAt(1_999)
                fixture.assertText("A made-up caption")
                fixture.renderAt(2_000)
                fixture.assertText("")
            }
        }
    }

    @Test
    fun sourceOffsetResetsForTheNextOpenedRendererWithoutLosingViewerDelay() {
        for (legacy in listOf(false, true)) {
            val delay = SubtitleDelay().apply { set(1_000) }
            Fixture(legacy, 126_000, 126_000, delay).use { fixture ->
                fixture.renderAt(1_000)
                fixture.assertText("A made-up caption")
            }
            // A following direct or server-rebased subtitle source has no full-file origin offset.
            Fixture(legacy, 1_000, 0, delay).use { fixture ->
                assertEquals(0L, delay.sourceStartMs)
                assertEquals(1_000, delay.milliseconds)
                fixture.renderAt(1_999)
                fixture.assertText("")
                fixture.renderAt(2_000)
                fixture.assertText("A made-up caption")
                fixture.renderAt(3_000)
                fixture.assertText("")
            }
        }
    }

    @Test
    fun seekAndDelayChangeRereadFullFileCueAtItsOriginalTimestamp() {
        for (legacy in listOf(false, true)) {
            val delay = SubtitleDelay()
            Fixture(legacy, 126_000, 126_000, delay).use { fixture ->
                fixture.renderAt(1_000)
                fixture.assertText("")
                delay.set(1_000)
                fixture.seekTo(1_000)
                fixture.assertText("A made-up caption")
                fixture.renderAt(2_000)
                fixture.assertText("")
            }
        }
    }

    @Test
    fun sourceClockUsesLongArithmeticAndHasNoUserDelayQuantization() {
        val delay = SubtitleDelay()
        assertEquals(0L, delay.sourceStartMs)
        assertEquals(0L, delay.rendererPositionUs(0))
        delay.sourceStartMs = 10_000_000_123L
        delay.set(1_000)
        assertEquals(10_000_000_123_000L, delay.rendererPositionUs(1_000_000))
        delay.sourceStartMs = 0
        assertEquals(0L, delay.rendererPositionUs(1_000_000))
    }

    private class Fixture(legacy: Boolean, cueStartMs: Long, sourceStartMs: Long, val delay: SubtitleDelay) : AutoCloseable {
        private val clock = FakeClock(0)
        private var positionMs = 0L
        private val outputs = mutableListOf<String>()
        private val format = Format.Builder().setSampleMimeType(
            if (legacy) MimeTypes.APPLICATION_SUBRIP else MimeTypes.APPLICATION_MEDIA3_CUES
        ).setSubsampleOffsetUs(0).build()
        private val stream = CueStream(
            format,
            if (legacy) 0 else cueStartMs * 1_000,
            if (legacy) {
                "1\n${timestamp(cueStartMs)} --> ${timestamp(cueStartMs + 1_000)}\nA made-up caption\n\n".toByteArray()
            } else {
                CueEncoder().encode(listOf(Cue.Builder().setText("A made-up caption").build()), 1_000_000)
            }
        )
        private val textRenderer = TextRenderer({ group -> outputs += group.cues.joinToString { it.text.toString() } }, null).apply {
            @Suppress("DEPRECATION")
            experimentalSetLegacyDecodingEnabled(true)
        }
        private val renderer = SubtitleDelayRenderer(textRenderer, delay)
        private val legacy = legacy

        init {
            delay.sourceStartMs = sourceStartMs
            renderer.init(0, PlayerId.UNSET, clock)
            renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, 0, false, false, 0, 0, MediaPeriodId("test"))
            renderer.start()
            renderAt(0)
        }

        private fun timestamp(milliseconds: Long): String {
            val hour = (milliseconds / 3_600_000).toString().padStart(2, '0')
            val minute = (milliseconds / 60_000 % 60).toString().padStart(2, '0')
            val second = (milliseconds / 1_000 % 60).toString().padStart(2, '0')
            val fraction = (milliseconds % 1_000).toString().padStart(3, '0')
            return "$hour:$minute:$second,$fraction"
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
        fun rewind() {
            formatSent = false
            sampleSent = false
        }
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
