/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Behavioural checks for the TV director's accepted findings; no production-only test hooks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayDirectorRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun tvMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable
    private fun TvEnvironment(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            PictureshowTheme(content)
        }
    }

    private fun joined() = SyncPlayPresentation(
        server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
        groupId = "g", groupName = "Film night", phase = "Paused",
        queue = listOf(SyncPlayQueueRow("a", "First film", true)),
        localPlayback = SyncLocalPlayback.READY
    )

    private fun show(state: SyncPlayPresentation, action: (String, String?) -> Unit = { _, _ -> }) {
        compose.setContent { TvEnvironment { SyncPlayPanel(state, action, {}) } }
        compose.waitForIdle()
    }

    private fun key(key: Key) {
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focus(tag: String, scroll: Boolean = true) {
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    @Test fun reconnectOpensOnRejoinAndRemoteSelectSendsThatRecovery() {
        val actions = mutableListOf<Pair<String, String?>>()
        show(joined().copy(phase = "Reconnecting")) { op, value -> actions += op to value }
        compose.onNodeWithTag("sync:recovery").assertIsFocused()
        key(Key.DirectionCenter)
        assertEquals(listOf<Pair<String, String?>>("join" to "g"), actions, "Initial Select must rejoin, not leave the group")
    }

    @Test fun newlyOfferedRecoveryTakesFocusEvenWhenPlaybackUsabilityDoesNotChange() {
        var state by mutableStateOf(joined())
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.onNodeWithTag("sync:play").assertIsFocused()
        compose.runOnIdle {
            state = state.copy(notice = SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.WARNING, SyncNoticeAction.REJOIN))
        }
        compose.onNodeWithTag("sync:recovery").assertIsFocused()
    }

    @Test fun emptyAuthoritativeQueueDisablesEveryTransport() {
        show(joined().copy(phase = "Idle", queue = emptyList()))
        for (tag in listOf("sync:previous", "sync:play", "sync:next")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
    }

    @Test fun emptyAuthoritativeQueueStartsOnUsefulWaitControl() {
        val actions = mutableListOf<Pair<String, String?>>()
        show(joined().copy(phase = "Idle", queue = emptyList())) { op, value -> actions += op to value }
        compose.onNodeWithTag("sync:ignore-wait").assertIsEnabled().assertIsFocused()
        key(Key.DirectionCenter)
        assertEquals(listOf<Pair<String, String?>>("ignore-wait" to "true"), actions)
    }

    @Test fun remoteCanReachEachCappedNameAndOverflowWithoutInventedActions() {
        val actions = mutableListOf<Pair<String, String?>>()
        show(joined().copy(participants = listOf("Morgan", "Morgan", "Sam", "Riley", "Jordan"))) { op, value ->
            actions += op to value
        }
        val reached = mutableSetOf<Int>()
        var overflowReached = false
        // Use only D-pad movement: performScrollTo would hide the actual reachability defect.
        repeat(24) {
            for (index in 0..3) {
                if (compose.onNodeWithTag("sync:participant:$index").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.Focused) == true) {
                    reached += index
                    compose.onNodeWithTag("sync:participant:$index").assertIsDisplayed()
                    key(Key.DirectionCenter)
                }
            }
            if (compose.onNodeWithText("+1 more person").fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.Focused) == true) {
                overflowReached = true
                compose.onNodeWithText("+1 more person").assertIsDisplayed()
                key(Key.DirectionCenter)
            }
            key(Key.DirectionDown)
        }
        assertEquals(setOf(0, 1, 2, 3), reached, "D-pad must reach all four names, including duplicate actual names")
        assertTrue(overflowReached, "D-pad must reach the +N line below the capped roster")
        for (index in 0..3) assertReadOnly("sync:participant:$index")
        compose.onNodeWithText("+1 more person").assert(readOnlyFact())
        assertTrue(actions.isEmpty(), "Select on roster facts must send no group command: $actions")
    }

    private fun readOnlyFact() = SemanticsMatcher("focusable fact without action role, click, disabled, selected or switch semantics") {
        it.config.contains(SemanticsProperties.Focused) &&
            !it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) &&
            !it.config.contains(SemanticsProperties.Selected) && !it.config.contains(SemanticsProperties.ToggleableState) &&
            !it.config.contains(SemanticsProperties.Role)
    }

    private fun assertReadOnly(tag: String) = compose.onNodeWithTag(tag).assert(readOnlyFact())

    @Test fun localBufferingFactIsFullyVisibleOnArrivalWithPlayFocused() {
        show(joined().copy(localPlayback = SyncLocalPlayback.BUFFERING, participants = List(5) { "Person $it" }))
        compose.onNodeWithTag("sync:play").assertIsFocused()
        compose.onNodeWithTag("sync:local").assertIsDisplayed()
        val local = compose.onNodeWithTag("sync:local").getUnclippedBoundsInRoot()
        val leave = compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        assertTrue(local.bottom < leave.top, "Initial local playback fact must fit above pinned Leave: $local / $leave")
    }

    @Test fun removingMiddleQueueEntryKeepsFocusOnItsNextNeighbour() = removeAndExpect(
        listOf(SyncPlayQueueRow("a", "First film", true), SyncPlayQueueRow("b", "Second film", false), SyncPlayQueueRow("c", "Third film", false)),
        "b", "c"
    )

    @Test fun removingLastQueueEntryKeepsFocusOnItsPreviousNeighbour() = removeAndExpect(
        listOf(SyncPlayQueueRow("a", "First film", true), SyncPlayQueueRow("b", "Second film", false)),
        "b", "a"
    )

    private fun removeAndExpect(queue: List<SyncPlayQueueRow>, removed: String, neighbour: String) {
        var state by mutableStateOf(joined().copy(queue = queue))
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(state, { op, value ->
                    actions += op to value
                    if (op == "remove-entry") state = state.copy(queue = state.queue.filterNot { it.id == value })
                }, {})
            }
        }
        focus("sync:remove:$removed")
        key(Key.DirectionCenter)
        compose.onNodeWithTag("sync:remove:$removed").assertDoesNotExist()
        assertEquals(listOf<Pair<String, String?>>("remove-entry" to removed), actions)
        compose.onNodeWithTag("sync:entry:$neighbour").assertIsFocused().assertIsDisplayed()
    }

    @Test fun removingTheOnlyQueueEntryFallsBackToUsefulWaitInsteadOfEmptyPlay() {
        var state by mutableStateOf(joined())
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(state, { op, _ ->
                    if (op == "remove-entry") state = state.copy(queue = emptyList())
                }, {})
            }
        }
        focus("sync:remove:a")
        key(Key.DirectionCenter)
        compose.onNodeWithTag("sync:entry:a").assertDoesNotExist()
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused().assertIsEnabled()
        compose.onNodeWithTag("sync:play").assertIsNotEnabled()
    }

    @Test fun initialPlayFocusKeepsTheWholeScopeLineBelowTheFixedTitle() {
        show(joined().copy(queue = (1..9).map { SyncPlayQueueRow("e$it", "Film $it", it == 1) }))
        compose.onNodeWithTag("sync:play").assertIsFocused()
        val title = compose.onNodeWithText("Watch together").getUnclippedBoundsInRoot()
        val scope = compose.onNodeWithText("Cinema · Viewer").getUnclippedBoundsInRoot()
        assertTrue(scope.top >= title.bottom, "Focus bring-into-view must not hide initial scope behind fixed title: scope=$scope title=$title")
        compose.onNodeWithText("Cinema · Viewer").assertIsDisplayed()
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
    }

    @Test fun pinnedLeaveSharesTheBodySurfaceEdges() {
        show(joined())
        focus("sync:shuffle")
        val body = compose.onNodeWithTag("sync:shuffle").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        assertEquals(body.left, footer.left, "Footer and body controls must share their left surface edge")
        assertEquals(body.right, footer.right, "Footer and body controls must share their right surface edge")
    }

    @Test fun focusedPinnedLeaveDoesNotExpandBeyondItsRestingWidth() {
        show(joined())
        val resting = compose.onNodeWithTag("sync:leave").fetchSemanticsNode().boundsInRoot
        focus("sync:leave", scroll = false)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val focused = compose.onNodeWithTag("sync:leave").fetchSemanticsNode().boundsInRoot
        assertEquals(resting.width, focused.width, 1f, "Focused full-width Leave must not grow to 1.1x its resting width")
        assertEquals(resting.left, focused.left, 1f, "Focus must preserve footer surface edges")
    }

    @Test fun pinnedCreateMatchesTheNameFieldEdgesAndDoesNotExpandOnFocus() {
        show(SyncPlayPresentation(policyKnown = true, policy = "CreateAndJoinGroups"))
        compose.onNodeWithTag("sync:name").performTextInput("Friday films")
        val name = compose.onNodeWithTag("sync:name").getUnclippedBoundsInRoot()
        val create = compose.onNodeWithTag("sync:create").getUnclippedBoundsInRoot()
        assertEquals(name.left, create.left, "Pinned Create and body field must share their left edge")
        assertEquals(name.right, create.right, "Pinned Create and body field must share their right edge")
        val resting = compose.onNodeWithTag("sync:create").fetchSemanticsNode().boundsInRoot
        key(Key.DirectionDown)
        compose.onNodeWithTag("sync:create").assertIsFocused()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val focused = compose.onNodeWithTag("sync:create").fetchSemanticsNode().boundsInRoot
        assertEquals(resting.width, focused.width, 1f, "Focused full-width Create must preserve its resting width")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun enabledCreateUsesQuietThemeTonalSurfaceAtRest() {
        var tonal = 0
        var onTonal = 0
        compose.setContent {
            TvEnvironment {
                tonal = androidx.tv.material3.MaterialTheme.colorScheme.secondaryContainer.toArgb()
                onTonal = androidx.tv.material3.MaterialTheme.colorScheme.onSecondaryContainer.toArgb()
                SyncPlayPanel(SyncPlayPresentation(policyKnown = true, policy = "CreateAndJoinGroups"), { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("sync:name").assertIsFocused()
        compose.onNodeWithTag("sync:create").assertIsNotEnabled()
        val disabled = dominantCreateColor()
        compose.onNodeWithTag("sync:name").performTextInput("Friday films")
        compose.onNodeWithTag("sync:create").assertIsEnabled()
        val enabled = dominantCreateColor()
        assertEquals(tonal, enabled, "Enabled resting Create must visibly use quiet theme secondaryContainer; disabled=$disabled enabled=$enabled tonal=$tonal")
        assertTrue(disabled != enabled, "Typing a usable name must visibly distinguish Create from its disabled appearance")
        val bitmap = compose.onNodeWithTag("sync:create").captureToImage().asAndroidBitmap()
        var exactTextInk = 0
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
            for (x in bitmap.width / 40 until bitmap.width / 2) {
                if (bitmap.getPixel(x, y) == onTonal) exactTextInk++
            }
        }
        assertTrue(exactTextInk >= 8, "Actual resting label must paint theme onSecondaryContainer ink, not merely report a style token")
    }

    private fun dominantCreateColor(): Int {
        val bitmap = compose.onNodeWithTag("sync:create").captureToImage().asAndroidBitmap()
        val colors = mutableMapOf<Int, Int>()
        // Ignore rounded corners, border, and the centre label; sample the broad left interior.
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
            for (x in bitmap.width / 10 until bitmap.width / 3) {
                val color = bitmap.getPixel(x, y)
                colors[color] = (colors[color] ?: 0) + 1
            }
        }
        return colors.maxBy { it.value }.key
    }
}
