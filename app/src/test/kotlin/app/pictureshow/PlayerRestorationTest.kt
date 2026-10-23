/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import app.pictureshow.core.MediaItem
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restorationRecoversCurrentEpisodeWithoutOpeningAnotherPlayer() {
        val first = MediaItem("one", "Episode one", "Episode")
        val next = MediaItem("two", "Episode two", "Episode")
        var restored: MediaItem? = null
        var playerCompositions = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PlayerRestoration(first, onRestore = { restored = it }) { onCurrent ->
                SideEffect {
                    playerCompositions++
                    onCurrent(next)
                }
                Text("Playing episode two")
            }
        }
        compose.onNodeWithText("Playing episode two").assertExists()
        compose.runOnIdle { assertNull(restored) }
        val before = playerCompositions
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(next, restored)
            assertEquals(before, playerCompositions)
        }
    }

    @Test
    fun manifestHandlesInputAndDisplayChangesButLeavesFontAndLocaleToRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        val handled = ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_KEYBOARD_HIDDEN or ActivityInfo.CONFIG_NAVIGATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_UI_MODE
        assertEquals(handled, info.configChanges and handled)
        assertEquals(0, info.configChanges and (ActivityInfo.CONFIG_FONT_SCALE or ActivityInfo.CONFIG_LOCALE))
    }
}
