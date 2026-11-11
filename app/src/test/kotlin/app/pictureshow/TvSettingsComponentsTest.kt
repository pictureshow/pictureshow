/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvSettingsComponentsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun choiceOpensCurrentRadioSelectsDirectlyRestoresOpenerAndSupportsBothStepDirections() {
        var value by mutableStateOf(20)
        compose.setContent {
            MaterialTheme {
                val first = remember { FocusRequester() }
                Column(Modifier.width(420.dp)) {
                    TvSettingsChoiceRow(
                        "Quality",
                        value,
                        listOf(4, 10, 20, 40),
                        { "$it Mbps" },
                        Modifier.focusRequester(first).testTag("choice")
                    ) { value = it }
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        compose.onNodeWithTag("choice").assertIsFocused()
        assertNull(compose.onNodeWithTag("choice").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        key("choice", Key.DirectionCenter)
        compose.onNodeWithTag("tv-settings:option:2").assertIsFocused().assertIsSelected()
        key("tv-settings:panel", Key.DirectionUp)
        compose.onNodeWithTag("tv-settings:option:1").assertIsFocused()
        key("tv-settings:panel", Key.DirectionCenter)
        compose.runOnIdle { assertEquals(10, value) }
        compose.onNodeWithTag("tv-settings:panel").assertDoesNotExist()
        compose.onNodeWithTag("choice").assertIsFocused()
        key("choice", Key.DirectionRight)
        compose.runOnIdle { assertEquals(20, value) }
        key("choice", Key.DirectionLeft)
        compose.runOnIdle { assertEquals(10, value) }
    }

    @Test fun backFromUncommittedPickerPreservesValueAndReturnsToItsOpener() {
        var value by mutableStateOf(20)
        compose.setContent {
            MaterialTheme {
                val first = remember { FocusRequester() }
                TvSettingsChoiceRow(
                    "Quality",
                    value,
                    listOf(4, 10, 20, 40),
                    { "$it Mbps" },
                    Modifier.focusRequester(first).testTag("choice")
                ) { value = it }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        key("choice", Key.DirectionCenter)
        key("tv-settings:panel", Key.DirectionDown)
        compose.onNodeWithTag("tv-settings:option:3").assertIsFocused()
        back()
        compose.runOnIdle { assertEquals(20, value) }
        compose.onNodeWithTag("choice").assertIsFocused()
        key("choice", Key.DirectionCenter)
        compose.onNodeWithTag("tv-settings:option:2").assertIsFocused().assertIsSelected()
    }

    @Test fun switchIsPassiveInsideOneFocusableRowWithSwitchSemantics() {
        var checked by mutableStateOf(true)
        var actions = 0
        compose.setContent {
            MaterialTheme {
                val first = remember { FocusRequester() }
                Column(Modifier.width(420.dp)) {
                    TvSettingsToggleRow("Auto-adjust quality", checked, Modifier.focusRequester(first).testTag("toggle")) { checked = it }
                    TvSettingsActionRow("Other setting", Modifier.testTag("next")) { actions++ }
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        compose.onNodeWithTag("toggle").assertIsFocused().assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        assertNull(compose.onNodeWithTag("toggle").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertNull(compose.onNodeWithTag("next").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        key("toggle", Key.DirectionCenter)
        compose.runOnIdle { assertEquals(false, checked) }
        assertEquals(
            androidx.compose.ui.state.ToggleableState.Off,
            compose.onNodeWithTag("toggle").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        )
        key("toggle", Key.DirectionDown)
        compose.onNodeWithTag("next").assertIsFocused()
        key("next", Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun commandAndValueRowsDoNotExposeASelectionState() {
        compose.setContent {
            MaterialTheme {
                val first = remember { FocusRequester() }
                Column {
                    TvSettingsActionRow("Unpin", Modifier.focusRequester(first).testTag("command")) {}
                    TvSettingsValueRow("Genre", "Any", Modifier.testTag("value")) {}
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        for (tag in listOf("command", "value")) {
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher("Commands have no selected or toggle state") {
                    !it.config.contains(SemanticsProperties.Selected) && !it.config.contains(SemanticsProperties.ToggleableState)
                }
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
        compose.onNodeWithTag("command").assertIsFocused()
        key("command", Key.DirectionDown)
        compose.onNodeWithTag("value").assertIsFocused()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun commandSupportingTextUsesTheDrawnForegroundWhileFocusedAndResting() {
        compose.setContent {
            PictureshowTheme {
                val first = remember { FocusRequester() }
                Column {
                    TvSettingsActionRow(
                        "Find by name",
                        Modifier.focusRequester(first).testTag("search"),
                        supportingText = "An exact query"
                    ) {
                    }
                    TvSettingsActionRow("Another command", Modifier.testTag("next")) {}
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        fun supportContrast(focused: Boolean) {
            val pixels = compose.onNodeWithTag("search").captureToImage().asAndroidBitmap()
            val supportColors = mutableMapOf<Int, Int>()
            for (y in pixels.height / 2 until pixels.height - 12) {
                for (x in 24 until pixels.width / 2) {
                    val color = pixels.getPixel(x, y)
                    supportColors[color] = (supportColors[color] ?: 0) + 1
                }
            }
            val background = pixels.getPixel(pixels.width / 2, pixels.height / 2)
            assertTrue(
                supportColors.any { (color, count) ->
                    count > 8 &&
                        androidx.core.graphics.ColorUtils.calculateContrast(color, background) >= 4.5
                },
                "Supporting text must be painted with readable contrast on the actual native surface; focused=$focused"
            )
        }
        compose.onNodeWithTag("search").assertIsFocused()
        supportContrast(true)
        key("search", Key.DirectionDown)
        compose.onNodeWithTag("next").assertIsFocused()
        supportContrast(false)
    }

    @Test fun longPickerArrivesAtCurrentOffscreenValueWithReadableSupportingText() {
        compose.setContent {
            MaterialTheme {
                TvSettingsChoicePicker(
                    "Language",
                    12,
                    (0..14).toList(),
                    { "Language $it" },
                    {},
                    {},
                    supportingText = { "Description for language $it" }
                )
            }
        }
        compose.onNodeWithTag("tv-settings:option:12").assertIsFocused().assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("Description for language 12", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun fullHeightPanelKeepsTitleAndPreviewFixedWhileDpadReachesLastSupportingTextAtLargeFont() {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    val first = remember { FocusRequester() }
                    TvSettingsPanel("Subtitle appearance", {}, first, preview = { Text("Live preview", Modifier.height(100.dp)) }) {
                        TvSettingsSectionHeader("Subtitle styles")
                        repeat(12) { index ->
                            TvSettingsActionRow(
                                "Setting $index",
                                (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("row:$index"),
                                supportingText = "Explanation for setting $index that stays inside its focused row."
                            ) {}
                        }
                    }
                }
            }
        }
        val panel = compose.onNodeWithTag("tv-settings:panel")
        val bounds = panel.getUnclippedBoundsInRoot()
        assertEquals(420.dp, bounds.right - bounds.left)
        val node = panel.fetchSemanticsNode()
        val root = generateSequence(node) { it.parent }.last()
        assertEquals(root.boundsInRoot.height, node.boundsInRoot.height, .5f, "The panel fills available window height")
        val title = compose.onNodeWithText("Subtitle appearance").getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithText("Live preview").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithText("Subtitle styles").getUnclippedBoundsInRoot()
        assertEquals(bounds.left + 28.dp, title.left)
        assertEquals(title.left, header.left)
        compose.onNodeWithTag("row:0").assertIsFocused()
        compose.onNodeWithTag("tv-settings:fade-bottom").assertExists()
        assertEquals(
            40.dp,
            compose.onNodeWithTag("tv-settings:fade-bottom").getUnclippedBoundsInRoot().bottom -
                compose.onNodeWithTag("tv-settings:fade-bottom").getUnclippedBoundsInRoot().top
        )
        val indicator = compose.onNodeWithTag("tv-settings:scroll-indicator").getUnclippedBoundsInRoot()
        assertEquals(2.dp, indicator.right - indicator.left)
        repeat(11) { key("tv-settings:panel", Key.DirectionDown) }
        compose.onNodeWithTag("row:11").assertIsFocused().assertIsDisplayed()
        val support = compose.onNode(
            hasText("Explanation for setting 11 that stays inside its focused row.") and !hasClickAction(),
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row:11").getUnclippedBoundsInRoot()
        assertTrue(support.top >= row.top && support.bottom <= row.bottom)
        assertEquals(title, compose.onNodeWithText("Subtitle appearance").getUnclippedBoundsInRoot())
        assertEquals(preview, compose.onNodeWithText("Live preview").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("tv-settings:fade-top").assertExists()
        val topFade = compose.onNodeWithTag("tv-settings:fade-top").getUnclippedBoundsInRoot()
        assertEquals(32.dp, topFade.bottom - topFade.top)
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test fun currentPickerOptionCanBeAcceptedAndSingleLineContentsAreCentred() {
        var value by mutableStateOf(20)
        compose.setContent {
            MaterialTheme {
                TvSettingsChoiceRow("Quality", value, listOf(10, 20, 40), { "$it Mbps" }, Modifier.testTag("choice")) { value = it }
            }
        }
        compose.onNodeWithTag("choice").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val row = compose.onNodeWithTag("tv-settings:option:1").getUnclippedBoundsInRoot()
        val text = compose.onNodeWithTag("tv-settings:label:20 Mbps", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val radio = compose.onNodeWithTag("tv-settings:radio:1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(56.dp, row.bottom - row.top)
        // Compose places whole pixels: an odd-pixel glyph box cannot share the
        // exact centre of an even-pixel row. Allow at most one physical pixel.
        val onePixel = (1f / compose.activity.resources.displayMetrics.density).dp
        assertTrue(kotlin.math.abs(((row.top + row.bottom) / 2 - (text.top + text.bottom) / 2).value) <= onePixel.value)
        assertEquals((row.top + row.bottom) / 2, (radio.top + radio.bottom) / 2)
        compose.onNodeWithTag("tv-settings:option:1").assertIsFocused().assertIsSelected()
        key("tv-settings:panel", Key.DirectionCenter)
        compose.onNodeWithTag("tv-settings:panel").assertDoesNotExist()
        compose.onNodeWithTag("choice").assertIsFocused()
        compose.runOnIdle { assertEquals(20, value) }
    }

    @Test fun shortChoiceValueKeepsItsCeilingInThePickerRatherThanTheTrailingSlot() {
        compose.setContent {
            MaterialTheme {
                TvSettingsChoiceRow(
                    "Home limit",
                    null as Int?,
                    listOf(null, 20),
                    ::bitrateLabel,
                    Modifier.testTag("choice"),
                    optionSupportingText = ::bitrateOptionSupporting
                ) {}
            }
        }
        compose.onNodeWithTag("choice").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Auto"))
        compose.onNodeWithText("Up to 200 Mbps; this is a ceiling, not unlimited.").assertDoesNotExist()
        compose.onNodeWithTag("choice").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("tv-settings:option:0").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Up to 200 Mbps; this is a ceiling, not unlimited.", useUnmergedTree = true).assertIsDisplayed()
    }
}
