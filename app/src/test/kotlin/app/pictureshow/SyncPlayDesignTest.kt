/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.sync.SyncQuality
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvDesignTest {
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun shortStatusChipWrapsContentWithinItsProductionWidthCap() {
        val state = SyncPlayPresentation(groupId = "g", groupName = "Films", phase = "Idle", participants = listOf("Sam"))
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.width(380.dp)) {
                    SyncPlayerStatusChip(state, {}, Modifier.widthIn(max = 380.dp), tv = true)
                }
            }
        }
        val bounds = compose.onNodeWithTag("sync:player-chip").getUnclippedBoundsInRoot()
        // A cap is not a requested width: a short status must leave substantial unused room.
        assertTrue(bounds.right - bounds.left < 340.dp, "Short chip must wrap content instead of filling the available width cap: $bounds")
        compose.onNodeWithText("1 person watching", useUnmergedTree = true).assertIsDisplayed()
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun tvMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java)).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Test fun modeAndDataTransitionsFocusTheirOwnPrimaryRatherThanClose() {
        var state by mutableStateOf(SyncPlayPresentation(policyKnown = false, busy = true))
        compose.setContent { PictureshowTheme { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        compose.runOnIdle {
            state = state.copy(
                policyKnown = true,
                policy = "CreateAndJoinGroups",
                busy = false,
                groups = listOf(SyncPlayGroupRow("g", "Film night", 2))
            )
        }
        compose.onNodeWithTag("sync:join:g").assertIsFocused()
        compose.runOnIdle { state = state.copy(groupId = "g", groupName = "Film night", phase = "Paused", queue = listOf(SyncPlayQueueRow("e", "Film", true))) }
        compose.onNodeWithTag("sync:play").assertIsFocused()
        compose.runOnIdle { state = state.copy(groupId = null, groupName = null, phase = "Idle", groups = emptyList()) }
        compose.onNodeWithTag("sync:name").assertIsFocused()
    }

    @Test fun leaveStaysPinnedAndNamesRemainSeparateUnknownFacts() {
        val state =
            SyncPlayPresentation(
                policyKnown = true,
                policy = "CreateAndJoinGroups",
                groupId = "g",
                groupName = "Film night",
                phase = "Waiting",
                waitReason = "Buffer",
                participants = listOf("Morgan", "Morgan", "Sam", "Riley", "Jordan"),
                queue = (1..9).map { SyncPlayQueueRow("e$it", "Film $it", it == 1) }
            )
        compose.setContent { PictureshowTheme { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.onNodeWithTag("sync:leave").assertIsDisplayed()
        compose.onNodeWithTag("sync:phase").assertIsDisplayed()
        compose.onNodeWithTag("sync:participant:3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync:participant:4").assertDoesNotExist()
        compose.onNodeWithText("+1 more person").assertExists()
        compose.onNodeWithTag("sync:leave").assertIsDisplayed()
        val leave = compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        assertTrue(leave.bottom <= panel.bottom)
        assertTrue(leave.top >= panel.top)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTouchDesignTest {
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun shortStatusChipWrapsContentWithinItsProductionWidthCap() {
        val state = SyncPlayPresentation(groupId = "g", groupName = "Films", phase = "Idle", participants = listOf("Sam"))
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                Box(Modifier.width(380.dp)) {
                    SyncPlayerStatusChip(state, {}, Modifier.widthIn(max = 380.dp), tv = false)
                }
            }
        }
        val bounds = compose.onNodeWithTag("sync:player-chip").getUnclippedBoundsInRoot()
        // A cap is not a requested width: a short status must leave substantial unused room.
        assertTrue(bounds.right - bounds.left < 340.dp, "Short chip must wrap content instead of filling the available width cap: $bounds")
        compose.onNodeWithText("1 person watching", useUnmergedTree = true).assertIsDisplayed()
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun touchMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java)).setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun narrowChipSeparatesContextAndKeepsIconAndCountClear() {
        var opened = 0
        val state = SyncPlayPresentation(
            groupId = "g", groupName = "Friday films with friends from every time zone", phase = "Paused",
            participants = List(12) { "Person $it" }, quality = SyncQuality.IN_SYNC
        )
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                Box(Modifier.width(240.dp)) { SyncPlayerStatusChip(state, { opened++ }, Modifier.width(240.dp)) }
            }
        }
        val statusNode = compose.onNodeWithTag("sync:chip-status", useUnmergedTree = true)
        val status = statusNode.getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithTag("sync:chip-icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("sync:chip-name", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val count = compose.onNodeWithTag("sync:chip-count", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(status.left - icon.right >= 8.dp)
        assertTrue(status.bottom <= name.top)
        assertTrue(count.left - name.right >= 8.dp)
        compose.onNodeWithText("12 people watching", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        statusNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1, "This regression must exercise an actual status wrap")
        for (line in 0 until layout.lineCount) {
            val text = layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line)).trim()
            assertTrue(!text.startsWith("·"), "Separator must never lead a wrapped status line")
        }
        compose.onNodeWithTag("sync:player-chip").performClick()
        assertEquals(1, opened)
    }

    @Test fun fixedTransportSelectedRepeatAndInverseWaitAreServerConfirmed() {
        val actions = mutableListOf<Pair<String, String?>>()
        val state =
            SyncPlayPresentation(groupId = "g", groupName = "Film night", phase = "Paused", repeat = "RepeatOne", shuffle = "Shuffle")
        compose.setContent { app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(state, { op, value -> actions += op to value }, {}) } }
        compose.onNodeWithTag("sync:close").assertIsDisplayed()
        compose.onNodeWithTag("sync:pause").assertDoesNotExist()
        val previous = compose.onNodeWithTag("sync:previous").getUnclippedBoundsInRoot()
        val play = compose.onNodeWithTag("sync:play").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithTag("sync:next").getUnclippedBoundsInRoot()
        assertEquals((previous.top + previous.bottom) / 2, (play.top + play.bottom) / 2)
        assertEquals((play.top + play.bottom) / 2, (next.top + next.bottom) / 2)
        compose.onNodeWithTag("sync:repeat:RepeatOne").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("sync:repeat:RepeatAll").performClick()
        compose.onNodeWithTag("sync:repeat:RepeatOne").assertIsSelected()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and hasAnyAncestor(hasTestTag("sync:ignore-wait"))
        )
            .performScrollTo().assertIsOn().performClick()
        assertEquals(listOf<Pair<String, String?>>("repeat" to "RepeatAll", "ignore-wait" to "true"), actions)
        compose.onNodeWithTag("sync:leave").assertIsDisplayed()
    }

    @Test fun sharedCastConsentKeepsActualCancelAndConfirmCallbacks() {
        var dismissed = 0
        var confirmed = 0
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                SyncLeaveForCastConsent(onDismiss = { dismissed++ }, onConfirm = { confirmed++ })
            }
        }
        compose.onNodeWithText("Leave Watch together before casting?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
        compose.onNodeWithText("Leave group and cast").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test fun rawDiagnosticAndPhaseEnumsNeverReachViewersAndErrorsHaveActions() {
        val state = SyncPlayPresentation(
            groupId = "g",
            phase = "Reconnecting",
            status = "Raw engineering barrier ±42 ms",
            notice = SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.ERROR, SyncNoticeAction.REJOIN)
        )
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent { app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(state, { op, value -> actions += op to value }, {}) } }
        compose.onNodeWithText(state.status).assertDoesNotExist()
        compose.onNodeWithText("Reconnecting").assertDoesNotExist()
        compose.onNodeWithText("Lost sync with the group. Rejoin to catch up.").assertIsDisplayed()
        compose.onNodeWithTag("sync:recovery").performClick()
        assertEquals(listOf<Pair<String, String?>>("join" to "g"), actions)
        compose.onNodeWithTag("sync:leave").assertIsDisplayed()
    }
}
