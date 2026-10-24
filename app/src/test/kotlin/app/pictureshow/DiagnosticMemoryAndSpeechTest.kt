/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DiagnosticMemoryAndSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingLongJsonOnlyComposesTheViewportAndHideRemainsReachable() {
        val json = (1..1000).joinToString("\n") { "  \"fixture-$it\": $it," }
        val chunks = diagnosticTextChunks(json, 1f)
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        compose.setContent { MaterialTheme { ThisTvScreen(preferences, { _, _ -> ThisTvReport("Summary", json) }, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View device profile JSON").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(chunks.first()).assertIsDisplayed()
        compose.onNodeWithText(chunks.last()).assertDoesNotExist()
        compose.onNodeWithText("Hide device profile JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Summary").assertIsDisplayed()
    }

    @Test fun hidingJsonWhileAChunkHasInputFocusReturnsToTheToggle() {
        val json = (1..100).joinToString("\n") { "  \"fixture-$it\": $it," }
        val chunks = diagnosticTextChunks(json, 1f)
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        compose.setContent { MaterialTheme { ThisTvScreen(preferences, { _, _ -> ThisTvReport("Summary", json) }, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View device profile JSON").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.OnClick) { it() }
        // Accessibility activation is independent of input focus; retain the toggle's action while reading.
        val hide = compose.onNodeWithText("Hide device profile JSON").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText(chunks[2]).assertIsFocused()
        compose.runOnIdle { hide() }
        compose.onNodeWithText("View device profile JSON").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Summary").assertIsDisplayed()
    }

    @Test fun decoderSummaryOmitsUnreportedProfileAndLevelFields() {
        val caps = DeviceCapabilities(
            "TV",
            listOf(VideoDecoder(VideoCodec.VP8, emptySet(), maxWidth = 1920, maxHeight = 1080, maxFrameRate = 60.0)),
            DisplayCapabilities(1920, 1080, 60.0),
            emptyList(),
            AudioOutput(2, emptySet()),
            PlaybackPreferences().deviceSettings()
        )
        val summary = thisTvReport(caps, "1", "11", "Speaker", emptyList()).summary
        assertTrue(summary.contains("8-bit"))
        val depth = summary.lines().single { it.trim().startsWith("8-bit") }
        assertFalse(depth.contains("profiles:"), depth)
        assertFalse(depth.contains("level:"), depth)
    }

    @Test fun sortDirectionHasOneSpokenLabelWithoutDecorativeArrow() {
        val account = Account(Server("s", "Test", "https://media.example.test", "12"), User("u", "Viewer"), "token")
        compose.setContent {
            MaterialTheme {
                CatalogGrid(CatalogGridState(account, MediaItem("library", "Fixture")), {}, {}, { _, _ -> }, {})
            }
        }
        val semantics = compose.onNodeWithTag("grid:sort-order").fetchSemanticsNode().config
        assertTrue(semantics[SemanticsProperties.StateDescription] == "Ascending")
        assertFalse(semantics.contains(SemanticsProperties.ContentDescription))
        assertFalse(semantics.contains(SemanticsProperties.Text))
    }
}
