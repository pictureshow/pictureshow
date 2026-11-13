/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w960dp-h540dp-mdpi")
class LiveTvReviewClosureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun summaryAndFooterTextAreCenteredInTheirFocusSurfacesAtNormalFont() = assertCenteredRows(1f)

    @Test fun summaryAndFooterTextAreCenteredInTheirFocusSurfacesAtLargeFont() = assertCenteredRows(1.3f)

    private fun assertCenteredRows(fontScale: Float) {
        val expected = surfaceCase(fontScale, false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PictureshowTheme {
                    Column(
                        Modifier.fillMaxSize().background(LiveBackground).padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LiveTvReadOnlySummaryRow("Priority", "Normal", Modifier.testTag("priority"))
                        LiveTvReadOnlySummaryRow("Some options depend on your server.", null, Modifier.testTag("footer"), small = true)
                    }
                }
            }
        }
        listOf(
            "priority" to listOf("Priority", "Normal"),
            "footer" to listOf("Some options depend on your server.")
        ).forEach { (tag, labels) ->
            val row = compose.onNodeWithTag(tag)
            row.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            row.assertIsFocused()
            val surface = row.getBoundsInRoot()
            assertTrue(surface.height.value >= expected.getValue("minimumRowHeightDp").jsonPrimitive.int)
            val center = (surface.top + surface.bottom) / 2
            labels.forEach { label ->
                val text = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
                assertEquals(
                    center.value,
                    ((text.top + text.bottom) / 2).value,
                    expected.getValue("maximumCenterOffsetDp").jsonPrimitive.float,
                    "$tag text must share its focus-surface centre"
                )
            }
        }
    }

    @Test fun wrappedSummaryValuesKeepTheirLabelOnTheFirstLine() {
        val supplied = "Monday\nWednesday\nFriday"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                PictureshowTheme {
                    Column(Modifier.fillMaxSize().background(LiveBackground).padding(32.dp)) {
                        LiveTvReadOnlySummaryRow("Days", supplied, Modifier.testTag("days"))
                    }
                }
            }
        }
        compose.onNodeWithTag("days").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val label = compose.onNodeWithText("Days", useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText(supplied, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(value.height > label.height * 2, "The supplied value must actually occupy multiple lines")
        assertEquals(
            value.top.value,
            label.top.value,
            surfaceCase(1.3f, true).getValue("firstLineTopToleranceDp").jsonPrimitive.float,
            "Center the complete row without centering a short label against a multi-line value"
        )
    }

    @Test fun disabledPrimaryActionHasVisibleFocusWithoutBecomingActionable() = assertDisabledFocus(primary = true)

    @Test fun disabledOutlinedActionHasVisibleFocusWithoutBecomingActionable() = assertDisabledFocus(primary = false)

    private fun assertDisabledFocus(primary: Boolean) {
        var writes = 0
        val minimumContrast = surfaceCase(1f, false).getValue("minimumFocusContrast").jsonPrimitive.float.toDouble()
        compose.setContent {
            PictureshowTheme {
                Column(
                    Modifier.fillMaxSize().background(LiveBackground).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LiveAction("Unavailable", true, Modifier.testTag("disabled"), enabled = false, primary = primary) { writes++ }
                    LiveAction("Edit options", true, Modifier.testTag("edit")) { error("The test never activates Edit") }
                }
            }
        }
        val disabled = compose.onNodeWithTag("disabled")
        compose.onNodeWithTag("edit").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("edit").assertIsFocused()
        fun brightBorderPixels(): Int {
            val pixels = disabled.captureToImage().toPixelMap()
            val band = 4
            return (0 until pixels.height).sumOf { y ->
                (0 until pixels.width).count { x ->
                    (x < band || y < band || x >= pixels.width - band || y >= pixels.height - band) &&
                        contrast(pixels[x, y], LiveBackground) >= minimumContrast
                }
            }
        }
        val unfocused = brightBorderPixels()
        disabled.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        disabled.assertIsFocused().assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(500)
        val focused = brightBorderPixels()
        assertTrue(
            focused >= 16 && focused >= unfocused + 16,
            "Disabled focus needs a real >=3:1 edge, unfocused=$unfocused focused=$focused"
        )
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        disabled.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes, "Visible disabled focus never enables a write") }
    }

    private fun surfaceCase(font: Float, wrapped: Boolean): JsonObject {
        val file = listOf(File("../spec/vectors/live-presentation.json"), File("spec/vectors/live-presentation.json")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }.single {
            it.getValue("operation").jsonPrimitive.content == "tv-review-surface" &&
                it.getValue("input").jsonObject.getValue("fontScale").jsonPrimitive.float == font &&
                it.getValue("input").jsonObject.getValue("wrappedValue").jsonPrimitive.boolean == wrapped
        }.getValue("expected").jsonObject
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
