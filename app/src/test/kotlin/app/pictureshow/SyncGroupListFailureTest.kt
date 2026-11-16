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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phone review P-2: when the group list can't be read, the phone chooser must not say "No groups are available" or
 * offer to start a group as if the server had answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class SyncGroupListFailureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aFailedListIsNotAnEmptyOneAndOffersNoCreate() {
        var state by mutableStateOf(
            SyncPlayPresentation(
                server = "Home",
                viewer = "pilot",
                policyKnown = true,
                policy = "CreateAndJoinGroups",
                groupsFailed = true
            )
        )
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(state, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("sync:groups-failed").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("sync:empty").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("sync:create").fetchSemanticsNodes().size)
        compose.runOnIdle { state = state.copy(groupsFailed = false) }
        compose.onNodeWithTag("sync:empty").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("sync:groups-failed").fetchSemanticsNodes().size)
        compose.onNodeWithTag("sync:create").assertExists()
    }
}
