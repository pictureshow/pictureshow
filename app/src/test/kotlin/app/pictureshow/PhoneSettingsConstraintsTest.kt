/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import org.robolectric.annotation.GraphicsMode

/** Executes the portable UI case at the measured 360dp landscape / 1.3x font clipping boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w800dp-h360dp-land-mdpi")
class PhoneSettingsConstraintsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeLargeFontShowsTheEntireFirstChoiceBelowItsPreview() {
        val case = vector("settings-first-row-visible")
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        // The native fixture's resource configuration must match the portable viewport, never silently ignore it.
        assertEquals(800, input.getValue("widthDp").jsonPrimitive.int)
        assertEquals(360, input.getValue("heightDp").jsonPrimitive.int)
        assertEquals("touch", input.getValue("surface").jsonPrimitive.content)
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                    assertEquals(
                        IntSize(input.getValue("widthDp").jsonPrimitive.int, input.getValue("heightDp").jsonPrimitive.int),
                        LocalWindowInfo.current.containerSize,
                        "The actual native window must match the portable viewport at mdpi"
                    )
                    AdvancedPlaybackSettings(input.getValue("group").jsonPrimitive.content, PlaybackPreferences(), true, {}, {})
                }
            }
        }
        val first = compose.onNode(hasText("Font size") and hasClickAction())
        val row = first.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("settings:touch-list").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("settings:subtitle-preview").getUnclippedBoundsInRoot()
        val besideChoices = preview.right <= viewport.left &&
            minOf(preview.bottom, viewport.bottom) > maxOf(preview.top, viewport.top)
        assertEquals(expected.getValue("previewBesideChoices").jsonPrimitive.boolean, besideChoices, "$preview / $viewport")
        val rowVisible = row.top >= viewport.top && row.bottom <= viewport.bottom && row.bottom > row.top
        assertEquals(expected.getValue("firstRowFullyVisible").jsonPrimitive.boolean, rowVisible, "$row / $viewport")
        val text = first.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        val valueVisible = rowVisible && input.getValue("currentValue").jsonPrimitive.content in text
        assertEquals(expected.getValue("currentValueFullyVisible").jsonPrimitive.boolean, valueVisible, text.toString())
        val done = compose.onNodeWithText("Done").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("settings:touch-panel").getUnclippedBoundsInRoot()
        assertEquals(
            true,
            panel.right - panel.left <= expected.getValue("maximumDialogWidthDp").jsonPrimitive.int.dp,
            "Painted dialog width: $panel"
        )
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
        first.performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(expected.getValue("choicePickerWindowCount").jsonPrimitive.int)
        compose.onNodeWithTag("touch:choice-option:0").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasText("Font size") and hasClickAction()).assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h842dp-port-mdpi")
    fun allocatedPortraitDialogIgnoresStaleLandscapeWindowHints() {
        val case = vector("settings-allocated-viewport-wins")
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val hints = input.getValue("outerHintSequence").jsonArray.map {
            val hint = it.jsonObject
            IntSize(hint.getValue("widthDp").jsonPrimitive.int, hint.getValue("heightDp").jsonPrimitive.int)
        }
        val hint = mutableStateOf(hints.first())
        compose.setContent {
            val actual = LocalWindowInfo.current
            assertEquals(
                IntSize(input.getValue("widthDp").jsonPrimitive.int, input.getValue("heightDp").jsonPrimitive.int),
                actual.containerSize,
                "The real portrait allocation must be verified before injecting a stale outer hint"
            )
            // Controlled fault injection: this models the stale activity hint while
            // the native dialog still receives its real portrait allocation.
            val outer = object : WindowInfo by actual {
                override val containerSize: IntSize get() = hint.value
            }
            CompositionLocalProvider(
                LocalWindowInfo provides outer,
                LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)
            ) {
                TouchTheme {
                    AdvancedPlaybackSettings(input.getValue("group").jsonPrimitive.content, PlaybackPreferences(), true, {}, {})
                }
            }
        }
        hints.forEach { next ->
            compose.runOnIdle { hint.value = next }
            val preview = compose.onNodeWithTag("settings:subtitle-preview").getUnclippedBoundsInRoot()
            val list = compose.onNodeWithTag("settings:touch-list").getUnclippedBoundsInRoot()
            assertEquals(
                expected.getValue("previewAboveChoices").jsonPrimitive.boolean,
                preview.bottom <= list.top,
                "$next: $preview / $list"
            )
            val tolerance = expected.getValue("maximumHorizontalEdgeOffsetDp").jsonPrimitive.float
            val fullWidth = abs(preview.left.value - list.left.value) <= tolerance &&
                abs(preview.right.value - list.right.value) <= tolerance
            assertEquals(expected.getValue("listFillsContentWidth").jsonPrimitive.boolean, fullWidth, "$next: $preview / $list")
            val first = compose.onNode(hasText("Font size") and hasClickAction())
            val row = first.getUnclippedBoundsInRoot()
            val visible = row.top >= list.top && row.bottom <= list.bottom && row.bottom > row.top
            assertEquals(expected.getValue("firstRowFullyVisible").jsonPrimitive.boolean, visible, "$next: $row / $list")
            val texts = first.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
            assertEquals(
                expected.getValue("currentValueFullyVisible").jsonPrimitive.boolean,
                visible && input.getValue("currentValue").jsonPrimitive.content in texts,
                "$next: $texts"
            )
            val done = compose.onNodeWithText("Done").getUnclippedBoundsInRoot()
            val panel = compose.onNodeWithTag("settings:touch-panel").getUnclippedBoundsInRoot()
            val doneVisible = done.top >= panel.top && done.bottom <= panel.bottom && done.bottom > done.top
            assertEquals(expected.getValue("doneFullyVisible").jsonPrimitive.boolean, doneVisible, "$next: $done / $panel")
        }
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

    @Test fun aChoiceConsequenceSharesItsWholeClickableRow() {
        val case = vector("settings-choice-target")
        val input = case.getValue("input").jsonObject
        val title = input.getValue("title").jsonPrimitive.content
        val consequence = input.getValue("consequence").jsonPrimitive.content
        compose.setContent {
            TouchTheme {
                Column {
                    TouchChoice(
                        title,
                        input.getValue("value").jsonPrimitive.content,
                        input.getValue("choices").jsonArray.map { it.jsonPrimitive.content },
                        { it },
                        supporting = consequence
                    ) {}
                }
            }
        }
        val row = compose.onNode(hasText(title) and hasClickAction()).getUnclippedBoundsInRoot()
        val explanation = compose.onNode(hasText(consequence) and !hasClickAction(), useUnmergedTree = true)
        val bounds = explanation.getUnclippedBoundsInRoot()
        assertEquals(
            case.getValue("expected").jsonObject.getValue("consequenceInsideClickTarget").jsonPrimitive.boolean,
            bounds.top >= row.top && bounds.bottom <= row.bottom,
            "$bounds outside target $row"
        )
        explanation.performClick()
        compose.onNodeWithText(input.getValue("choices").jsonArray[1].jsonPrimitive.content).assertExists()
    }

    @Test fun anOptionWithAConsequenceKeepsItsRadioOnTheLabelLine() {
        val case = vector("settings-radio-first-line")
        val input = case.getValue("input").jsonObject
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                TouchTheme {
                    Box(Modifier.width(320.dp)) {
                        TouchChoiceOptions(
                            TouchChoicePanel(
                                input.getValue("title").jsonPrimitive.content,
                                0,
                                input.getValue("choices").jsonArray.map { it.jsonPrimitive.content },
                                input.getValue("notes").jsonArray.map { if (it == JsonNull) null else it.jsonPrimitive.content },
                                true,
                                {}
                            ),
                            onSelected = {}
                        )
                    }
                }
            }
        }
        val label = compose.onNodeWithTag("touch:choice-label:0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val radio = compose.onNode(
            hasTestTag("touch:radio-indicator") and hasAnyAncestor(hasTestTag("touch:choice-option:0")),
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val labelCenter = (label.top.value + label.bottom.value) / 2
        val radioCenter = (radio.top.value + radio.bottom.value) / 2
        assertEquals(
            true,
            abs(labelCenter - radioCenter) <= case.getValue("expected").jsonObject.getValue("maximumCenterOffsetDp").jsonPrimitive.float,
            "$label / $radio"
        )
    }

    @Test fun aGroupPickerReturnsToTheSameScrolledChoice() {
        val case = vector("settings-choice-scroll-return")
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, input.getValue("fontScale").jsonPrimitive.float)) {
                TouchTheme { AdvancedPlaybackSettings(input.getValue("group").jsonPrimitive.content, PlaybackPreferences(), true, {}, {}) }
            }
        }
        val title = input.getValue("scrollTargetTitle").jsonPrimitive.content
        val opener = compose.onNode(hasText(title) and hasClickAction()).performScrollTo()
        val before = opener.getUnclippedBoundsInRoot()
        opener.performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(expected.getValue("choicePickerWindowCount").jsonPrimitive.int)
        compose.onNodeWithText("Cancel").performClick()
        val after = compose.onNode(hasText(title) and hasClickAction()).getUnclippedBoundsInRoot()
        assertEquals(
            true,
            abs(before.top.value - after.top.value) <= expected.getValue("maximumScrollReturnOffsetDp").jsonPrimitive.float,
            "$before / $after"
        )
    }

    private fun vector(operation: String): JsonObject {
        val file = listOf(File("../spec/vectors/playback-settings-layout.json"), File("spec/vectors/playback-settings-layout.json"))
            .single { it.isFile }
        val document = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("playback-settings-layout", document.getValue("suite").jsonPrimitive.content)
        return document.getValue("cases").jsonArray.map { it.jsonObject }.single {
            it.getValue("operation").jsonPrimitive.content == operation
        }
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
