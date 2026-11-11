/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Executes the portable UI case at the measured 360dp landscape / 1.3x font clipping boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w800dp-h360dp-land-mdpi")
class PhoneSettingsConstraintsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeLargeFontShowsTheEntireFirstChoiceBelowItsPreview() {
        val file = listOf(File("../spec/vectors/playback-settings-layout.json"), File("spec/vectors/playback-settings-layout.json"))
            .single { it.isFile }
        val document = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("playback-settings-layout", document.getValue("suite").jsonPrimitive.content)
        val case = document.getValue("cases").jsonArray.single().jsonObject
        assertEquals("settings-first-row-visible", case.getValue("operation").jsonPrimitive.content)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        // The native fixture's resource configuration must match the portable viewport, never silently ignore it.
        assertEquals(800, input.getValue("widthDp").jsonPrimitive.int)
        assertEquals(360, input.getValue("heightDp").jsonPrimitive.int)
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                    AdvancedPlaybackSettings(input.getValue("group").jsonPrimitive.content, PlaybackPreferences(), true, {}, {})
                }
            }
        }
        val first = compose.onNode(hasText("Font size") and hasClickAction())
        val row = first.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("settings:touch-list").getUnclippedBoundsInRoot()
        val rowVisible = row.top >= viewport.top && row.bottom <= viewport.bottom && row.bottom > row.top
        assertEquals(expected.getValue("firstRowFullyVisible").jsonPrimitive.boolean, rowVisible, "$row / $viewport")
        val text = first.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        val valueVisible = rowVisible && input.getValue("currentValue").jsonPrimitive.content in text
        assertEquals(expected.getValue("currentValueFullyVisible").jsonPrimitive.boolean, valueVisible, text.toString())
        val done = compose.onNodeWithText("Done").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("settings:touch-panel").getUnclippedBoundsInRoot()
        val doneVisible = done.top >= panel.top && done.bottom <= panel.bottom && done.bottom > done.top
        assertEquals(expected.getValue("doneFullyVisible").jsonPrimitive.boolean, doneVisible, "$done / $panel")
        val nextTitle = compose.onNode(
            hasText(input.getValue("secondRowTitle").jsonPrimitive.content) and !hasClickAction(),
            useUnmergedTree = true
        )
            .getUnclippedBoundsInRoot()
        val visibleHeight = minOf(nextTitle.bottom, viewport.bottom) - maxOf(nextTitle.top, viewport.top)
        assertEquals(expected.getValue("secondRowTitleVisible").jsonPrimitive.boolean, visibleHeight >= 8.dp, "$nextTitle / $viewport")
        assertPreviewLabelsCentered(expected.getValue("previewLabelsCentered").jsonPrimitive.boolean)
    }

    @Test fun previewCaptionsFollowTheTextAndImageCueCentersOnBothSurfaces() {
        val touch = mutableStateOf(true)
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.tv.material3.MaterialTheme {
                    Box(Modifier.width(320.dp)) { SubtitlePreview(SubtitleAppearance(), touch.value) }
                }
            }
        }
        assertPreviewLabelsCentered(true)
        compose.runOnIdle { touch.value = false }
        assertPreviewLabelsCentered(true)
    }

    private fun assertPreviewLabelsCentered(expected: Boolean) {
        val preview = compose.onNodeWithTag("settings:subtitle-preview").getUnclippedBoundsInRoot()
        listOf("Text" to 0.25f, "Image" to 0.75f).forEach { (label, fraction) ->
            val bounds = compose.onNodeWithTag("settings:subtitle-label:$label").getUnclippedBoundsInRoot()
            val center = (bounds.left.value + bounds.right.value) / 2
            val cueCenter = preview.left.value + (preview.right.value - preview.left.value) * fraction
            // Equal-width cells can round an odd text width by half a physical pixel.
            assertEquals(expected, abs(center - cueCenter) <= 1f, "$label center=$center cueCenter=$cueCenter")
        }
    }
}
