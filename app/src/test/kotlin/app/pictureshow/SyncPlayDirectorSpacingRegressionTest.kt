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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

/** Proposed test source only. Not staged, compiled or run; see the accompanying plan.
 * Baseline-only instrumentation needed: neutral skeleton tags and outer footer tag.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayDirectorSpacingRegressionTest {
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
        queue = (1..9).map { SyncPlayQueueRow("e$it", "Film $it", it == 1) }
    )

    private fun show(state: SyncPlayPresentation) {
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.waitForIdle()
    }

    private fun onePixel(): Dp = (1f / compose.activity.resources.displayMetrics.density).dp
    private fun assertSameDp(expected: Dp, actual: Dp, message: String) {
        assertTrue(abs((expected - actual).value) <= onePixel().value, "$message: expected=$expected actual=$actual")
    }

    @Test fun transportHasTwelveDpVerticalClearanceFromLocalFactAndWaitRow() {
        // UNKNOWN quality omits the estimate caption so the local fact is the immediate neighbour.
        show(joined())
        compose.onNodeWithTag("sync:play").assertIsFocused().assertIsDisplayed()
        val local = compose.onNodeWithTag("sync:local").getUnclippedBoundsInRoot()
        val controls = listOf("previous", "play", "next").map {
            compose.onNodeWithTag("sync:$it").getUnclippedBoundsInRoot()
        }
        val wait = compose.onNodeWithTag("sync:ignore-wait").getUnclippedBoundsInRoot()
        val top = controls.minOf { it.top }
        val bottom = controls.maxOf { it.bottom }
        assertTrue(
            top - local.bottom >= 12.dp - onePixel(),
            "Transport needs12dp above its actual controls: local=$local controls=$controls"
        )
        assertTrue(
            wait.top - bottom >= 12.dp - onePixel(),
            "Wait needs12dp below the tallest actual control: controls=$controls wait=$wait"
        )
    }

    @Test fun leftAcknowledgementIsVisibleAbovePinnedNameWithoutTakingFocusOrOpeningIme() {
        show(
            SyncPlayPresentation(
                policyKnown = true,
                policy = "CreateAndJoinGroups",
                notice = SyncNotice(SyncNoticeKind.LEFT, SyncNoticeTone.INFO)
            )
        )
        compose.onNodeWithText("You left the group. This device is paused.").assertIsDisplayed()
        compose.onNodeWithTag("sync:name").assertIsFocused().assertIsDisplayed()
        val notice = compose.onNodeWithTag("sync:status").getUnclippedBoundsInRoot()
        val name = compose.onNodeWithTag("sync:name").getUnclippedBoundsInRoot()
        assertTrue(notice.bottom <= name.top, "Leave acknowledgement must precede the new form: notice=$notice name=$name")
        assertFalse(
            shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible,
            "Acknowledgement placement must preserve the no-IME-on-focus contract"
        )
    }

    @Test fun busyPlaceholdersMatchActualTwoLineGroupRowsInHeightPitchAndNeutralSemantics() {
        var state by mutableStateOf(SyncPlayPresentation(busy = true))
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("sync:group-placeholder", useUnmergedTree = true).assertCountEquals(2)
        val placeholders = compose.onAllNodesWithTag("sync:group-placeholder", useUnmergedTree = true)
        val first = placeholders[0].getUnclippedBoundsInRoot()
        val second = placeholders[1].getUnclippedBoundsInRoot()
        placeholders.fetchSemanticsNodes().forEach { node ->
            val config = node.config
            assertFalse(config.contains(SemanticsActions.OnClick), "A loading placeholder must not offer Select")
            assertFalse(config.contains(SemanticsActions.RequestFocus), "A loading placeholder must not be a remote focus target")
            assertFalse(config.contains(SemanticsProperties.Role), "A loading placeholder must not pretend to be a button")
            assertFalse(config.contains(SemanticsProperties.Disabled), "A loading placeholder is neutral, not a disabled control")
            assertTrue(config.getOrNull(SemanticsProperties.Focused) != true, "Loading shape must not own focus")
        }
        compose.runOnIdle {
            state = SyncPlayPresentation(
                policyKnown = true,
                policy = "JoinGroups",
                groups = listOf(SyncPlayGroupRow("a", "Friday films", 2), SyncPlayGroupRow("b", "Saturday films", 3))
            )
        }
        compose.waitForIdle()
        // These are the real group row compositions, including their supporting membership line.
        val realFirst = compose.onNodeWithTag("sync:join:a").getUnclippedBoundsInRoot()
        val realSecond = compose.onNodeWithTag("sync:join:b").getUnclippedBoundsInRoot()
        assertSameDp(
            (realFirst.bottom - realFirst.top),
            (first.bottom - first.top),
            "First skeleton must reserve the real two-line row height"
        )
        assertSameDp(
            (realSecond.bottom - realSecond.top),
            (second.bottom - second.top),
            "Second skeleton must reserve the real two-line row height"
        )
        assertSameDp(realSecond.top - realFirst.top, second.top - first.top, "Skeleton pitch must match loaded group-row pitch")
        assertSameDp(realSecond.top - realFirst.bottom, second.top - first.bottom, "Sibling row gap must survive loading unchanged")
        assertSameDp((realFirst.right - realFirst.left), (first.right - first.left), "Skeleton and loaded group share the surface width")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun finalEightDpOfBottomFadeFullyHidesAdversarialBrightContent() {
        var surface = 0
        var stripPixels = 0
        var insetPixels = 0
        compose.setContent {
            TvEnvironment {
                val density = LocalDensity.current
                val surfaceColor = MaterialTheme.colorScheme.surface
                SideEffect {
                    surface = surfaceColor.toArgb()
                    stripPixels = with(density) { 8.dp.roundToPx() }
                    insetPixels = with(density) { 2.dp.roundToPx() }
                }
                TvSettingsList(
                    Modifier.width(300.dp).height(240.dp).background(MaterialTheme.colorScheme.surface).testTag("regression:bright-list"),
                    minimalFocusScroll = true
                ) {
                    // Continuous adversarial pixels behind the entire fade, not text or empty padding.
                    Box(Modifier.fillMaxWidth().height(2000.dp).background(Color.Magenta))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tv-settings:fade-bottom").assertExists()
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        val listBounds = compose.onNodeWithTag("regression:bright-list").getUnclippedBoundsInRoot()
        val fadeBounds = compose.onNodeWithTag("tv-settings:fade-bottom").getUnclippedBoundsInRoot()
        assertSameDp(listBounds.bottom, fadeBounds.bottom, "Fade must reach the actual viewport edge")
        val bitmap = compose.onNodeWithTag("regression:bright-list").captureToImage().asAndroidBitmap()
        val left = bitmap.width / 4
        val right = bitmap.width * 3 / 4
        // Confirm the bright source actually rendered; an empty/black screenshot cannot pass.
        assertColorNear(
            Color.Magenta.toArgb(),
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
            "Bright adversarial content did not render"
        )
        for (y in bitmap.height - stripPixels until bitmap.height) {
            for (x in left until right) {
                assertColorNear(surface, bitmap.getPixel(x, y), "Final8dp leaks underlying pixels at($x,$y)")
            }
        }
        val density = compose.activity.resources.displayMetrics.density
        val fadeTopPixels = ((fadeBounds.top - listBounds.top).value * density).toInt()
        val upperFade = bitmap.getPixel(bitmap.width / 2, fadeTopPixels + insetPixels)
        assertTrue(channelDistance(surface, upperFade) > 32, "Retain a blended upper fade, not a solid40dp occluding slab")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun pinnedFooterStartsWithAVisibleFullWidthDivider() {
        show(joined())
        // Tag belongs to existing OUTER footer Box, before its padding; no divider-only test hook.
        val footer = compose.onNodeWithTag("tv-settings:footer")
        val bitmap = footer.captureToImage().asAndroidBitmap()
        val fourDp = (4f * compose.activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val middle = bitmap.width / 2
        val line = bitmap.getPixel(middle, 0)
        val background = bitmap.getPixel(middle, fourDp)
        assertTrue(
            channelDistance(line, background) >= 16,
            "A visible divider must separate the viewport from the pinned footer; line=$line background=$background"
        )
        // Sample the broad centre, excluding rounded panel edges and the scroll-indicator column.
        for (x in bitmap.width / 10 until bitmap.width * 9 / 10) {
            assertColorNear(line, bitmap.getPixel(x, 0), "Footer divider must span the content column at x=$x")
        }
    }

    @Test fun defaultListKeepsAmbientBringIntoViewPolicyAndScrollAffordance() {
        val inherited = object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 17f
        }
        var actual: BringIntoViewSpec? = null
        compose.setContent {
            TvEnvironment {
                CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                    // Intentionally omit minimalFocusScroll, preserving the contract of default callers.
                    TvSettingsList(Modifier.width(300.dp).height(240.dp)) {
                        val current = LocalBringIntoViewSpec.current
                        SideEffect { actual = current }
                        Box(Modifier.fillMaxWidth().height(2000.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertSame(inherited, actual, "Sync-specific reveal policy must not replace the default caller's ambient policy")
        compose.onNodeWithTag("tv-settings:fade-bottom").assertExists()
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        compose.onNodeWithTag("tv-settings:fade-top").assertDoesNotExist()
    }

    private fun channelDistance(a: Int, b: Int): Int = (0..3).maxOf { shift ->
        abs(((a ushr (shift * 8)) and 255) - ((b ushr (shift * 8)) and 255))
    }

    private fun assertColorNear(expected: Int, actual: Int, context: String) {
        assertTrue(
            channelDistance(expected, actual) <= 1,
            "$context: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}"
        )
    }
}
