/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** PRIVATE SOURCE-ONLY DRAFT. The unapplied proposal adds one neutral viewport tag.
 * All input/focus/reflow paths below use production compositions. No build/run evidence yet.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvEdgeAndFocusRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private var surface = 0

    @Before fun tvMode() {
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun TvEnvironment(content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) {
            PictureshowTheme {
                val scale = LocalDensity.current.density
                val color = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    density = scale
                    surface = color
                }
                content()
            }
        }
    }

    private fun joined() = SyncPlayPresentation(
        server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
        groupId = "g", groupName = "Friday films", phase = "Paused", localPlayback = SyncLocalPlayback.READY,
        groups = listOf(SyncPlayGroupRow("g", "Friday films", 2)),
        queue = listOf(
            SyncPlayQueueRow("a", "Big Buck Bunny", true),
            SyncPlayQueueRow("b", "Elephants Dream", false),
            SyncPlayQueueRow("c", "Sintel", false)
        ),
        participants = listOf("Morgan", "Sam", "Riley", "Taylor", "Jordan", "Casey")
    )
    private fun longJoined() = joined().copy(queue = (1..9).map { SyncPlayQueueRow("e$it", "Film $it", it == 1) })
    private fun leftChooser() = SyncPlayPresentation(
        server = "Cinema",
        viewer = "Viewer",
        policyKnown = true,
        policy = "CreateAndJoinGroups",
        notice = SyncNotice(SyncNoticeKind.LEFT, SyncNoticeTone.INFO)
    )
    private fun show(state: SyncPlayPresentation) {
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        settle()
    }
    private fun settle() {
        compose.waitForIdle()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }
    private fun key(value: Key) {
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(value) }
        settle()
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun focused(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }
    private fun remoteTo(tag: String, direction: Key, limit: Int = 28) {
        for (step in 0..limit) {
            if (focused(tag)) return
            if (step < limit) key(direction)
        }
        compose.onNodeWithTag(tag).assertIsFocused() // Report failed real traversal; never force focus.
    }
    private fun viewport(): DpRect = compose.onNodeWithTag("tv-settings:viewport").getUnclippedBoundsInRoot()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun assertActualFont(scale: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("sync:group").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "Actual group TextLayoutResult must be available")
        layouts.forEach { assertEquals(scale, it.layoutInput.density.fontScale, .01f, "Verify font in the actual TV Dialog") }
    }

    @Test fun activeTopFadeHasAnOpaqueEightDpBandAgainstTwoBrightUnderlays() {
        var underlay by mutableStateOf(Color.Magenta)
        compose.setContent {
            TvEnvironment {
                TvSettingsList(
                    Modifier.width(300.dp).height(240.dp).background(MaterialTheme.colorScheme.surface),
                    minimalFocusScroll = true
                ) {
                    Box(Modifier.fillMaxWidth().height(2000.dp).background(underlay))
                    Box(Modifier.fillMaxWidth().height(1.dp).testTag("edge-probe:end"))
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("edge-probe:end"))
        settle()
        val fade = bounds("tv-settings:fade-top")
        assertEquals(32.dp, fade.bottom - fade.top, "Preserve the existing top fade extent")
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        val first = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
        assertColorNear(Color.Magenta.toArgb(), first.getPixel(first.width / 2, first.height / 2), "Magenta source really rendered")
        compose.runOnIdle { underlay = Color.Cyan }
        settle()
        val second = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
        assertColorNear(Color.Cyan.toArgb(), second.getPixel(second.width / 2, second.height / 2), "Cyan source really rendered")
        val band = (8f * density).roundToInt()
        val possibleDivider = density.roundToInt().coerceAtLeast(1)
        for (y in 0 until band) {
            for (x in first.width / 4 until first.width * 3 / 4) {
                assertColorNear(first.getPixel(x, y), second.getPixel(x, y), "Top8dp must not depend on bright content at($x,$y)")
                // A1dp opaque divider at the exact boundary is allowed; the rest is surface mask.
                if (y >= possibleDivider) assertColorNear(surface, second.getPixel(x, y), "Opaque band must use surface")
            }
        }
        val blendingY = (30f * density).roundToInt()
        assertTrue(
            colorDistance(first.getPixel(first.width / 2, blendingY), second.getPixel(second.width / 2, blendingY)) > 32,
            "Retain a blended lower fade rather than hiding32dp under a solid slab"
        )
    }

    @Test fun headerDividerIsVisibleOnlyAfterTheActualPanelScrolls() {
        show(longJoined())
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
        assertFalse(hasVisibleBoundaryDivider(), "Header stays seamless at scroll0")
        remoteTo("sync:shuffle", Key.DirectionDown)
        compose.onNodeWithTag("tv-settings:fade-top").assertExists()
        assertTrue(hasVisibleBoundaryDivider(), "Scrolled header needs a broad visible divider at the measured viewport boundary")
    }

    private fun hasVisibleBoundaryDivider(): Boolean {
        val panel = bounds("sync:panel")
        val view = viewport()
        val bitmap = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
        val y = ((view.top - panel.top).value * density).roundToInt()
        val radius = density.roundToInt().coerceAtLeast(1)
        val left = ((view.left - panel.left + (view.right - view.left) / 10).value * density).roundToInt().coerceAtLeast(0)
        val right = ((view.right - panel.left - (view.right - view.left) / 10).value * density).roundToInt().coerceAtMost(bitmap.width)
        for (row in (y - radius).coerceAtLeast(0)..(y + radius).coerceAtMost(bitmap.height - 1)) {
            val color = bitmap.getPixel((left + right) / 2, row)
            if (colorDistance(color, surface) < 16) continue
            val solid = (left until right).count { colorDistance(bitmap.getPixel(it, row), color) <= 1 }
            if (solid >= (right - left) * .95f) return true
        }
        return false
    }

    @Test fun defaultListRetainsAmbientPolicyAndItsExistingBlendedTopAffordance() {
        val ambient = object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
        }
        var actual: BringIntoViewSpec? = null
        compose.setContent {
            TvEnvironment {
                CompositionLocalProvider(LocalBringIntoViewSpec provides ambient) {
                    // Deliberately leave minimalFocusScroll at its production default.
                    TvSettingsList(Modifier.width(300.dp).height(240.dp).background(MaterialTheme.colorScheme.surface)) {
                        val inherited = LocalBringIntoViewSpec.current
                        SideEffect { actual = inherited }
                        Box(Modifier.fillMaxWidth().height(2000.dp).background(Color.Magenta))
                        Box(Modifier.fillMaxWidth().height(1.dp).testTag("edge-probe:end"))
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("edge-probe:end"))
        settle()
        assertSame(ambient, actual, "Opt-in Sync policy must not replace default callers' ambient policy")
        val fade = bounds("tv-settings:fade-top")
        assertEquals(32.dp, fade.bottom - fade.top)
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        val bitmap = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
        val y = (6f * density).roundToInt()
        assertTrue(
            colorDistance(bitmap.getPixel(bitmap.width / 2, y), surface) > 8,
            "The accepted opt-in fix must not silently replace the ordinary caller's existing blended top edge"
        )
    }

    @Test fun returningUpToPlayRestoresWholeStatusAtFont10() = verifyPlayReturn(1f)

    @Test fun returningUpToPlayRestoresWholeStatusAtFont13() = verifyPlayReturn(1.3f)
    private fun verifyPlayReturn(font: Float) {
        RuntimeEnvironment.setFontScale(font)
        show(longJoined())
        assertActualFont(font)
        compose.onNodeWithTag("sync:play").assertIsFocused()
        remoteTo("sync:shuffle", Key.DirectionDown)
        compose.onNodeWithTag("tv-settings:fade-top").assertExists()
        remoteTo("sync:play", Key.DirectionUp)
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
        val view = viewport()
        for (tag in listOf("sync:group", "sync:now-playing", "sync:phase", "sync:local")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val row = bounds(tag)
            assertTrue(
                row.top >= view.top && row.bottom <= view.bottom,
                "Returning to first useful control must restore its entire context: $tag=$row viewport=$view"
            )
        }
    }

    @Test fun removeLastAtFont10RevealsPreviousNeighbour() = verifyRemoval("c", "b", 1f)

    @Test fun removeLastAtFont13RevealsPreviousNeighbour() = verifyRemoval("c", "b", 1.3f)

    @Test fun removeMiddleAtFont10RevealsNextNeighbour() = verifyRemoval("b", "c", 1f)

    @Test fun removeMiddleAtFont13RevealsNextNeighbour() = verifyRemoval("b", "c", 1.3f)

    @Test fun removeCurrentAtFont10RevealsNextNeighbour() = verifyRemoval("a", "b", 1f)

    @Test fun removeCurrentAtFont13RevealsNextNeighbour() = verifyRemoval("a", "b", 1.3f)
    private fun verifyRemoval(removed: String, neighbour: String, font: Float) {
        RuntimeEnvironment.setFontScale(font)
        var state by mutableStateOf(joined())
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(state, { op, value ->
                    actions += op to value
                    if (op == "remove-entry") state = state.copy(queue = state.queue.filterNot { it.id == value })
                }, {})
            }
        }
        settle()
        assertActualFont(font)
        remoteTo("sync:entry:$removed", Key.DirectionDown)
        key(Key.DirectionRight)
        compose.onNodeWithTag("sync:remove:$removed").assertIsFocused()
        key(Key.DirectionCenter)
        // No scroll, focus request or offset reset after this actual callback/relayout.
        compose.onNodeWithTag("sync:entry:$removed").assertDoesNotExist()
        assertEquals(listOf<Pair<String, String?>>("remove-entry" to removed), actions)
        compose.onNodeWithTag("sync:entry:$neighbour").assertIsFocused().assertIsDisplayed()
        assertClearsActiveFades("sync:entry:$neighbour")
    }
    private fun assertClearsActiveFades(tag: String) {
        val view = viewport()
        val row = bounds(tag)
        val top = if (exists("tv-settings:fade-top")) bounds("tv-settings:fade-top").bottom + 8.dp else view.top
        val bottom = if (exists("tv-settings:fade-bottom")) bounds("tv-settings:fade-bottom").top - 8.dp else view.bottom
        val tolerance = (1f / density).dp
        assertTrue(
            row.top >= top - tolerance && row.bottom <= bottom + tolerance,
            "Programmatic neighbour focus must clear both actual fade masks: row=$row effective=[$top,$bottom] viewport=$view"
        )
    }

    @Test fun repeatOffIsEnteredFromWaitWithoutChangingSelection() = verifyRepeatEntry("RepeatNone", false)

    @Test fun repeatOneIsEnteredFromWaitWithoutChangingSelection() = verifyRepeatEntry("RepeatOne", false)

    @Test fun repeatAllIsEnteredFromWaitWithoutChangingSelection() = verifyRepeatEntry("RepeatAll", false)

    @Test fun repeatOffIsEnteredFromShuffleWithoutChangingSelection() = verifyRepeatEntry("RepeatNone", true)

    @Test fun repeatOneIsEnteredFromShuffleWithoutChangingSelection() = verifyRepeatEntry("RepeatOne", true)

    @Test fun repeatAllIsEnteredFromShuffleWithoutChangingSelection() = verifyRepeatEntry("RepeatAll", true)
    private fun verifyRepeatEntry(selected: String, fromShuffle: Boolean) {
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            TvEnvironment { SyncPlayPanel(joined().copy(repeat = selected), { op, value -> actions += op to value }, {}) }
        }
        settle()
        if (fromShuffle) {
            remoteTo("sync:shuffle", Key.DirectionDown)
            key(Key.DirectionUp)
        } else {
            remoteTo("sync:ignore-wait", Key.DirectionDown)
            key(Key.DirectionDown)
        }
        compose.onNodeWithTag("sync:repeat:$selected").assertIsFocused().assertIsSelected()
        assertTrue(actions.isEmpty(), "Entering the single-choice group must send no shared setting command")
        val modes = listOf("RepeatNone", "RepeatOne", "RepeatAll")
        val index = modes.indexOf(selected)
        val outward = if (index < 2) Key.DirectionRight else Key.DirectionLeft
        val inward = if (index < 2) Key.DirectionLeft else Key.DirectionRight
        val adjacent = modes[if (index < 2) index + 1 else index - 1]
        key(outward)
        compose.onNodeWithTag("sync:repeat:$adjacent").assertIsFocused()
        assertTrue(actions.isEmpty(), "Left/Right moves focus without applying Repeat")
        key(inward)
        compose.onNodeWithTag("sync:repeat:$selected").assertIsFocused().assertIsSelected()
        assertTrue(actions.isEmpty())
        key(Key.DirectionCenter)
        assertEquals(listOf<Pair<String, String?>>("repeat" to selected), actions)
    }

    @Test fun leaveWithPreloadedGroupsLandsOnNameAndKeepsAcknowledgementOutOfFades() {
        var state by mutableStateOf(joined())
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(state, { op, value ->
                    actions += op to value
                    if (op ==
                        "leave"
                    ) {
                        state =
                            state.copy(
                                groupId = null,
                                groupName = null,
                                phase = "Idle",
                                queue = emptyList(),
                                participants = emptyList(),
                                notice = SyncNotice(SyncNoticeKind.LEFT, SyncNoticeTone.INFO)
                            ) // cached groups deliberately retained
                    }
                }, {})
            }
        }
        settle()
        remoteTo("sync:leave", Key.DirectionDown)
        key(Key.DirectionCenter)
        assertEquals(listOf<Pair<String, String?>>("leave" to null), actions)
        compose.onNodeWithTag("sync:name").assertIsFocused().assertIsDisplayed()
        assertFalse(focused("sync:join:g"), "A stray Select after Leave must not land on rejoin")
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        val notice = bounds("sync:status")
        val name = bounds("sync:name")
        assertTrue(notice.bottom <= name.top, "LEFT must be above the pinned editor, never in the body fade: notice=$notice name=$name")
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
        assertFalse(shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible)
    }

    @Test fun delayedGroupsArrivalRetainsAnAlreadyFocusedLeftName() {
        var state by mutableStateOf(leftChooser())
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        settle()
        compose.onNodeWithTag("sync:name").assertIsFocused()
        compose.runOnIdle { state = state.copy(groups = listOf(SyncPlayGroupRow("g", "Friday films", 2))) }
        settle()
        compose.onNodeWithTag("sync:name").assertIsFocused()
        assertFalse(focused("sync:join:g"))
        compose.onNodeWithTag("sync:status").assertIsDisplayed()
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
    }

    @Test fun freshLoadedChooserWithoutNoticeStillStartsOnFirstJoin() {
        show(leftChooser().copy(notice = null, groups = listOf(SyncPlayGroupRow("g", "Friday films", 2))))
        compose.onNodeWithTag("sync:join:g").assertIsFocused()
    }

    @Test fun completedGroupLoadStillPreservesAttachedRefreshFocus() {
        var state by mutableStateOf(SyncPlayPresentation(server = "Cinema", viewer = "Viewer", busy = true))
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        settle()
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        compose.runOnIdle {
            state =
                state.copy(
                    busy = false,
                    policyKnown = true,
                    policy = "CreateAndJoinGroups",
                    groups = listOf(SyncPlayGroupRow("g", "Friday films", 2))
                )
        }
        settle()
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
    }

    private fun colorDistance(a: Int, b: Int) = (0..3).maxOf { shift ->
        abs(((a ushr (shift * 8)) and 255) - ((b ushr (shift * 8)) and 255))
    }
    private fun assertColorNear(expected: Int, actual: Int, message: String) {
        assertTrue(
            colorDistance(expected, actual) <= 1,
            "$message expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}"
        )
    }
}
