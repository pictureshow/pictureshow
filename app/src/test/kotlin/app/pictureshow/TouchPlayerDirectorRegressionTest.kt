/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun show(grouped: Boolean = false, whiteVideo: Boolean = false, overlay: JsonObject? = null) {
        val input = overlay?.getValue("input")?.jsonObject
        val phase = input?.getValue("phase")?.jsonPrimitive?.content ?: "Playing"
        val groupName = input?.getValue("groupName")?.jsonPrimitive?.content ?: "Sync91Design4k"
        val participants = input?.getValue("participants")?.jsonArray ?: JsonArray(emptyList())
        val positionMs = input?.getValue("positionMs")?.jsonPrimitive?.long ?: 20_000L
        val fontScale = input?.getValue("fontScale")?.jsonPrimitive?.float ?: 1f
        RuntimeEnvironment.setFontScale(fontScale)
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(positionMs)
            if (grouped) {
                val gateway = object : SyncPlayGateway {
                    override suspend fun groups(account: Account) = ClientResult.Success(
                        listOf(
                            SyncGroup(
                                "g",
                                groupName,
                                phase,
                                participants.map { it.jsonPrimitive.content },
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
                            put("GroupName", groupName)
                            put("State", phase)
                            put("Participants", participants)
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
                            put("StartPositionTicks", positionMs * 10_000)
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
            playback = PlaybackHostState(MediaItem("film", "A film"), positionMs, output, {}, {})
            playback.sync = sync
            playback.playerSource = mutableStateOf(exposed)
            playback.stateSource = mutableStateOf(PlaybackState(preparing = false))
            playback.duration = 60_000
            if (overlay != null) {
                val action = requireNotNull(input).getValue("action").jsonPrimitive.content
                if (action == "skip-intro") {
                    playback.segments = listOf(MediaSegment("intro", SegmentType.Intro, 0, 600_000_000))
                } else {
                    assertEquals("next-episode", action)
                    playback.nextLookup = EpisodeLookup(0, ClientResult.Success(MediaItem("next", "Another episode")))
                }
                val group = requireNotNull(sync)
                // Geometry fixture bridge uses the actual Host snapshot/apply path. No ready/seekable state is invented.
                val bridge = object : SyncRenderer {
                    override val sourceIdentity: Any get() = playback.player ?: output
                    override fun snapshot() = playback.syncRendererSnapshot()
                    override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double) =
                        playback.applySyncTimeline(positionTicks, playing, rate)
                }
                assertTrue(
                    group.bindRenderer(
                        playback,
                        bridge,
                        opening = SyncOpen(
                            group.membership.generation,
                            "entry-a",
                            "film",
                            positionMs * 10_000
                        )
                    )
                )
            }
            sent.clear()
        }
        compose.setContent {
            val direction = when (input?.get("layoutDirection")?.jsonPrimitive?.content) {
                null -> LocalLayoutDirection.current
                "rtl" -> LayoutDirection.Rtl
                "ltr" -> LayoutDirection.Ltr
                else -> error("Unsupported native layout direction")
            }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    TouchPlayerContent(
                        account,
                        playback,
                        // Neutral picture seam: every production chrome layer remains under test.
                        videoContent = if (whiteVideo) ({ Box(Modifier.fillMaxSize().background(Color.White)) }) else null
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val actual = dpBounds("touch:player")
        assertEquals(
            input?.getValue("widthDp")?.jsonPrimitive?.float ?: 640f,
            (actual.right - actual.left).value,
            1f,
            "Actual window width, not injected LocalConfiguration"
        )
        assertEquals(
            input?.getValue("heightDp")?.jsonPrimitive?.float ?: 360f,
            (actual.bottom - actual.top).value,
            1f,
            "Actual window height"
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("touch:title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(fontScale, it.layoutInput.density.fontScale, .01f) }
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
            // The circular local backing must protect the Material glyph, beyond the whole-picture scrim.
            // At 16dp from center this samples backing outside its 24dp glyph and inside the 48dp circle.
            for (direction in listOf(-1, 1)) {
                val x = (bounds.center.x + direction * 16f * density - frame.left).roundToInt()
                val y = (bounds.center.y - frame.top).roundToInt()
                assertTrue(
                    rgb(shown.getPixel(x, y)).all { it <= e.number("maximumSeekLocalBackingChannel") },
                    "Bright-video seek needs local contrast backing: ($x,$y)=${rgb(shown.getPixel(x, y))}"
                )
            }
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

    private fun overlayProfile(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/touch-player-director-clearance-paint-edges.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("touch-player-director-clearance-paint-edges", suite.getValue("suite").jsonPrimitive.content)
        val profiles = suite.getValue("cases").jsonArray.map { it.jsonObject }
            .filter { it.getValue("profile").jsonPrimitive.content == "actual-player-group-overlay-clearance" }
        assertEquals(
            setOf(
                "group-chip-skip-intro-portrait-normal",
                "group-chip-next-episode-portrait-normal",
                "group-chip-skip-intro-portrait-large-font",
                "group-chip-next-episode-portrait-large-font",
                "group-chip-skip-intro-landscape-guard",
                "group-chip-next-episode-landscape-guard",
                "group-chip-skip-intro-landscape-rtl",
                "group-chip-next-episode-landscape-rtl",
                "group-chip-next-episode-portrait-large-font-rtl"
            ),
            profiles.map { it.getValue("id").jsonPrimitive.content }.toSet()
        )
        assertEquals(9, profiles.size, "All admitted overlay cases must be present exactly once")
        return profiles.single { it.getValue("id").jsonPrimitive.content == id }
    }

    private fun controlRequests() = sent.filterNot { it in setOf("Ready", "Ping") }

    private fun overlayTarget(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun chipButton() = compose.onNode(
        hasClickAction() and hasAnyAncestor(hasTestTag("sync:player-chip")),
        useUnmergedTree = true
    )

    private fun assertOverlayClear(c: JsonObject, a: Rect, b: Rect, role: String) {
        val density = compose.onNodeWithTag("touch:player").fetchSemanticsNode().layoutInfo.density.density
        val gap = c.getValue("expected").jsonObject.number("minimumOverlayClearanceDp") * density
        assertTrue(
            a.right + gap <= b.left + .5f ||
                b.right + gap <= a.left + .5f ||
                a.bottom + gap <= b.top + .5f ||
                b.bottom + gap <= a.top + .5f,
            "${c.getValue("id")}: actual $role must clear whole live chip by ${gap / density}dp: chip=$a other=$b"
        )
    }

    private fun assertWholeChipText(c: JsonObject, tag: String, expectedKey: String, blocker: Rect) {
        val input = c.getValue("input").jsonObject
        val expected = c.getValue("expected").jsonObject
        val node = overlayTarget(tag).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "Real chip Text layout must be available: $tag")
        val text = layouts.joinToString(" ") { it.layoutInput.text.text.replace('\n', ' ') }
        assertTrue(text.contains(expected.getValue(expectedKey).jsonPrimitive.content), "Whole chip phrase: $tag=$text")
        layouts.forEach { layout ->
            assertEquals(input.number("fontScale"), layout.layoutInput.density.fontScale, .01f)
            val rounding = expected.number("maximumGlyphBoundaryErrorNativePx")
            assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines, "Whole chip phrase: $tag $text")
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line), "Whole chip phrase: $tag $text")
                assertTrue(
                    layout.getLineLeft(line) >= -rounding &&
                        layout.getLineRight(line) <= layout.size.width + rounding &&
                        layout.getLineTop(line) >= -rounding &&
                        layout.getLineBottom(line) <= layout.size.height + rounding,
                    "Actual chip glyph line must fit the Text box: $tag $text size=${layout.size} line=$line"
                )
            }
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val chip = chipButton().fetchSemanticsNode().boundsInRoot
        assertTrue(
            bounds.left >= chip.left && bounds.right <= chip.right && bounds.top >= chip.top && bounds.bottom <= chip.bottom,
            "Actual whole phrase must remain inside chip: $tag bounds=$bounds chip=$chip"
        )
        assertOverlayClear(c, bounds, blocker, "action vs $tag text")
    }

    private fun assertGroupOverlay(id: String) {
        val c = overlayProfile(id)
        val input = c.getValue("input").jsonObject
        val e = c.getValue("expected").jsonObject
        assertTrue(input.getValue("participating").jsonPrimitive.boolean)
        assertTrue(input.getValue("controlsInitiallyVisible").jsonPrimitive.boolean)
        assertEquals(1, input.getValue("queueItems").jsonPrimitive.int)
        assertEquals(60_000L, input.getValue("durationMs").jsonPrimitive.long)
        show(grouped = true, overlay = c)
        val root = compose.onNodeWithTag("touch:player").fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        assertEquals(input.getValue("densityDpi").jsonPrimitive.int, compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(input.number("densityDpi") / 160f, density, .001f)
        assertEquals(
            if (input.getValue("orientation").jsonPrimitive.content ==
                "portrait"
            ) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            },
            compose.activity.resources.configuration.orientation
        )
        compose.runOnIdle {
            val group = requireNotNull(sync)
            assertTrue(group.participating, "Real membership prerequisite")
            assertEquals(input.getValue("phase").jsonPrimitive.content, group.state.value.phase)
            assertEquals(input.getValue("groupName").jsonPrimitive.content, group.state.value.groupName)
            assertEquals(2, group.state.value.participants.size)
            assertFalse(group.panelOpen)
            assertEquals(input.getValue("positionMs").jsonPrimitive.long, decoder.currentPosition)
            assertFalse(playback.ended)
            assertFalse(playback.switching)
            sent.clear()
        }
        val card = input.getValue("action").jsonPrimitive.content == "next-episode"
        val action = overlayTarget(if (card) "next:card" else "touch:skip").assertIsDisplayed()
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        val chip = chipButton().assertIsDisplayed().assertIsEnabled()
        val chipBounds = chip.fetchSemanticsNode().touchBoundsInRoot
        val frame = root.boundsInRoot
        for ((role, rect) in listOf("chip" to chipBounds, "action" to actionBounds)) {
            assertTrue(
                rect.left >= frame.left && rect.right <= frame.right && rect.top >= frame.top && rect.bottom <= frame.bottom,
                "Whole $role must stay inside actual player: $rect frame=$frame"
            )
        }
        assertOverlayClear(c, chipBounds, actionBounds, "Skip/card bounds")
        e["actionPlacement"]?.jsonPrimitive?.content?.let { placement ->
            val gap = e.number("minimumOverlayClearanceDp") * density
            val rounding = e.number("maximumGlyphBoundaryErrorNativePx")
            val horizontalGapFits = chipBounds.right + gap <= actionBounds.left + rounding ||
                actionBounds.right + gap <= chipBounds.left + rounding
            val geometry = "chip=$chipBounds action=$actionBounds gap=$gap horizontalGapFits=$horizontalGapFits"
            when (placement) {
                "beside" -> {
                    assertTrue(horizontalGapFits, "The actual landscape roles must have room beside each other: $geometry")
                    assertTrue(
                        actionBounds.right + gap <= chipBounds.left + rounding,
                        "Actual RTL places the action physically left of the chip with full clearance: $geometry"
                    )
                    assertTrue(
                        abs(chipBounds.top - actionBounds.top) <= rounding,
                        "A fitting action must retain the chip's top band instead of stacking: $geometry"
                    )
                }
                "below" -> {
                    assertFalse(horizontalGapFits, "The actual portrait roles must exercise a horizontal conflict: $geometry")
                    assertTrue(
                        abs(frame.right - chipBounds.right - 16f * density) <= rounding,
                        "Actual RTL anchors the chip at the physical right native edge: frame=$frame $geometry"
                    )
                    assertTrue(
                        actionBounds.top >= chipBounds.bottom + gap - rounding,
                        "A conflicting action must be below the whole chip with native clearance: $geometry"
                    )
                }
                else -> error("Unsupported actual action placement: $placement")
            }
        }
        assertWholeChipText(c, "sync:chip-status", "chipPhasePhrase", actionBounds)
        assertWholeChipText(c, "sync:chip-name", "chipGroupName", actionBounds)
        assertWholeChipText(c, "sync:chip-count", "chipCountPhrase", actionBounds)
        val targetTags = if (card) listOf("next:play", "next:cancel") else listOf("touch:skip")
        for (tag in targetTags) {
            val target = overlayTarget(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().touchBoundsInRoot
            assertTrue(target.width / density >= e.number("minimumActionTargetDp") - .5f)
            assertTrue(target.height / density >= e.number("minimumActionTargetDp") - .5f)
            assertOverlayClear(c, chipBounds, target, tag)
        }
        // Existing notice-specific tests stay unchanged; keep full native control targets in this collision fixture.
        for (tag in listOf("touch:back10", "touch:forward10", "touch:toggle")) {
            val bounds = overlayTarget(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().touchBoundsInRoot
            val minimum = if (tag == "touch:toggle") e.number("minimumPrimaryTargetDp") else e.number("minimumActionTargetDp")
            assertTrue(bounds.width / density >= minimum - .5f && bounds.height / density >= minimum - .5f)
        }
        overlayTarget("touch:conversion-notice").assertDoesNotExist()
        if (card) {
            compose.onNodeWithText(e.getValue("cardTitle").jsonPrimitive.content, useUnmergedTree = true).assertIsDisplayed()
            assertFalse(e.getValue("countdownVisible").jsonPrimitive.boolean)
            compose.onNodeWithText("s left", substring = true, useUnmergedTree = true).assertDoesNotExist()
        } else {
            compose.onNodeWithText(e.getValue("skipLabel").jsonPrimitive.content, useUnmergedTree = true).assertIsDisplayed()
            overlayTarget("next:card").assertDoesNotExist()
        }
        val actionLabels = if (card) {
            listOf(e.getValue("cardTitle").jsonPrimitive.content, "Play now", "Cancel")
        } else {
            listOf(e.getValue("skipLabel").jsonPrimitive.content)
        }
        for (label in actionLabels) {
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty(), "Actual action label layout: $label")
            layouts.forEach { layout ->
                val rounding = e.number("maximumGlyphBoundaryErrorNativePx")
                val metrics = "label=$label size=${layout.size} " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
                    "constraints=${layout.layoutInput.constraints} lines=${layout.lineCount} " +
                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                    "maxLinesExceeded=${layout.multiParagraph.didExceedMaxLines} " +
                    "lineBoxes=${(0 until layout.lineCount).map {
                        listOf(layout.getLineLeft(it), layout.getLineRight(it), layout.getLineTop(it), layout.getLineBottom(it))
                    }}"
                assertFalse(
                    layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
                    "Whole action label must fit vertically and retain all lines: $metrics"
                )
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line), "Whole action label must not be ellipsized: $metrics")
                    assertTrue(
                        layout.getLineLeft(line) >= -rounding &&
                            layout.getLineRight(line) <= layout.size.width + rounding &&
                            layout.getLineTop(line) >= -rounding &&
                            layout.getLineBottom(line) <= layout.size.height + rounding,
                        "Actual action-label glyph line must fit the Text box: line=$line $metrics"
                    )
                }
            }
            assertOverlayClear(c, chipBounds, text.fetchSemanticsNode().boundsInRoot, "action label $label")
        }
        // Physical pointer dispatch at the chip's action-facing edge catches an overlay intercepting the live button.
        chip.performTouchInput {
            val x = if (input["layoutDirection"]?.jsonPrimitive?.content == "rtl") 4f * density else width - 4f * density
            click(Offset(x, center.y))
        }
        compose.runOnIdle {
            assertTrue(requireNotNull(sync).panelOpen, "The chip tap must open its real group panel, not Skip/card")
            assertFalse(playback.nextCancelled)
            assertTrue(controlRequests().isEmpty(), "Chip tap cannot perform an action: $sent")
            requireNotNull(sync).panelOpen = false
        }
        overlayTarget(if (card) "next:cancel" else "touch:skip").performTouchInput { click(center) }
        compose.runOnIdle {
            scheduler.runCurrent()
            assertFalse(requireNotNull(sync).panelOpen, "Action tap cannot hit the group chip")
            assertEquals(e.getValue("membershipRetained").jsonPrimitive.boolean, requireNotNull(sync).participating)
            if (card) {
                assertTrue(playback.nextCancelled, "Physical Cancel tap must reach the real manual card")
                assertTrue(controlRequests().isEmpty(), "Cancel cannot send control requests: $sent")
            } else {
                assertEquals(listOf("Seek"), controlRequests(), "Physical Skip tap must dispatch the real group request")
            }
            assertFalse(playback.exitRequested)
            assertFalse(playback.switching)
            assertEquals(
                input.getValue("positionMs").jsonPrimitive.long,
                decoder.currentPosition,
                "Group request cannot optimistically seek/advance the local fixture decoder"
            )
        }
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun groupChipPortraitSkipIntroKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-skip-intro-portrait-normal")
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun groupChipPortraitManualNextEpisodeKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-next-episode-portrait-normal")
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun groupChipPortraitLargeFontSkipIntroKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-skip-intro-portrait-large-font")
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun groupChipPortraitLargeFontManualNextEpisodeKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-next-episode-portrait-large-font")
    }

    @Test fun groupChipLandscapeSkipIntroKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-skip-intro-landscape-guard")
    }

    @Test fun groupChipLandscapeManualNextEpisodeKeepsWholePhrasesAndSeparateActionTargets() {
        assertGroupOverlay("group-chip-next-episode-landscape-guard")
    }

    private fun rgb(argb: Int) = listOf((argb ushr 16) and 255, (argb ushr 8) and 255, argb and 255)

    @Test fun groupChipRtlLandscapeSkipIntroStaysBesideWhenTheActualGapFits() {
        assertGroupOverlay("group-chip-skip-intro-landscape-rtl")
    }

    @Test fun groupChipRtlLandscapeManualNextEpisodeStaysBesideWhenTheActualGapFits() {
        assertGroupOverlay("group-chip-next-episode-landscape-rtl")
    }

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun groupChipRtlPortraitLargeFontManualNextEpisodeStacksBelowTheWholeChip() {
        assertGroupOverlay("group-chip-next-episode-portrait-large-font-rtl")
    }

    private class Decoder(positionMs: Long = 20_000) : SimpleBasePlayer(Looper.getMainLooper()) {
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
            .setContentPositionMs(positionMs).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
