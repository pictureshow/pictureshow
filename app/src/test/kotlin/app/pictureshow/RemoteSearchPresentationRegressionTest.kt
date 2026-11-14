/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** Real phone shell/search layout and deliberate controller dispatch; no physical-keyboard delivery claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteSearchPresentationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = mutableListOf<RemoteRequest>()
    private var target = RemoteTarget("s1", "tv", "TV")
    private var sendReply: CompletableDeferred<Int>? = null
    private var listReply: CompletableDeferred<Unit>? = null
    private val host =
        RemoteControllerHost(
            scope,
            object : RemoteControllerGateway {
                override suspend fun targets(account: Account): ClientResult<List<RemoteTarget>> {
                    listReply?.await()
                    return ClientResult.Success(listOf(target))
                }

                override suspend fun send(account: Account, request: RemoteRequest): Int {
                    requests += request
                    return sendReply?.await() ?: 204
                }
            }
        ) { 0L }
    private var density = 0f
    private var window = IntSize.Zero

    private data class Case(val input: JsonObject, val expected: JsonObject) {
        fun text(key: String) = input.getValue(key).jsonPrimitive.content

        fun number(key: String) = input.getValue(key).jsonPrimitive.float

        fun token(key: String) = expected.getValue(key).jsonPrimitive.content

        fun count(key: String) = expected.getValue(key).jsonPrimitive.int

        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
    }

    private fun case(id: String): Case {
        val file =
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "spec/vectors/sync-phone-remote-search.json") }
                .first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-phone-remote-search", suite.getValue("suite").jsonPrimitive.content)
        val row =
            suite
                .getValue("cases")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == id }
        return Case(row.getValue("input").jsonObject, row.getValue("expected").jsonObject)
    }

    @Before fun phone() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java)).setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
    }

    @After fun close() {
        scope.cancel()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun mount(c: Case) {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        target =
            RemoteTarget(
                session = c.text("session"),
                device = "owned-tv",
                name = c.text("deviceName"),
                viewer = "other",
                viewerName = c.text("viewerName"),
                commands =
                c.input
                    .getValue("commands")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )
        host.bind(Account(Server("server", "Cinema", "https://server.example/jf", "12"), User("me", "Viewer"), "token"))
        host.setForeground(true)
        host.attach(target)
        assertEquals(
            "attached",
            host.state.value.controller
                ?.phase
        )
        assertEquals(
            true,
            host.state.value.controller
                ?.command
                ?.fresh,
            "Use a real accepted fresh target list"
        )
        host.setForeground(false)
        compose.setContent {
            TouchTheme {
                density = LocalDensity.current.density
                window = LocalWindowInfo.current.containerSize
                TouchShell(showNavigation = true, selected = null, onTab = {}) {
                    TouchRemoteScreen(host.state.collectAsState().value, host, {}, {})
                }
            }
        }
        settle()
        assertEquals(c.number("densityDpi").toInt(), compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(c.number("densityDpi") / 160f, density, .001f)
        assertEquals(IntSize((c.number("widthDp") * density).toInt(), (c.number("heightDp") * density).toInt()), window)
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (c.number("statusBarDp") * density).roundToInt(), 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, (c.number("navigationBarDp") * density).roundToInt())
                ).setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
        compose.runOnIdle { ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets) }
        settle()
        val actualViewport = viewport()
        val expectedViewport =
            c.input
                .getValue("capturedPageViewportPx")
                .jsonArray
                .map { it.jsonPrimitive.float }
        listOf(actualViewport.left, actualViewport.top, actualViewport.right, actualViewport.bottom)
            .zip(expectedViewport)
            .forEach { (actual, expected) ->
                assertEquals(expected, actual, 1f, "Real shell/insets must match native profile")
            }
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun field() = compose.onNodeWithTag("remote:text")

    private fun send() = compose.onNodeWithTag("remote:send")

    private fun owner() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)

    private fun viewport() = owner().fetchSemanticsNode().boundsInRoot

    private fun rect(node: SemanticsNodeInteraction): Rect {
        val box = node.getUnclippedBoundsInRoot()
        return Rect(box.left.value * density, box.top.value * density, box.right.value * density, box.bottom.value * density)
    }

    private fun controls() = listOf(
        compose.onNodeWithTag("remote:dpad-toggle"),
        field(),
        send(),
        compose.onNodeWithTag("remote:disconnect"),
        compose.onNodeWithText("Choose another TV")
    ).map(::rect)

    private fun scrollEnd() {
        compose.onNodeWithTag("remote:disconnect").performScrollTo()
        settle()
        field().performClick()
        settle()
    }

    private fun assertRequest(c: Case) {
        assertEquals(c.count("requests"), requests.size)
        val request = requests.single()
        assertEquals(c.token("requestMethod"), request.method)
        assertEquals(c.token("requestPath"), request.path)
        val body = requireNotNull(request.body)
        assertEquals(c.token("requestName"), body.getValue("Name").jsonPrimitive.content)
        assertEquals(
            c.text("validText"),
            body
                .getValue("Arguments")
                .jsonObject
                .getValue("String")
                .jsonPrimitive.content
        )
    }

    @Test fun idleRemoteSearchSubmitHasNoReservedVoidAndIsWholeBeforeScrolling() {
        val c = case("remote-search-submit-adjacency-and-first-viewport")
        mount(c)
        val range = owner().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range.value(), .5f, "Discoverability must not depend on a rescue scroll")
        val submit = rect(send())
        val helper = rect(compose.onNodeWithText(c.text("helperText"), useUnmergedTree = true))
        val gap = max(0f, max(submit.top - helper.bottom, helper.top - submit.bottom))
        assertTrue(
            gap <= c.metric("helperSubmitGapMaxDp") * density + 1f,
            "The search submit belongs next to its explanation, with no invisible-message void: gapDp=${gap / density}"
        )
        val view = viewport()
        val hit = send().fetchSemanticsNode().touchBoundsInRoot
        assertTrue(hit.height >= c.metric("submitMinHeightDp") * density, "The full submit hit target remains48dp")
        assertTrue(hit.width >= c.metric("submitMinWidthDp") * density)
        assertTrue(
            hit.top >= view.top && hit.bottom <= view.bottom,
            "Whole submit must be discoverable before scrolling: $submit in $view"
        )
        assertFalse(hit.overlaps(helper), "The hit target must not cover the helper")
        assertEquals(c.count("requests"), requests.size)
    }

    @Test fun searchValidationAndActualPendingSendPreserveEveryRetainedControl() {
        val c = case("remote-search-reservation-stable-across-send-lifecycle")
        mount(c)
        scrollEnd()
        field().performTextReplacement(c.text("validText"))
        settle()
        val baseline = controls()
        val scroll = owner().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

        fun stable(reason: String) {
            assertEquals(baseline, controls(), reason)
            assertEquals(scroll, owner().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .5f)
        }
        val reply = CompletableDeferred<Int>()
        sendReply = reply
        send().performClick()
        settle()
        assertFalse(reply.isCompleted, "Observe a real request awaiting its gateway reply")
        assertRequest(c)
        stable("A pending search request must not move retained controls")
        reply.complete(
            c.input
                .getValue("gatewayStatus")
                .jsonPrimitive.int
        )
        settle()
        stable("Completing the actual send must not move controls")
        field().performTextReplacement(
            "x".repeat(
                c.input
                    .getValue("invalidLength")
                    .jsonPrimitive.int
            )
        )
        send().performClick()
        settle()
        assertValidation(c)
        stable("Validation replaces explanation without moving controls")
        assertRequest(c)
        field().performTextReplacement(c.text("validText"))
        settle()
        compose.onNodeWithText(c.text("helperText"), useUnmergedTree = true).assertIsDisplayed()
        stable("Editing restores normal explanation in the same reservation")
        val refresh = CompletableDeferred<Unit>()
        listReply = refresh
        compose.runOnIdle { host.setForeground(true) }
        settle()
        assertEquals(
            false,
            host.state.value.controller
                ?.command
                ?.fresh
        )
        field().assertIsNotEnabled()
        send().assertIsNotEnabled()
        stable("Waiting for fresh target readiness must not move controls")
        refresh.complete(Unit)
        settle()
        compose.runOnIdle { host.setForeground(false) }
        field().assertIsEnabled()
        stable("A real fresh target restores readiness without movement")
        compose.runOnIdle { host.detach() }
        settle()
        field().assertDoesNotExist()
        send().assertDoesNotExist()
        compose.onNodeWithText("Choose a TV").assertIsDisplayed()
        assertRequest(c)
    }

    private fun assertValidation(c: Case) {
        val error = compose.onNodeWithText(c.token("validationText"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("polite", c.token("validationLiveRegion"))
        assertEquals(LiveRegionMode.Polite, error.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
    }

    @Test fun imeSendIsExplicitAndSharesBlankInvalidAndStaleTargetGuards() {
        val c = case("remote-search-ime-send-uses-guarded-submit")
        mount(c)
        scrollEnd()
        assertEquals("Send", c.token("keyboardAction"))
        assertEquals(
            ImeAction.Send,
            field().fetchSemanticsNode().config[SemanticsProperties.ImeAction],
            "Keyboard action must deliberately say Send"
        )
        field().performTextReplacement(c.text("validText"))
        assertEquals(0, requests.size, "Typing must never send automatically")
        field().performImeAction()
        settle()
        assertRequest(c)
        send().assertIsNotEnabled()
        assertEquals("", field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field().performImeAction()
        settle()
        assertRequest(c)
        field().performTextReplacement(
            "x".repeat(
                c.input
                    .getValue("invalidLength")
                    .jsonPrimitive.int
            )
        )
        field().performImeAction()
        settle()
        assertValidation(c)
        assertRequest(c)
        field().performTextReplacement(c.text("validText"))
        val refresh = CompletableDeferred<Unit>()
        listReply = refresh
        compose.runOnIdle { host.setForeground(true) }
        settle()
        field().assertIsNotEnabled()
        send().assertIsNotEnabled()
        if (field().fetchSemanticsNode().config.contains(SemanticsActions.OnImeAction)) {
            field().performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        }
        settle()
        assertRequest(c)
        refresh.complete(Unit)
        settle()
        compose.runOnIdle { host.setForeground(false) }
        field().assertIsEnabled()
        var staleSubmit: (() -> Unit)? = null
        field().performSemanticsAction(SemanticsActions.OnImeAction) { action ->
            staleSubmit = {
                action()
                Unit
            }
        }
        compose.runOnIdle {
            host.detach()
            requireNotNull(staleSubmit)()
        }
        settle()
        field().assertDoesNotExist()
        assertRequest(c)
    }
}
