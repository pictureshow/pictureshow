/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NextEpisodeCopyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val account = Account(Server("server", "Fixture", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private val next = MediaItem("next", "Next story")

    @Test fun secondsRemainUnambiguousWhileRunningAndWhileHeld() {
        var paused by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { NextEpisodeControl(account, next, true, false, paused, {}, {}, countdownSeconds = 10) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Next episode · 10 s left").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText("Next episode · 9 s left").assertIsDisplayed()
        compose.runOnIdle { paused = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("Next episode · 9 s left").assertIsDisplayed()
    }

    @Test fun timeUntilTheEndAlsoNamesItsUnit() {
        compose.setContent {
            MaterialTheme { NextEpisodeControl(account, next, true, false, true, {}, {}, remainingMs = 9_200) }
        }
        compose.onNodeWithText("Next episode · 10 s left").assertIsDisplayed()
    }

    @Test fun talkBackHeadingHasTheEpisodeAndNoCountdownWhileAutoplayStaysOff() {
        var plays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { NextEpisodeControl(account, next, true, true, true, {}, { plays++ }, autoPlay = false) }
        }
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithContentDescription("Next episode, Next story").assertExists()
        compose.onNodeWithText("Next episode", useUnmergedTree = true).assertDoesNotExist() // heading owns one spoken label
        compose.runOnIdle { assertEquals(0, plays) }
    }
}
