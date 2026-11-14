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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** Native production panel rhythm, including actual painted boundaries rather than touch-target gaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w985dp-h554dp-land-156dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvRhythmRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private var surface = 0
    private var border = 0
    private var onSurface = 0
    private var onSurfaceVariant = 0
    private var bodyLarge = TextStyle.Default
    private var titleSmall = TextStyle.Default
    private var window = IntSize.Zero
    private val commands = mutableListOf<String>()

    private data class Case(val input: JsonObject, val expected: JsonObject) {
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun count(key: String) = expected.getValue(key).jsonPrimitive.int
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun token(key: String) = expected.getValue(key).jsonPrimitive.content
    }

    private fun case(id: String): Case {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/sync-tv-rhythm.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-rhythm", suite.getValue("suite").jsonPrimitive.content)
        val row = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        return Case(row.getValue("input").jsonObject, row.getValue("expected").jsonObject)
    }

    @Before fun tvMode() {
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun Environment(content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) {
            PictureshowTheme {
                density = LocalDensity.current.density
                surface = MaterialTheme.colorScheme.surface.toArgb()
                border = MaterialTheme.colorScheme.borderVariant.toArgb()
                onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
                bodyLarge = MaterialTheme.typography.bodyLarge
                titleSmall = MaterialTheme.typography.titleSmall
                window = LocalWindowInfo.current.containerSize
                content()
            }
        }
    }

    private fun mount(c: Case) {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        assertEquals(0, c.input.getValue("queueEntries").jsonPrimitive.int)
        compose.setContent {
            Environment {
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
        assertEquals(c.number("densityDpi") / 160f, density, .001f)
        assertEquals(
            IntSize(c.input.getValue("windowWidthPx").jsonPrimitive.int, c.input.getValue("windowHeightPx").jsonPrimitive.int),
            window
        )
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
        assertEquals(c.number("fontScale"), layout(text(R.string.sync_repeat)).layoutInput.density.fontScale, .01f)
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun focusShuffle(c: Case) {
        repeat(c.input.getValue("downKeys").jsonPrimitive.int) {
            compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(Key.DirectionDown) }
            settle()
        }
        compose.onNodeWithTag("sync:shuffle").assertIsFocused()
        val target = compose.onNodeWithTag("sync:shuffle", useUnmergedTree = true).fetchSemanticsNode()
        val scroll = generateSequence(target.parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.value() > 0f, "Use actual scrolled focus entry, with the pinned header boundary visible")
    }

    private fun text(resource: Int) = compose.onNodeWithText(compose.activity.getString(resource), useUnmergedTree = true)
    private fun rect(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size)
        return results.single()
    }

    private fun capture(): Pair<Bitmap, Rect> {
        val node = compose.onNodeWithTag("sync:panel")
        return node.captureToImage().asAndroidBitmap() to rect(node)
    }

    private fun distance(a: Int, b: Int): Int = (0..3).maxOf { channel ->
        abs(((a ushr (channel * 8)) and 255) - ((b ushr (channel * 8)) and 255))
    }

    private fun localBox(node: Rect, panel: Rect) = intArrayOf(
        node.left.roundToInt() - panel.left.roundToInt(),
        node.top.roundToInt() - panel.top.roundToInt(),
        node.right.roundToInt() - panel.left.roundToInt(),
        node.bottom.roundToInt() - panel.top.roundToInt()
    )

    @Test fun pinnedPreviewHasTwelveDpBeforeTheActualScrolledHeaderRule() {
        val c = case("pinned-preview-rule-separation-native-156dpi")
        mount(c)
        focusShuffle(c)
        val scope = compose.activity.getString(R.string.sync_scope, c.text("server"), c.text("viewer"))
        val preview = rect(compose.onNodeWithText(scope, useUnmergedTree = true))
        val viewport = rect(compose.onNodeWithTag("tv-settings:viewport"))
        val (image, panel) = capture()
        val left = (image.width * .1f).roundToInt()
        val right = (image.width * .9f).roundToInt()
        val start = preview.bottom.roundToInt() - panel.top.roundToInt()
        val end = minOf(image.height, viewport.top.roundToInt() - panel.top.roundToInt() + 3)
        val rule = (start until end).firstOrNull { y ->
            (left until right).count { x -> distance(image.getPixel(x, y), border) <= c.count("paintChannelTolerance") } >=
                (right - left) * c.metric("broadRuleCoverageMin")
        }
        assertTrue(rule != null, "The actual broad painted header boundary must be present after scrolling")
        val gap = requireNotNull(rule) + panel.top.roundToInt() - preview.bottom
        assertTrue(
            gap >= c.metric("previewRuleGapMinDp") * density - c.count("nativePixelTolerance"),
            "Pinned preview text box must clear the actual painted rule by12dp: gapPx=$gap preview=$preview viewport=$viewport"
        )
        assertEquals(c.count("commands"), commands.size)
    }

    @Test fun repeatOutlineHasTwelveDpBeforeFocusedShuffleAndKeepsItsOwnLabelCloser() {
        val c = case("repeat-next-setting-painted-separation-native-156dpi")
        mount(c)
        // Wait is focused, so all Repeat chips are quiet and Shuffle cannot supply foreign focus-plate ink.
        val modes = c.input.getValue("targetModes").jsonArray.map { it.jsonPrimitive.content }
        val before = modes.associateWith { rect(compose.onNodeWithTag("sync:repeat:$it")) }
        val labelBefore = rect(text(R.string.sync_repeat))
        val (reference, referencePanel) = capture()
        val paintedEdges = modes.associateWith { mode ->
            val box = localBox(before.getValue(mode), referencePanel)
            val middle = (box[0] + (box[2] - box[0]) / 3) until (box[2] - (box[2] - box[0]) / 3)
            val rows = (box[1] until box[3]).filter { y ->
                middle.count { x -> distance(reference.getPixel(x, y), surface) >= c.count("backgroundChannelDeltaMin") } >=
                    c.count("minimumPaintedPixelsPerRow")
            }
            assertTrue(rows.isNotEmpty(), "Actual quiet chip must supply a painted outline/fill: $mode")
            intArrayOf(rows.first(), rows.last())
        }
        focusShuffle(c)
        val (subject, panel) = capture()
        val shuffle = localBox(rect(compose.onNodeWithTag("sync:shuffle")), panel)
        val middle = (shuffle[0] + (shuffle[2] - shuffle[0]) / 3) until (shuffle[2] - (shuffle[2] - shuffle[0]) / 3)
        val plateColor = subject.getPixel((shuffle[0] + shuffle[2]) / 2, (shuffle[1] + shuffle[3]) / 2)
        assertTrue(distance(plateColor, surface) >= c.count("plateBackgroundChannelDeltaMin"))
        val plateTop = (shuffle[1] until shuffle[3]).firstOrNull { y ->
            middle.count { x -> distance(subject.getPixel(x, y), plateColor) <= c.count("paintChannelTolerance") } >=
                middle.count() * c.metric("broadPlateCoverageMin")
        }
        assertTrue(plateTop != null, "The actual focused native Shuffle plate must be painted")
        val failures = mutableListOf<String>()
        for (mode in modes) {
            val node = rect(compose.onNodeWithTag("sync:repeat:$mode"))
            val a = localBox(before.getValue(mode), referencePanel)
            val b = localBox(node, panel)
            assertEquals(a[2] - a[0], b[2] - b[0])
            assertEquals(a[3] - a[1], b[3] - b[1])
            val edges = paintedEdges.getValue(mode)
            val top = b[1] + edges[0] - a[1]
            val bottom = b[1] + edges[1] - a[1]
            val xs = (a[0] + (a[2] - a[0]) / 3) until (a[2] - (a[2] - a[0]) / 3)
            // Confirm the actual last quiet-chip paint row survives at this scrolled location. The reference
            // identifies chip ink independently, so the neighboring focused Shuffle plate cannot become its edge.
            val witnesses = xs.filter { x ->
                distance(reference.getPixel(x, edges[1]), surface) >= c.count("backgroundChannelDeltaMin")
            }
            assertTrue(witnesses.size >= c.count("minimumPaintedPixelsPerRow"))
            for (x in witnesses) {
                assertTrue(
                    distance(reference.getPixel(x, edges[1]), subject.getPixel(b[0] + x - a[0], bottom)) <=
                        c.count("paintChannelTolerance"),
                    "Measured boundary must remain actual quiet-chip paint: $mode"
                )
            }
            val labelBottom = labelBefore.bottom - referencePanel.top.roundToInt() + b[1] - a[1]
            val labelGap = top - labelBottom
            val nextGap = requireNotNull(plateTop) - (bottom + 1)
            if (nextGap < c.metric("nextSettingGapMinDp") * density - c.count("nativePixelTolerance") || nextGap <= labelGap) {
                failures += "$mode paintedNextGapPx=$nextGap labelBoxToChipPaintPx=$labelGap"
            }
        }
        assertTrue(failures.isEmpty(), "Repeat must have12dp before its next setting and keep its label closer: $failures")
        assertEquals(c.count("commands"), commands.size)
    }

    @Test fun repeatUsesTheSettingTitleTypographyAndInkInsteadOfSectionHeadingStyle() {
        val c = case("repeat-setting-title-hierarchy-native-156dpi")
        mount(c)
        assertEquals("bodyLarge", c.token("settingTypography"))
        assertEquals("onSurface", c.token("settingInk"))
        assertEquals("titleSmall", c.token("sectionTypography"))
        assertEquals("onSurfaceVariant", c.token("sectionInk"))
        val repeat = layout(text(R.string.sync_repeat)).layoutInput.style
        val row = layout(compose.onNodeWithTag("tv-settings:label:${compose.activity.getString(R.string.sync_shuffle)}", true))
            .layoutInput.style
        val groupNode = text(R.string.sync_group_playback)
        val group = layout(groupNode).layoutInput.style
        assertEquals(titleSmall.fontSize, group.fontSize, "Use the actual production section heading as the hierarchy reference")
        assertEquals(onSurfaceVariant, group.color.toArgb(), "The actual section retains its quieter foreground")
        assertTrue(groupNode.fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertFalse(text(R.string.sync_repeat).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        assertEquals(bodyLarge.fontSize, row.fontSize, "The actual sibling row uses the TV setting-title scale")
        assertEquals(bodyLarge.fontSize, repeat.fontSize, "Repeat is a setting title at the same level as Shuffle")
        assertEquals(bodyLarge.fontWeight, repeat.fontWeight)
        assertEquals(bodyLarge.lineHeight, repeat.lineHeight)
        assertEquals(bodyLarge.letterSpacing, repeat.letterSpacing)
        assertEquals(onSurface, repeat.color.toArgb(), "Repeat must paint with normal setting-title ink")
        assertTrue(repeat.fontSize != group.fontSize, "Setting title and section heading must remain visually distinct")
        assertTrue(repeat.color != group.color, "Setting title and section heading need distinct foreground levels")
        assertEquals(c.count("commands"), commands.size)
    }
}
