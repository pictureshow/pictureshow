/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.CommandState
import app.pictureshow.core.control.RemoteControllerState
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.ViewingIdentity
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** Actual phone remote/group render paths. Native scroll reachability is distinct from first-viewport visibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPhoneRemoteChromeRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = mutableListOf<RemoteRequest>()
    private val host = RemoteControllerHost(
        scope,
        object : RemoteControllerGateway {
            override suspend fun targets(account: Account) = ClientResult.Success(emptyList<RemoteTarget>())
            override suspend fun send(account: Account, request: RemoteRequest): Int {
                requests += request
                return 204
            }
        }
    ) { 0L }
    private var density = 0f
    private var window = IntSize.Zero
    private var surface = 0
    private var outline = 0

    private data class Case(val input: JsonObject, val expected: JsonObject) {
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun token(key: String) = expected.getValue(key).jsonPrimitive.content
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun count(key: String) = expected.getValue(key).jsonPrimitive.int
    }

    private fun case(id: String): Case {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/sync-phone-remote-chrome.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-phone-remote-chrome", suite.getValue("suite").jsonPrimitive.content)
        val row = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        return Case(row.getValue("input").jsonObject, row.getValue("expected").jsonObject)
    }

    @Before fun phoneMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }

    @After fun close() {
        scope.cancel()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun target(c: Case) = RemoteTarget(
        session = "s1",
        device = c.text("deviceId"),
        name = c.text("deviceName"),
        viewer = "other",
        viewerName = c.text("viewerName"),
        sameViewer = false,
        duplicateName = c.input.getValue("duplicateName").jsonPrimitive.boolean,
        commands = c.input.getValue("remoteCommands").jsonArray.map { it.jsonPrimitive.content }
    )

    private fun attached(target: RemoteTarget) = RemoteControllerUi(
        RemoteControllerState(
            "server",
            "me",
            phase = "attached",
            target = target,
            command = CommandState(ViewingIdentity("server", "me", target.device, target.session, 1), fresh = true)
        )
    )

    private fun setup(c: Case) {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
    }

    @Composable
    private fun Environment(content: @Composable () -> Unit) {
        TouchTheme {
            density = LocalDensity.current.density
            window = LocalWindowInfo.current.containerSize
            surface = MaterialTheme.colorScheme.surface.toArgb()
            outline = MaterialTheme.colorScheme.outlineVariant.toArgb()
            content()
        }
    }

    private fun settled(c: Case) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(c.number("densityDpi") / 160f, density, .001f, "Use actual Android density")
        assertEquals(c.number("densityDpi").toInt(), compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(IntSize((c.number("widthDp") * density).toInt(), (c.number("heightDp") * density).toInt()), window)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        assertEquals(1, result.size)
        return result.single()
    }

    private fun native(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().boundsInRoot
    private fun noCommands(c: Case) = assertEquals(c.count("remoteRequests"), requests.size, "Opening or detaching is not a TV command")

    @Test fun remotePageAndPickerSeparateDeviceIdentityFromOptionalSignedInViewer() {
        val c = case("remote-device-identity-and-account")
        setup(c)
        var ui by mutableStateOf(attached(target(c)))
        var picker by mutableStateOf(false)
        var picked = 0
        compose.setContent {
            Environment {
                if (picker) {
                    TouchCastChooser(
                        ui.copy(targets = listOf(requireNotNull(ui.controller?.target))), CastUi(), false, null,
                        { picked++ }, {}, {}, {}, c.text("pickerTitle")
                    )
                } else {
                    TouchRemoteScreen(ui, host, {}, {})
                }
            }
        }
        settled(c)
        val name = c.token("deviceLabel")
        val account = c.token("signedInText")
        val heading = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            heading.fetchSemanticsNode().config.contains(SemanticsProperties.Heading),
            "The page title is the device, not device+account"
        )
        val support = compose.onNodeWithText(account, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(native(support).top >= native(heading).bottom, "Signed-in viewer is a separate supporting line")
        assertEquals(c.number("fontScale"), layout(heading).layoutInput.density.fontScale, .01f)
        compose.runOnIdle { ui = attached(target(c).copy(sameViewer = true)) }
        settled(c)
        compose.onNodeWithText(account, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            ui = attached(target(c))
            picker = true
        }
        settled(c)
        val row = compose.onNodeWithTag("remote:target:s1").assertIsDisplayed()
        val deviceText = compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        val accountText = compose.onNodeWithText(account, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(native(deviceText).bottom <= native(accountText).top, "Picker also separates device from signed-in identity")
        row.performClick()
        assertEquals(c.count("picks"), picked)
        compose.runOnIdle { ui = attached(target(c).copy(sameViewer = true)) }
        settled(c)
        compose.onNodeWithText(account, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
        noCommands(c)
    }

    @Test fun actualRemoteBarContainsStatusBeforeDeviceWithNativeChromeAndOnlyOpens() {
        val c = case("remote-contained-status-first-bar")
        setup(c)
        var opened = 0
        compose.setContent {
            Environment {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    TouchRemoteBar(attached(target(c)), 0) { opened++ }
                }
            }
        }
        settled(c)
        val bar = compose.onNodeWithTag("remote:bar").assertIsDisplayed()
        val box = native(bar)
        assertEquals(c.metric("barHeightDp"), box.height / density, 1f / density, "Bar reserves a stable two-line64dp slot")
        val status = compose.onNodeWithTag("remote:bar-status", useUnmergedTree = true).assertIsDisplayed()
        val label = compose.onNodeWithTag("remote:bar-target", useUnmergedTree = true).assertIsDisplayed()
        val statusLayout = layout(status)
        assertEquals(c.number("fontScale"), statusLayout.layoutInput.density.fontScale, .01f)
        assertEquals(c.token("status"), statusLayout.layoutInput.text.text)
        assertEquals(c.token("deviceLabel"), layout(label).layoutInput.text.text)
        assertEquals(c.count("statusLines"), statusLayout.lineCount)
        assertFalse(statusLayout.isLineEllipsized(0), "A long model/account cannot take width from the status line")
        assertTrue(native(status).bottom <= native(label).top, "Status is the first line, device is the second")
        val icon = compose.onNodeWithTag("remote:bar-icon", useUnmergedTree = true)
        val chevron = compose.onNodeWithTag("remote:bar-chevron", useUnmergedTree = true)
        val divider = compose.onNodeWithTag("remote:bar-divider", useUnmergedTree = true)
        val iconBox = native(icon)
        assertEquals(c.metric("tvIconSizeDp"), iconBox.width / density, 1f / density)
        assertEquals(c.metric("tvIconSizeDp"), iconBox.height / density, 1f / density)
        assertTrue(iconBox.right <= native(status).left && native(status).right <= native(chevron).left)
        assertEquals(c.metric("dividerHeightDp"), native(divider).height / density, 1f / density)
        assertEquals(box.top, native(divider).top, 1f, "The divider is the actual top boundary of contained bottom chrome")
        assertEquals(box.width, native(divider).width, 1f)
        listOf(icon, chevron, divider).forEach { node ->
            val config = node.fetchSemanticsNode().config
            assertFalse(config.contains(SemanticsActions.OnClick), "Decorative chrome must not introduce extra actions")
            assertTrue(config.getOrNull(SemanticsProperties.Focused) != true)
        }
        val image = bar.captureToImage().asAndroidBitmap()
        assertPaint(iconBox, box, image, c)
        assertPaint(native(chevron), box, image, c)
        val y = (native(divider).top - box.top).roundToInt()
        val rulePixels = (image.width / 10 until image.width * 9 / 10).count { x ->
            distance(image.getPixel(x, y), outline) <= c.count("paintChannelTolerance")
        }
        assertTrue(rulePixels >= image.width * c.metric("dividerCoverageMin"), "The top outlineVariant rule must really paint")
        bar.performClick()
        assertEquals(c.count("opens"), opened)
        noCommands(c)
    }

    @Test fun attachedRemoteExitActionsAreReachableByRealPageScrollAtLargeFont() {
        val c = case("remote-exit-actions-scroll-reachable")
        setup(c)
        host.bind(Account(Server("server", "Cinema", "https://server.example/jf", "12"), User("me", "Ana"), "token"))
        host.attach(target(c))
        assertEquals("attached", host.state.value.controller?.phase, "The actual host must own an attached target before Disconnect")
        assertEquals(target(c), host.state.value.controller?.target)
        var choices = 0
        compose.setContent { Environment { TouchRemoteScreen(host.state.collectAsState().value, host, { choices++ }, {}) } }
        settled(c)
        val choose = compose.onNodeWithText(c.token("chooseText"))
        choose.performScrollTo()
        settled(c)
        assertWholeTouchTarget(choose, c)
        assertEquals(
            c.number("fontScale"),
            layout(compose.onNodeWithText(c.token("chooseText"), useUnmergedTree = true)).layoutInput.density.fontScale,
            .01f
        )
        choose.performClick()
        assertEquals(c.count("choices"), choices)
        val disconnect = compose.onNodeWithTag("remote:disconnect")
        disconnect.performScrollTo()
        settled(c)
        assertWholeTouchTarget(disconnect, c)
        disconnect.performClick()
        settled(c)
        assertEquals(c.token("phaseAfterDisconnect"), host.state.value.controller?.phase)
        assertEquals(null, host.state.value.controller?.target)
        noCommands(c)
    }

    private fun assertWholeTouchTarget(node: SemanticsNodeInteraction, c: Case) {
        node.assertIsDisplayed().assertIsEnabled()
        val target = node.getUnclippedBoundsInRoot()
        val nativeTarget = native(node)
        assertTrue((target.bottom - target.top).value >= c.metric("targetMinHeightDp"), "The full48dp control remains reachable")
        assertTrue(nativeTarget.top >= 0f && nativeTarget.bottom <= window.height, "Whole action must be inside actual phone viewport")
    }

    @Test fun actualGroupBodyHasAPassivePositionCueThroughPaddingTailAndEndButNotWhenFitting() {
        val c = case("group-body-structural-scroll-position")
        setup(c)
        assertEquals(0, c.input.getValue("queueEntries").jsonPrimitive.int)
        val commands = mutableListOf<String>()
        var state by mutableStateOf(
            SyncPlayPresentation(
                server = c.text("server"), viewer = c.text("viewer"), policyKnown = true, policy = "CreateAndJoinGroups",
                groupId = "g", groupName = c.text("groupName"), phase = c.text("phase"),
                localPlayback = SyncLocalPlayback.UNAVAILABLE,
                participants = c.input.getValue("participants").jsonArray.map { it.jsonPrimitive.content }
            )
        )
        compose.setContent { Environment { SyncPlayPanel(state, { op, _ -> commands += op }, {}) } }
        settled(c)
        val owner = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
        assertEquals(
            c.number("fontScale"),
            layout(compose.onNodeWithText(compose.activity.getString(R.string.sync_repeat), useUnmergedTree = true))
                .layoutInput.density.fontScale,
            .01f
        )
        fun range() = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range().maxValue() > c.number("paddingOnlyTailDp") * density)
        assertEquals(0f, range().value(), .5f, "Inspect first arrival before any rescue scroll")
        val first = thumbPaint(c)
        val remaining = c.number("paddingOnlyTailDp") * density
        val tailDelta = range().maxValue() - remaining - range().value()
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, tailDelta)) }
        settled(c)
        assertEquals(remaining, range().maxValue() - range().value(), 1f)
        assertTrue(range().value() > 0f && range().value() < range().maxValue())
        compose.onNodeWithTag("sync:fade-bottom").assertDoesNotExist()
        val tail = thumbPaint(c)
        assertTrue(tail.first > first.first, "Native painted position must advance when actual body scroll advances")
        val endDelta = range().maxValue() - range().value()
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, endDelta)) }
        settled(c)
        assertEquals(range().maxValue(), range().value(), 1f)
        val end = thumbPaint(c)
        assertTrue(end.first >= tail.first && end.second >= tail.second)
        val viewport = native(owner)
        assertTrue(abs(end.second - viewport.bottom) <= c.metric("endInsetMaxDp") * density + 1f, "End thumb reaches the real viewport end")
        compose.runOnIdle {
            state = SyncPlayPresentation(
                server = c.text("server"),
                viewer = c.text("viewer"),
                policyKnown = true,
                policy = c.text("fittingPolicy")
            )
        }
        settled(c)
        assertEquals(0f, range().maxValue(), .5f, "The actual empty join-only chooser must really fit before asserting cue absence")
        compose.onNodeWithTag("sync:touch-scroll-indicator", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(c.count("groupCommands"), commands.size)
    }

    private fun thumbPaint(c: Case): Pair<Float, Float> {
        val thumb = compose.onNodeWithTag("sync:touch-scroll-indicator", useUnmergedTree = true).assertIsDisplayed()
        val config = thumb.fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsActions.OnClick))
        assertFalse(config.contains(SemanticsActions.ScrollBy))
        assertTrue(config.getOrNull(SemanticsProperties.Focused) != true)
        val bounds = native(thumb)
        assertEquals(c.metric("indicatorWidthDp"), bounds.width / density, 1f / density)
        val panel = compose.onNodeWithTag("sync:panel")
        val panelBounds = native(panel)
        val image = panel.captureToImage().asAndroidBitmap()
        val left = bounds.left.roundToInt() - panelBounds.left.roundToInt()
        val right = bounds.right.roundToInt() - panelBounds.left.roundToInt()
        val top = bounds.top.roundToInt() - panelBounds.top.roundToInt()
        val bottom = bounds.bottom.roundToInt() - panelBounds.top.roundToInt()
        val rows = (top until bottom).filter { y ->
            (left until right).count { x -> distance(image.getPixel(x, y), surface) >= c.count("backgroundChannelDeltaMin") } >=
                c.count("minimumThumbPixelsPerRow")
        }
        assertTrue(
            rows.size >= c.count("minimumThumbPaintRows"),
            "Passive position cue must actually paint, including while fade is absent"
        )
        return (rows.first() + panelBounds.top.roundToInt()).toFloat() to (rows.last() + 1 + panelBounds.top.roundToInt()).toFloat()
    }

    private fun assertPaint(bounds: Rect, panel: Rect, image: Bitmap, c: Case) {
        var ink = 0
        val left = bounds.left.roundToInt() - panel.left.roundToInt()
        val right = bounds.right.roundToInt() - panel.left.roundToInt()
        val top = bounds.top.roundToInt() - panel.top.roundToInt()
        val bottom = bounds.bottom.roundToInt() - panel.top.roundToInt()
        val background = image.getPixel((left + right) / 2, top)
        for (y in top until bottom) {
            for (x in left until right) {
                if (distance(image.getPixel(x, y), background) >= c.count("backgroundChannelDeltaMin")) ink++
            }
        }
        assertTrue(ink >= c.count("minimumIconPaintPixels"), "Decorative TV/chevron must have real native paint")
    }

    private fun distance(a: Int, b: Int): Int = (0..3).maxOf { channel ->
        abs(((a ushr (channel * 8)) and 255) - ((b ushr (channel * 8)) and 255))
    }
}
