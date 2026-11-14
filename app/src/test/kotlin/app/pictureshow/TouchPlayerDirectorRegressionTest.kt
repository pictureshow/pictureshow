/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
import com.google.common.util.concurrent.Futures
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual player layout/paint; recording gateway is a fixture, not server or device proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerDirectorRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val epoch = 1_762_776_000_000L
    private val sent = mutableListOf<String>()
    private var pauseFeedback = 0
    private var sync: SyncPlayHost? = null
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var playback: PlaybackHostState

    private fun expected(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/touch-player-director-clearance-paint-edges.json") }.first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }.getValue("expected").jsonObject
    }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.float
    private fun dpBounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @After fun close() {
        compose.runOnIdle {
            if (::playback.isInitialized) playback.reportScope.cancel()
            scope.cancel()
            scheduler.runCurrent()
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }
    private fun show(grouped: Boolean = false, whiteVideo: Boolean = false) {
        val phase = "Playing"
        RuntimeEnvironment.setFontScale(1f)
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder()
            if (grouped) {
                val gateway = object : SyncPlayGateway {
                    override suspend fun groups(account: Account) = ClientResult.Success(
                        listOf(
                            SyncGroup(
                                "g",
                                "Sync91Design4k",
                                phase,
                                emptyList(),
                                epoch * 10_000
                            )
                        )
                    )
                    override suspend fun access(account: Account) = ClientResult.Success("CreateAndJoinGroups")
                    override suspend fun time(account: Account) = ClientResult.Success(SyncUtcTime(epoch, epoch))
                    override suspend fun send(account: Account, request: SyncRequest): Int {
                        sent += request.operation
                        return 204
                    }
                }
                val group = SyncPlayHost(scope, gateway, { epoch + scheduler.currentTime }, { scheduler.currentTime })
                sync = group
                group.bind(account)
                group.socket(true)
                group.refresh()
                scheduler.runCurrent()
                group.join("g")
                scheduler.runCurrent()
                fun update(type: String, data: JsonObject) = buildJsonObject {
                    put("MessageType", "SyncPlayGroupUpdate")
                    put(
                        "Data",
                        buildJsonObject {
                            put("GroupId", "g")
                            put("Type", type)
                            put("Data", data)
                        }
                    )
                }.toString()
                group.receive(
                    update(
                        "GroupJoined",
                        buildJsonObject {
                            put("GroupId", "g")
                            put("GroupName", "Sync91Design4k")
                            put("State", phase)
                            put("Participants", JsonArray(emptyList()))
                            put("LastUpdatedAt", syncUtcTimestamp(epoch))
                        }
                    )
                )
                group.receive(
                    update(
                        "PlayQueue",
                        buildJsonObject {
                            put("Reason", "NewPlaylist")
                            put("LastUpdate", syncUtcTimestamp(epoch))
                            put(
                                "Playlist",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("ItemId", "film")
                                            put("PlaylistItemId", "entry-a")
                                        }
                                    )
                                )
                            )
                            put("PlayingItemIndex", 0)
                            put("StartPositionTicks", 200_000_000L)
                            put("IsPlaying", phase == "Playing")
                            put("RepeatMode", "RepeatNone")
                            put("ShuffleMode", "Sorted")
                        }
                    )
                )
                scheduler.runCurrent()
                assertTrue(group.participating, "Fixture must establish actual production membership")
                assertEquals(phase, group.state.value.phase, "Fixture must supply authoritative group phase")
                assertEquals(1, group.state.value.queue.size)
                output.syncRequest = group::transport
                output.syncParticipating = { group.participating }
                output.onSyncPauseRequested = { pauseFeedback++ }
            }
            val exposed = if (grouped) SyncTransportPlayer(decoder, output::interceptSync) else decoder
            playback = PlaybackHostState(MediaItem("film", "A film"), 20_000, output, {}, {})
            playback.sync = sync
            playback.playerSource = mutableStateOf(exposed)
            playback.stateSource = mutableStateOf(PlaybackState(preparing = false))
            playback.duration = 60_000
            sent.clear()
        }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TouchPlayerContent(
                    account,
                    playback,
                    // Neutral picture seam: every production chrome layer remains under test.
                    videoContent = if (whiteVideo) ({ Box(Modifier.fillMaxSize().background(Color.White)) }) else null
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val actual = dpBounds("touch:player")
        assertEquals(640f, (actual.right - actual.left).value, 1f, "Actual window width, not injected LocalConfiguration")
        assertEquals(360f, (actual.bottom - actual.top).value, 1f, "Actual window height")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("touch:title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(1f, it.layoutInput.density.fontScale, .01f) }
    }

    @Test fun joinedLandscapeTransportHasMeaningfulChipClearanceAndFreeBandPlacement() {
        val e = expected("joined-landscape-free-band-clearance")
        show(grouped = true)
        compose.onNodeWithTag("sync:player-chip").assertIsDisplayed()
        val chip = dpBounds("sync:player-chip")
        val transport = dpBounds("touch:transport")
        val panel = dpBounds("touch:panel")
        val frame = dpBounds("touch:player")
        assertEquals(e.number("transportWidthDp"), (transport.right - transport.left).value, 1f)
        assertEquals(e.number("primaryTargetDp"), (transport.bottom - transport.top).value, 1f)
        val chipGap = (transport.top - chip.bottom).value
        val panelGap = (panel.top - transport.bottom).value
        // The captured profile has a full-width usable band below the chip. Establish that
        // premise from measured production nodes before requiring vertical, balanced placement.
        val available = (panel.top - chip.bottom).value
        assertTrue(
            available >= (transport.bottom - transport.top).value + 32f,
            "Fixture needs an adequate free band: chip=$chip panel=$panel transport=$transport"
        )
        assertTrue(
            chipGap >= e.number("minimumChipClearanceDp") - .5f,
            "Actual full transport must separate from actual chip: gap=$chipGap chip=$chip transport=$transport"
        )
        assertTrue(
            panelGap >= e.number("minimumPanelClearanceDp") - .5f,
            "Transport must also clear the actual panel: gap=$panelGap panel=$panel transport=$transport"
        )
        assertTrue(
            abs(chipGap - panelGap) <= e.number("maximumFreeBandGapImbalanceDp") + 1f,
            "Use the adequate free band rather than clamping against its edge: chipGap=$chipGap panelGap=$panelGap"
        )
        assertTrue(transport.left >= frame.left && transport.right <= frame.right)
        assertTrue(transport.top >= frame.top && transport.bottom <= panel.top)
    }

    @Test fun brightVideoHasWholeSeekTargetBackingOnlyWhileControlsAreShown() {
        val e = expected("bright-video-whole-seek-target-backing-and-hidden-release")
        show(whiteVideo = true)
        val player = compose.onNodeWithTag("touch:player")
        val frame = player.fetchSemanticsNode().boundsInRoot
        val shown = player.captureToImage().asAndroidBitmap()
        val density = shown.width / 640f
        val probes = mutableListOf<Pair<Int, Int>>()
        for (tag in listOf("touch:back10", "touch:forward10")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals(e.number("seekTargetSizeDp"), bounds.width / density, 1f)
            assertEquals(e.number("seekTargetSizeDp"), bounds.height / density, 1f)
            // Probe the full target perimeter, away from the centered 24dp white glyph.
            // This rejects backing that protects only the glyph or only one part of the target.
            val inset = e.number("borderInsetDp") * density
            for (t in listOf(.1f, .3f, .5f, .7f, .9f)) {
                val x = bounds.left + inset + (bounds.width - 2 * inset) * t - frame.left
                val y = bounds.top + inset + (bounds.height - 2 * inset) * t - frame.top
                probes += x.roundToInt() to (bounds.top + inset - frame.top).roundToInt()
                probes += x.roundToInt() to (bounds.bottom - inset - frame.top).roundToInt()
                probes += (bounds.left + inset - frame.left).roundToInt() to y.roundToInt()
                probes += (bounds.right - inset - frame.left).roundToInt() to y.roundToInt()
            }
        }
        for ((x, y) in probes) {
            val channels = rgb(shown.getPixel(x, y))
            assertTrue(
                channels.all { it >= e.number("minimumVideoChannelWhenShown") && it <= e.number("maximumVideoChannelWhenShown") },
                "Whole 48dp seek backing must darken adversarial white picture while retaining picture detail: ($x,$y)=$channels"
            )
        }
        val panel = dpBounds("touch:panel")
        val panelPixel = shown.getPixel((32f * density).roundToInt(), ((panel.bottom.value - 3f) * density).roundToInt())
        assertTrue(
            rgb(panelPixel).all {
                it < e.number("minimumVideoChannelWhenShown")
            },
            "Existing bottom gradient must still deepen the global backing"
        )
        // Real background tap, not an injected controls flag. Chip may remain, picture backing must leave.
        compose.onNodeWithTag("touch:gestures").performTouchInput { click(Offset(100f * density, 160f * density)) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("touch:transport").assertDoesNotExist()
        val hidden = player.captureToImage().asAndroidBitmap()
        for ((x, y) in probes) {
            assertTrue(
                rgb(hidden.getPixel(x, y)).all { abs(255 - it) <= e.number("maximumHiddenWhiteChannelDelta") },
                "Hiding controls must restore the actual white picture at former seek targets: ($x,$y)"
            )
        }
    }

    @Test fun qualityPaintedRightEdgeMatchesTotalClockWithoutShrinkingItsTarget() {
        val e = expected("quality-and-total-clock-painted-right-edge")
        show()
        val quality = compose.onNodeWithTag("touch:quality").assertIsDisplayed()
        val target = quality.fetchSemanticsNode().touchBoundsInRoot
        val targetDensity = quality.fetchSemanticsNode().layoutInfo.density.density
        val frame = compose.onNodeWithTag("touch:player").fetchSemanticsNode().boundsInRoot
        assertTrue(
            target.width / targetDensity >= e.number("minimumQualityTargetDp"),
            "Actual expanded Quality touch width: $target density=$targetDensity"
        )
        assertTrue(
            target.height / targetDensity >= e.number("minimumQualityTargetDp"),
            "Actual expanded Quality touch height: $target density=$targetDensity"
        )
        assertTrue(
            target.left >= frame.left && target.right <= frame.right && target.top >= frame.top && target.bottom <= frame.bottom,
            "Actual expanded Quality target must remain inside player: target=$target frame=$frame"
        )
        val text = compose.onNodeWithText("Quality · Auto", useUnmergedTree = true)
        val total = compose.onNodeWithTag("touch:total-time", useUnmergedTree = true)
        val root = compose.onNodeWithTag("touch:player").fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        fun inkRight(node: androidx.compose.ui.test.SemanticsNodeInteraction): Float {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val image = node.captureToImage().asAndroidBitmap()
            // Both actual Text nodes sit above black video. Threshold excludes the black
            // panel and retains disabled Quality's real ink, including its antialias fringe.
            val inkColumns = (0 until image.width).filter { x -> (0 until image.height).any { y -> rgb(image.getPixel(x, y)).max() > 40 } }
            assertTrue(inkColumns.isNotEmpty(), "Actual Text must paint non-background ink")
            return bounds.left + inkColumns.last() + 1f
        }
        val qualityRight = inkRight(text)
        val clockRight = inkRight(total)
        assertTrue(
            abs(qualityRight - clockRight) / density <= e.number("maximumInkRightEdgeDifferenceDp"),
            "Actual last painted glyph edges must align, not just button containers: quality=${qualityRight / density} clock=${clockRight / density}"
        )
    }

    private fun rgb(argb: Int) = listOf((argb ushr 16) and 255, (argb ushr 8) and 255, argb and 255)
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
