/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.SegmentType
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The viewer's own settings come before the technical decoder report, and Back lands on the entry that was opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsOrderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val viewerSettings = listOf("settings:bitrate", "settings:passthrough", "settings:size", "settings:background") +
        SegmentType.entries.filter { it != SegmentType.Unknown }.map { "settings:segment:$it" } +
        listOf("settings:audio-language", "settings:subtitle-language", "settings:subtitle-mode")

    private fun open() {
        val report = ThisTvReport("Decoder\nReported capability", "{}")
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "settings") {
                    composable("settings") {
                        PlaybackSettingsScreen(
                            PlaybackSettingsState(),
                            "PCM only",
                            {},
                            { _, _ -> },
                            {},
                            onThisTv = { nav.navigate("this-tv") },
                            onAbout = { nav.navigate("about") },
                            onBack = {}
                        )
                    }
                    composable("this-tv") { ThisTvScreen(preferences, capture = { _, _ -> report }) { nav.popBackStack() } }
                    aboutScreens(nav)
                }
            }
        }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    @Test
    fun viewerSettingsComeBeforeTheThisTvReport() {
        open()
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        val thisTv = compose.onNodeWithTag("settings:this-tv").getUnclippedBoundsInRoot()
        viewerSettings.forEach { tag ->
            val setting = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(setting.bottom < thisTv.top, "$tag ($setting) should come before This TV ($thisTv)")
        }
    }

    @Test
    fun backFromThisTvLandsOnThisTvAfterWalkingDownTheList() {
        open()
        // Walk the whole list with the remote, as a viewer would, to the report at the end.
        viewerSettings.drop(1).forEach { tag ->
            key(Key.DirectionDown)
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        key(Key.DirectionDown)
        compose.onNodeWithTag("settings:this-tv").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Refresh capabilities").assertExists() }.isSuccess }
        back()
        compose.onNodeWithTag("settings:this-tv").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("settings:back").assertIsFocused()
    }

    @Test
    fun backFromAboutLandsOnAboutNotTheFirstSetting() {
        open()
        key(Key.DirectionUp)
        compose.onNodeWithTag("settings:about").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("about:licenses").assertIsFocused()
        back()
        compose.onNodeWithTag("settings:about").assertIsFocused().assertIsDisplayed()
    }
}
