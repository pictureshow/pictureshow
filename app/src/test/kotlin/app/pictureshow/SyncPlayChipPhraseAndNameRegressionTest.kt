/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pictureshow.core.sync.SyncQuality
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual chip rendering, including native text layout; no replica of the chip's sizing model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayChipPhraseAndNameRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun state(name: String = "Friday films with friends from every time zone", phase: String = "Paused", quality: SyncQuality = SyncQuality.IN_SYNC, count: Int = 6) =
        SyncPlayPresentation(groupId = "g", groupName = name, phase = phase, quality = quality, participants = List(count) { "Person $it" })

    private fun show(state: SyncPlayPresentation, widthDp: Int = 360, fontScale: Float = 1.3f) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            assertEquals(IntSize(widthDp * 2, 1280), LocalWindowInfo.current.containerSize,
                "Actual native window must match the claimed logical chip width at320dpi")
            app.pictureshow.ui.PictureshowTheme {
                Box(Modifier.fillMaxSize()) {
                    // Same16dp outer inset and440dp cap used by the real touch player and fixture.
                    SyncPlayerStatusChip(state, {}, Modifier.align(Alignment.TopStart).padding(16.dp).widthIn(max = 440.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("sync:player-chip").assertIsDisplayed()
    }

    private data class PaintedLine(val text: String, val top: Float, val left: Float, val ellipsized: Boolean, val fontScale: Float)
    private fun clean(text: String) = text.replace('\u00a0', ' ').replace('\u202f', ' ').trim()

    private fun textLayouts(matcher: SemanticsMatcher): List<Pair<TextLayoutResult, androidx.compose.ui.geometry.Rect>> {
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
        val semantics = nodes.fetchSemanticsNodes()
        return semantics.flatMapIndexed { index, node ->
            if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) emptyList() else {
                val layouts = mutableListOf<TextLayoutResult>()
                nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.map { it to node.boundsInRoot }
            }
        }
    }

    private fun statusLines(): List<PaintedLine> {
        // Works with today's single Text and a later container of actual Text runs.
        // No anticipated helper or layout algorithm is reproduced here.
        val matcher = hasTestTag("sync:chip-status") or hasAnyAncestor(hasTestTag("sync:chip-status"))
        return textLayouts(matcher).flatMap { (layout, bounds) ->
            (0 until layout.lineCount).map { line ->
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line, visibleEnd = true)
                PaintedLine(clean(layout.layoutInput.text.text.substring(start, end)),
                    bounds.top + layout.getLineTop(line), bounds.left + layout.getLineLeft(line),
                    layout.isLineEllipsized(line), layout.layoutInput.density.fontScale)
            }
        }.filter { it.text.isNotEmpty() }
    }

    private fun assertPhaseStaysWholeOnFirstPaintedLine(expectedPhase: String) {
        val lines = statusLines()
        assertTrue(lines.isNotEmpty(), "Actual status TextLayoutResults must be captured")
        lines.forEach { assertEquals(1.3f, it.fontScale, .01f, "Actual native chip text must use font1.3") }
        val firstTop = lines.minOf { it.top }
        val first = lines.filter { abs(it.top - firstTop) <= 1f }.sortedBy { it.left }
            .joinToString(" ") { it.text }
        assertTrue(first.startsWith(expectedPhase), "First painted status line must contain the entire phase; lines=$lines")
        val all = lines.joinToString(" ") { it.text }
        assertTrue(all.contains(compose.activity.getString(R.string.sync_quality_in_sync)), "Known quality must remain visible; lines=$lines")
        assertTrue(lines.none { it.text.startsWith("·") }, "A separator must not lead a visual row; lines=$lines")
        assertTrue(lines.none { it.ellipsized }, "These fixtures have room for both facts across two lines; lines=$lines")
    }

    @Test fun pausedPhaseDoesNotBreakBeforeGroupWhenQualityWrapsAtFont13() {
        show(state())
        assertPhaseStaysWholeOnFirstPaintedLine(compose.activity.getString(R.string.sync_group_paused))
    }

    @Test
    @Config(qualifiers = "w280dp-h640dp-port-xhdpi")
    fun playingPhaseDoesNotBreakBeforeGroupOnNarrowPhoneAtFont13() {
        show(state(phase = "Playing", count = 12), widthDp = 280)
        assertPhaseStaysWholeOnFirstPaintedLine(compose.activity.getString(R.string.sync_group_playing))
    }

    private fun assertNameUsefulOrOmitted(expected: String, expectedCount: String) {
        val countNode = compose.onNodeWithTag("sync:chip-count", useUnmergedTree = true).assertIsDisplayed()
        val countLayouts = textLayouts(hasTestTag("sync:chip-count"))
        assertTrue(countLayouts.isNotEmpty(), "Count must retain actual text layout")
        assertWholeOneLine(countLayouts.single().first, expectedCount, "Count must remain readable")
        val names = compose.onAllNodes(hasTestTag("sync:chip-name"), useUnmergedTree = true).fetchSemanticsNodes()
        if (names.isEmpty()) {
            val count = countNode.getUnclippedBoundsInRoot()
            val status = compose.onNodeWithTag("sync:chip-status", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(abs((count.left - status.left).value) <= .5f,
                "Omitting the name must also omit its separator/slack, leaving count at the text-column edge: count=$count status=$status")
        } else {
            val layout = textLayouts(hasTestTag("sync:chip-name")).single().first
            val rendered = layout.layoutInput.text.text
            assertEquals(1, layout.lineCount)
            if (rendered != expected) {
                assertTrue(rendered.endsWith("…"), "Name must remain whole or an exact original prefix plus ellipsis: '$rendered'")
                val suppliedPrefix = rendered.removeSuffix("…")
                assertTrue(suppliedPrefix.length >= 6 && suppliedPrefix.length < expected.length && expected.startsWith(suppliedPrefix),
                    "Measured truncation must retain6+ original ASCII graphemes, not invent an abbreviation: rendered='$rendered' original='$expected'")
            }
            if (layout.isLineEllipsized(0)) {
                // Official visibleEnd excludes naturally ellipsized characters. This also accepts
                // a measured prefix run if a renderer applies another ellipsis, provided6+ remain.
                val visible = rendered.substring(0, layout.getLineEnd(0, visibleEnd = true)).trimEnd().removeSuffix("…")
                assertTrue(expected.startsWith(visible) && visible.codePointCount(0, visible.length) >= 6,
                    "An ellipsized name needs6+ actually visible original graphemes, otherwise omit it; visible='$visible' full='$expected'")
                assertTrue(layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1f,
                    "The visible prefix must stay within the actual line box")
            } else {
                // Both natural full-name rendering and explicitly measured prefix+… are valid.
                assertWholeOneLine(layout, rendered, "Displayed name or useful prefix must fit its actual line box")
            }
        }
    }

    @Test
    @Config(qualifiers = "w280dp-h640dp-port-xhdpi")
    fun narrowLongNameIsUsefulOrOmittedRatherThanOneToThreeLetterStub() {
        val name = "Friday films with friends from every time zone"
        show(state(name = name), widthDp = 280)
        assertNameUsefulOrOmitted(name, "6 people watching")
    }

    @Test
    @Config(qualifiers = "w280dp-h640dp-port-xhdpi")
    fun narrowFiveLetterNameIsWholeOrOmittedRatherThanPointlesslyEllipsized() {
        show(state(name = "Films", quality = SyncQuality.UNKNOWN, count = 1), widthDp = 280)
        assertNameUsefulOrOmitted("Films", "1 person watching")
    }

    private fun assertWholeOneLine(layout: TextLayoutResult, expected: String, context: String) {
        assertEquals(expected, layout.layoutInput.text.text, context)
        assertEquals(1, layout.lineCount, context)
        assertEquals(expected.length, layout.getLineEnd(0, visibleEnd = true), "$context: full text must be laid out")
        assertFalse(layout.isLineEllipsized(0), "$context: no hidden tail")
        assertTrue(layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1f,
            "$context: actual line must fit its measured box; size=${layout.size} left=${layout.getLineLeft(0)} right=${layout.getLineRight(0)}")
    }

    @Test fun ordinaryWidthKeepsTheWholeShortNameAndSingularCount() {
        show(state(name = "Films", quality = SyncQuality.UNKNOWN, count = 1), fontScale = 1f)
        val name = textLayouts(hasTestTag("sync:chip-name")).single().first
        assertWholeOneLine(name, "Films", "A fitting short name must not be dropped or truncated")
        val count = textLayouts(hasTestTag("sync:chip-count")).single().first
        assertWholeOneLine(count, "1 person watching", "Singular count must remain readable")
    }

    @Test fun unknownQualityStillRendersOnlyThePhase() {
        show(state(name = "Films", quality = SyncQuality.UNKNOWN, count = 1))
        val lines = statusLines()
        assertTrue(lines.isNotEmpty())
        assertEquals(compose.activity.getString(R.string.sync_group_paused), lines.joinToString(" ") { it.text },
            "Actual rendered chip must still omit UNKNOWN quality, not merely the pure quality helper")
    }
}
