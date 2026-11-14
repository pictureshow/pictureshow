/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.google.common.util.concurrent.Futures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual native player content, real mutable quality inputs and the production notice/menu lifetimes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "sw360dp-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerQualityContextRegressionTest {
    @get:Rule(order = -2) val timeLimit = PerTestTimeLimit(180)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var host: PlaybackHostState
    private lateinit var factA: String
    private lateinit var factB: String
    private var requests = 0
    private var fontScale = 1f
    private val cue = "touch:quality-cue"
    private val cueTree get() = hasTestTag(cue) or hasAnyAncestor(hasTestTag(cue))

    @After fun close() {
        compose.runOnIdle {
            if (::host.isInitialized) host.reportScope.cancel()
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }
    private fun decision(method: PlayMethod) = PlayDecision(
        "film", MediaSourceInfo(id = "source"), method, "https://media.example.test/video", emptyMap(), "session",
        if (method == PlayMethod.Transcode) listOf("VideoCodecNotSupported") else emptyList()
    )
    private fun vector(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-phone-quality-cue.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("sync-phone-quality-cue", suite.getValue("suite").jsonPrimitive.content)
        return suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
    }
    private fun JsonObject.input() = getValue("input").jsonObject
    private fun JsonObject.expected() = getValue("expected").jsonObject
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun show(case: JsonObject, method: PlayMethod = PlayMethod.DirectPlay) {
        val input = case.input()
        fontScale = input.getValue("fontScale").jsonPrimitive.float
        RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            output.syncRequest = { _, _ -> requests++; false }
            decoder = Decoder()
            host = PlaybackHostState(MediaItem("film", "A film"), 20_000, output, {}, {})
            host.playerSource = mutableStateOf(decoder)
            host.stateSource = mutableStateOf(PlaybackState(preparing = false, decision = decision(method)))
            host.duration = 60_000
            host.estimatedBandwidth = input.number("bandwidthA")
            factA = assertNotNull(host.adaptiveQualityNotice, "Actual quality input A must reduce the configured ceiling")
            host.estimatedBandwidth = input.number("bandwidthB")
            factB = assertNotNull(host.adaptiveQualityNotice, "Actual quality input B must reduce the configured ceiling")
            assertNotEquals(factA, factB, "A/B must produce different production presentation facts")
            host.estimatedBandwidth = input.number("bandwidthA")
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, host) } }
        advance(input.number("mountSettleMs"))
        compose.onNodeWithTag("touch:panel").assertIsDisplayed()
        assertNativeProfile(case)
    }
    private fun assertNativeProfile(case: JsonObject) {
        val input = case.input()
        val font = input.getValue("fontScale").jsonPrimitive.float
        val player = compose.onNodeWithTag("touch:player")
        val bounds = player.getUnclippedBoundsInRoot()
        val density = player.fetchSemanticsNode().layoutInfo.density.density
        assertEquals(input.getValue("widthDp").jsonPrimitive.float, (bounds.right - bounds.left).value, 1f / density)
        assertEquals(input.getValue("heightDp").jsonPrimitive.float, (bounds.bottom - bounds.top).value, 1f / density)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("touch:title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(font, it.layoutInput.density.fontScale, .01f) }
    }
    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }
    private fun changeBandwidth(value: Long?, settleMs: Long) {
        compose.runOnIdle { host.estimatedBandwidth = value }
        settleNativeFrames("bandwidth", settleMs)
    }
    // Setup clock budgets stay unchanged; native Dialog/layout work runs between actual frames.
    private var nativeSettleIndex = 0
    private fun settleNativeFrames(reason: String, milliseconds: Long) {
        require(milliseconds > 0)
        val index = ++nativeSettleIndex
        val start = compose.mainClock.currentTime
        val clocks = org.json.JSONArray().put(start)
        compose.waitForIdle()
        recordNativeSettle(index, reason, "before", start, milliseconds, clocks)
        var frames = 0L
        while (compose.mainClock.currentTime - start < milliseconds) {
            val previous = compose.mainClock.currentTime
            compose.mainClock.advanceTimeByFrame()
            check(compose.mainClock.currentTime > previous) { "Native setup clock must advance" }
            clocks.put(compose.mainClock.currentTime)
            compose.waitForIdle()
            frames++
            check(frames <= milliseconds) { "Native setup exceeded its finite frame budget" }
        }
        recordNativeSettle(index, reason, "after", start, milliseconds, clocks)
    }
    private fun recordNativeSettle(index: Int, reason: String, stage: String, start: Long,
                                   budget: Long, clocks: org.json.JSONArray) {
        fun rect(box: androidx.compose.ui.geometry.Rect) =
            org.json.JSONArray(listOf(box.left, box.top, box.right, box.bottom))
        val geometry = org.json.JSONArray()
        val roots = linkedMapOf<Int, androidx.compose.ui.semantics.SemanticsNode>()
        for (tag in listOf("touch:player", "touch:panel", "touch:actions", "touch:conversion-notice",
                           "touch:open:Speed", "touch:quality-cue", "touch:quality-cue-text",
                           "touch:sheet:Speed", "touch:sheet:done")) {
            val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            for (node in nodes) {
                geometry.put(org.json.JSONObject().put("tag", tag).put("id", node.id)
                    .put("parentId", node.parent?.id ?: org.json.JSONObject.NULL)
                    .put("boundsInRootPxClipped", rect(node.boundsInRoot))
                    .put("touchBoundsInRootPx", rect(node.touchBoundsInRoot))
                    .put("positionInRootPx", org.json.JSONArray(listOf(node.positionInRoot.x, node.positionInRoot.y)))
                    .put("nativeSizePx", org.json.JSONArray(listOf(node.size.width, node.size.height)))
                    .put("density", node.layoutInfo.density.density)
                    .put("fontScale", node.layoutInfo.density.fontScale))
                generateSequence(node) { it.parent }.last().let { roots[it.id] = it }
            }
        }
        val rootGeometry = org.json.JSONArray()
        roots.values.forEach { root -> rootGeometry.put(org.json.JSONObject().put("id", root.id)
            .put("boundsInRootPxClipped", rect(root.boundsInRoot))
            .put("nativeSizePx", org.json.JSONArray(listOf(root.size.width, root.size.height)))) }
        val observation = org.json.JSONObject().put("index", index).put("reason", reason).put("stage", stage)
            .put("startClockMs", start).put("actualClockMs", compose.mainClock.currentTime)
            .put("requestedSetupBudgetMs", budget).put("frameClockMs", clocks)
            .put("actualFact", host.adaptiveQualityNotice ?: org.json.JSONObject.NULL)
            .put("factA", factA).put("factB", factB)
            .put("decisionMethod", host.state.decision?.method?.name ?: org.json.JSONObject.NULL)
            .put("foreground", output.foreground.value).put("preparing", host.state.preparing)
            .put("error", host.error ?: org.json.JSONObject.NULL)
            .put("actualTaggedGeometry", geometry).put("actualRootGeometry", rootGeometry)
        val directory = File("build/reports/quality-native-settle")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$index-$reason-$stage.json").writeText(observation.toString(2) + "\n")
        println("QUALITY_NATIVE_SETTLE " + observation.toString())
    }

    private fun assertNoCue() {
        val texts = compose.onAllNodes(cueTree, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
        assertTrue(texts.none { it.text.isNotBlank() }, "No pending/initial/expired quality fact may be emitted in the cue")
    }
    private fun assertCue(visible: Boolean, fact: String, polite: Boolean = true) {
        if (!visible) { assertNoCue(); return }
        assertVisibleCue(fact, polite)
    }
    private fun assertVisibleCue(fact: String, polite: Boolean = true) {
        val container = compose.onNodeWithTag(cue)
        container.assertIsDisplayed()
        assertEquals(polite, container.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite)
        val text = compose.onNodeWithTag("touch:quality-cue-text", useUnmergedTree = true)
        text.assertIsDisplayed().assertTextEquals(fact)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "The cue must contain an actual native Text")
        layouts.forEach {
            assertFalse(it.hasVisualOverflow, "The complete truthful quality fact must be visible")
            assertEquals(fontScale, it.layoutInput.density.fontScale, .01f)
        }
        // assertIsDisplayed alone accepts alpha(0f). The actual native Text must also paint its glyphs.
        // Playback content underneath is the production black source, not a test-created notice or announcement.
        val bitmap = text.captureToImage().asAndroidBitmap()
        var darkest = 765
        var lightest = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val light = ((pixel ushr 16) and 255) + ((pixel ushr 8) and 255) + (pixel and 255)
            darkest = minOf(darkest, light)
            lightest = maxOf(lightest, light)
        }
        assertTrue(lightest - darkest >= 96, "An invisible alpha-zero Text cannot replace visibly painted native message glyphs")
    }

    @Test fun actualQualityMenuRetainsTruthfulAutomaticContextAndReductionDetail() {
        val case = vector("quality-detail-reachable")
        val input = case.input(); val expected = case.expected()
        show(case)
        val context = host.automaticQualityContext
        val position = host.position
        val override = host.qualityOverrideMbps
        assertNull(override)
        compose.onNodeWithTag("touch:quality").performClick()
        advance(input.number("menuSettleMs"))
        compose.onNodeWithTag("player:quality-choice:auto").assertIsDisplayed()
        assertTrue(expected.flag("automaticDetailReachable"))
        compose.onNodeWithText("$context\n$factA", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        advance(input.number("menuSettleMs"))
        compose.onNodeWithTag("touch:panel").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(expected.flag("overrideChanged"), override != host.qualityOverrideMbps)
            assertEquals(expected.flag("positionChanged"), position != host.position)
            assertEquals(factA, host.adaptiveQualityNotice)
            assertEquals(expected.getValue("requestCount").jsonPrimitive.int, requests)
        }
    }

    @Test fun actualVisibleCueIsChangedOnlyExpiresAndCanAnnounceANewReductionEpisode() {
        val case = vector("quality-changed-fact-lifetime-and-no-replay")
        val input = case.input(); val expected = case.expected()
        show(case)
        assertCue(expected.flag("initialVisible"), factA)
        changeBandwidth(input.number("bandwidthB"), input.number("settleMs"))
        assertEquals(factB, host.adaptiveQualityNotice)
        assertCue(expected.flag("changedVisible"), factB, expected.flag("polite"))
        assertTrue(input.number("visibleCheckMs") < input.number("durationMs"))
        assertTrue(input.number("visibleCheckMs") + input.number("expiryAdvanceMs") > input.number("durationMs"))
        advance(input.number("visibleCheckMs"))
        assertCue(expected.flag("changedVisible"), factB, expected.flag("polite"))
        advance(input.number("expiryAdvanceMs"))
        assertCue(expected.flag("expiredVisible"), factB)
        changeBandwidth(input.number("bandwidthB"), input.number("settleMs"))
        assertCue(expected.flag("equalReplay"), factB)
        compose.onNodeWithTag("touch:gestures").performClick()
        advance(input.number("controlsSettleMs"))
        compose.onNodeWithTag("touch:panel").assertDoesNotExist()
        compose.onNodeWithTag("touch:gestures").performClick()
        advance(input.number("controlsSettleMs"))
        compose.onNodeWithTag("touch:panel").assertIsDisplayed()
        assertCue(expected.flag("controlsRevealReplay"), factB)
        val reset = vector("quality-null-resets-reduction-episode")
        val resetInput = reset.input(); val resetExpected = reset.expected()
        assertNativeProfile(reset)
        assertEquals(JsonNull, resetInput.getValue("clearBandwidth"))
        changeBandwidth(null, resetInput.number("settleMs"))
        assertEquals(resetExpected.flag("nullFact"), host.adaptiveQualityNotice == null)
        assertCue(resetExpected.flag("nullVisible"), factB)
        changeBandwidth(resetInput.number("restoreBandwidth"), resetInput.number("settleMs"))
        assertCue(resetExpected.flag("newEpisodeVisible"), factB)
        compose.runOnIdle { assertEquals(expected.getValue("requestCount").jsonPrimitive.int, requests) }
    }

    @Test fun actualConversionAndMountedSheetDeferTheVisibleCueWithoutConsumingItsFact() {
        val conversion = vector("quality-conversion-priority-defers")
        val input = conversion.input(); val expected = conversion.expected()
        show(conversion, PlayMethod.Transcode)
        assertEquals(CONVERSION_NOTICE_MS, input.number("blockingDurationMs"))
        assertTrue(expected.flag("conversionVisibleBeforeExpiry"))
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        changeBandwidth(input.number("bandwidthB"), input.number("settleMs"))
        advance(input.number("blockedCheckMs"))
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        assertCue(expected.flag("blockedCueVisible"), factB)
        advance(input.number("blockingDurationMs") + input.number("expiryMarginMs"))
        assertFalse(expected.flag("conversionVisibleAfterExpiry"))
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
        assertCue(expected.flag("resumedCueVisible"), factB)
        advance(input.number("cueExpiryAdvanceMs"))
        assertNoCue()
        val sheet = vector("quality-sheet-priority-defers")
        val sheetInput = sheet.input(); val sheetExpected = sheet.expected()
        assertNativeProfile(sheet)
        val sheetName = sheetInput.getValue("sheet").jsonPrimitive.content
        compose.onNodeWithTag("touch:open:$sheetName").performScrollTo().performClick()
        settleNativeFrames("sheet-open", sheetInput.number("sheetSettleMs"))
        assertTrue(sheetExpected.flag("sheetVisible"))
        compose.onNodeWithTag("touch:sheet:$sheetName").assertIsDisplayed()
        changeBandwidth(sheetInput.number("pendingBandwidth"), sheetInput.number("settleMs"))
        advance(sheetInput.number("holdMs"))
        assertCue(sheetExpected.flag("blockedCueVisible"), factA)
        compose.onNodeWithTag("touch:sheet:$sheetName").assertIsDisplayed()
        compose.onNodeWithTag("touch:sheet:done").performClick()
        settleNativeFrames("sheet-close", sheetInput.number("sheetSettleMs"))
        assertCue(sheetExpected.flag("resumedCueVisible"), factA)
        compose.runOnIdle {
            assertEquals(factA, host.adaptiveQualityNotice)
            assertEquals(sheetExpected.getValue("requestCount").jsonPrimitive.int, requests)
        }
    }

    private class Decoder : SimpleBasePlayer(Looper.getMainLooper()) {
        private val value = State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE
        ).build()).setPlaylist(listOf(MediaItemData.Builder("film").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(20_000).build()
        override fun getState(): State = value
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
