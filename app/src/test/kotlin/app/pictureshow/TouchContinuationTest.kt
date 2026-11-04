/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchContinuationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private var paused by mutableStateOf(false)
    private var ended by mutableStateOf(false)
    private var visible by mutableStateOf(true)
    private var plays = 0
    private var cancels = 0

    @Test fun touchCountdownPausesForSheetAndContinuesFromSameRemainingTime() {
        launch()
        compose.mainClock.advanceTimeBy(3_100)
        compose.onNodeWithText("Next episode · 7 s left").assertIsDisplayed()
        compose.runOnIdle {
            paused = true
            visible = false
        }
        settleUi()
        compose.mainClock.advanceTimeBy(12_000)
        compose.runOnIdle {
            paused = false
            visible = true
            assertEquals(0, plays)
        }
        settleUi()
        val tree = compose.onRoot().printToString()
        kotlin.test.assertTrue(tree.contains("7 s left"), tree)
        compose.onNodeWithText("Next episode · 7 s left").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(7_100)
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test fun touchCancelPreventsCountdownAndEndFromPlayingNext() {
        launch()
        compose.onNodeWithTag("next:cancel").performClick()
        compose.runOnIdle { ended = true }
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(1, cancels)
        }
        compose.onNodeWithTag("next:play").assertDoesNotExist()
    }

    @Test fun touchPlayNowRunsOnceAndRemovesCard() {
        launch()
        compose.onNodeWithTag("next:play").performClick()
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle { assertEquals(1, plays) }
        compose.onNodeWithTag("next:play").assertDoesNotExist()
    }

    private fun settleUi() {
        // Let pending snapshot/recomposition/layout work settle before measuring another timed interval.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun launch() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    NextEpisodeControl(
                        account, MediaItem("next", "Another episode"), true, ended, paused,
                        onFocusRemoved = {}, onPlay = { plays++ }, onCancel = { cancels++ }, visible = visible,
                        card = { seconds, play, cancel -> TouchNextEpisodeCard("Another episode", seconds, true, play, cancel) }
                    )
                }
            }
        }
    }
}
