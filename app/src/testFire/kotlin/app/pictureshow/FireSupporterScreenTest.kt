/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FireSupporterScreenTest {
    @get:Rule(order = -2) val timeLimit = PerTestTimeLimit(180)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun remoteOpensPublicWebsiteFromTheSupporterRoute() {
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, "supporter") { supporterScreen(nav) }
            }
        }
        compose.onNodeWithTag("supporter:purchase").assertDoesNotExist()
        compose.onNodeWithTag("supporter:website").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://pictureshow.app/", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun missingBrowserLeavesAReadableFallbackAndRemoteBack() {
        var left = false
        val noBrowser = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides noBrowser) {
                MaterialTheme { FireSupporterScreen { left = true } }
            }
        }
        compose.onNodeWithTag("supporter:website").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("supporter:website").assertDoesNotExist()
        compose.onNodeWithTag("supporter:unavailable").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("supporter:back").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(left) }
    }
}
