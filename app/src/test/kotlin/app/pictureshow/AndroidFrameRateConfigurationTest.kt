/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidFrameRateConfigurationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun observableConfigurationStartsAndDisposesTvModeRequestWithoutReplacingContextOrPlayer() {
        var added = 0
        var removed = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentTracks" -> Tracks.EMPTY
                "getApplicationLooper" -> Looper.getMainLooper()
                "addListener" -> {
                    added++
                    null
                }
                "removeListener" -> {
                    removed++
                    null
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Configuration player"
                else -> error("Unexpected player method: ${method.name}")
            }
        } as Player
        val configuration = mutableStateOf(Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_NORMAL })
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                AndroidFrameRateMatch(player, enabled = true)
            }
        }
        compose.runOnIdle {
            assertEquals(0, added)
            configuration.value = Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_TELEVISION }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, added, "Changing the observable configuration must activate the TV adapter")
            assertEquals(0, removed)
            configuration.value = Configuration().apply { uiMode = Configuration.UI_MODE_TYPE_NORMAL }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, added)
            assertEquals(1, removed, "Leaving TV mode disposes its request without replacing the player")
        }
    }
}
