/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import com.google.common.util.concurrent.Futures
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerChromeRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var host: PlaybackHostState
    private fun vector(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-phone-player-chrome.json") }.first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
    }

    @After fun close() {
        compose.runOnIdle {
            if (::host.isInitialized) host.reportScope.cancel()
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }
    private fun show(case: JsonObject, reducedQuality: Boolean = false, live: Boolean = false) {
        val input = case.getValue("input").jsonObject
        val font = input.getValue("fontScale").jsonPrimitive.float
        RuntimeEnvironment.setFontScale(font)
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder()
            host = PlaybackHostState(MediaItem("film", "A film", if (live) "TvChannel" else "Movie"), 20_000, output, {}, {})
            host.playerSource = mutableStateOf(decoder)
            host.stateSource = mutableStateOf(PlaybackState(preparing = false))
            host.duration = 60_000
            if (live) {
                host.liveWindow = app.pictureshow.core.playback.ObservedLiveWindow(100_000, 30_000, 27_000, true, 1_762_766_400_000)
            }
            if (reducedQuality) host.estimatedBandwidth = input.getValue("estimatedBandwidth").jsonPrimitive.content.toLong()
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, host) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        val frame = compose.onNodeWithTag("touch:player").getUnclippedBoundsInRoot()
        assertEquals(input.getValue("widthDp").jsonPrimitive.float, (frame.right - frame.left).value, 1f)
        assertEquals(input.getValue("heightDp").jsonPrimitive.float, (frame.bottom - frame.top).value, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("touch:title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(font, it.layoutInput.density.fontScale, .01f, "Actual native title must use requested font") }
    }

    @Test fun actualProductionScrimHasTransparentUpperEdge() = paint("scrim-transparent-upper-edge", true)

    @Test fun actualProductionScrimDarkensMonotonicallyTowardActions() = paint("scrim-opacity-ramp", false)
    private fun paint(id: String, topOnly: Boolean) {
        val case = vector(id)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val underlay = Color(input.getValue("underlayArgb").jsonPrimitive.content.toLong(16).toInt())
        compose.setContent {
            Box(
                Modifier.size(input.getValue("widthDp").jsonPrimitive.float.dp, input.getValue("heightDp").jsonPrimitive.float.dp)
                    .background(underlay).testTag("chrome:paint")
            ) {
                // Exact production-used modifier, painted over a known source rather than black video.
                Box(Modifier.matchParentSize().touchPlayerScrim())
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("chrome:paint").captureToImage().asAndroidBitmap()
        val fractions = input.getValue("sampleFractions").jsonArray.map { it.jsonPrimitive.float }
        val samples = fractions.map { bitmap.getPixel(bitmap.width / 2, (it * (bitmap.height - 1)).roundToInt()) }
        if (topOnly) {
            assertTrue(
                distance(underlay.toArgb(), samples.first()) <= expected.getValue("maxUpperEdgeChannelDelta").jsonPrimitive.int,
                "The actual scrim's upper edge must reveal its known bright source; a solid panel fails"
            )
        } else {
            fun brightness(argb: Int) = ((argb ushr 16) and 255) + ((argb ushr 8) and 255) + (argb and 255)
            val levels = samples.map(::brightness)
            val tolerance = expected.getValue("monotonicChannelSumTolerance").jsonPrimitive.int
            levels.zipWithNext().forEach { (above, below) ->
                assertTrue(below <= above + tolerance, "Opacity must not brighten toward actions: $levels")
            }
            assertTrue(
                levels.first() - levels.last() >= expected.getValue("minimumChannelSumDrop").jsonPrimitive.int,
                "A constant solid paint is not a gradient: $levels"
            )
            assertTrue(
                levels.last() <= expected.getValue("maxActionRegionChannelSum").jsonPrimitive.int,
                "The action region must remain dark/readable over a bright source"
            )
        }
    }

    @Test fun seekTimeSharesSeekRowAtFont10() = seekRow("seek-time-row-font-1-0")

    @Test fun seekTimeSharesSeekRowAtFont13() = seekRow("seek-time-row-font-1-3")
    private fun seekRow(id: String) {
        val case = vector(id)
        show(case)
        val slider = compose.onNodeWithTag("touch:scrubber").getUnclippedBoundsInRoot()
        // Neutral tag belongs to the actual clock Text, not a decorative layout container.
        val clock = compose.onNodeWithTag("touch:seek-time", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val density = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().layoutInfo.density.density
        val tolerance = (case.getValue("expected").jsonObject.getValue("verticalTolerancePx").jsonPrimitive.float / density).dp
        assertTrue(
            clock.top >= slider.top - tolerance && clock.bottom <= slider.bottom + tolerance,
            "Actual seek clock text must share the seek target's vertical row, not consume a separate lower row: slider=$slider clock=$clock"
        )
    }

    @Test fun adaptiveQualityDetailDoesNotConsumePersistentRowAtFont10() = quality("quality-detail-context-font-1-0")

    @Test fun adaptiveQualityDetailDoesNotConsumePersistentRowAtFont13() = quality("quality-detail-context-font-1-3")
    private fun quality(id: String) {
        val case = vector(id)
        show(case, reducedQuality = true)
        compose.runOnIdle { assertNotNull(host.adaptiveQualityNotice, "Fixture must supply a real reduction fact") }
        assertEquals(
            case.getValue("expected").jsonObject.getValue("persistentQualityRows").jsonPrimitive.int,
            compose.onAllNodesWithTag("touch:quality-notice", useUnmergedTree = true).fetchSemanticsNodes().size,
            "Adaptive detail belongs in its existing Info/Quality context, not a permanent extra player row"
        )
    }

    @Test fun compactTargetsRemainNativeSizedAndReachableAtFont10() = targets("targets-font-1-0")

    @Test fun compactTargetsRemainNativeSizedAndReachableAtFont13() = targets("targets-font-1-3")
    private fun targets(id: String) {
        val case = vector(id)
        show(case)
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        val frame = compose.onNodeWithTag("touch:player").fetchSemanticsNode().boundsInRoot
        val slider = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode()
        assertTrue(
            slider.touchBoundsInRoot.height >= expected.getValue("minimumTouchTargetDp").jsonPrimitive.float *
                slider.layoutInfo.density.density - expected.getValue("physicalPixelTolerance").jsonPrimitive.float,
            "Seek row compaction must retain the native48dp slider input height"
        )
        val targetTags = input.getValue("targetTags").jsonArray.map { it.jsonPrimitive.content }
        val stationaryTransport = setOf("touch:toggle", "touch:back10", "touch:forward10", "touch:syncplay", "touch:quality")
        val overflowTargets = setOf("touch:stop", "touch:open:Info")
        // Incoming native transport is a sibling of the bottom scroll panel. Overflow is reached by More.
        // Check ordinary targets first, then open the real popup once without invoking destructive Stop.
        for (tag in targetTags.filterNot { it in overflowTargets } + targetTags.filter { it in overflowTargets }) {
            if (tag == targetTags.firstOrNull { it in overflowTargets }) {
                compose.onNodeWithTag("touch:more").performScrollTo().assertIsDisplayed().performClick()
                compose.waitForIdle()
            }
            val target = compose.onNodeWithTag(tag)
            if (tag !in stationaryTransport && tag !in overflowTargets) target.performScrollTo()
            target.assertIsDisplayed().assertHasClickAction()
            val node = target.fetchSemanticsNode()
            val density = node.layoutInfo.density.density
            val touch = node.touchBoundsInRoot
            val minimum = expected.getValue("minimumTouchTargetDp").jsonPrimitive.float
            val tolerance = expected.getValue("physicalPixelTolerance").jsonPrimitive.float
            assertTrue(
                touch.width >= minimum * density - tolerance && touch.height >= minimum * density - tolerance,
                "Compacting chrome must retain native48dp input targets: $tag $touch density=$density"
            )
            assertTrue(
                touch.left >= frame.left - tolerance &&
                    touch.right <= frame.right + tolerance &&
                    touch.top >= frame.top - tolerance &&
                    touch.bottom <= frame.bottom + tolerance,
                "Actual target must be reachable within the window after ordinary fallback scroll: $tag $touch frame=$frame"
            )
        }
    }

    @Test fun landscapeVodKeepsHeaderOutOfCompactBottomAndShowsRemainingAtFont10() = compact("compact-vod-font-1-0", false)

    @Test fun landscapeVodKeepsHeaderOutOfCompactBottomAndShowsRemainingAtFont13() = compact("compact-vod-font-1-3", false)

    @Test fun landscapeLiveKeepsOneBehindReadoutAndCompactBottomAtFont10() = compact("compact-live-font-1-0", true)

    @Test fun landscapeLiveKeepsOneBehindReadoutAndCompactBottomAtFont13() = compact("compact-live-font-1-3", true)

    private fun compact(id: String, live: Boolean) {
        val case = vector(id)
        show(case, live = live)
        val expected = case.getValue("expected").jsonObject
        val panel = compose.onNodeWithTag("touch:panel").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithTag("touch:title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val secondary = compose.onNodeWithTag("touch:secondary").getUnclippedBoundsInRoot()
        assertTrue(title.bottom < panel.top, "Landscape title stays in the upper header, leaving the picture and compact bottom readable")
        assertTrue(
            (panel.bottom - panel.top).value <= expected.getValue("maximumBottomHeightDp").jsonPrimitive.float,
            "The actual bottom HUD must fit the roughly30% screen budget: $panel"
        )
        assertTrue(secondary.top >= panel.top && secondary.bottom <= panel.bottom)
        val headerBottom = listOf("touch:title", "touch:quality", "touch:syncplay")
            .maxOf { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().bottom.value }
        val transport = compose.onNodeWithTag("touch:transport").getUnclippedBoundsInRoot()
        assertTrue(transport.top.value >= headerBottom, "Center transport must not cover the upper metadata/header actions")
        if (!live) {
            compose.runOnIdle {
                host.segments = listOf(app.pictureshow.core.MediaSegment("intro", app.pictureshow.core.SegmentType.Intro, 0, 600_000_000))
            }
            compose.waitForIdle()
            fun stableAction(tag: String) {
                val shown = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue(shown.top.value >= headerBottom, "Action clears complete title/Quality/Watch together header: $tag $shown")
                val gestures = compose.onNodeWithTag("touch:gestures")
                assertEquals("Hide controls", gestures.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
                // Invoke the layer's semantic action: a center pointer can hit the next-episode card above it.
                gestures.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                compose.waitForIdle()
                assertEquals("Show controls", gestures.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
                val hidden = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertEquals(shown.top.value, hidden.top.value, .5f, "Hiding controls must retain the action's vertical anchor")
                gestures.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                compose.waitForIdle()
                assertEquals("Hide controls", gestures.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
                val restored = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertEquals(shown.top.value, restored.top.value, .5f, "Revealing controls must retain the action's vertical anchor")
            }
            stableAction("touch:skip")
            compose.runOnIdle { host.deferredNext = MediaItem("next", "Another episode") }
            compose.waitForIdle()
            stableAction("next:cancel")
            compose.runOnIdle {
                host.deferredNext = null
                host.segments = emptyList()
            }
            compose.waitForIdle()
        }
        if (live) {
            compose.onNodeWithTag("touch:behind-live", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("touch:live-status").assertExists()
            assertEquals(
                listOf("Live playback status"),
                compose.onNodeWithTag(
                    "touch:live-status"
                ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
            )
        } else {
            compose.onNodeWithTag("touch:seek-time", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("touch:total-time", useUnmergedTree = true).assertExists()
            assertEquals(
                "0:20 of 1:00",
                compose.onNodeWithTag(
                    "touch:scrubber"
                ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            )
        }
    }

    private fun distance(a: Int, b: Int) =
        (0..2).maxOf { channel -> abs(((a ushr (channel * 8)) and 255) - ((b ushr (channel * 8)) and 255)) }
    private class Decoder : SimpleBasePlayer(Looper.getMainLooper()) {
        private var value = State.Builder().setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE
            ).build()
        ).setPlaylist(listOf(MediaItemData.Builder("film").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(20_000).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
