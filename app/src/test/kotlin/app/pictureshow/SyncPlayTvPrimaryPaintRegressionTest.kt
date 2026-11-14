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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** Test-only proposal. Root owns staging and baseline execution; no native IME claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvPrimaryPaintRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 0f
    private var onSurface = 0
    private var surface = 0
    private var styleSizes = emptyMap<String, TextUnit>()
    private var themeColors = emptyMap<String, Int>()
    private data class SurfaceVector(val input: JsonObject, val expected: JsonObject) {
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun count(key: String) = expected.getValue(key).jsonPrimitive.int
        fun flag(key: String) = expected.getValue(key).jsonPrimitive.boolean
        fun token(key: String) = expected.getValue(key).jsonPrimitive.content
    }
    private fun vector(id: String, profile: String): SurfaceVector {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-tv-surfaces.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-surfaces", suite.getValue("suite").jsonPrimitive.content)
        val found = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        assertEquals(profile, found.getValue("profile").jsonPrimitive.content)
        return SurfaceVector(found.getValue("input").jsonObject, found.getValue("expected").jsonObject)
    }
    private fun color(value: String) = Color(value.toLong(16).toInt())

    @Before fun tvMode() {
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun TvEnvironment(theme: JsonObject? = null, content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) {
            PictureshowTheme {
                if (theme != null && theme.getValue("kind").jsonPrimitive.content == "alternate") {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            primary = color(theme.getValue("primary").jsonPrimitive.content),
                            onPrimary = color(theme.getValue("onPrimary").jsonPrimitive.content),
                            surface = color(theme.getValue("surface").jsonPrimitive.content),
                            onSurface = color(theme.getValue("onSurface").jsonPrimitive.content)
                        )
                    ) { RecordTheme(content) }
                } else {
                    RecordTheme(content)
                }
            }
        }
    }

    @Composable private fun RecordTheme(content: @Composable () -> Unit) {
        val scale = LocalDensity.current.density
        val ink = MaterialTheme.colorScheme.onSurface.toArgb()
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val size = MaterialTheme.typography.bodyMedium.fontSize
        val panelSurface = MaterialTheme.colorScheme.surface.toArgb()
        val sizes = mapOf("bodyMedium" to size, "bodyLarge" to MaterialTheme.typography.bodyLarge.fontSize)
        val colors = mapOf("onSurfaceVariant" to quiet, "onSurface" to ink)
        SideEffect {
            density = scale
            onSurface = ink
            surface = panelSurface
            styleSizes = sizes
            themeColors = colors
        }
        content()
    }

    private fun chooser() = SyncPlayPresentation(
        server = "Cinema",
        viewer = "Viewer",
        policyKnown = true,
        policy = "CreateAndJoinGroups"
    )
    private fun showChooser(case: SurfaceVector) {
        RuntimeEnvironment.setFontScale(case.number("fontScale"))
        compose.setContent { TvEnvironment(case.input.getValue("theme").jsonObject) { SyncPlayPanel(chooser(), { _, _ -> }, {}) } }
        settle()
        compose.onNodeWithTag("sync:name").assertIsFocused()
    }
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }
    private fun key(value: Key) {
        compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(value) }
        settle()
    }
    private fun bounds(tag: String, unmerged: Boolean = false) =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).getUnclippedBoundsInRoot()
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.size, "Read the actual rendered Text layout for $tag")
        return results.single()
    }

    @Test fun enabledCreateHasABroadVisibleFocusMarkInDefaultTheme() = verifyCreateFocus("create-visible-focus-default")

    @Test fun enabledCreateHasABroadVisibleFocusMarkInAlternateTheme() = verifyCreateFocus("create-visible-focus-alternate")
    private fun verifyCreateFocus(id: String) {
        val case = vector(id, "create-focus")
        showChooser(case)
        compose.onNodeWithTag("sync:name").performTextInput(case.text("draft"))
        // A semantics focus request only selects the state to compare. Native Down order stays
        // covered separately; it must not prevent examining the actual Create focus paint.
        compose.onNodeWithTag("sync:refresh").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        compose.onNodeWithTag("sync:refresh").assertIsFocused()
        compose.onNodeWithTag("sync:create").assertIsEnabled()
        val panel = bounds("sync:panel")
        val button = bounds("sync:create")
        val resting = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
        compose.onNodeWithTag("sync:create").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle()
        compose.onNodeWithTag("sync:create").assertIsFocused()
        assertEquals(
            case.flag("stableBounds"),
            button == bounds("sync:create"),
            "Vector stable-bounds outcome must match actual geometry"
        )
        assertEquals(button, bounds("sync:create"), "Focus must preserve the full-width body grid and scale1")
        val focused = compose.onNodeWithTag("sync:panel").captureToImage().asAndroidBitmap()
        assertBroadFocusChange(resting, focused, panel, button, case)
    }

    private fun assertBroadFocusChange(rest: Bitmap, focus: Bitmap, panel: DpRect, button: DpRect, case: SurfaceVector) {
        // Compare broad bands at the same screen coordinates. This accepts either a contrasting
        // fill or a visible outline; it never fixes a token or a proposed implementation.
        val excludedFraction = (1f - case.metric("focusMarkBandWidthFraction")) / 2
        val left = ((button.left - panel.left + (button.right - button.left) * excludedFraction).value * density).roundToInt()
        val right = ((button.right - panel.left - (button.right - button.left) * excludedFraction).value * density).roundToInt()
        val top = ((button.top - panel.top).value * density).roundToInt()
        val bottom = ((button.bottom - panel.top).value * density).roundToInt()
        val radius = (case.metric("focusProbeRadiusDp") * density).roundToInt().coerceAtLeast(1)
        val requiredRows = (case.metric("focusMarkMinThicknessDp") * density).roundToInt().coerceAtLeast(1)
        val bands = listOf((top - radius)..(top + radius), (bottom - radius)..(bottom + radius))
        val visible = bands.any { band ->
            var consecutive = 0
            band.any { y ->
                if (y !in 0 until rest.height) {
                    consecutive = 0
                    false
                } else {
                    val changed = (left until right).count { x ->
                        ColorUtils.calculateContrast(focus.getPixel(x, y), rest.getPixel(x, y)) >=
                            case.metric("focusMarkMinContrast").toDouble()
                    }
                    consecutive = if (changed >= (right - left) * case.metric("focusMarkChangedWidthFraction")) consecutive + 1 else 0
                    consecutive >= requiredRows
                }
            }
        }
        assertTrue(visible, "Create needs a broad2dp focus mark with3:1 change from rest; identical primary paint is invisible")
    }

    @Test fun selectKeepsTvCreateAndFooterDividerMounted() = verifyFooter("create-footer-during-edit")

    @Test fun modeledImeConsumedBackKeepsDraftNameFocusAndFooterWithoutFocusMove() = verifyFooter("create-footer-after-platform-dismissal")
    private fun verifyFooter(id: String) {
        val case = vector(id, "create-footer")
        showChooser(case)
        compose.onNodeWithTag("sync:name").performTextInput(case.text("draft"))
        val before = bounds("sync:name")
        key(Key.DirectionCenter)
        val imm = compose.activity.getSystemService(InputMethodManager::class.java)
        assertTrue(
            shadowOf(imm).isSoftInputVisible,
            "Existing explicit Select-to-editor path must still request platform input"
        )
        assertEquals(
            case.token("retainedDraft"),
            compose.onNodeWithTag("sync:name").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text,
            "Editing must retain the actual editable draft"
        )
        when (case.text("keyboardState")) {
            "requested" -> Unit
            "platform-hidden" -> {
                // Model platform-consumed Back without app delivery/edit-flag/focus mutation.
                // The shadow is not actual floating TV IME or zero-inset integration evidence.
                compose.runOnIdle { imm.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
                settle()
                assertFalse(shadowOf(imm).isSoftInputVisible)
                assertEquals(
                    case.token("retainedDraft"),
                    compose.onNodeWithTag("sync:name").fetchSemanticsNode()
                        .config[SemanticsProperties.EditableText].text,
                    "Dismissal must retain the actual editable draft"
                )
            }
            else -> error("Unsupported keyboardState in TV render profile")
        }
        compose.onNodeWithTag("sync:name").assertIsFocused()
        assertEquals(
            case.flag("stableEditorBounds"),
            before == bounds("sync:name"),
            "Vector stable-editor outcome must match actual geometry"
        )
        assertEquals(before, bounds("sync:name"), "Select/dismissal must preserve stable editor geometry")
        assertFooterPainted(case)
    }

    private fun assertFooterPainted(case: SurfaceVector) {
        val createNodes = compose.onAllNodesWithTag("sync:create").fetchSemanticsNodes()
        assertEquals(case.flag("footerVisible"), createNodes.isNotEmpty(), "Vector footer visibility must match actual mounted action")
        compose.onNodeWithTag("sync:create").assertIsDisplayed().assertIsEnabled()
        assertEquals(
            case.flag("createEnabled"),
            !createNodes.single().config.contains(SemanticsProperties.Disabled),
            "Vector enabled outcome must match actual Create semantics"
        )
        val bitmap = compose.onNodeWithTag("tv-settings:footer").captureToImage().asAndroidBitmap()
        val inset = (case.metric("dividerProbeInsetDp") * density).roundToInt().coerceAtLeast(1)
        val divider = bitmap.getPixel(bitmap.width / 2, 0)
        val background = bitmap.getPixel(bitmap.width / 2, inset)
        assertTrue(distance(divider, background) >= case.count("dividerChannelDeltaMin"), "Mounted footer must also paint its divider")
        for (x in (bitmap.width * case.metric("dividerSideExcludedFraction")).roundToInt() until
            (bitmap.width * (1f - case.metric("dividerSideExcludedFraction"))).roundToInt()) {
            assertTrue(
                distance(divider, bitmap.getPixel(x, 0)) <= case.count("dividerChannelTolerance"),
                "Footer divider must span the body column"
            )
        }
    }

    @Test fun whollyVisibleEnabledRestRowRetainsFullLabelPaintAtFont10() = verifyWholeRowPaint("whole-row-full-ink-font-1-0")

    @Test fun whollyVisibleEnabledRestRowRetainsFullLabelPaintAtFont13() = verifyWholeRowPaint("whole-row-full-ink-font-1-3")
    private fun verifyWholeRowPaint(id: String) {
        val case = vector(id, "whole-row-ink")
        assertEquals("whole", case.text("rowVisibility"))
        val font = case.number("fontScale")
        val labels = case.input.getValue("rowLabels").jsonArray.map { it.jsonPrimitive.content }
        RuntimeEnvironment.setFontScale(font)
        val largeViewport = case.number("measuringViewportHeightDp").dp
        var viewportHeight by mutableStateOf(largeViewport)
        compose.setContent {
            TvEnvironment(case.input.getValue("theme").jsonObject) {
                TvSettingsList(
                    Modifier.width(case.number("viewportWidthDp").dp).height(viewportHeight).background(MaterialTheme.colorScheme.surface),
                    minimalFocusScroll = true
                ) {
                    TvSettingsToggleRow(labels[0], false, Modifier.testTag("paint:first")) {}
                    TvSettingsToggleRow(labels[1], false, Modifier.testTag("paint:whole")) {}
                    TvSettingsToggleRow(labels[2], false, Modifier.testTag("paint:following")) {}
                }
            }
        }
        settle()
        // Measure the real production row at this width/font before choosing the subject
        // viewport. Native row height can grow at font1.3; no nominal56dp assumption.
        val measuredView = bounds("tv-settings:viewport")
        val measuredRow = bounds("paint:whole")
        val exactWholeRowHeight = measuredRow.bottom - measuredView.top
        assertTrue(
            exactWholeRowHeight > 0.dp && exactWholeRowHeight < largeViewport,
            "Initial viewport must be large enough to measure the complete real row"
        )
        compose.runOnIdle { viewportHeight = exactWholeRowHeight }
        settle()
        val view = bounds("tv-settings:viewport")
        val row = bounds("paint:whole")
        val tolerance = (1f / density).dp
        assertTrue(
            row.top >= view.top && row.bottom <= view.bottom + tolerance,
            "The production control must be wholly inside the viewport, not a cut-row case: row=$row viewport=$view"
        )
        compose.onNodeWithTag("paint:whole").assertIsEnabled()
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        val text = layout("tv-settings:label:${labels[1]}")
        assertEquals(font, text.layoutInput.density.fontScale, .01f, "Verify the actual native text font scale")
        val glyph = bounds("tv-settings:label:${labels[1]}", unmerged = true)
        val bitmap = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
        // Keep the first screenshot as the subject. Enlarge only the viewport to obtain an
        // unobscured reference from the same native row, same text layout, same theme/font.
        // This checks every solid glyph pixel, including lower strokes; a few bright top pixels
        // must not let a partially faded label pass.
        compose.runOnIdle { viewportHeight = largeViewport }
        settle()
        val clearView = bounds("tv-settings:viewport")
        val clearGlyph = bounds("tv-settings:label:${labels[1]}", unmerged = true)
        val reference = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
        assertEquals(glyph.right - glyph.left, clearGlyph.right - clearGlyph.left)
        assertEquals(glyph.bottom - glyph.top, clearGlyph.bottom - clearGlyph.top)
        val fades = compose.onAllNodesWithTag("tv-settings:fade-bottom").fetchSemanticsNodes()
        if (fades.isNotEmpty()) {
            assertTrue(
                clearGlyph.bottom <= bounds("tv-settings:fade-bottom").top,
                "The production reference must clear the actual fade"
            )
        }
        val subjectX = ((glyph.left - view.left).value * density).roundToInt()
        val subjectY = ((glyph.top - view.top).value * density).roundToInt()
        val referenceX = ((clearGlyph.left - clearView.left).value * density).roundToInt()
        val referenceY = ((clearGlyph.top - clearView.top).value * density).roundToInt()
        val width = ((glyph.right - glyph.left).value * density).roundToInt()
        val height = ((glyph.bottom - glyph.top).value * density).roundToInt()
        assertTrue(
            subjectX >= 0 && subjectY >= 0 && subjectX + width <= bitmap.width && subjectY + height <= bitmap.height,
            "Inspect a real wholly visible glyph box, never a clipped or empty crop"
        )
        var solidInk = 0
        var attenuatedInk = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (distance(onSurface, reference.getPixel(referenceX + x, referenceY + y)) <= case.count("solidGlyphChannelTolerance")) {
                    solidInk++
                    if (distance(onSurface, bitmap.getPixel(subjectX + x, subjectY + y)) >
                        case.count("solidGlyphChannelTolerance")
                    ) {
                        attenuatedInk++
                    }
                }
            }
        }
        assertTrue(
            solidInk >= case.count("minReferenceSolidGlyphPixels"),
            "The unobscured real row must actually paint onSurface glyph strokes"
        )
        assertEquals(
            case.count("maxAttenuatedSolidGlyphPixels"),
            attenuatedInk,
            "Every solid glyph stroke of a wholly visible enabled row must retain full onSurface ink; solid=$solidInk row=$row viewport=$view"
        )
    }

    @Test fun emptyStatusHasTwelveDpClearanceFromRefresh() {
        val case = vector("empty-status-clearance", "empty-status-spacing")
        showChooser(case)
        val refresh = bounds("sync:refresh")
        val empty = bounds("sync:empty")
        val gap = if (empty.top >= refresh.bottom) empty.top - refresh.bottom else refresh.top - empty.bottom
        assertTrue(
            gap >= case.metric("minimumGapDp").dp - (1f / density).dp,
            "Status and Refresh need12dp separation in either permitted order: refresh=$refresh empty=$empty"
        )
    }

    @Test fun emptyStatusUsesQuietBodyMediumTypography() {
        val case = vector("empty-status-hierarchy", "empty-status-style")
        showChooser(case)
        val text = layout("sync:empty")
        assertEquals(
            styleSizes.getValue(case.token("textStyle")),
            text.layoutInput.style.fontSize,
            "No-groups status must not compete with bodyLarge action labels"
        )
        assertEquals(
            themeColors.getValue(case.token("textColor")),
            text.layoutInput.style.color.toArgb(),
            "No-groups status needs actual onSurfaceVariant ink"
        )
    }

    @Test fun cutRegisteredRowKeepsFortyDpMaskAndEightDpOpaqueBandAtFont10() = verifyCutRow("cut-row-full-mask-font-1-0")

    @Test fun cutRegisteredRowKeepsFortyDpMaskAndEightDpOpaqueBandAtFont13() = verifyCutRow("cut-row-full-mask-font-1-3")
    private fun verifyCutRow(id: String) {
        val case = vector(id, "cut-row-mask")
        assertEquals("cut", case.text("rowVisibility"))
        val font = case.number("fontScale")
        RuntimeEnvironment.setFontScale(font)
        val labels = case.input.getValue("rowLabels").jsonArray.map { it.jsonPrimitive.content }
        val colors = case.input.getValue("underlayColors").jsonArray.map { color(it.jsonPrimitive.content) }
        val largeViewport = case.number("measuringViewportHeightDp").dp
        var viewportHeight by mutableStateOf(largeViewport)
        var underlay by mutableStateOf(colors.first())
        compose.setContent {
            TvEnvironment(case.input.getValue("theme").jsonObject) {
                TvSettingsList(
                    Modifier.width(case.number("viewportWidthDp").dp).height(viewportHeight)
                        .background(MaterialTheme.colorScheme.surface),
                    minimalFocusScroll = true
                ) {
                    TvSettingsToggleRow(labels[0], false, Modifier.testTag("cut:first")) {}
                    // Existing shared native row is automatically registered by the proposed fix.
                    // The bright trailing visual is real content inside that row, not an
                    // unregistered giant fallback Box; no new production test hook is required.
                    TvSettingsRow(labels[1], null, Modifier.testTag("cut:row"), onClick = {}, trailing = {
                        Box(
                            Modifier.width(case.number("brightContentWidthDp").dp)
                                .height(case.number("brightContentHeightDp").dp).background(underlay)
                                .testTag("cut:bright")
                        )
                    })
                    TvSettingsToggleRow(labels[2], false, Modifier.testTag("cut:following")) {}
                }
            }
        }
        settle()
        val measuredView = bounds("tv-settings:viewport")
        val measuredRow = bounds("cut:row")
        val cutHeight = measuredRow.top - measuredView.top +
            (measuredRow.bottom - measuredRow.top) * case.number("cutFractionOfMeasuredRow")
        compose.runOnIdle { viewportHeight = cutHeight }
        settle()
        val view = bounds("tv-settings:viewport")
        val row = bounds("cut:row")
        assertTrue(
            row.top < view.bottom && row.bottom > view.bottom,
            "Real registered row must cross the edge in UNCLIPPED geometry: row=$row viewport=$view"
        )
        compose.onNodeWithTag("cut:row").assertIsEnabled()
        assertEquals(font, layout("tv-settings:label:${labels[1]}").layoutInput.density.fontScale, .01f)
        val fade = bounds("tv-settings:fade-bottom")
        assertEquals(
            case.metric("bottomFadeExtentDp").dp,
            fade.bottom - fade.top,
            "A cut registered row must keep the complete40dp mask"
        )
        assertEquals(view.bottom, fade.bottom, "Mask must end at the actual viewport edge")
        compose.onNodeWithTag("tv-settings:scroll-indicator").assertExists()
        val bright = compose.onNodeWithTag("cut:bright", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val left = ((bright.left - view.left + (bright.right - bright.left) / 4).value * density).roundToInt()
        val right = ((bright.right - view.left - (bright.right - bright.left) / 4).value * density).roundToInt()
        val probeX = (left + right) / 2
        val probeY = ((fade.top - view.top - 4.dp).value * density).roundToInt()
        assertTrue(
            probeY >= ((bright.top - view.top).value * density).roundToInt(),
            "Bright adversarial source must visibly extend above the fade for a non-vacuous check"
        )
        val strip = (case.metric("opaqueBandDp") * density).roundToInt()
        var first: Bitmap? = null
        for (paint in colors) {
            compose.runOnIdle { underlay = paint }
            settle()
            val bitmap = compose.onNodeWithTag("tv-settings:viewport").captureToImage().asAndroidBitmap()
            assertTrue(
                distance(paint.toArgb(), bitmap.getPixel(probeX, probeY)) <= case.count("opaqueChannelTolerance"),
                "The registered row's adversarial source must actually render before inspecting its covered edge"
            )
            for (y in bitmap.height - strip until bitmap.height) {
                for (x in left until right) {
                    val actual = bitmap.getPixel(x, y)
                    assertTrue(
                        distance(surface, actual) <= case.count("opaqueChannelTolerance"),
                        "Final8dp must completely hide cut-row bright content at($x,$y)"
                    )
                    first?.let { original ->
                        assertTrue(
                            distance(original.getPixel(x, y), actual) <= case.count("opaqueChannelTolerance"),
                            "Opaque edge must be independent of two bright registered-row underlays"
                        )
                    }
                }
            }
            first = bitmap
        }
    }

    private fun distance(a: Int, b: Int) = (0..3).maxOf { shift ->
        abs(((a ushr (shift * 8)) and 255) - ((b ushr (shift * 8)) and 255))
    }
}
