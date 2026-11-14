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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
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

/** Real SyncPlayPanel and shared TvSettingsList. All paint metrics use actual native pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvStructureDiscoveryRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var density = 1f
    private var outlineVariant = 0

    @Before fun tvMode() {
        RuntimeEnvironment.setFontScale(1f)
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @After fun restoreFont() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Composable private fun Environment(tv: Boolean, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            density = LocalDensity.current.density
            if (tv) {
                PictureshowTheme { content() }
            } else {
                app.pictureshow.ui.PictureshowTheme {
                    outlineVariant = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.toArgb()
                    content()
                }
            }
        }
    }

    private data class Case(val input: JsonObject, val expected: JsonObject) {
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun flag(key: String) = expected.getValue(key).jsonPrimitive.boolean
        fun text(key: String) = expected.getValue(key).jsonPrimitive.content
    }

    private fun case(id: String): Case {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-tv-structure-discovery.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-structure-discovery", suite.getValue("suite").jsonPrimitive.content)
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

    private fun launch(c: Case, joined: Boolean): MutableState<SyncPlayPresentation> {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        val tv = c.input.getValue("platform").jsonPrimitive.content == "tv"
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        val initial = if (joined) {
            assertEquals(0, c.input.getValue("queueEntries").jsonPrimitive.int)
            allowedEmpty().copy(
                groupId = "g",
                groupName = "Friday films",
                phase = c.input.getValue("phase").jsonPrimitive.content,
                localPlayback = SyncLocalPlayback.UNAVAILABLE,
                participants = listOf("Morgan", "Sam")
            )
        } else {
            allowedEmpty()
        }
        val state = mutableStateOf(initial)
        compose.setContent { Environment(tv) { SyncPlayPanel(state.value, { _, _ -> }, {}) } }
        settle()
        return state
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun focusTag(behavior: String): String = when (behavior) {
        "wait-setting" -> "sync:ignore-wait"
        else -> error("Unbound portable focus behavior: $behavior")
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun bounds(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot()
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val values = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
        assertEquals(1, values.size, "Actual production Text must expose one native layout")
        return values.single()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualTvSectionsSeparatePersonalWaitFromSharedRepeat() {
        val c = case("joined-personal-and-group-sections")
        launch(c, joined = true)
        assertGroupContextVisibility(c)
        compose.onNodeWithTag(focusTag(c.text("firstUsefulFocus"))).assertIsFocused()
        val personalHeading = compose.onNodeWithText(c.text("personalHeading"), useUnmergedTree = true)
        val groupHeading = compose.onNodeWithText(c.text("groupHeading"), useUnmergedTree = true)
        personalHeading.assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        groupHeading.assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val personalFact = compose.onNode(
            hasAnyAncestor(hasTestTag("sync:personal-scope")) and hasText(c.text("personalScopeContains"), substring = true),
            useUnmergedTree = true
        )
        val groupFact = compose.onNode(
            hasAnyAncestor(hasTestTag("sync:group-scope")) and hasText(c.text("groupScopeContains"), substring = true),
            useUnmergedTree = true
        )
        personalFact.assertExists()
        groupFact.assertExists()
        val nodes = mapOf(
            "personalHeading" to personalHeading,
            "personalScope" to personalFact,
            "wait" to compose.onNodeWithTag("sync:ignore-wait"),
            "groupHeading" to groupHeading,
            "groupScope" to groupFact,
            "repeat" to compose.onNodeWithText(text(R.string.sync_repeat), useUnmergedTree = true)
        )
        val order = c.expected.getValue("readingOrder").jsonArray.map { it.jsonPrimitive.content }
        order.zipWithNext().forEach { (before, after) ->
            assertTrue(
                bounds(nodes.getValue(before)).bottom <= bounds(nodes.getValue(after)).top,
                "Actual section order must separate personal and shared controls: $before then $after"
            )
        }
        // Bring each actual heading/supporting Text into view, including the section initially below the fold.
        listOf(personalHeading, personalFact, groupHeading, groupFact).forEach { node ->
            node.performScrollTo()
            settle()
            node.assertIsDisplayed()
            layout(node)
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualForwardDiscoveryHasContrastingIndicatorAndWholeHeaderAtFont10() = discovery("forward-discovery-font10")

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualForwardDiscoveryHasContrastingIndicatorAndWholeHeaderAtFont13() = discovery("forward-discovery-font13")

    private fun assertGroupContextVisibility(c: Case) {
        val context = compose.onNodeWithTag("sync:personal")
        if (c.flag("groupContextVisible")) context.assertExists() else context.assertDoesNotExist()
    }

    private fun scrollOwner(): SemanticsNodeInteraction {
        val target = compose.onNodeWithTag("sync:ignore-wait").fetchSemanticsNode()
        val owner = generateSequence(target.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Fixture must use the actual production vertical scroll ancestor")
        return compose.onNode(SemanticsMatcher("actual TV vertical scroll owner") { it.id == owner.id }, useUnmergedTree = true)
    }

    private fun discovery(id: String) {
        val c = case(id)
        launch(c, joined = true)
        assertGroupContextVisibility(c)
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
        val owner = scrollOwner()
        val range = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > range.value(), "Profile must establish actual forward-scrolling content")
        val errors = mutableListOf<String>()
        val viewNode = compose.onNodeWithTag("tv-settings:viewport")
        val view = bounds(viewNode)
        val image = viewNode.captureToImage().asAndroidBitmap()
        val indicator = bounds(compose.onNodeWithTag("tv-settings:scroll-indicator").assertIsDisplayed())
        val x = (((indicator.left + indicator.right) / 2 - view.left).value * density).roundToInt()
        val backgroundX = ((indicator.right - view.left).value * density).roundToInt() + 2
        assertTrue(x in 0 until image.width && backgroundX in 0 until image.width, "Actual indicator and adjacent surface must fit capture")
        val top = ((indicator.top - view.top).value * density).roundToInt().coerceAtLeast(0)
        val bottom = ((indicator.bottom - view.top).value * density).roundToInt().coerceAtMost(image.height)
        var run = 0
        var longest = 0
        var maximumContrast = 1.0
        for (y in top until bottom) {
            val contrast = ColorUtils.calculateContrast(image.getPixel(x, y), image.getPixel(backgroundX, y))
            maximumContrast = maxOf(maximumContrast, contrast)
            run = if (contrast >= c.metric("indicatorMinimumContrast")) run + 1 else 0
            longest = maxOf(longest, run)
        }
        if (longest < c.metric("indicatorMinimumPaintedRunDp") * density) {
            errors += "Actual indicator needs ${c.metric("indicatorMinimumContrast")}:1 " +
                "for a ${c.metric("indicatorMinimumPaintedRunDp")}dp run; " +
                "maximumContrast=$maximumContrast longestNativeRun=$longest density=$density"
        }
        val focusedLabel = compose.onNodeWithTag("tv-settings:label:${text(R.string.sync_wait_for_me)}", useUnmergedTree = true)
        assertEquals(c.number("fontScale"), layout(focusedLabel).layoutInput.density.fontScale, .01f)
        if (c.flag("currentLabelAboveMask")) {
            val ink = actualInkBounds(focusedLabel)
            val fades = compose.onAllNodesWithTag("tv-settings:fade-bottom").fetchSemanticsNodes()
            val clearBottom = if (fades.isEmpty()) view.bottom else bounds(compose.onNodeWithTag("tv-settings:fade-bottom")).top
            if (ink.bottom > clearBottom.value * density + c.metric("nativePixelTolerance")) {
                errors += "Current focused label ink must clear actual mask: ink=$ink clearBottom=$clearBottom density=$density"
            }
        }
        val headers = compose.onAllNodesWithText(c.text("continuationHeading"), useUnmergedTree = true).fetchSemanticsNodes()
        if (headers.size != 1) {
            errors += "Initial empty group needs a real Group playback header above the guard; actual=${headers.size}"
        } else {
            val header = compose.onNodeWithText(c.text("continuationHeading"), useUnmergedTree = true)
            header.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            val box = bounds(header)
            val fades = compose.onAllNodesWithTag("tv-settings:fade-bottom").fetchSemanticsNodes()
            val clearBottom = if (fades.isEmpty()) view.bottom else bounds(compose.onNodeWithTag("tv-settings:fade-bottom")).top
            if (box.top < view.top || box.bottom > clearBottom) {
                errors += "Whole next heading must be allocated above actual mask: heading=$box viewport=$view clearBottom=$clearBottom"
            } else {
                header.assertIsDisplayed()
                val ink = actualInkBounds(header)
                if (ink.bottom > clearBottom.value * density + c.metric("nativePixelTolerance")) {
                    errors += "Initial next-heading glyphs must clear unchanged actual mask: ink=$ink clearBottom=$clearBottom"
                }
            }
        }
        if (!c.flag("emptyTransportVisible")) {
            for (tag in listOf("sync:previous", "sync:play", "sync:pause", "sync:next")) {
                if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
                    errors += "An empty queue must not mount an unusable transport: $tag"
                }
            }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualPrivacyPaintClearsFloatingNameLabelAtFont10() = privacyGap("create-privacy-to-floating-label-font10")

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualPrivacyPaintClearsFloatingNameLabelAtFont13() = privacyGap("create-privacy-to-floating-label-font13")

    private fun privacyGap(id: String) {
        val c = case(id)
        launch(c, joined = false)
        if (c.flag("nameFocused")) compose.onNodeWithTag("sync:name").assertIsFocused()
        val privacy = compose.onNodeWithText(text(R.string.sync_privacy), useUnmergedTree = true).assertIsDisplayed()
        val label = compose.onNode(
            hasAnyAncestor(hasTestTag("sync:name")) and hasText(text(R.string.sync_group_name)),
            useUnmergedTree = true
        ).assertIsDisplayed()
        assertEquals(c.number("fontScale"), layout(privacy).layoutInput.density.fontScale, .01f)
        assertEquals(c.number("fontScale"), layout(label).layoutInput.density.fontScale, .01f)
        val privacyInk = actualInkBounds(privacy)
        val labelInk = actualInkBounds(label)
        val minimum = c.metric("minimumPaintedGapDp") * density - c.metric("nativePixelTolerance")
        assertTrue(
            labelInk.top - privacyInk.bottom >= minimum,
            "Privacy paint must separate from actual floating-label paint: privacy=$privacyInk label=$labelInk " +
                "gapPx=${labelInk.top - privacyInk.bottom} minimumPx=$minimum density=$density"
        )
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun actualTvEmptyQueueHidesTransportAndRealMediaKeepsActions() = emptyTransport("empty-transport-tv")

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun actualPhoneEmptyQueueHidesTransportAndRealMediaKeepsActions() = emptyTransport("empty-transport-phone")

    private fun emptyTransport(id: String) {
        val c = case(id)
        val tv = c.input.getValue("platform").jsonPrimitive.content == "tv"
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        val sent = mutableListOf<String>()
        assertEquals(0, c.input.getValue("queueEntries").jsonPrimitive.int)
        val state = mutableStateOf(
            allowedEmpty().copy(
                groupId = "g",
                groupName = "Friday films",
                phase = c.input.getValue("phase").jsonPrimitive.content,
                localPlayback = SyncLocalPlayback.UNAVAILABLE
            )
        )
        compose.setContent { Environment(tv) { SyncPlayPanel(state.value, { op, _ -> sent += op }, {}) } }
        settle()
        val absent = c.expected.getValue("emptyAbsentActions").jsonArray.map { "sync:${it.jsonPrimitive.content}" }
        absent.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        if (tv) compose.onNodeWithTag(focusTag(c.text("emptyUsefulFocus"))).assertIsFocused()
        compose.runOnIdle {
            state.value = state.value.copy(
                phase = c.input.getValue("nonemptyPhase").jsonPrimitive.content,
                localPlayback = SyncLocalPlayback.READY,
                queue = listOf(SyncPlayQueueRow("film", "A real film", true))
            )
        }
        settle()
        val enabled = c.expected.getValue("nonemptyEnabledActions").jsonArray.map { "sync:${it.jsonPrimitive.content}" }
        val actions = c.expected.getValue("nonemptyActions").jsonArray.map { it.jsonPrimitive.content }
        enabled.zip(actions).forEachIndexed { index, (tag, action) ->
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            if (tv) {
                node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                compose.waitForIdle()
                node.assertIsFocused()
                compose.onNodeWithTag("sync:panel").performKeyInput { pressKey(Key.DirectionCenter) }
                compose.waitForIdle()
            } else {
                node.performClick()
            }
            assertEquals(actions.take(index + 1), sent, "Each native transport request must dispatch exactly once, in order")
            assertEquals(action, sent.last(), "Nonempty native transport must dispatch its real action")
        }
        if (tv) {
            compose.onNodeWithTag("sync:play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("sync:play").assertIsFocused()
        }
        compose.runOnIdle { state.value = state.value.copy(phase = "Idle", queue = emptyList()) }
        settle()
        absent.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        if (tv) compose.onNodeWithTag(focusTag(c.text("emptyUsefulFocus"))).assertIsFocused()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun actualPhone640FooterIsOneRowAndInitialNextSectionPaintsAtFont10() = phoneDiscovery("phone640-discovery-font10")

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun actualPhone640FooterIsOneRowAndInitialNextSectionPaintsAtFont13() = phoneDiscovery("phone640-discovery-font13")

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun actualPhone800FooterIsOneRowAndInitialNextSectionPaintsAtFont13() = phoneDiscovery("phone800-discovery-font13")

    private fun phoneDiscovery(id: String) {
        val c = case(id)
        launch(c, joined = true)
        val panel = bounds(compose.onNodeWithTag("sync:panel"))
        val tolerance = c.metric("nativePixelTolerance") / density
        assertEquals(c.number("windowWidthDp"), (panel.right - panel.left).value, tolerance)
        assertEquals(c.number("windowHeightDp"), (panel.bottom - panel.top).value, tolerance)
        val errors = mutableListOf<String>()
        val footer = bounds(compose.onNodeWithTag("sync:touch-footer"))
        val caption = bounds(compose.onNodeWithText(text(R.string.sync_leave_explanation), useUnmergedTree = true))
        val leave = bounds(compose.onNodeWithTag("sync:leave"))
        if (
            caption.right + c.metric("footerHorizontalGapDp").dp > leave.left + tolerance.dp ||
            minOf(caption.bottom, leave.bottom) <= maxOf(caption.top, leave.top)
        ) {
            errors += "Actual footer caption and Leave must share one horizontal row: caption=$caption leave=$leave"
        }
        val leaveTouchTarget = compose.onNodeWithTag("sync:leave").fetchSemanticsNode().touchBoundsInRoot
        if (minOf(leaveTouchTarget.width, leaveTouchTarget.height) / density < c.metric("minimumLeaveTargetDp") - tolerance) {
            errors += "Compact row must preserve actual native Leave touch target: target=$leaveTouchTarget density=$density"
        }
        val shuffle = compose.onNodeWithText(text(R.string.sync_shuffle), useUnmergedTree = true)
        val ownerNode = generateSequence(shuffle.fetchSemanticsNode().parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Phone fixture must use actual production scroll owner")
        val owner = compose.onNode(SemanticsMatcher("actual phone scroll owner") { it.id == ownerNode.id }, useUnmergedTree = true)
        val viewport = bounds(owner)
        val range = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > range.value(), "Profile must establish actual forward content")
        if (c.flag("initialWholeShuffleLabel")) {
            phoneTextPaint(shuffle, owner, c, errors)
        }
        val dividers = compose.onAllNodesWithTag("sync:touch-footer-divider").fetchSemanticsNodes()
        if (dividers.size != 1) {
            errors += "Forward content requires one actually painted footer divider; actual=${dividers.size}"
        } else {
            val divider = compose.onNodeWithTag("sync:touch-footer-divider").assertIsDisplayed()
            val line = bounds(divider)
            assertEquals(c.metric("dividerHeightDp"), (line.bottom - line.top).value, tolerance)
            assertEquals(footer.left.value, line.left.value, tolerance)
            assertEquals(footer.right.value, line.right.value, tolerance)
            assertEquals(footer.top.value, line.top.value, tolerance)
            val bitmap = divider.captureToImage().asAndroidBitmap()
            val sample = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertEquals("outlineVariant", c.text("dividerColorToken"))
            assertTrue(
                channelDistance(sample, outlineVariant) <= 2,
                "Actual divider must paint theme outlineVariant: $sample/$outlineVariant"
            )
        }
        // One ordinary measured scroll to the end, with one actual window's finite nested-scroll budget.
        owner.performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, range.maxValue() - range.value() + (panel.bottom - panel.top).value * density))
        }
        settle()
        val end = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(end.value() >= end.maxValue(), "Measured ordinary scroll must actually reach end")
        if (c.expected.getValue("dividerAbsentAtEnd").jsonPrimitive.boolean) {
            compose.onNodeWithTag("sync:touch-footer-divider").assertDoesNotExist()
        }
        // Ordinary measured scrolls reveal actual text, without requesting focus or changing app state.
        val revealText = c.expected.getValue("revealTextAfterOrdinaryScroll").jsonArray.map { it.jsonPrimitive.content }
        for (label in revealText) {
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            val before = bounds(node)
            val delta = (((before.top + before.bottom) / 2) - ((viewport.top + viewport.bottom) / 2)).value * density
            owner.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            settle()
            phoneTextPaint(node, owner, c, errors)
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    private fun phoneTextPaint(node: SemanticsNodeInteraction, owner: SemanticsNodeInteraction, c: Case, errors: MutableList<String>) {
        val viewport = bounds(owner)
        val bottomFades = compose.onAllNodesWithTag("sync:fade-bottom").fetchSemanticsNodes()
        val clearBottom = if (bottomFades.isEmpty()) viewport.bottom else bounds(compose.onNodeWithTag("sync:fade-bottom")).top
        val topFades = compose.onAllNodesWithTag("sync:fade-top").fetchSemanticsNodes()
        val clearTop = if (topFades.isEmpty()) viewport.top else bounds(compose.onNodeWithTag("sync:fade-top")).bottom
        val box = bounds(node)
        if (box.top < clearTop || box.bottom > clearBottom) {
            errors += "Real continuation Text must fit above actual fades: text=$box clearTop=$clearTop clearBottom=$clearBottom"
        } else {
            node.assertIsDisplayed()
            assertEquals(c.number("fontScale"), layout(node).layoutInput.density.fontScale, .01f)
            val ink = actualInkBounds(node)
            if (ink.bottom > clearBottom.value * density + c.metric("nativePixelTolerance")) {
                errors += "Continuation must actually paint above unchanged fade: ink=$ink clearBottom=$clearBottom"
            }
        }
    }

    private fun channelDistance(a: Int, b: Int): Int = (0..3).maxOf { shift ->
        abs(((a ushr (shift * 8)) and 255) - ((b ushr (shift * 8)) and 255))
    }

    /** Same native solid-ink approach as the protected chooser-attention test; metadata alone cannot pass. */
    private fun actualInkBounds(node: SemanticsNodeInteraction): Rect {
        val ink = layout(node).layoutInput.style.color
        val target = android.graphics.Color.argb(
            (ink.alpha * 255).toInt(),
            (ink.red * 255).toInt(),
            (ink.green * 255).toInt(),
            (ink.blue * 255).toInt()
        )
        val box = node.fetchSemanticsNode().boundsInRoot
        val bitmap: Bitmap = node.captureToImage().asAndroidBitmap()
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val difference = (0..3).maxOf { shift ->
                    abs(((color ushr (shift * 8)) and 255) - ((target ushr (shift * 8)) and 255))
                }
                if (difference <= 8) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= left && bottom >= top, "Actual native Text glyph ink must render")
        return Rect(box.left + left, box.top + top, box.left + right + 1, box.top + bottom + 1)
    }
}
