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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.playback.PassthroughEncoding
import kotlin.test.assertEquals
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
        compose.onNodeWithText("Downmix is on, so passthrough is not used.", substring = true).assertExists()
        verifyDisabledRows()
        closePanel()
        compose.onNodeWithText("Downmix is on, so passthrough is not used.", substring = true).assertExists()
    }
}
