/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.sync.SyncStayReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** SYN-08 reminder: reachable by touch, D-pad and screen readers, with Watch only when there is something to rejoin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncStayReminderUiTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reminderRoutesAreHomeLibrariesAndTitlePagesOnly() {
        assertTrue(syncStayRoute("home"))
        assertTrue(syncStayRoute("libraries"))
        assertTrue(syncStayRoute("item?item={item}&liveRecording={liveRecording}"))
        assertTrue(syncStayRoute("item?item={item}"))
        listOf(null, "player", "syncplay", "settings", "music/now", "cast", "search").forEach {
            assertFalse(syncStayRoute(it), "$it")
        }
    }

    @Test fun touchReminderNamesTheGroupAndItsActionsForScreenReaders() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                TouchSyncStayBar(
                    SyncStayReminder("Evening", rejoin = true, leaveUnconfirmed = false),
                    onWatch = { clicks += "watch" },
                    onLeave = { clicks += "leave" }
                )
            }
        }
        compose.onNodeWithTag("sync:stay:line").assertTextEquals("Still in Evening")
        compose.onNodeWithContentDescription("Watch with Evening").performClick()
        compose.onNodeWithContentDescription("Leave Evening").performClick()
        compose.runOnIdle { assertEquals(listOf("watch", "leave"), clicks) }
    }

    @Test fun reminderWithoutAQueueOrWithAnUnconfirmedLeaveOffersOnlyLeave() {
        var reminder by androidx.compose.runtime.mutableStateOf(SyncStayReminder(null, rejoin = false, leaveUnconfirmed = false))
        compose.setContent {
            androidx.compose.material3.MaterialTheme { TouchSyncStayBar(reminder, {}, {}) }
        }
        compose.onNodeWithTag("sync:stay:line").assertTextEquals("Still in your Watch together group")
        compose.onNodeWithTag("sync:stay:watch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Leave the group").assertExists()
        compose.runOnIdle { reminder = SyncStayReminder("Evening", rejoin = false, leaveUnconfirmed = true) }
        compose.onNodeWithTag("sync:stay:line").assertTextEquals("Leaving Evening wasn’t confirmed")
        compose.onNodeWithTag("sync:stay:watch").assertDoesNotExist()
        compose.onNodeWithTag("sync:stay:leave").assertExists()
    }

    @Test fun tvReminderIsReachableByDpadAndActivatesWithCenter() {
        val clicks = mutableListOf<String>()
        val start = FocusRequester()
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.tv.material3.Button(onClick = {}, modifier = Modifier.focusRequester(start)) {
                        androidx.tv.material3.Text("Row above")
                    }
                    TvSyncStayBar(
                        SyncStayReminder("Evening", rejoin = true, leaveUnconfirmed = false),
                        onWatch = { clicks += "watch" },
                        onLeave = { clicks += "leave" }
                    )
                }
            }
        }
        compose.runOnIdle { start.requestFocus() }
        compose.waitForIdle()
        key(Key.DirectionDown)
        compose.onNodeWithTag("sync:stay:watch").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("sync:stay:leave").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Watch with Evening").assertExists()
        compose.runOnIdle { assertEquals(listOf("leave"), clicks) }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }
}
