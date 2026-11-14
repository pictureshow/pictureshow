/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.sync.SyncQuality
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
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
class SyncPlayDirectorFollowupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun tvMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun TvEnvironment(content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) { PictureshowTheme(content) }
    }
    private fun joined() = SyncPlayPresentation(
        server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
        groupId = "g", groupName = "Friday films", phase = "Paused", localPlayback = SyncLocalPlayback.READY,
        queue = (1..9).map { SyncPlayQueueRow("e$it", "Film $it", it == 1) }, participants = List(6) { "Person $it" }
    )
    private fun show(state: SyncPlayPresentation) {
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.waitForIdle()
    }
    private fun key(key: Key) {
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun chooserNameFocusDoesNotOpenSoftwareKeyboardUntilSelect() {
        show(SyncPlayPresentation(policyKnown = true, policy = "CreateAndJoinGroups"))
        compose.onNodeWithTag("sync:name").assertIsFocused()
        val imm = compose.activity.getSystemService(InputMethodManager::class.java)
        assertFalse(shadowOf(imm).isSoftInputVisible, "TV initial name focus must not open the keyboard")
        key(Key.DirectionCenter)
        assertTrue(shadowOf(imm).isSoftInputVisible, "Explicit TV Select must still open the editor")
    }

    @Test fun transportLeavesRoomForFocusPaintBetweenBothNeighbours() {
        show(joined())
        val previous = compose.onNodeWithTag("sync:previous").getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("sync:play").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithTag("sync:next").getUnclippedBoundsInRoot()
        assertTrue(play.left - previous.right >= 12.dp, "Previous/Play need12dp gap: $previous / $play")
        assertTrue(next.left - play.right >= 12.dp, "Play/Next need12dp gap: $play / $next")
    }

    @Test fun pinnedDestructiveFooterHasVerticalTitleSafeInset() {
        show(joined())
        val leave = compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        assertTrue(leave.bottom <= 516.dp, "Leave must finish at least24dp above a540dp TV bottom: $leave")
    }

    @Test fun warningBannerOwnsRecoveryActionInsteadOfRepeatingRejoinInPhase() {
        show(joined().copy(quality = SyncQuality.OUT_OF_SYNC))
        val phase = compose.onNodeWithTag("sync:phase").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertFalse(phase.contains("Rejoin"), "Recovery banner owns the action; phase must not impersonate another action: $phase")
        compose.onNodeWithTag("sync:recovery").assertIsFocused()
    }

    @Test fun qualityWarningHasDeviceSpecificCopyDistinctFromConnectionError() {
        show(joined().copy(quality = SyncQuality.OUT_OF_SYNC))
        compose.onNodeWithText("This device is out of sync with the group. Rejoin to catch up.").assertExists()
    }

    @Test fun remoteScrolledFocusRestsAboveBottomFadeWithBreathingRoom() {
        show(joined())
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        compose.onNodeWithTag("sync:shuffle").assertIsFocused()
        val row = compose.onNodeWithTag("sync:shuffle").getUnclippedBoundsInRoot()
        val fade = compose.onNodeWithTag("tv-settings:fade-bottom").getUnclippedBoundsInRoot()
        assertTrue(row.bottom <= fade.top - 8.dp, "Focused row must clear the opaque fade plus8dp: $row / $fade")
    }

    @Test fun currentRealTitleIsVisibleAboveTheGroupPhase() {
        show(joined())
        compose.onNodeWithTag("sync:now-playing").assertIsDisplayed().assertTextContains("Film 1", substring = true)
        val title = compose.onNodeWithTag("sync:now-playing").getUnclippedBoundsInRoot()
        val phase = compose.onNodeWithTag("sync:phase").getUnclippedBoundsInRoot()
        assertTrue(title.bottom <= phase.top, "Current title must precede its phase: $title / $phase")
    }

    @Test fun unavailableCurrentTitleUsesTheLocalizedPlaceholderAbovePhase() {
        show(
            joined().copy(queue = listOf(SyncPlayQueueRow("entry-private", null, true, itemId = "media-private", titleUnavailable = true)))
        )
        compose.onNodeWithTag("sync:now-playing").assertIsDisplayed().assertTextContains("Title unavailable", substring = true)
    }
}
