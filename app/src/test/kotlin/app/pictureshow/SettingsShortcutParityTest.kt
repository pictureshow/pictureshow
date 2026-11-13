/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.playback.PassthroughEncoding
import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsShortcutParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun step(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
    }

    private fun openGroup(group: String) {
        val opener = compose.onNodeWithTag("settings:advanced:$group")
        opener.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        opener.assertIsFocused().assertIsDisplayed()
        opener.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun panelChoice(title: String) = compose.onNode(
        hasText(title) and hasClickAction() and hasAnyAncestor(hasTestTag("tv-settings:panel"))
    )

    private fun closePanel() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun mainCopyCase() = Json.parseToJsonElement(
        listOf(File("../spec/vectors/playback-settings-layout.json"), File("spec/vectors/playback-settings-layout.json"))
            .single { it.isFile }.readText()
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        .single { it.getValue("operation").jsonPrimitive.content == "main-settings-contextual-copy" }

    @Test fun mainTvShortcutsUseContextualNamesAndOneSegmentCaption() {
        val case = mainCopyCase()
        compose.setContent {
            MaterialTheme { PlaybackSettingsScreen(PlaybackSettingsState(loading = false), "PCM only", {}, { _, _ -> }, {}, onBack = {}) }
        }
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        case.getValue("input").jsonObject.getValue("rows").jsonArray.forEach { item ->
            val row = item.jsonObject
            val node = compose.onNodeWithTag(row.getValue("tag").jsonPrimitive.content)
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            node.assertIsDisplayed().assert(hasText(row.getValue("title").jsonPrimitive.content))
                .assert(hasText(row.getValue("supporting").jsonPrimitive.content))
        }
        val expected = case.getValue("expected").jsonObject
        assertEquals(
            expected.getValue("segmentCaptionCount").jsonPrimitive.int,
            compose.onAllNodesWithText(expected.getValue("segmentCaption").jsonPrimitive.content).fetchSemanticsNodes().size
        )
        assertEquals(
            expected.getValue("shortcutMetaCopyCount").jsonPrimitive.int,
            compose.onAllNodesWithText("Shortcut to", substring = true).fetchSemanticsNodes().size
        )
        assertEquals(0, compose.onAllNodesWithText("Ask shows a skip button.", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun mainTouchShortcutsUseContextualNamesAndOneSegmentCaption() {
        val case = mainCopyCase()
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                TouchSettings(PlaybackSettingsState(loading = false), "PCM only", {}, { _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        case.getValue("input").jsonObject.getValue("rows").jsonArray.forEach { item ->
            val row = item.jsonObject
            compose.onNodeWithText(row.getValue("title").jsonPrimitive.content).performScrollTo().assertIsDisplayed()
                .assert(hasText(row.getValue("supporting").jsonPrimitive.content))
        }
        val expected = case.getValue("expected").jsonObject
        assertEquals(
            expected.getValue("segmentCaptionCount").jsonPrimitive.int,
            compose.onAllNodesWithText(expected.getValue("segmentCaption").jsonPrimitive.content).fetchSemanticsNodes().size
        )
        assertEquals(
            expected.getValue("shortcutMetaCopyCount").jsonPrimitive.int,
            compose.onAllNodesWithText("Shortcut to", substring = true).fetchSemanticsNodes().size
        )
    }

    @Test fun homeAndSubtitleShortcutsAndTheirPanelsEditTheSamePreferencesInBothDirections() {
        var state by mutableStateOf(PlaybackSettingsState(loading = false))
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(state, "PCM only", { change ->
                    state = state.copy(local = change(state.local))
                }, { _, _ -> }, {}, onBack = {})
            }
        }
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        step("settings:bitrate")
        compose.runOnIdle { assertEquals(120, state.local.bitrateMbps) }
        openGroup("Network quality")
        panelChoice("Home limit").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "120 Mbps"))
        panelChoice("Home limit").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(80, state.local.bitrateMbps) }
        closePanel()
        compose.onNodeWithTag("settings:advanced:Network quality").assertIsFocused()
        compose.onNodeWithTag("settings:bitrate").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "80 Mbps"))
        step("settings:size")
        compose.runOnIdle { assertEquals(SubtitleSize.Small, state.local.subtitleSize) }
        openGroup("Subtitle appearance")
        panelChoice(
            "Font size"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, subtitleSizeLabel(SubtitleSize.Small)))
        panelChoice("Font size").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(SubtitleSize.Normal, state.local.subtitleSize) }
        closePanel()
        compose.onNodeWithTag(
            "settings:size"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, subtitleSizeLabel(SubtitleSize.Normal)))
        step("settings:background")
        compose.runOnIdle { assertEquals(SubtitleBackground.None, state.local.subtitleBackground) }
        openGroup("Subtitle appearance")
        panelChoice(
            "Background"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, subtitleBackgroundLabel(SubtitleBackground.None)))
        panelChoice("Background").performSemanticsAction(SemanticsActions.RequestFocus)
        panelChoice("Background").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(SubtitleBackground.Shadow, state.local.subtitleBackground) }
        closePanel()
        compose.onNodeWithTag(
            "settings:background"
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, subtitleBackgroundLabel(SubtitleBackground.Shadow)))
    }

    @Test fun detectedRouteFormatsReachAudioPanelAndDisconnectionDoesNotDiscardStoredPreferences() {
        var supported by mutableStateOf(setOf(PassthroughEncoding.AC3))
        var state by mutableStateOf(PlaybackSettingsState(loading = false))
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    state,
                    "AC3",
                    { change -> state = state.copy(local = change(state.local)) },
                    { _, _ -> },
                    {},
                    supportedPassthrough = supported,
                    onBack = {}
                )
            }
        }
        openGroup("Audio output")
        compose.onNodeWithTag("settings:Dolby Digital").assertIsEnabled()
        compose.onNodeWithTag("settings:DTS").assertDoesNotExist()
        compose.onNodeWithTag("settings:Dolby Digital").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(setOf(PassthroughEncoding.AC3), state.local.tuning.disabledPassthrough) }
        compose.runOnIdle { supported = emptySet() }
        PassthroughEncoding.entries.forEach { compose.onNodeWithTag("settings:${passthroughLabel(it)}").assertDoesNotExist() }
        compose.onNodeWithText("Format choices appear when a receiver is connected.").assertExists()
        compose.runOnIdle { assertEquals(setOf(PassthroughEncoding.AC3), state.local.tuning.disabledPassthrough) }
        closePanel()
        compose.onNodeWithText("On when a compatible receiver is connected. None detected now.", substring = true).assertExists()
        compose.onNodeWithText("PCM only", substring = true).assertDoesNotExist()
    }

    @Test fun unsupportedFormatsStayHiddenAndEveryDetectedDisabledFormatRemainsReadableWithoutChangingPermission() {
        var supported by mutableStateOf(emptySet<PassthroughEncoding>())
        var state by mutableStateOf(
            PlaybackSettingsState(
                loading = false,
                local = PlaybackPreferences().copy(
                    tuning = PlaybackPreferences().tuning.copy(disabledPassthrough = setOf(PassthroughEncoding.DTS))
                )
            )
        )
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    state,
                    "PCM only",
                    { change -> state = state.copy(local = change(state.local)) },
                    { _, _ -> },
                    {},
                    supportedPassthrough = supported,
                    onBack = {}
                )
            }
        }
        openGroup("Audio output")
        PassthroughEncoding.entries.forEach { compose.onNodeWithTag("settings:${passthroughLabel(it)}").assertDoesNotExist() }
        compose.onNodeWithText("Format choices appear when a receiver is connected.").assertExists()
        compose.runOnIdle { assertEquals(setOf(PassthroughEncoding.DTS), state.local.tuning.disabledPassthrough) }
        compose.runOnIdle { supported = PassthroughEncoding.entries.toSet() }
        compose.onNodeWithText("Format choices appear when a receiver is connected.").assertDoesNotExist()
        fun toggle(title: String) {
            panelChoice(title).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
        }
        fun verifyDisabledRows() {
            panelChoice("Audio channel limit").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
            fun down() {
                compose.onNodeWithTag("tv-settings:panel").performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
            }
            down() // Downmix to stereo.
            down() // Global passthrough permission.
            val before = state.local
            PassthroughEncoding.entries.forEach { format ->
                down()
                val row = compose.onNodeWithTag("settings:${passthroughLabel(format)}")
                row.assertIsFocused().assertIsDisplayed().assertIsNotEnabled()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                compose.onNodeWithTag("tv-settings:panel").performKeyInput { pressKey(Key.DirectionCenter) }
                compose.waitForIdle()
                row.assertIsFocused()
                compose.runOnIdle { assertEquals(before, state.local) }
            }
        }
        toggle("Audio passthrough")
        compose.runOnIdle { assertEquals(false, state.local.passthrough) }
        verifyDisabledRows()
        toggle("Audio passthrough")
        toggle("Downmix to stereo")
        compose.runOnIdle {
            assertEquals(true, state.local.passthrough)
            assertEquals(true, state.local.tuning.downmix)
        }
        panelChoice("Audio passthrough").assert(hasText("Downmix is on, so passthrough is not used.", substring = true))
        verifyDisabledRows()
        closePanel()
        compose.onNodeWithTag("settings:passthrough").assert(hasText("Downmix is on, so passthrough is not used.", substring = true))
    }
}
