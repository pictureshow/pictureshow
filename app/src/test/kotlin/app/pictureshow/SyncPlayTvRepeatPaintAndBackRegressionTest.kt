/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.robolectric.shadows.ShadowDialog

/** Measure whole-panel Repeat paint, actual remote focus entry and keyboard Back routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvRepeatPaintAndBackRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private var surface = 0
    private data class Profile(val input: JsonObject, val expected: JsonObject) {
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun count(key: String) = expected.getValue(key).jsonPrimitive.int
        fun flag(key: String) = expected.getValue(key).jsonPrimitive.boolean
        fun token(key: String) = expected.getValue(key).jsonPrimitive.content
    }
    private fun vector(id: String, profile: String): Profile {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-tv-surfaces.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-surfaces", suite.getValue("suite").jsonPrimitive.content)
        val case = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals(profile, case.getValue("profile").jsonPrimitive.content)
        return Profile(case.getValue("input").jsonObject, case.getValue("expected").jsonObject)
    }
    @Before fun tvMode() {
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
                val background = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect { density = scale; surface = background }
                content()
            }
        }
    }
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    @Test fun repeatPaintStaysOnItsRestingGridAtFont10() = verifyRepeat("repeat-stable-paint-font-1-0")
    @Test fun repeatPaintStaysOnItsRestingGridAtFont13() = verifyRepeat("repeat-stable-paint-font-1-3")
    private fun verifyRepeat(id: String) {
        val case = vector(id, "repeat-paint")
        RuntimeEnvironment.setFontScale(case.number("fontScale"))
        val commands = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(SyncPlayPresentation(
                    server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups",
                    groupId = "g", groupName = "Friday films", phase = "Paused", localPlayback = SyncLocalPlayback.READY,
                    repeat = case.text("selectedRepeat"), queue = listOf(SyncPlayQueueRow("a", "First film", true))
                ), { op, value -> commands += op to value }, {})
            }
        }
        settle()
        for (mode in case.input.getValue("targetModes").jsonArray.map { it.jsonPrimitive.content }) {
            val tag = "sync:repeat:$mode"
            compose.onNodeWithTag(tag).performScrollTo()
            placePaintProbeInsideViewport(tag, case)
            focusRepeat(mode, case) // Enter on checked value, then navigate within the real row.
            assertPaintProbeInsideViewport(tag, case)
            assertClearOfFades(tag)
            // Pinned Leave is outside the scrolling body; focusing it does not reposition Repeat.
            // All three chips are quiet, so an adjacent focused chip cannot contaminate the crop.
            focus("sync:leave")
            assertPaintProbeInsideViewport(tag, case)
            val restingNode = bounds(tag)
            val restingPanel = bounds("sync:panel")
            val rest = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
            focusRepeat(mode, case)
            assertPaintProbeInsideViewport(tag, case)
            val focusedNode = bounds(tag)
            assertEquals(case.flag("unchangedSemanticBounds"), restingNode == focusedNode,
                "Semantic geometry must stay fixed, but it alone cannot prove scale1 paint")
            assertEquals(restingPanel, bounds("sync:panel"))
            assertClearOfFades(tag)
            val focused = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
            val restPaint = paintBounds(rest, restingPanel, restingNode, case)
            val focusPaint = paintBounds(focused, restingPanel, focusedNode, case)
            val expected = intArrayOf(
                ((restingNode.left - restingPanel.left).value * density).roundToInt(),
                ((restingNode.top - restingPanel.top).value * density).roundToInt(),
                ((restingNode.right - restingPanel.left).value * density).roundToInt() - 1,
                ((restingNode.bottom - restingPanel.top).value * density).roundToInt() - 1
            )
            for (edge in 0..3) {
                assertTrue(abs(restPaint[edge] - expected[edge]) <= case.count("paintEdgeTolerancePx"),
                    "Rest chip paint must establish the real body grid: mode=$mode edge=$edge paint=${restPaint.toList()} node=${expected.toList()}")
                assertTrue(abs(focusPaint[edge] - restPaint[edge]) <= case.count("paintEdgeTolerancePx"),
                    "Focused Repeat paint must not expand beyond rest: mode=$mode edge=$edge rest=${restPaint.toList()} focused=${focusPaint.toList()}")
            }
        }
        assertEquals(case.count("repeatRequests"), commands.count { it.first == "repeat" },
            "Paint inspection/focus must not change the selected shared Repeat value")
    }
    /** Match actual remote entry: the focus group first targets the checked value.
     * Unchecked chips are reached with native D-pad movement inside that group. */
    private fun focusRepeat(mode: String, case: Profile) {
        val modes = case.input.getValue("targetModes").jsonArray.map { it.jsonPrimitive.content }
        val selected = case.text("selectedRepeat")
        val selectedIndex = modes.indexOf(selected)
        val targetIndex = modes.indexOf(mode)
        require(selectedIndex >= 0 && targetIndex >= 0) { "Repeat profile must include checked and target values" }
        focus("sync:repeat:$selected")
        val direction = if (targetIndex >= selectedIndex) Key.DirectionRight else Key.DirectionLeft
        repeat(abs(targetIndex - selectedIndex)) {
            compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(direction) }
            settle()
        }
        compose.onNodeWithTag("sync:repeat:$mode").assertIsFocused()
    }

    /** Ordinary production ScrollBy: keep the complete existing paint probe away from pinned chrome.
     * This changes only fixture placement; it neither crops paint nor changes reveal/edge thresholds. */
    private fun placePaintProbeInsideViewport(tag: String, case: Profile) {
        val row = bounds(tag)
        val viewport = bounds("tv-settings:viewport")
        val probe = case.number("probeOutsetDp").dp
        val onePixel = (1f / density).dp
        val minimumScroll = probe * 2f
        val distance = when {
            row.bottom + probe >= viewport.bottom ->
                maxOf(minimumScroll, row.bottom + probe + onePixel - viewport.bottom)
            row.top - probe <= viewport.top ->
                minOf(-minimumScroll, row.top - probe - onePixel - viewport.top)
            else -> 0.dp
        }
        if (distance == 0.dp) return
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val owner = generateSequence(target.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) &&
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Repeat fixture must use its actual vertical scrolling ancestor")
        compose.onNode(SemanticsMatcher("actual Repeat vertical scroll owner") { it.id == owner.id },
            useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, distance.value * density)
        }
        settle()
    }

    private fun assertPaintProbeInsideViewport(tag: String, case: Profile) {
        val row = bounds(tag)
        val viewport = bounds("tv-settings:viewport")
        val probe = case.number("probeOutsetDp").dp
        assertTrue(row.left - probe > viewport.left && row.right + probe < viewport.right &&
            row.top - probe > viewport.top && row.bottom + probe < viewport.bottom,
            "Fixture paint probe must be strictly inside the actual viewport before comparing paint; " +
                "pinned footer/divider is unrelated ink: tag=$tag row=$row viewport=$viewport probe=$probe")
    }

    private fun assertClearOfFades(tag: String) {
        val row = bounds(tag)
        val viewport = bounds("tv-settings:viewport")
        val top = if (compose.onAllNodesWithTag("tv-settings:fade-top").fetchSemanticsNodes().isNotEmpty())
            bounds("tv-settings:fade-top").bottom + 8.dp else viewport.top
        val bottom = if (compose.onAllNodesWithTag("tv-settings:fade-bottom").fetchSemanticsNodes().isNotEmpty())
            bounds("tv-settings:fade-bottom").top - 8.dp else viewport.bottom
        val tolerance = (1f / density).dp
        assertTrue(row.top >= top - tolerance && row.bottom <= bottom + tolerance,
            "Inspect unobscured chip paint, not a fade-induced silhouette: $row clear=[$top,$bottom]")
    }
    private fun paintBounds(bitmap: Bitmap, panel: DpRect, node: DpRect, case: Profile): IntArray {
        val left = ((node.left - panel.left).value * density).roundToInt()
        val right = ((node.right - panel.left).value * density).roundToInt() - 1
        val top = ((node.top - panel.top).value * density).roundToInt()
        val bottom = ((node.bottom - panel.top).value * density).roundToInt() - 1
        val outset = (case.number("probeOutsetDp") * density).roundToInt()
        fun painted(x: Int, y: Int) = (0..2).maxOf { channel ->
            abs(((bitmap.getPixel(x, y) ushr (channel * 8)) and 255) - ((surface ushr (channel * 8)) and 255))
        } >= case.count("paintBackgroundChannelDeltaMin")
        val xs = ((left - outset).coerceAtLeast(0)..(right + outset).coerceAtMost(bitmap.width - 1)).filter { x ->
            (top + (bottom - top) / 3..bottom - (bottom - top) / 3).any { y -> painted(x, y) }
        }
        val ys = ((top - outset).coerceAtLeast(0)..(bottom + outset).coerceAtMost(bitmap.height - 1)).filter { y ->
            (left + (right - left) / 3..right - (right - left) / 3).any { x -> painted(x, y) }
        }
        assertTrue(xs.isNotEmpty() && ys.isNotEmpty(), "Real chip outline/fill must be visibly painted; an empty capture cannot pass")
        return intArrayOf(xs.first(), ys.first(), xs.last(), ys.last())
    }

    @Test fun zeroInsetImeVisibleThenHiddenLetsNextBackDismissPanelOnce() {
        val case = vector("back-after-zero-inset-ime-dismissal", "editor-back-routing")
        RuntimeEnvironment.setFontScale(case.number("fontScale"))
        var dismissed = 0
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(SyncPlayPresentation(
                    server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups"
                ), { _, _ -> }, { dismissed++ })
            }
        }
        settle()
        compose.onNodeWithTag("sync:name").performTextInput(case.text("draft"))
        val editor = bounds("sync:name")
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
        for (visible in case.input.getValue("imeVisibilitySequence").jsonArray.map { it.jsonPrimitive.boolean }) {
            val bottomPx = (case.number("imeBottomInsetDp") * density).roundToInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
                .build()
            // Validate the public input, then deliver it through the actual Dialog view tree.
            // No app edit flag, direct requestKeyboard callback or IME shadow is modified.
            assertEquals(visible, insets.isVisible(WindowInsetsCompat.Type.ime()),
                "Harness must support explicit zero-height IME visibility independently of bottom inset")
            assertEquals(bottomPx, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(requireNotNull(dialog.window).decorView, insets) }
            settle()
        }
        compose.onNodeWithTag("sync:name").assertIsFocused()
        assertEquals(case.token("draftBeforeBack"), compose.onNodeWithTag("sync:name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertEquals(case.flag("stableEditorBounds"), editor == bounds("sync:name"))
        assertEquals(case.count("panelDismissalsBeforeBack"), dismissed)
        // Existing project pattern for the actual public Compose Dialog Back dispatch path.
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(case.count("panelDismissalsAfterBack"), dismissed,
            "After a platform-delivered zero-inset IME hide, the next app Back must dismiss exactly once")
    }

    @Test fun zeroInsetVisibleImeBackRetainsChooserAndDraft() {
        val case = vector("back-with-zero-inset-ime-visible", "editor-visible-back-guard")
        RuntimeEnvironment.setFontScale(case.number("fontScale"))
        var dismissed = 0
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(SyncPlayPresentation(
                    server = "Cinema", viewer = "Viewer", policyKnown = true, policy = "CreateAndJoinGroups"
                ), { _, _ -> }, { dismissed++ })
            }
        }
        settle()
        compose.onNodeWithTag("sync:name").performTextInput(case.text("draft"))
        val editor = bounds("sync:name")
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
        val bottomPx = (case.number("imeBottomInsetDp") * density).roundToInt()
        val visible = case.input.getValue("imeVisible").jsonPrimitive.boolean
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
            .setVisible(WindowInsetsCompat.Type.ime(), visible)
            .build()
        assertEquals(visible, insets.isVisible(WindowInsetsCompat.Type.ime()),
            "Public input must represent visible IME independently of its zero bottom inset")
        assertEquals(bottomPx, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(requireNotNull(dialog.window).decorView, insets) }
        settle()
        assertEquals(case.count("panelDismissalsBeforeBack"), dismissed)
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(case.count("panelDismissalsAfterBack"), dismissed,
            "An app Back while a zero-inset IME is visible must retain the chooser")
        compose.onNodeWithTag("sync:panel").assertExists()
        compose.onNodeWithTag("sync:name").assertIsFocused()
        assertEquals(case.token("retainedDraft"), compose.onNodeWithTag("sync:name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        assertEquals(case.flag("stableEditorBounds"), editor == bounds("sync:name"))
        // A hide request need not synchronously deliver a new WindowInsets frame in Robolectric.
        // No immediate invisible-insets or InputMethodManager shadow-count assertion is made.
    }
}
