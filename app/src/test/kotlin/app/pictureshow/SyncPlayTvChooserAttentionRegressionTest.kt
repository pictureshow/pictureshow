/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
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

/** Exercises attention, cold-load focus and section context through production compositions.
 * Native IME/device evidence is separate from these actual Compose paint and focus checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvChooserAttentionRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 1f

    @Before fun tvMode() {
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun TvEnvironment(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            PictureshowTheme {
                density = LocalDensity.current.density
                content()
            }
        }
    }

    private data class Case(val input: JsonObject, val expected: JsonObject) {
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun flag(key: String) = expected.getValue(key).jsonPrimitive.boolean
        fun token(key: String) = expected.getValue(key).jsonPrimitive.content
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
    }
    private fun case(id: String): Case {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-tv-attention.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("sync-tv-attention", suite.getValue("suite").jsonPrimitive.content)
        val row = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        return Case(row.getValue("input").jsonObject, row.getValue("expected").jsonObject)
    }
    private fun allowedEmpty() = SyncPlayPresentation(
        server = "Cinema",
        viewer = "Viewer",
        policyKnown = true,
        policy = "CreateAndJoinGroups"
    )
    private fun checking() = SyncPlayPresentation(server = "Cinema", viewer = "Viewer", busy = true)
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }
    private fun key(value: Key) {
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(value) }
        settle()
    }
    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size, "The actual production Text must expose one native layout")
        return layouts.single()
    }
    private fun assertNoImplicitIme() {
        assertFalse(
            shadowOf(compose.activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible,
            "Automatic focus selection must not request the software keyboard; native IME proof is separate"
        )
    }

    @Test fun enabledCreateHasQuietRestAndUnmistakableFocusedFillAtFont10() = verifyCreatePaint("create-quiet-rest-focus-font10")

    @Test fun enabledCreateHasQuietRestAndUnmistakableFocusedFillAtFont13() = verifyCreatePaint("create-quiet-rest-focus-font13")
    private fun verifyCreatePaint(id: String) {
        val c = case(id)
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent { TvEnvironment { SyncPlayPanel(allowedEmpty(), { op, value -> actions += op to value }, {}) } }
        settle()
        compose.onNodeWithTag("sync:name").assertIsFocused().performTextInput(c.text("draft"))
        settle()
        compose.onNodeWithTag("sync:name").assertIsFocused()
        compose.onNodeWithTag("sync:create").assertIsEnabled()
        assertEquals(
            c.number("fontScale"),
            layout(compose.onNodeWithText(text(R.string.sync_create_join), useUnmergedTree = true))
                .layoutInput.density.fontScale,
            .01f,
            "Read actual Dialog text scale"
        )
        val restBounds = compose.onNodeWithTag("sync:create").fetchSemanticsNode().boundsInRoot
        val rest = broadInteriorColor(compose.onNodeWithTag("sync:create").captureToImage().asAndroidBitmap())
        // Native Down path, not a future helper or forced focus request.
        key(Key.DirectionDown)
        compose.onNodeWithTag("sync:create").assertIsFocused().assertIsEnabled()
        assertEquals(
            restBounds,
            compose.onNodeWithTag("sync:create").fetchSemanticsNode().boundsInRoot,
            "Quiet-rest fix must preserve pinned full-width scale1 geometry"
        )
        val focus = broadInteriorColor(compose.onNodeWithTag("sync:create").captureToImage().asAndroidBitmap())
        val contrast = ColorUtils.calculateContrast(focus, rest)
        assertTrue(
            contrast >= c.metric("focusFillMinContrast").toDouble(),
            "Actual Create fills need3:1 rest-to-focus contrast: rest=${Integer.toHexString(
                rest
            )} focus=${Integer.toHexString(focus)} contrast=$contrast"
        )
        if (c.flag("focusedBrighterThanRest")) {
            assertTrue(
                ColorUtils.calculateLuminance(focus) > ColorUtils.calculateLuminance(rest),
                "Rest must be quieter; light fill must identify focus"
            )
        }
        assertTrue(actions.isEmpty(), "Moving focus must not create a group")
        assertNoImplicitIme()
    }
    private fun broadInteriorColor(bitmap: Bitmap): Int {
        val counts = mutableMapOf<Int, Int>()
        // Broad trailing interior excludes rounded corners, border and the left-aligned label.
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
            for (x in bitmap.width / 2 until bitmap.width * 4 / 5) {
                val color = bitmap.getPixel(x, y)
                counts[color] = (counts[color] ?: 0) + 1
            }
        }
        val mode = counts.maxBy { it.value }
        assertTrue(mode.value >= counts.values.sum() * .8, "A broad native fill must exist; do not accept a stray bright pixel")
        return mode.key
    }

    @Test fun untouchedColdEmptyLoadPromotesRefreshToNameWhenCreationBecomesAvailable() {
        val c = case("cold-empty-access-resolves-before-input")
        var state by mutableStateOf(checking())
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { op, value -> actions += op to value }, {}) } }
        settle()
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        // No keyboard, focus request, pointer or remote input between initial load and result.
        compose.runOnIdle { state = allowedEmpty() }
        settle()
        assertEquals("name", c.token("focusedTarget"))
        compose.onNodeWithTag("sync:" + c.token("focusedTarget")).assertIsFocused().assertIsDisplayed()
        assertTrue(actions.isEmpty(), "Automatic focus promotion sends no network action")
        assertNoImplicitIme()
    }

    @Test fun dpadIntentDuringColdEmptyLoadRetainsRefreshWhenAccessResolves() {
        val c = case("cold-empty-access-resolves-after-dpad")
        var state by mutableStateOf(checking())
        val actions = mutableListOf<Pair<String, String?>>()
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { op, value -> actions += op to value }, {}) } }
        settle()
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        // The checking state has no second enabled target. A real D-pad navigation attempt
        // establishes user ownership even if focus cannot leave Refresh yet; no fake movement.
        key(Key.DirectionDown)
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        compose.runOnIdle { state = allowedEmpty() }
        settle()
        assertEquals("refresh", c.token("focusedTarget"))
        compose.onNodeWithTag("sync:" + c.token("focusedTarget")).assertIsFocused()
        assertTrue(actions.isEmpty())
        assertNoImplicitIme()
    }

    @Test fun pinnedCreateSectionNamesItsTaskAndPlacesPrivacyBeforeTheEditor() {
        val c = case("create-heading-privacy-before-editor")
        compose.setContent { TvEnvironment { SyncPlayPanel(allowedEmpty(), { _, _ -> }, {}) } }
        settle()
        val heading = compose.onNodeWithText(text(R.string.sync_create), useUnmergedTree = true)
        heading.assertExists().assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val privacyNodes = compose.onAllNodesWithText(text(R.string.sync_privacy), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, privacyNodes.size, "Privacy belongs to the create section once, not repeated in the group list")
        val privacy = compose.onNodeWithText(text(R.string.sync_privacy), useUnmergedTree = true)
        privacy.assertIsDisplayed()
        val titleBounds = heading.fetchSemanticsNode().boundsInRoot
        val privacyBounds = privacy.fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("sync:name").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("tv-settings:viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(
            titleBounds.bottom <= privacyBounds.top && privacyBounds.bottom <= field.top,
            "Read task heading, privacy context, then editor: heading=$titleBounds privacy=$privacyBounds field=$field"
        )
        assertTrue(
            privacyBounds.top - titleBounds.bottom <= c.metric("headingPrivacyMaxGapDp") * density,
            "Privacy must read as the heading's supporting context"
        )
        assertTrue(field.bottom <= viewport.top + 1f, "Creation remains pinned above the scrolling groups body")
        compose.onNodeWithText(text(R.string.sync_groups), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("sync:name").assertIsFocused()
        assertNoImplicitIme()
    }

    @Test fun sharedPersonalFactIsGroupContextBeforeControlsWithPaintedBlockClearance() {
        val c = case("joined-shared-personal-is-group-context")
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        val queue = c.input.getValue("queue").jsonArray.map { entry ->
            val row = entry.jsonObject
            SyncPlayQueueRow(
                row.getValue("id").jsonPrimitive.content,
                row.getValue("title").jsonPrimitive.content,
                row.getValue("current").jsonPrimitive.boolean
            )
        }
        val state = allowedEmpty().copy(
            groupId = "g",
            groupName = c.text("groupName"),
            phase = c.text("phase"),
            queue = queue,
            localPlayback = SyncLocalPlayback.READY
        )
        compose.setContent { TvEnvironment { SyncPlayPanel(state, { _, _ -> }, {}) } }
        settle()
        compose.onNodeWithTag("sync:${c.expected.getValue("firstUsefulFocus").jsonPrimitive.content}").assertIsFocused()
        val personal = compose.onNodeWithTag("sync:personal", useUnmergedTree = true)
        personal.assertIsDisplayed()
        val fact = personal.fetchSemanticsNode().boundsInRoot
        val transport = listOf("sync:previous", "sync:play", "sync:next").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        assertTrue(
            fact.bottom <= transport.minOf { it.top },
            "Group-wide shared/personal fact must precede transport, never read as Wait's caption: fact=$fact controls=$transport"
        )
        val factInk = actualInkBounds(personal)
        // Select the actual nearest visible predecessor, rather than measuring across phase
        // or empty-queue guidance and accidentally counting their height as block spacing.
        val precedingInk = listOf("sync:now-playing", "sync:phase", "sync:empty-next-step").flatMap { tag ->
            val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (nodes.isEmpty()) {
                emptyList()
            } else {
                assertEquals(1, nodes.size, "Each status/context line must have one rendered identity: $tag")
                val node = compose.onNodeWithTag(tag)
                node.assertIsDisplayed()
                listOf(actualInkBounds(node))
            }
        }.filter { it.bottom <= factInk.top }
        assertTrue(precedingInk.isNotEmpty(), "The group-context fact needs a real preceding status/context line")
        val nearestPrecedingInk = precedingInk.maxBy { it.bottom }
        val minimum = c.metric("minimumBlockGapDp") * density
        assertTrue(
            factInk.top - nearestPrecedingInk.bottom >= minimum - 1f,
            "Adjacent painted status/context and group fact need12dp block clearance: preceding=$nearestPrecedingInk fact=$factInk density=$density"
        )
        assertTrue(
            transport.minOf { it.top } - factInk.bottom >= minimum - 1f,
            "Group context needs breathing room before controls: fact=$factInk controls=$transport"
        )
    }
    private fun actualInkBounds(node: SemanticsNodeInteraction): Rect {
        val ink = layout(node).layoutInput.style.color
        val target = android.graphics.Color.argb(
            (ink.alpha * 255).toInt(),
            (ink.red * 255).toInt(),
            (ink.green * 255).toInt(),
            (
                ink.blue *
                    255
                ).toInt()
        )
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val bitmap = node.captureToImage().asAndroidBitmap()
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val difference = (0..3).maxOf { shift -> abs(((color ushr (shift * 8)) and 255) - ((target ushr (shift * 8)) and 255)) }
                if (difference <= 8) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= left && bottom >= top, "Actual native glyph ink must render; style metadata alone is insufficient")
        return Rect(bounds.left + left, bounds.top + top, bounds.left + right + 1, bounds.top + bottom + 1)
    }
}
