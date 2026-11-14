/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
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

/** Actual physical156dpi panel: a cut later row must not attenuate whole Repeat control paint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w985dp-h554dp-land-156dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvWholeRepeatInkRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private var surface = 0
    private var window = IntSize.Zero

    private data class Profile(val input: JsonObject, val expected: JsonObject) {
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun count(key: String) = expected.getValue(key).jsonPrimitive.int
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
    }

    private fun vector(): Profile {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/sync-tv-surfaces.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-surfaces", suite.getValue("suite").jsonPrimitive.content)
        val row = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "whole-repeat-full-ink-native-156dpi" }
        assertEquals("whole-repeat-ink", row.getValue("profile").jsonPrimitive.content)
        return Profile(row.getValue("input").jsonObject, row.getValue("expected").jsonObject)
    }

    @Before fun tvMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @After fun restoreFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Composable private fun TvEnvironment(content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) {
            PictureshowTheme {
                val scale = LocalDensity.current.density
                val background = MaterialTheme.colorScheme.surface.toArgb()
                val actualWindow = LocalWindowInfo.current.containerSize
                SideEffect {
                    density = scale
                    surface = background
                    window = actualWindow
                }
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

    @Test fun wholeEnabledRepeatKeepsAllNativePaintBesidePartialShuffleAt156Dpi() {
        val c = vector()
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        assertEquals(0, c.input.getValue("queueEntries").jsonPrimitive.int)
        val commands = mutableListOf<String>()
        compose.setContent {
            TvEnvironment {
                SyncPlayPanel(
                    SyncPlayPresentation(
                        server = c.text("server"), viewer = c.text("viewer"), policyKnown = true, policy = "CreateAndJoinGroups",
                        groupId = "g", groupName = c.text("groupName"), phase = c.text("phase"),
                        localPlayback = SyncLocalPlayback.UNAVAILABLE, repeat = c.text("selectedRepeat"),
                        participants = c.input.getValue("participants").jsonArray.map { it.jsonPrimitive.content }
                    ),
                    { op, _ -> commands += op },
                    {}
                )
            }
        }
        settle()
        assertEquals(c.input.getValue("densityDpi").jsonPrimitive.int, compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(c.number("densityDpi") / 160f, density, .001f, "Use actual Android density, not a LocalDensity override")
        assertEquals(
            IntSize(c.input.getValue("windowWidthPx").jsonPrimitive.int, c.input.getValue("windowHeightPx").jsonPrimitive.int),
            window,
            "The fixture must really render960x540physical pixels, not960dp"
        )
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_repeat_off), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(c.number("fontScale"), layouts.single().layoutInput.density.fontScale, .01f)
        val modes = c.input.getValue("targetModes").jsonArray.map { it.jsonPrimitive.content }
        val subjectPanel = bounds("sync:panel")
        val view = bounds("tv-settings:viewport")
        val fade = bounds("tv-settings:fade-bottom")
        val onePixel = (1f / density).dp
        assertEquals(c.metric("bottomFadeExtentDp"), (fade.bottom - fade.top).value, onePixel.value)
        assertEquals(view.bottom.value, fade.bottom.value, onePixel.value, "Partial tail retains the actual full mask node")
        val shuffle = bounds("sync:shuffle")
        assertTrue(
            shuffle.top < view.bottom && shuffle.bottom > view.bottom,
            "Native-size profile must establish a truly cut later row in UNCLIPPED geometry: shuffle=$shuffle viewport=$view"
        )
        val subjectNodes = modes.associateWith { mode ->
            val tag = "sync:repeat:$mode"
            compose.onNodeWithTag(tag).assertIsEnabled()
            bounds(tag)
        }
        val guardStart = view.bottom - c.metric("opaqueBandDp").dp
        for ((mode, node) in subjectNodes) {
            assertTrue(
                node.left >= view.left &&
                    node.right <= view.right &&
                    node.top >= view.top &&
                    node.bottom + onePixel <= guardStart,
                "Inspect eligible whole controls above opaque guard, not arbitrary rectangles insideviewport: " +
                    "mode=$mode chip=$node viewport=$view guardStart=$guardStart"
            )
        }
        // Capture the first-arrival paint BEFORE any scroll or focus setup.
        val panelNode = compose.onNodeWithTag("sync:panel")
        val captureBounds = panelNode.fetchSemanticsNode().boundsInRoot
        val subject = panelNode.captureToImage().asAndroidBitmap()
        assertEquals(
            captureBounds.right.roundToInt() - captureBounds.left.roundToInt(),
            subject.width,
            "Capture width follows the actual native Compose bounds, independently of window size: $captureBounds"
        )
        assertEquals(
            captureBounds.bottom.roundToInt() - captureBounds.top.roundToInt(),
            subject.height,
            "Capture height follows the actual native Compose bounds, independently of window size: $captureBounds"
        )
        val target = compose.onNodeWithTag("sync:repeat:${modes.first()}", useUnmergedTree = true).fetchSemanticsNode()
        val scrollNode = generateSequence(target.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Use the real production Repeat vertical scroll owner")
        val owner = compose.onNode(SemanticsMatcher("actual Repeat scroll owner") { it.id == scrollNode.id }, useUnmergedTree = true)
        val first = subjectNodes.getValue(modes.first())
        val delta = (((first.top + first.bottom) / 2) - ((view.top + view.bottom) / 2)).value * density
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
        settle()
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
        assertEquals(subjectPanel, bounds("sync:panel"), "Reference changes only ordinary scroll position")
        val referenceNodes = modes.associateWith { mode -> bounds("sync:repeat:$mode") }
        val topFades = compose.onAllNodesWithTag("tv-settings:fade-top").fetchSemanticsNodes()
        val clearTop = if (topFades.isEmpty()) view.top else bounds("tv-settings:fade-top").bottom + 8.dp
        val bottomFades = compose.onAllNodesWithTag("tv-settings:fade-bottom").fetchSemanticsNodes()
        val clearBottom = if (bottomFades.isEmpty()) view.bottom else bounds("tv-settings:fade-bottom").top - 8.dp
        for ((mode, node) in referenceNodes) {
            assertTrue(node.top >= clearTop && node.bottom <= clearBottom, "Reference must clear actual fades: $mode=$node")
        }
        val reference = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
        val failures = mutableListOf<String>()
        for (mode in modes) {
            val before = subjectNodes.getValue(mode)
            val after = referenceNodes.getValue(mode)
            val shift = (before.top - after.top).value * density
            assertEquals(shift.roundToInt().toFloat(), shift, .001f, "Reference must preserve native raster phase")
            val a = pixelBox(before, subjectPanel, c.count("probeOutsetNativePx"))
            val b = pixelBox(after, subjectPanel, c.count("probeOutsetNativePx"))
            assertEquals(a[2] - a[0], b[2] - b[0], "Same actual native chip width: $mode")
            assertEquals(a[3] - a[1], b[3] - b[1], "Same actual native chip height: $mode")
            assertEquals(a[0], b[0], "Scroll cannot change horizontal native chip alignment")
            assertProbeInside(a, subject, view, subjectPanel)
            assertProbeInside(b, reference, view, subjectPanel)
            var painted = 0
            var changed = 0
            var maximumDelta = 0
            for (y in 0 until a[3] - a[1]) {
                for (x in 0 until a[2] - a[0]) {
                    val clear = reference.getPixel(b[0] + x, b[1] + y)
                    if (distance(clear, surface) >= c.count("paintBackgroundChannelDeltaMin")) {
                        painted++
                        val difference = distance(clear, subject.getPixel(a[0] + x, a[1] + y))
                        maximumDelta = maxOf(maximumDelta, difference)
                        if (difference > c.count("paintChannelTolerance")) changed++
                    }
                }
            }
            assertTrue(
                painted >= c.count("minimumReferencePaintedPixels"),
                "Real reference chip must paint: mode=$mode pixels=$painted"
            )
            if (changed > c.count("maximumChangedPaintedPixels")) {
                failures += "Whole enabled Repeat paint must match unobscured reference: mode=$mode " +
                    "changed=$changed painted=$painted maximumChannelDelta=$maximumDelta chip=$before fade=$fade"
            }
        }
        assertEquals(c.count("repeatRequests"), commands.count { it == "repeat" }, "No shared state changes during paint inspection")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun pixelBox(node: DpRect, panel: DpRect, outset: Int) = intArrayOf(
        ((node.left - panel.left).value * density).roundToInt() - outset,
        ((node.top - panel.top).value * density).roundToInt() - outset,
        ((node.right - panel.left).value * density).roundToInt() + outset,
        ((node.bottom - panel.top).value * density).roundToInt() + outset
    )

    private fun assertProbeInside(box: IntArray, bitmap: Bitmap, view: DpRect, panel: DpRect) {
        val viewport = pixelBox(view, panel, 0)
        assertTrue(
            box[0] >= viewport[0] &&
                box[1] >= viewport[1] &&
                box[2] <= viewport[2] &&
                box[3] <= viewport[3] &&
                box[0] >= 0 &&
                box[1] >= 0 &&
                box[2] <= bitmap.width &&
                box[3] <= bitmap.height,
            "Complete native paint probe must stay inside actual viewport, away from pinned footer/divider"
        )
    }

    private fun distance(a: Int, b: Int): Int = (0..3).maxOf { channel ->
        abs(((a ushr (channel * 8)) and 255) - ((b ushr (channel * 8)) and 255))
    }
}
