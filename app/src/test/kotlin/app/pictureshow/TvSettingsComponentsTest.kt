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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.test.assertEquals
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
        key("toggle", Key.DirectionCenter)
        compose.runOnIdle { assertEquals(false, checked) }
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
        compose.onNodeWithTag("row:0").assertIsFocused()
        compose.onNodeWithTag("tv-settings:fade-bottom").assertExists()
        repeat(11) { key("tv-settings:panel", Key.DirectionDown) }
        compose.onNodeWithTag("row:11").assertIsFocused().assertIsDisplayed()
        val support = compose.onNodeWithText(
            "Explanation for setting 11 that stays inside its focused row.",
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row:11").getUnclippedBoundsInRoot()
        assertTrue(support.top >= row.top && support.bottom <= row.bottom)
        assertEquals(title, compose.onNodeWithText("Subtitle appearance").getUnclippedBoundsInRoot())
        assertEquals(preview, compose.onNodeWithText("Live preview").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("tv-settings:fade-top").assertExists()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }
}
