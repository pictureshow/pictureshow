/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.pictureshow.core.sync.SyncQuality
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The ordinary landscape player opens this panel at 360dp height, without a keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w640dp-h360dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayCompactTouchRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun touchMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }

    @Test fun joinedLandscapeOpensWithAllTransportControlsFullyVisibleAndUsable() {
        val actions = mutableListOf<Pair<String, String?>>()
        val state = SyncPlayPresentation(
            server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
            groupId = "g", groupName = "Film night", phase = "Paused", quality = SyncQuality.IN_SYNC,
            localPlayback = SyncLocalPlayback.READY,
            participants = listOf("Morgan", "Sam"),
            queue = listOf(SyncPlayQueueRow("a", "First film", true), SyncPlayQueueRow("b", "Second film", false))
        )
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                SyncPlayPanel(state, { op, value -> actions += op to value }, {})
            }
        }
        val panel = compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        val leave = compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        for ((tag, operation) in listOf("sync:previous" to "previous", "sync:play" to "play", "sync:next" to "next")) {
            val target = compose.onNodeWithTag(tag)
            // No performScrollTo: opening the panel must reveal useful transport immediately.
            target.assertIsDisplayed().assertIsEnabled()
            val bounds = target.getUnclippedBoundsInRoot()
            assertTrue(
                bounds.top >= panel.top && bounds.bottom <= leave.top && bounds.left >= panel.left && bounds.right <= panel.right,
                "Initial compact transport must fit completely above Leave, not under its footer: $tag=$bounds panel=$panel leave=$leave"
            )
            target.performClick()
            assertEquals(operation, actions.last().first)
        }
        assertEquals(listOf("previous", "play", "next"), actions.map { it.first })
        compose.onNodeWithTag("sync:leave").assertIsDisplayed()
    }
}
