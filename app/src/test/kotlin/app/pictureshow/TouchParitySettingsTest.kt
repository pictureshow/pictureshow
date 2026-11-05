/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** SET-10, SET-11 and the search recent chip in touch form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h850dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchParitySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settings(onThisDevice: () -> Unit = {}): () -> PlaybackSettingsState {
        var state by mutableStateOf(PlaybackSettingsState())
        compose.setContent {
            MaterialTheme {
                TouchSettings(
                    state,
                    "PCM",
                    { change -> state = state.copy(local = change(state.local)) },
                    { _, _ -> },
                    {},
                    {},
                    {},
                    {},
                    onThisDevice = onThisDevice
                )
            }
        }
        return { state }
    }

    @Test fun conversionNoticeDefaultsOnAndTogglesTheStoredPreference() {
        val state = settings()
        val toggle = compose.onNodeWithTag("touch:settings:conversion-notice").performScrollTo().assertIsOn()
        compose.onNodeWithText("Shows a short note when the server has to convert a video for this phone.").assertExists()
        toggle.performClick()
        compose.waitForIdle()
        toggle.assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        compose.runOnIdle { assertEquals(false, state().local.conversionNotice) }
    }

    // Catalog T10: the whole row toggles, and it is one named switch.
    @Test fun aSettingsSwitchTogglesFromItsWholeRow() {
        settings()
        compose.onNodeWithText("Audio passthrough").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("touch:settings:passthrough").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test fun conversionNoticeSitsDirectlyAfterAudioPassthrough() {
        settings()
        val toggle = compose.onNodeWithTag("touch:settings:conversion-notice").performScrollTo().getBoundsInRoot()
        val passthrough = compose.onNodeWithTag("touch:settings:passthrough").getBoundsInRoot()
        val next = compose.onNodeWithText("Subtitle size").getBoundsInRoot()
        assertTrue(passthrough.bottom <= toggle.top, "the notice follows passthrough")
        assertTrue(toggle.bottom <= next.top, "no other setting comes between them")
    }

    @Test fun thisDeviceIsNamedForTheFormFactorAtTheTabletBreakpoint() {
        assertEquals("This phone", touchDeviceName(599))
        assertEquals("This tablet", touchDeviceName(600))
    }

    @Test fun settingsOpenThisDevice() {
        var opened = 0
        settings(onThisDevice = { opened++ })
        compose.onNodeWithText("This phone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Config(qualifiers = "sw720dp-w1000dp-h700dp-land-xhdpi")
    @Test
    fun aTabletSaysTablet() {
        settings()
        compose.onNodeWithText("This tablet").performScrollTo()
        compose.onNodeWithText("Shows a short note when the server has to convert a video for this tablet.").assertExists()
    }

    @Test fun thisDeviceShowsTheCapturedReportWithoutSayingTv() {
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        val report = ThisTvReport("Decoder fixture\nReported capability", "{\"fixture\":true}")
        compose.setContent { MaterialTheme { TouchThisDevice(preferences, capture = { _, _ -> report }, onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("touch:this-device:report").runCatching { fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText("This phone").assertExists()
        compose.onNodeWithText("Decoder fixture", substring = true).assertExists()
        compose.onNodeWithText("TV", substring = true).assertDoesNotExist()
        compose.onNodeWithText("View device profile JSON").performScrollTo().performClick()
        compose.onNodeWithText("{\"fixture\":true}").assertExists()
    }

    @Test fun aFailedCaptureExplainsAndRefreshRecovers() {
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        var fail = true
        compose.setContent {
            MaterialTheme {
                TouchThisDevice(
                    preferences,
                    capture = { _, _ ->
                        check(!fail)
                        ThisTvReport("Recovered capability", "{}")
                    },
                    onBack = {}
                )
            }
        }
        val failed = "Could not read capabilities. Select Refresh to try again."
        compose.waitUntil(5_000) { compose.onNodeWithText(failed).runCatching { fetchSemanticsNode() }.isSuccess }
        fail = false
        compose.onNodeWithText("Refresh capabilities").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("Recovered capability").runCatching { fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithText(failed).assertDoesNotExist()
    }

    @Test fun sharedDiagnosticsAreNamedForTheDevice() {
        val intent = thisTvShareIntent(ThisTvReport("Summary", "{}"), "This phone")
        assertEquals("Pictureshow · This phone", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Pictureshow · This TV", thisTvShareIntent(ThisTvReport("Summary", "{}")).getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test fun aRecentSearchPutsTheCaretAtTheEndOfItsText() {
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        var state by mutableStateOf(SearchState(recent = listOf("recent words")))
        compose.setContent {
            MaterialTheme {
                TouchSearchScreen(
                    state,
                    account,
                    { state = state.copy(query = it) },
                    {},
                    { state = state.copy(query = it) },
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithText("recent words").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("touch:search:query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange("recent words".length))
        )
    }
}
