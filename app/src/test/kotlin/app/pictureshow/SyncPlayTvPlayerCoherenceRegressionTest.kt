/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.InterruptedIOException
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual physical156dpi TV panel/player surfaces; recording gateways never claim peer output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w985dp-h554dp-land-156dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTvPlayerCoherenceRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val epoch = 1_762_776_000_000L
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val sent = mutableListOf<String>()
    private val nativeChanges = mutableListOf<Boolean>()
    private val refreshRelease = CompletableDeferred<Unit>()
    private var holdRefresh = false
    private var pauseFeedback = 0
    private lateinit var group: SyncPlayHost
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private lateinit var native: Player
    private val nativeListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            nativeChanges += playWhenReady
        }
    }

    @After fun close() {
        compose.runOnIdle {
            refreshRelease.complete(Unit)
            if (::native.isInitialized) native.removeListener(nativeListener)
            scope.cancel()
            scheduler.runCurrent()
            if (::output.isInitialized) output.release()
        }
        if (::http.isInitialized) stopKoin()
        if (::http.isInitialized) http.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun update(type: String, data: JsonObject) = group.receive(
        buildJsonObject {
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
    )

    /** Find the actual primary independently of which of its two labels the product chose. */
    private fun primary() = compose.onNode(
        hasAnyAncestor(hasTestTag("player:frame")) and hasClickAction() and (hasText("Play") or hasText("Pause"))
    )
    private fun transportSent() = sent.filter { it in setOf("Pause", "Unpause", "Stop") }
    private fun launch(c: Profile) {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        val input = c.input
        val participating = input.getValue("participating").jsonPrimitive.boolean
        val phase = c.text("phase")
        val entries = input.getValue("queueEntries").jsonPrimitive.int
        val localShowPlay = input.getValue("localShowPlayButton").jsonPrimitive.boolean
        val usable = input.getValue("usable").jsonPrimitive.boolean
        val gateway = object : SyncPlayGateway {
            override suspend fun groups(account: Account): ClientResult<List<SyncGroup>> {
                if (holdRefresh) refreshRelease.await()
                return ClientResult.Success(listOf(SyncGroup("g", "Friday films", phase, emptyList(), epoch * 10_000)))
            }
            override suspend fun access(account: Account) = ClientResult.Success("CreateAndJoinGroups")
            override suspend fun time(account: Account) = ClientResult.Success(SyncUtcTime(epoch, epoch))
            override suspend fun send(account: Account, request: SyncRequest): Int {
                sent += request.operation
                return 204
            }
        }
        compose.runOnIdle {
            group = SyncPlayHost(scope, gateway, { epoch + scheduler.currentTime }, { scheduler.currentTime })
            group.bind(account)
            group.socket(true)
            group.refresh()
            scheduler.runCurrent()
            if (participating) {
                group.join("g")
                scheduler.runCurrent()
                update(
                    "GroupJoined",
                    buildJsonObject {
                        put("GroupId", "g")
                        put("GroupName", "Friday films")
                        put("State", phase)
                        put("Participants", JsonArray(emptyList()))
                        put("LastUpdatedAt", syncUtcTimestamp(epoch))
                    }
                )
                update(
                    "PlayQueue",
                    buildJsonObject {
                        put("Reason", "NewPlaylist")
                        put("LastUpdate", syncUtcTimestamp(epoch))
                        put(
                            "Playlist",
                            JsonArray(
                                (0 until entries).map {
                                    buildJsonObject {
                                        put("ItemId", "film")
                                        put("PlaylistItemId", "entry-a")
                                    }
                                }
                            )
                        )
                        put("PlayingItemIndex", 0)
                        put("StartPositionTicks", 200_000_000L)
                        put("IsPlaying", phase == "Playing")
                        put("RepeatMode", "RepeatNone")
                        put("ShuffleMode", "Sorted")
                    }
                )
                scheduler.runCurrent()
            }
        }
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val json = when {
                        request.url.encodedPath.endsWith("/PlaybackInfo") ->
                            """{"PlaySessionId":"viewing","MediaSources":[{"Id":"source","RunTimeTicks":600000000,"SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls","TranscodingUrl":"/Videos/film/master.m3u8","MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"}]}]}"""
                        request.url.encodedPath == "/Items/film" -> buildJsonObject {
                            put("Id", "film")
                            put("Name", c.text("title"))
                            put("Type", "Movie")
                            put(
                                "Chapters",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("StartPositionTicks", 0)
                                            put("Name", "A real chapter")
                                        }
                                    )
                                )
                            )
                        }.toString()
                        request.url.encodedPath.startsWith(
                            "/MediaSegments"
                        ) -> """{"Items":[{"Id":"intro","Type":"Intro","StartTicks":0,"EndTicks":500000000}]}"""
                        else -> return@addHandler respond("", HttpStatusCode.NoContent)
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
                    single { group }
                    single { inertRemoteTarget() }
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                }
            )
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        compose.setContent {
            TvEnvironment {
                PlayerItemScreen(account, MediaItem("film", c.text("title"), "Movie"), 0, catalog, {
                }, { exitCalls++ }, providedOutput = output)
            }
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            output.player.value?.playbackState == Player.STATE_BUFFERING
        }
        compose.waitForIdle()
        compose.runOnIdle {
            scheduler.runCurrent()
            native = requireNotNull(output.player.value)
            output.applySync { native.playWhenReady = !localShowPlay }
            output.setSpeed(c.number("speed"))
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player:surface").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            if (!usable) {
                holdRefresh = true
                group.refresh()
                scheduler.runCurrent()
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(participating, group.participating, "Fixture membership")
            if (participating) {
                assertEquals(phase, group.state.value.phase, "Fixture authoritative phase")
                assertEquals(entries, group.state.value.queue.size, "Fixture authoritative queue")
                assertEquals(!usable, group.state.value.busy, "Fixture usable/busy state")
                assertNotNull(output.syncRequest, "Actual host must install its output interception")
                assertTrue(output.syncParticipating(), "Actual binding must expose membership")
            }
            assertTrue(native === output.player.value, "Fixture must retain the actual mounted output player")
            assertEquals(localShowPlay, Util.shouldShowPlayButton(native), "Fixture local native projection")
            val feedback = output.onSyncPauseRequested
            output.onSyncPauseRequested = {
                pauseFeedback++
                feedback()
            }
            native.addListener(nativeListener)
            sent.clear()
            nativeChanges.clear()
            pauseFeedback = 0
        }
        primary().assertIsDisplayed()
        assertNative(c)
        labels(c).forEach { action(it).assertExists() }
        assertFont(c, compose.onNodeWithText(c.text("title"), useUnmergedTree = true))
        compose.onNodeWithTag("skip:Intro").assertExists()
    }
    private var density = 0f
    private var window = IntSize.Zero
    private var exitCalls = 0
    private data class Profile(val input: JsonObject, val expected: JsonObject) {
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
    }
    private fun profile(id: String): Profile {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/sync-tv-player-coherence.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-tv-player-coherence", suite.getValue("suite").jsonPrimitive.content)
        val c = suite.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        return Profile(c.getValue("input").jsonObject, c.getValue("expected").jsonObject)
    }

    @Before fun tvMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    @Composable private fun TvEnvironment(content: @Composable () -> Unit) {
        val config = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        CompositionLocalProvider(LocalConfiguration provides config) {
            PictureshowTheme {
                val scale = LocalDensity.current.density
                val actualWindow = LocalWindowInfo.current.containerSize
                SideEffect {
                    density = scale
                    window = actualWindow
                }
                content()
            }
        }
    }
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }
    private fun assertNative(c: Profile) {
        assertEquals(c.input.getValue("densityDpi").jsonPrimitive.int, compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(c.number("densityDpi") / 160f, density, .001f, "Use actual Android density, not a LocalDensity override")
        assertEquals(
            IntSize(c.number("windowWidthPx").toInt(), c.number("windowHeightPx").toInt()),
            window,
            "Really render960x540physical pixels, not960dp"
        )
    }
    private fun action(label: String) = compose.onNode(
        hasAnyAncestor(hasTestTag("player:frame")) and hasClickAction() and hasText(label)
    )
    private fun nativeBounds(node: SemanticsNodeInteraction): Rect {
        val b = node.getUnclippedBoundsInRoot()
        return Rect(b.left.value * density, b.top.value * density, b.right.value * density, b.bottom.value * density)
    }
    private fun requestFocus(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        settle()
        node.assertIsFocused()
    }
    private fun wholeFocusedTarget(c: Profile, node: SemanticsNodeInteraction) {
        node.assertIsFocused().assertIsDisplayed()
        val actual = node.fetchSemanticsNode()
        val hit = actual.touchBoundsInRoot
        val unclipped = nativeBounds(node)
        val clipped = actual.boundsInRoot
        assertTrue(hit.width >= c.metric("minimumTargetDp") * density - 1f)
        assertTrue(hit.height >= c.metric("minimumTargetDp") * density - 1f)
        assertTrue(
            hit.left >= -1f && hit.top >= -1f && hit.right <= window.width + 1f && hit.bottom <= window.height + 1f,
            "Focused actual native target must be whole: $hit in $window"
        )
        assertTrue(
            abs(clipped.left - unclipped.left) <= 1f && abs(clipped.right - unclipped.right) <= 1f,
            "Focused target must actually reveal in its clip viewport: clipped=$clipped unclipped=$unclipped"
        )
    }
    private fun assertFont(c: Profile, node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(c.number("fontScale"), layouts.single().layoutInput.density.fontScale, .01f)
    }
    private fun labels(c: Profile) = c.input.getValue("actionLabels").jsonArray.map { it.jsonPrimitive.content }

    @Test fun tvRosterCountUpdatesAtNativeFontOneWithoutCommandsOrFocusChange() = roster("tv-roster-lifecycle-font-one")

    @Test fun tvRosterCountUpdatesAtNativeLargeFontWithoutCommandsOrFocusChange() = roster("tv-roster-lifecycle-large-font")
    private fun roster(id: String) {
        val c = profile(id)
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        val snapshots = c.input.getValue("participantSnapshots").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
        val expected = c.expected.getValue("countTexts").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(0, 1, 2, 0), snapshots.map { it.size }, "Actual server-roster sequence")
        assertEquals(snapshots.size, expected.size, "Every snapshot has an executed count oracle")
        val commands = mutableListOf<String>()
        val state = mutableStateOf(
            SyncPlayPresentation(
                server = "Cinema", viewer = "Viewer", policyKnown = true,
                policy = "CreateAndJoinGroups", groupId = "g", groupName = "Friday films", phase = c.text("phase"),
                participants = snapshots.first(), localPlayback = SyncLocalPlayback.UNAVAILABLE
            )
        )
        compose.setContent { TvEnvironment { SyncPlayPanel(state.value, { op, _ -> commands += op }, {}) } }
        settle()
        assertNative(c)
        compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
        var previous: String? = null
        snapshots.zip(expected).forEachIndexed { index, (people, text) ->
            if (index > 0) compose.runOnIdle { state.value = state.value.copy(participants = people) }
            settle()
            val localized = compose.activity.resources.getQuantityString(
                R.plurals.sync_people_watching,
                people.size,
                NumberFormat.getIntegerInstance(compose.activity.resources.configuration.locales[0]).format(people.size)
            )
            assertEquals(text, localized)
            val count = compose.onNodeWithTag("sync:people-count").assertIsDisplayed().assertTextContains(text)
            assertFont(c, count)
            val semantics = count.fetchSemanticsNode().config
            assertFalse(semantics.contains(SemanticsActions.OnClick))
            assertFalse(semantics.contains(SemanticsActions.RequestFocus))
            val b = nativeBounds(count)
            val viewport = nativeBounds(compose.onNodeWithTag("tv-settings:viewport"))
            assertTrue(b.top >= viewport.top - 1f && b.bottom <= viewport.bottom + 1f, "Whole actual roster count on arrival")
            compose.onNodeWithTag("sync:ignore-wait").assertIsFocused()
            previous?.takeIf { it != text }?.let { compose.onNodeWithText(it).assertDoesNotExist() }
            assertEquals(c.expected.getValue("groupCommands").jsonPrimitive.int, commands.size)
            previous = text
        }
    }

    @Test fun nativeTvActionsNeverWrapAndDpadRevealsLastThenReturnsAtFontOne() = row("tv-actions-one-row-font-one")

    @Test fun nativeTvActionsNeverWrapAndDpadRevealsLastThenReturnsAtLargeFont() = row("tv-actions-one-row-large-font")
    private fun row(id: String) {
        val c = profile(id)
        launch(c)
        val names = labels(c)
        val boxes = names.map { nativeBounds(action(it)) }
        val top = boxes.first().top
        for ((label, b) in names.zip(boxes)) {
            assertTrue(
                abs(b.top - top) <= c.metric("sameRowTolerancePx"),
                "All actual actions share one native row: $label=$b firstTop=$top"
            )
        }
        requestFocus(action(names.first()))
        names.forEachIndexed { index, label ->
            if (index > 0) action(names[index - 1]).performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            wholeFocusedTarget(c, action(label))
        }
        for (index in names.lastIndex downTo 1) {
            action(names[index]).performKeyInput { pressKey(Key.DirectionLeft) }
            settle()
            wholeFocusedTarget(c, action(names[index - 1]))
        }
        assertEquals(0, exitCalls, "Stop must be reachable without invoking it")
        assertTrue(transportSent().isEmpty(), "Directional traversal must send no group transport")
    }

    @Test fun nativeTvChipContentTimelineAndSkipUseOneSafeHorizontalEdgeAtFontOne() = edges("tv-safe-edges-font-one")

    @Test fun nativeTvChipContentTimelineAndSkipUseOneSafeHorizontalEdgeAtLargeFont() = edges("tv-safe-edges-large-font")
    private fun edges(id: String) {
        val c = profile(id)
        launch(c)
        val expectedLeft = c.metric("horizontalInsetDp") * density
        val chip = nativeBounds(compose.onNodeWithTag("sync:player-chip"))
        val titleNode = compose.onNodeWithText(c.text("title"), useUnmergedTree = true)
        val title = nativeBounds(titleNode)
        assertEquals(expectedLeft, chip.left, 1f, "Keep the actual chip48dp safe edge")
        assertEquals(expectedLeft, title.left, 1f, "Actual title must share chip safe edge")
        val frame = compose.onNodeWithTag("player:surface")
        val origin = frame.fetchSemanticsNode().boundsInRoot
        val image = frame.captureToImage().asAndroidBitmap()
        val ink = brightInk(c, image, title, origin)
        assertTrue(
            ink.left >= expectedLeft - 1f && ink.left <= expectedLeft + c.metric("firstGlyphBearingMaxPx"),
            "Actual title glyph ink, independently of layout box: $ink expectedLeft=$expectedLeft"
        )
        assertEquals(expectedLeft, nativeBounds(compose.onNodeWithTag("player:position")).left, 1f)
        assertEquals(expectedLeft, nativeBounds(primary()).left, 1f)
        val skip = compose.onNodeWithTag("skip:Intro").assertIsDisplayed()
        assertEquals(window.width - expectedLeft, nativeBounds(skip).right, 1f, "Actual Skip shares48dp right safe edge")
        val hit = skip.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(hit.width >= c.metric("minimumTargetDp") * density - 1f && hit.height >= c.metric("minimumTargetDp") * density - 1f)
    }
    private fun brightInk(c: Profile, image: Bitmap, box: Rect, capture: Rect): Rect {
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
            for (x in box.left.roundToInt() until box.right.roundToInt()) {
                val localX = x - capture.left.roundToInt()
                val localY = y - capture.top.roundToInt()
                assertTrue(localX in 0 until image.width && localY in 0 until image.height)
                val pixel = image.getPixel(localX, localY)
                if (listOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255).all { it >= c.metric("titleInkMinimumChannel") }) {
                    xs += x
                    ys += y
                }
            }
        }
        assertTrue(xs.size >= c.metric("minimumTitleInkPixels"), "Actual title must draw real foreground ink")
        return Rect(xs.min().toFloat(), ys.min().toFloat(), xs.max() + 1f, ys.max() + 1f)
    }

    /** Real decoder intentionally remains buffering, independently of the authoritative group timeline. */
    private class WaitingSource : DataSource {
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            manifest = if (dataSpec.uri.path?.endsWith(".m3u8") == true) {
                (
                    "#EXTM3U\n#EXT-X-TARGETDURATION:61\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:60.008,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    ).byteInputStream()
            } else {
                null
            }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            manifest?.let { return it.read(buffer, offset, length) }
            return try {
                Thread.sleep(Long.MAX_VALUE)
                C.RESULT_END_OF_INPUT
            } catch (
                _: InterruptedException
            ) {
                throw InterruptedIOException()
            }
        }
        override fun close() = Unit
    }
}
