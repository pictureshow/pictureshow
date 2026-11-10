/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction as NativeAction
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
class ShelfReaderStopsTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun seasonStopsBelongToTheCardsScrollableRowWithoutTakingInputFocusOrActivating() = verifyStops(false)

    @Test
    fun rtlSeasonStopsKeepNativeHorizontalActionsAndScrollOnlyInward() = verifyStops(true)

    @Test
    fun nativeInwardScrollFromStartMovesTheRow() = verifyStops(false, true)

    @Test
    fun rtlNativeInwardScrollFromStartMovesTheRow() = verifyStops(true, true)

    private fun verifyStops(rtl: Boolean, inwardAtStart: Boolean = false) {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val account = Account(Server("s", "Cinema", "https://media.example.test", "12"), User("u", "Jamie"), "fake")
        val special = MediaItem("specials", "Specials", "Season")
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(
                            account,
                            MediaItem("series", "A series", "Series"),
                            loading = false,
                            nextUp = listOf(MediaItem("next", "Next episode", "Episode")),
                            seasons = listOf(special) + (1..31).map { MediaItem("season-$it", "Season $it", "Season") }
                        ),
                        {},
                        {},
                        {},
                        {},
                        {}
                    )
                }
            }
        }
        repeat(2) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        val card = compose.onNodeWithTag("detail:seasons:specials")
        card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.runOnIdle { shadowOf(manager).setScreenReader(true) }
        compose.waitForIdle()
        for (end in listOf("Start", "End")) {
            if (end == "End") {
                repeat(31) { compose.onRoot().performKeyInput { pressKey(if (rtl) Key.DirectionLeft else Key.DirectionRight) } }
            }
            val stop = compose.onNodeWithContentDescription("$end of Seasons").fetchSemanticsNode()
            val focusedCard = compose.onNodeWithTag(
                if (end == "Start") "detail:seasons:specials" else "detail:seasons:season-31"
            ).fetchSemanticsNode()
            fun horizontalContainer(node: SemanticsNode): Int? = generateSequence(node.parent) { it.parent }
                .firstOrNull { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }?.id
            val row = requireNotNull(horizontalContainer(focusedCard))
            assertEquals(
                row,
                horizontalContainer(stop),
                "$end must be inside the card's horizontal scroll container for TalkBack edge traversal"
            )
            val rowSemantics = generateSequence(focusedCard.parent) { it.parent }.first { it.id == row }
            val id = stop.id
            compose.runOnIdle {
                val node = requireNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
                val rowInfo = requireNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(row))
                // The native stop is readable but cannot take input focus or activate a title.
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                assertTrue(bounds.width() > 0 && bounds.height() > 0, "$end needs a visible gutter: $bounds")
                val backward = if (rtl) NativeAction.ACTION_SCROLL_RIGHT else NativeAction.ACTION_SCROLL_LEFT
                val forward = if (rtl) NativeAction.ACTION_SCROLL_LEFT else NativeAction.ACTION_SCROLL_RIGHT
                assertTrue(rowInfo.actionList.contains(backward), "$end must retain native backward navigation")
                assertTrue(rowInfo.actionList.contains(forward), "$end must retain native forward navigation")
                val range = rowSemantics.config[SemanticsProperties.HorizontalScrollAxisRange]
                val before = range.value()
                assertFalse(
                    host.accessibilityNodeProvider.performAction(row, if (end == "Start") backward.id else forward.id, null),
                    "$end must reject an impossible outward scroll synchronously"
                )
                assertEquals(before, range.value(), "$end must not move on a rejected action")
                assertTrue(node.isScreenReaderFocusable, "$end must be independently eligible for TalkBack focus")
                assertFalse(node.isFocusable, "$end must not take input focus from the card")
                assertFalse(node.isClickable, "$end must not activate the card")
                @Suppress("DEPRECATION")
                node.recycle()
                @Suppress("DEPRECATION")
                rowInfo.recycle()
            }
            if (inwardAtStart) {
                val range = rowSemantics.config[SemanticsProperties.HorizontalScrollAxisRange]
                val before = range.value()
                compose.runOnIdle {
                    val inward = if (rtl) NativeAction.ACTION_SCROLL_LEFT else NativeAction.ACTION_SCROLL_RIGHT
                    assertTrue(host.accessibilityNodeProvider.performAction(row, inward.id, null), "Start must accept inward scrolling")
                }
                compose.mainClock.advanceTimeBy(1000)
                compose.waitForIdle()
                compose.runOnIdle { assertTrue(range.value() > before, "Start's inward action must move the real row") }
                return
            }
        }
        compose.onNodeWithTag("detail:seasons:season-31").assertIsFocused()
        val last = compose.onNodeWithTag("detail:seasons:season-31").fetchSemanticsNode()
        val row = generateSequence(last.parent) { it.parent }.first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
        val range = row.config[SemanticsProperties.HorizontalScrollAxisRange]
        val before = range.value()
        compose.runOnIdle {
            val inward = if (rtl) NativeAction.ACTION_SCROLL_RIGHT else NativeAction.ACTION_SCROLL_LEFT
            assertTrue(host.accessibilityNodeProvider.performAction(row.id, inward.id, null), "An inward native action must be accepted")
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(range.value() < before, "The accepted inward action must move the real row") }
    }
}
