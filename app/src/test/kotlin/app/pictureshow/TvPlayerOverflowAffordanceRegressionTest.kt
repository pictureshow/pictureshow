/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlin.math.abs
import kotlin.math.pow
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
class TvPlayerOverflowAffordanceRegressionTest {
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
        hasAnyAncestor(hasTestTag("player:surface")) and hasClickAction() and (hasText("Play") or hasText("Pause"))
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
            .map { File(it, "spec/vectors/player-control-affordances.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("player-control-affordances", suite.getValue("suite").jsonPrimitive.content)
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
        hasAnyAncestor(hasTestTag("player:surface")) and hasClickAction() and hasText(label)
    )
    private fun nativeBounds(node: SemanticsNodeInteraction): Rect {
        val child = node.fetchSemanticsNode().layoutInfo.coordinates
        val root = generateSequence(child) { it.parentLayoutCoordinates }.last()
        return root.localBoundingBoxOf(child, clipBounds = false)
    }
    private fun requestFocus(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        settle()
        node.assertIsFocused()
    }

    /** Button owns its focus draw layer: its layout/hit contract is the unchanged old six-test target contract. */
    private fun targetLayoutBounds(node: SemanticsNodeInteraction): Rect {
        val b = node.getUnclippedBoundsInRoot()
        return Rect(b.left.value * density, b.top.value * density, b.right.value * density, b.bottom.value * density)
    }
    private fun wholeFocusedTarget(c: Profile, node: SemanticsNodeInteraction) {
        node.assertIsFocused().assertIsDisplayed()
        val actual = node.fetchSemanticsNode()
        val hit = actual.touchBoundsInRoot
        val unclipped = targetLayoutBounds(node)
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

    @Test fun largeFontOverflowHasVisiblePassiveProgressAndWholeFocusedLabels() {
        val c = profile("tv-action-overflow-large-font")
        launch(c)
        val names = labels(c)
        requestFocus(action(names.first()))
        wholeFocusedLabel(c, names.first())
        val row = generateSequence(action(names.first()).fetchSemanticsNode().parent) { it.parent }
            .first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
        val range = row.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "The actual nine-action row must overflow at this native profile")
        val start = range.value()
        assertEquals(0f, start, 1f, "Start at actual first-action scroll position")
        val initial = cue(c)
        action(names.first()).assertIsFocused()
        names.forEachIndexed { index, label ->
            if (index > 0) action(names[index - 1]).performKeyInput { pressKey(Key.DirectionRight) }
            settle()
            wholeFocusedLabel(c, label)
        }
        assertTrue(range.value() > start, "D-pad traversal must really move the native row")
        val remaining = range.maxValue() - range.value()
        if (remaining > 1f) {
            compose.onNode(SemanticsMatcher("Actual action scroll owner") { it.id == row.id })
                .performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(remaining, 0f)) }
            settle()
        }
        assertEquals(range.maxValue(), range.value(), 1f, "Observe the real scroll end, not an app boolean")
        wholeFocusedLabel(c, names.last())
        val endNodes = compose.onAllNodesWithTag("player:actions-overflow", useUnmergedTree = true).fetchSemanticsNodes()
        if (endNodes.isNotEmpty()) {
            val end = cue(c)
            val changed = (initial.ink.keys + end.ink.keys).count { initial.ink[it] != end.ink[it] }
            assertTrue(changed >= c.metric("minimumCueChangedPixels"), "Visible cue must change with actual start/end position")
        }
        action(names.last()).assertIsFocused()
        partialLabelPaint(c, names.dropLast(1), row.id)
        action(names.last()).assertIsFocused()
        for (index in names.lastIndex downTo 1) {
            action(names[index]).performKeyInput { pressKey(Key.DirectionLeft) }
            settle()
            wholeFocusedLabel(c, names[index - 1])
        }
        assertEquals(0f, range.value(), 1f)
        cue(c)
        action(names.first()).assertIsFocused()
        assertEquals(c.metric("groupCommands").toInt(), transportSent().size, "Passive cue and traversal send no transport")
        assertEquals(c.metric("exitCalls").toInt(), exitCalls, "Focus Stop without invoking it")
    }

    private fun wholeFocusedLabel(c: Profile, label: String) {
        wholeFocusedTarget(c, action(label))
        val text = compose.onNode(hasAnyAncestor(hasTestTag("player:surface")) and hasText(label), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertFalse(layouts.single().hasVisualOverflow, "Focused label must be whole, not truncated: $label")
        val natural = nativeBounds(text)
        val clipped = text.fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(natural.left - clipped.left) <= 1f && abs(natural.right - clipped.right) <= 1f,
            "Focused label must not be guillotined by the actual scroll clip: $label natural=$natural clipped=$clipped"
        )
    }

    private data class CuePaint(val ink: Map<Pair<Int, Int>, Int>)
    private fun cue(c: Profile): CuePaint {
        val node = compose.onNodeWithTag("player:actions-overflow", useUnmergedTree = true).assertIsDisplayed()
        val actual = node.fetchSemanticsNode()
        val descendants = generateSequence(listOf(actual)) { layer -> layer.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten()
        descendants.forEach {
            assertFalse(it.config.contains(SemanticsActions.OnClick), "Cue is passive, not another generic button")
            assertFalse(it.config.contains(SemanticsActions.RequestFocus), "Cue must not steal remote focus")
            assertTrue(it.config.getOrNull(SemanticsProperties.Focused) != true)
        }
        val b = actual.boundsInRoot
        assertTrue(
            b.width > 0f && b.height > 0f && b.left >= 0f && b.top >= 0f && b.right <= window.width && b.bottom <= window.height,
            "Cue is actually whole in the physical TV viewport: $b"
        )
        val frame = compose.onNodeWithTag("player:surface")
        val origin = frame.fetchSemanticsNode().boundsInRoot
        val image = frame.captureToImage().asAndroidBitmap()
        fun pixels(box: Rect) = buildList {
            for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
                for (x in box.left.roundToInt() until box.right.roundToInt()) {
                    add(image.getPixel(x - origin.left.roundToInt(), y - origin.top.roundToInt()))
                }
            }
        }
        val surround = Rect(
            maxOf(origin.left, b.left - density),
            maxOf(origin.top, b.top - density),
            minOf(origin.right, b.right + density),
            minOf(origin.bottom, b.bottom + density)
        )
        val solid = pixels(surround).groupingBy { it }.eachCount().filterValues { it >= c.metric("minimumCueCorePixels") }
        assertTrue(solid.size >= 2, "Cue must contain actual native foreground and backing ink")
        val outside = buildList {
            for (y in surround.top.roundToInt() until surround.bottom.roundToInt()) {
                for (x in surround.left.roundToInt() until surround.right.roundToInt()) {
                    if (x < b.left || x >= b.right || y < b.top || y >= b.bottom) {
                        add(image.getPixel(x - origin.left.roundToInt(), y - origin.top.roundToInt()))
                    }
                }
            }
        }
        assertTrue(outside.isNotEmpty())
        val backing = outside.groupingBy { it }.eachCount().maxBy { it.value }.key
        val foreground = solid.keys.maxBy { contrast(it, backing) }
        val ratio = contrast(foreground, backing)
        assertTrue(ratio >= c.metric("minimumCueContrast"), "Actual passive cue contrast=$ratio")
        val ink = buildMap {
            for (y in b.top.roundToInt() until b.bottom.roundToInt()) {
                for (x in b.left.roundToInt() until b.right.roundToInt()) {
                    val p = image.getPixel(x - origin.left.roundToInt(), y - origin.top.roundToInt())
                    if (p == foreground) put(x to y, p)
                }
            }
        }
        assertTrue(ink.size >= c.metric("minimumCueCorePixels"), "Native cue paints real solid pixels within its own bounds")
        return CuePaint(ink)
    }

    /** Same unfocused native label before/after ordinary scrolling: conservative edge paint may fade or hide it. */
    private fun partialLabelPaint(c: Profile, candidates: List<String>, rowId: Int) {
        val owner = compose.onNode(SemanticsMatcher("Actual action scroll owner") { it.id == rowId })
        val range = owner.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        val viewport = owner.fetchSemanticsNode().boundsInRoot
        val eligible = candidates.filter { name ->
            val button = action(name)
            val config = button.fetchSemanticsNode().config
            val target = targetLayoutBounds(button)
            val textNode = compose.onNode(hasAnyAncestor(hasTestTag("player:surface")) and hasText(name), useUnmergedTree = true)
            val ink = nativeBounds(textNode)
            !config.contains(SemanticsProperties.Disabled) &&
                config.getOrNull(SemanticsProperties.Focused) != true &&
                target.left >= viewport.left - 1f &&
                target.right <= viewport.right + 1f &&
                target.top >= viewport.top - 1f &&
                target.bottom <= viewport.bottom + 1f &&
                ink.left >= viewport.left &&
                ink.right <= viewport.right &&
                ink.top >= viewport.top &&
                ink.bottom <= viewport.bottom
        }
        assertTrue(eligible.isNotEmpty(), "Actual row must supply an enabled unfocused whole reference before the focused last action")
        val label = eligible.last()
        val button = action(label).assertIsEnabled().assertIsDisplayed()
        assertTrue(button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) != true)
        val fullButton = targetLayoutBounds(button)
        assertTrue(
            fullButton.left >= viewport.left - 1f &&
                fullButton.right <= viewport.right + 1f &&
                fullButton.top >= viewport.top - 1f &&
                fullButton.bottom <= viewport.bottom + 1f,
            "Reference button must be whole in the actual row viewport: $fullButton in $viewport"
        )
        val hit = button.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(hit.width >= c.metric("minimumTargetDp") * density - 1f && hit.height >= c.metric("minimumTargetDp") * density - 1f)
        assertTrue(hit.left >= -1f && hit.top >= -1f && hit.right <= window.width + 1f && hit.bottom <= window.height + 1f)
        val text = compose.onNode(hasAnyAncestor(hasTestTag("player:surface")) and hasText(label), useUnmergedTree = true)
        val referenceBox = nativeBounds(text)
        assertTrue(
            referenceBox.left >= viewport.left && referenceBox.right <= viewport.right,
            "Reference label is genuinely whole and unfocused before clipping: $referenceBox in $viewport"
        )
        val frame = compose.onNodeWithTag("player:surface")
        val origin = frame.fetchSemanticsNode().boundsInRoot
        val reference = frame.captureToImage().asAndroidBitmap()
        fun pixel(image: android.graphics.Bitmap, x: Int, y: Int) =
            image.getPixel(x - origin.left.roundToInt(), y - origin.top.roundToInt())
        fun channel(p: Int) = maxOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255)
        val width = referenceBox.right.roundToInt() - referenceBox.left.roundToInt()
        val height = referenceBox.bottom.roundToInt() - referenceBox.top.roundToInt()
        val left = referenceBox.left.roundToInt()
        val top = referenceBox.top.roundToInt()
        val colors = buildList { for (y in 0 until height) for (x in 0 until width) add(pixel(reference, left + x, top + y)) }
        val referenceInk = colors.filter { channel(it) >= c.metric("minimumLabelCoreChannel") }
        if (referenceInk.isEmpty()) recordReferenceFailure(c, label, rowId, reference, origin, referenceBox)
        assertTrue(
            referenceInk.isNotEmpty(),
            "Whole unfocused $label reference must paint native ink at channel >= ${c.metric("minimumLabelCoreChannel")}"
        )
        val foreground = referenceInk.groupingBy { it }.eachCount().maxBy { it.value }.key
        val probe = (c.metric("partialEdgeProbeDp") * density).roundToInt()
        val scroll = range.value()
        val maximum = range.maxValue()
        val feasible = (0 until width).filter { column ->
            val candidateCut = column + probe
            val requestedScroll = scroll + referenceBox.left + candidateCut - viewport.right
            candidateCut > 0 && candidateCut < referenceBox.width - density && requestedScroll >= 0f && requestedScroll <= maximum
        }
        assertTrue(feasible.isNotEmpty(), "Actual scroll allocation must allow a genuinely partial reference glyph")
        fun coreCount(column: Int) = (column until column + probe - 1).sumOf { x ->
            (0 until height).count { y -> pixel(reference, left + x, top + y) == foreground }
        }
        val chosenColumn = feasible.maxBy { coreCount(it) }
        assertTrue(
            coreCount(chosenColumn) >= c.metric("minimumLabelCorePixels"),
            "A reachable edge probe must cross actual solid reference glyph core, not a gap"
        )
        val cut = chosenColumn + probe
        val delta = referenceBox.left + cut - viewport.right
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(delta, 0f)) }
        settle()
        val partial = nativeBounds(text)
        val clipped = text.fetchSemanticsNode().boundsInRoot
        assertTrue(
            partial.left < viewport.right && partial.right > viewport.right + density,
            "Really place an unfocused label across the native clip: $partial in $viewport"
        )
        assertEquals(viewport.right, clipped.right, 1f)
        val current = frame.captureToImage().asAndroidBitmap()
        var cores = 0
        var attenuated = 0
        val actualCut = (viewport.right - partial.left).roundToInt()
        for (x in actualCut - probe until actualCut - 1) {
            for (y in 0 until height) {
                if (pixel(reference, left + x, top + y) != foreground) continue
                cores++
                val actual = pixel(current, partial.left.roundToInt() + x, partial.top.roundToInt() + y)
                if (channel(foreground) - channel(actual) >= c.metric("minimumEdgeAttenuationChannel")) attenuated++
            }
        }
        assertTrue(cores >= c.metric("minimumLabelCorePixels"), "Probe crosses actual solid glyph ink, not an interword gap")
        assertTrue(
            attenuated.toFloat() / cores >= c.metric("minimumAttenuatedCoreFraction"),
            "A partial unfocused label must have conservative edge paint, not hard-cut letters: attenuated=$attenuated/$cores"
        )
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(range.maxValue() - range.value(), 0f)) }
        settle()
    }

    /** One failure-only native frame and measured regions; no paint/geometry prerequisite is skipped. */
    private fun recordReferenceFailure(
        c: Profile,
        label: String,
        rowId: Int,
        image: android.graphics.Bitmap,
        origin: Rect,
        referenceBox: Rect
    ) {
        val directory = File(requireNotNull(System.getProperty("user.dir")), "build/reports/tv-whole-reference-diagnostic").apply {
            mkdirs()
        }
        fun rectangle(b: Rect) = org.json.JSONArray(listOf(b.left, b.top, b.right, b.bottom))
        fun sample(box: Rect): android.graphics.Bitmap? {
            val left = (box.left - origin.left).roundToInt().coerceIn(0, image.width)
            val top = (box.top - origin.top).roundToInt().coerceIn(0, image.height)
            val right = (box.right - origin.left).roundToInt().coerceIn(0, image.width)
            val bottom = (box.bottom - origin.top).roundToInt().coerceIn(0, image.height)
            return if (right > left &&
                bottom > top
            ) {
                android.graphics.Bitmap.createBitmap(image, left, top, right - left, bottom - top)
            } else {
                null
            }
        }
        fun statistics(box: Rect): org.json.JSONObject {
            val bitmap = sample(box)
            val pixels = buildList {
                if (bitmap != null) for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) add(bitmap.getPixel(x, y))
            }
            fun rgb(pixel: Int) = listOf((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
            return org.json.JSONObject().put("sampleBoundsInRoot", rectangle(box))
                .put("widthPx", bitmap?.width ?: 0).put("heightPx", bitmap?.height ?: 0).put("pixels", pixels.size)
                .put("nativeCorePixels", pixels.count { rgb(it).max() >= c.metric("minimumLabelCoreChannel") })
                .put(
                    "minimumRgbChannels",
                    org.json.JSONArray(
                        if (pixels.isEmpty()) {
                            emptyList<Int>()
                        } else {
                            (0..2).map { channel ->
                                pixels.minOf { rgb(it)[channel] }
                            }
                        }
                    )
                )
                .put(
                    "maximumRgbChannels",
                    org.json.JSONArray(
                        if (pixels.isEmpty()) {
                            emptyList<Int>()
                        } else {
                            (0..2).map { channel ->
                                pixels.maxOf { rgb(it)[channel] }
                            }
                        }
                    )
                )
                .put(
                    "argbModes",
                    org.json.JSONArray(
                        pixels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
                            .map { org.json.JSONObject().put("argb", "%08x".format(it.key)).put("pixels", it.value) }
                    )
                )
        }
        val owner = compose.onNode(SemanticsMatcher("Actual action scroll owner") { it.id == rowId })
        val range = owner.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        val target = action(label)
        val actions = org.json.JSONArray()
        for (name in labels(c)) {
            val button = action(name)
            val text = compose.onNode(hasAnyAncestor(hasTestTag("player:surface")) and hasText(name), useUnmergedTree = true)
            actions.put(
                org.json.JSONObject().put("label", name)
                    .put("focused", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
                    .put("actualDisabled", button.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
                    .put("buttonLayoutUnclipped", rectangle(targetLayoutBounds(button)))
                    .put("buttonRootTransformedUnclipped", rectangle(nativeBounds(button)))
                    .put("buttonSemanticsBoundsInRoot", rectangle(button.fetchSemanticsNode().boundsInRoot))
                    .put("textRootTransformedUnclipped", rectangle(nativeBounds(text)))
                    .put("textSemanticsBoundsInRoot", rectangle(text.fetchSemanticsNode().boundsInRoot))
                    .put("textPixels", statistics(nativeBounds(text)))
            )
        }
        val summary = org.json.JSONObject().put("referenceLabel", label)
            .put("density", density).put("fontScale", c.number("fontScale"))
            .put("windowPx", org.json.JSONArray(listOf(window.width, window.height)))
            .put("captureBoundsInRoot", rectangle(origin)).put("captureWidthPx", image.width).put("captureHeightPx", image.height)
            .put("viewportSemanticsBoundsInRoot", rectangle(owner.fetchSemanticsNode().boundsInRoot))
            .put("viewportRootTransformedUnclipped", rectangle(nativeBounds(owner)))
            .put("scrollValuePx", range.value()).put("scrollMaximumPx", range.maxValue())
            .put("referenceTextRootTransformedUnclipped", rectangle(referenceBox))
            .put("referenceButtonLayoutUnclipped", rectangle(targetLayoutBounds(target)))
            .put("referenceTextPixels", statistics(referenceBox)).put("referenceButtonPixels", statistics(targetLayoutBounds(target)))
            .put("minimumLabelCoreChannel", c.metric("minimumLabelCoreChannel")).put("actions", actions)
        listOf(
            "full-native-frame" to image,
            "reference-target" to sample(targetLayoutBounds(target)),
            "reference-text" to sample(referenceBox)
        )
            .forEach { (name, bitmap) ->
                if (bitmap !=
                    null
                ) {
                    File(directory, "$name.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        File(directory, "reference-paint.json").writeText(summary.toString(2))
    }

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(pixel: Int): Double {
            val channels = listOf((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255).map {
                val s = it / 255.0
                if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
            }
            return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
        }
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
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
