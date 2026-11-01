/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.ui.SubtitleView
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleCueDiagnosticsTest {
    private val lines = mutableListOf<String>()
    private val decision =
        PlayDecision("item", MediaSourceInfo(), PlayMethod.DirectPlay, "https://example.test/video", emptyMap(), "session")
    private val diagnostics = SubtitleCueDiagnostics(decision, lines::add)
    private fun group() = CueGroup(listOf(Cue.Builder().setText("Private caption https://secret.test/?ApiKey=secret").build()), 1_000_000)
    private fun show(group: CueGroup, position: Long = 42_000) = showSubtitleCues(
        SubtitleView(RuntimeEnvironment.getApplication()),
        group,
        group.cues,
        position,
        diagnostics
    )

    @Test
    fun selectAndLoadedLinesContainOnlyFixedFieldsAndEveryExplicitChoiceIsRecorded() {
        diagnostics.selected(7, false, 123_456)
        diagnostics.selected(7, true, 123_457)
        diagnostics.selected(-1, true, 123_458)
        diagnostics.loaded(7, 11)
        assertEquals(
            listOf(
                "subtitle-select item=item session=session stream=7 paused=false positionMs=123456 selection=1",
                "subtitle-select item=item session=session stream=7 paused=true positionMs=123457 selection=2",
                "subtitle-select item=item session=session stream=off paused=true positionMs=123458 selection=3",
                "subtitle-loaded item=item session=session stream=7 attempts=11"
            ),
            lines
        )
    }

    @Test
    fun receiptsRequireNonemptyViewDeliveryAndDoNotRelabelStaleGroupsOrRepeatForRecomposition() {
        diagnostics.selected(1, true, 42_000)
        val old = group()
        diagnostics.delivered(old, 1)
        assertEquals(1, lines.size, "Receiving decoded cues alone must not log a view receipt")
        show(old)
        show(old)
        diagnostics.selected(2, true, 42_000)
        show(old) // Existing visible cues reapplied after a choice are not a new delivery.
        val stale = group()
        diagnostics.delivered(stale, 1)
        show(stale)
        val current = group()
        diagnostics.delivered(current, 2)
        show(current)
        show(current)
        show(CueGroup.EMPTY_TIME_ZERO)
        assertEquals(
            listOf(
                "subtitle-cues item=item session=session stream=1 firstCueAtMs=42000 selection=1",
                "subtitle-cues item=item session=session stream=1 firstCueAtMs=42000 selection=2",
                "subtitle-cues item=item session=session stream=2 firstCueAtMs=42000 selection=2"
            ),
            lines.filter { it.startsWith("subtitle-cues") }
        )
        assertTrue(lines.none { it.contains("Private") || it.contains("https:") || it.contains("secret") })
    }

    @Test
    fun unknownCueProvenanceIsExplicitRatherThanInventingTheRequestedStream() {
        diagnostics.selected(9, false, 42_000)
        show(group())
        assertEquals("subtitle-cues item=item session=session stream=unknown firstCueAtMs=42000 selection=1", lines.last())
    }

    @Test
    fun realRendererCapturesOriginBeforeQueuedOutputCanRaceWithASelectionSwitch() {
        val renderer = SubtitleCueRenderer({ group -> show(group) }, Handler(Looper.getMainLooper()), diagnostics)
        fun enable(index: Int) {
            val format = Format.Builder().setId("server-subtitle:$index")
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.APPLICATION_SUBRIP)
                .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE).setSubsampleOffsetUs(0).build()
            renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), Cues(format), 0, false, false, 0, 0, MediaPeriodId("fixture"))
            renderer.render(0, 0)
        }
        diagnostics.selected(1, true, 42_000)
        enable(1) // Nonempty output is queued, not delivered to the view yet.
        renderer.disable()
        diagnostics.selected(2, true, 42_000)
        enable(2)
        assertTrue(lines.none { it.startsWith("subtitle-cues") })
        shadowOf(Looper.getMainLooper()).idle()
        renderer.disable()
        renderer.reset()
        assertEquals(
            listOf(
                "subtitle-cues item=item session=session stream=1 firstCueAtMs=42000 selection=2",
                "subtitle-cues item=item session=session stream=2 firstCueAtMs=42000 selection=2"
            ),
            lines.filter { it.startsWith("subtitle-cues") }
        )
    }

    @Test
    fun embeddedOriginResolvesWhenTrackMetadataArrivesAfterEnableAndQueuedOutput() {
        val renderer = SubtitleCueRenderer({ group -> show(group) }, Handler(Looper.getMainLooper()), diagnostics)
        val format = Format.Builder().setId("container-track")
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE).build()
        diagnostics.selected(7, true, 42_000)
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), Cues(format), 0, false, false, 0, 0, MediaPeriodId("fixture"))
        renderer.render(0, 0) // Capture the actual format while the server-index map is still absent.
        renderer.disable()
        diagnostics.tracks(
            listOf(MediaStream(type = MediaStreamType.Subtitle, index = 7)),
            listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
        )
        shadowOf(Looper.getMainLooper()).idle()
        renderer.reset()
        assertEquals(
            listOf("subtitle-cues item=item session=session stream=7 firstCueAtMs=42000 selection=1"),
            lines.filter { it.startsWith("subtitle-cues") }
        )
    }

    @Test
    fun legacyReplacementIsUnknownUntilResetFlushesThePreviousDecoder() {
        val renderer = SubtitleCueRenderer({}, Handler(Looper.getMainLooper()), diagnostics)
        val parsed = Format.Builder().setId("server-subtitle:1").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE).build()
        val legacy = Format.Builder().setId("server-subtitle:2").setSampleMimeType(MimeTypes.APPLICATION_CEA608).build()
        val id = MediaPeriodId("fixture")
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(parsed), Cues(parsed), 0, false, false, 0, 0, id)
        assertEquals(1, renderer.cueStreamIndex)
        renderer.replaceStream(arrayOf(legacy), Cues(legacy), 0, 0, id)
        assertEquals(null, renderer.cueStreamIndex, "Do not relabel old decoder output as the incoming track")
        renderer.resetPosition(0, false)
        assertEquals(2, renderer.cueStreamIndex)
        renderer.disable()
        renderer.reset()
    }

    private class Cues(private val format: Format) : SampleStream {
        private var read = 0
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
            if (read++ == 0) {
                holder.format = format
                return C.RESULT_FORMAT_READ
            }
            if (read > 2) return C.RESULT_NOTHING_READ
            val bytes = CueEncoder().encode(listOf(Cue.Builder().setText("Fixture cue").build()), 3_000_000)
            buffer.ensureSpaceForWrite(bytes.size)
            requireNotNull(buffer.data).put(bytes)
            buffer.timeUs = 0
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            return C.RESULT_BUFFER_READ
        }
    }
}
