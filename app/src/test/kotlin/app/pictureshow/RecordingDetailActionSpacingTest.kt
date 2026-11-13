/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
class RecordingDetailActionSpacingTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun normalTextKeepsEqualControlGapsWithFocusCaptions() = verify(1f)

    @Test fun largeTextKeepsEqualControlGapsWithFocusCaptions() = verify(1.3f)

    @Test fun liveSeekMarksHaveLargerGlyphsAndDifferentDisabledInkWithoutChangingOrdinaryControls() {
        compose.setContent {
            TouchTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    Row {
                        PlayerIconButton("back10", "Back", Modifier.testTag("enabled"), liveSeek = true) {}
                        PlayerIconButton("forward10", "Forward", Modifier.testTag("disabled"), enabled = false, liveSeek = true) {}
                        PlayerIconButton("back10", "Ordinary", Modifier.testTag("ordinary")) {}
                    }
                }
            }
        }
        val marks = compose.onAllNodesWithTag("touch:live-seek-mark", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, marks.size, "Ordinary playback keeps its existing icon")
        marks.forEach { assertEquals(32, it.layoutInfo.coordinates.size.width) }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        fun brightest(tag: String): Float {
            val box = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            return (box.left.value.toInt() until box.right.value.toInt()).maxOf { x ->
                (box.top.value.toInt() until box.bottom.value.toInt()).maxOf { y -> pixels[x, y].red }
            }
        }
        assertTrue(brightest("enabled") > .9f)
        assertTrue(brightest("disabled") in 0.25f..0.5f, "Disabled ink stays visibly distinct over video")
    }

    @Test fun liveToggleUsesFortyPercentBlackOverNonblackVideoInBothEnabledStates() {
        compose.setContent {
            TouchTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
                    Row {
                        listOf(true, false).forEach { enabled ->
                            androidx.compose.material3.FilledIconButton(
                                onClick = {},
                                modifier = Modifier.size(64.dp).testTag("toggle-$enabled"),
                                enabled = enabled,
                                colors = touchPlayerToggleColors(true)
                            ) { Box(Modifier.size(32.dp)) }
                        }
                    }
                }
            }
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        listOf(true, false).forEach { enabled ->
            val box = compose.onNodeWithTag("toggle-$enabled").getUnclippedBoundsInRoot()
            val background = pixels[(box.left.value + 8).toInt(), (box.top.value + 32).toInt()]
            assertEquals(
                .6f,
                background.red,
                .02f,
                "40% black over white is60% grey; transparent and opaque containers both fail ($enabled)"
            )
            assertEquals(.6f, background.green, .02f)
            assertEquals(.6f, background.blue, .02f)
        }
    }

    private fun verify(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PictureshowTheme {
                    Box(Modifier.fillMaxSize().background(LiveBackground).padding(48.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(48.dp).testTag("preceding"))
                            listOf("played" to "Mark as unwatched", "favorite" to "Remove from favorites").forEach { (target, label) ->
                                RecordingDetailMutationButton(
                                    target,
                                    label,
                                    false,
                                    true,
                                    true,
                                    Modifier.testTag(target),
                                    containerModifier = Modifier.testTag("$target-slot"),
                                    onClick = {}
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val preceding = bounds("preceding")
        val played = bounds("played-slot")
        val favorite = bounds("favorite-slot")
        assertEquals(48.dp, (played.right - played.left), "A hidden or focus-visible caption must not reserve horizontal space")
        assertEquals(48.dp, (favorite.right - favorite.left))
        assertEquals(16.dp, played.left - preceding.right)
        assertEquals(16.dp, favorite.left - played.right)
        assertEquals(played.top, favorite.top)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val captionInk = ((played.left.value - 32).toInt() until played.left.value.toInt()).any { x ->
            ((played.top.value + 56).toInt() until (played.top.value + 84).toInt()).any { y ->
                val pixel = pixels[x, y]
                pixel.red > .5f && pixel.green > .5f && pixel.blue > .5f
            }
        }
        assertTrue(captionInk, "A long focus caption paints beyond the 48dp slot instead of clipping or ellipsizing")
        val rest = pixels[(favorite.left.value + 24).toInt(), (favorite.top.value + 8).toInt()]
        assertTrue(rest.red > LiveBackground.red + .025f, "An unselected action keeps a quiet visible circular container: $rest")
        compose.onNodeWithTag("played").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("played").assertIsFocused()
        assertEquals(favorite, bounds("favorite-slot"), "Focusing the neighbouring icon must not move this target")
    }
}
