/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.session.MediaSession
import app.pictureshow.core.*
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual TV entry, real PlaybackHostEffects/SyncPlaybackBinding, real wrapped ExoPlayer and gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncTvPlayerIntentRegressionTest {
    @get:Rule(order = -2) val timeLimit = PerTestTimeLimit(180)
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
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { nativeChanges += playWhenReady }
    }

    @After fun close() {
        compose.runOnIdle {
            refreshRelease.complete(Unit)
            if (::native.isInitialized) native.removeListener(nativeListener)
            scope.cancel()
            scheduler.runCurrent()
            if (::output.isInitialized) output.release()
        }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    private fun vector(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-player-intent-presentation.json") }.first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
    }
    private fun update(type: String, data: JsonObject) = group.receive(buildJsonObject {
        put("MessageType", "SyncPlayGroupUpdate")
        put("Data", buildJsonObject { put("GroupId", "g"); put("Type", type); put("Data", data) })
    }.toString())
    private fun expectedLabel(case: JsonObject): String = when (case.getValue("expected").jsonObject.getValue("action").jsonPrimitive.content) {
        "pause" -> "Pause"
        "play" -> "Play"
        else -> error("This case has no enabled primary action")
    }
    /** Find the actual primary independently of which of its two labels the product chose. */
    private fun primary() = compose.onNode(
        hasAnyAncestor(hasTestTag("player:surface")) and hasClickAction() and (hasText("Play") or hasText("Pause"))
    )
    private fun transportSent() = sent.filter { it in setOf("Pause", "Unpause", "Stop") }
    private fun launch(id: String): JsonObject {
        val case = vector(id)
        val input = case.getValue("input").jsonObject
        val participating = input.getValue("participating").jsonPrimitive.boolean
        val phase = input.getValue("phase").jsonPrimitive.content
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
            override suspend fun send(account: Account, request: SyncRequest): Int { sent += request.operation; return 204 }
        }
        compose.runOnIdle {
            group = SyncPlayHost(scope, gateway, { epoch + scheduler.currentTime }, { scheduler.currentTime })
            group.bind(account); group.socket(true); group.refresh(); scheduler.runCurrent()
            if (participating) {
                group.join("g"); scheduler.runCurrent()
                update("GroupJoined", buildJsonObject {
                    put("GroupId", "g"); put("GroupName", "Friday films"); put("State", phase)
                    put("Participants", JsonArray(emptyList())); put("LastUpdatedAt", syncUtcTimestamp(epoch))
                })
                update("PlayQueue", buildJsonObject {
                    put("Reason", "NewPlaylist"); put("LastUpdate", syncUtcTimestamp(epoch))
                    put("Playlist", JsonArray((0 until entries).map {
                        buildJsonObject { put("ItemId", "film"); put("PlaylistItemId", "entry-a") }
                    }))
                    put("PlayingItemIndex", 0); put("StartPositionTicks", 200_000_000L)
                    put("IsPlaying", phase == "Playing"); put("RepeatMode", "RepeatNone"); put("ShuffleMode", "Sorted")
                })
                scheduler.runCurrent()
            }
        }
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) { engine { addHandler { request ->
            val json = when {
                request.url.encodedPath.endsWith("/PlaybackInfo") ->
                    """{"PlaySessionId":"viewing","MediaSources":[{"Id":"source","RunTimeTicks":600000000,"SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls","TranscodingUrl":"/Videos/film/master.m3u8","MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"}]}]}"""
                request.url.encodedPath == "/Items/film" -> """{"Id":"film","Name":"A film","Type":"Movie"}"""
                request.url.encodedPath.startsWith("/MediaSegments") -> """{"Items":[]}"""
                else -> return@addHandler respond("", HttpStatusCode.NoContent)
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        } } }
        startKoin { modules(module {
            single { group }; single { inertRemoteTarget() }; single { http }
            single { DeviceIdentityStore(store) }; single { PlaybackPreferencesStore(store) }
        }) }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        compose.setContent { PictureshowTheme {
            PlayerItemScreen(account, MediaItem("film", "A film", "Movie"), 0, catalog, {}, {}, providedOutput = output)
        } }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            output.player.value?.playbackState == Player.STATE_BUFFERING
        }
        compose.waitForIdle()
        compose.runOnIdle {
            scheduler.runCurrent()
            native = requireNotNull(output.player.value)
            output.applySync { native.playWhenReady = !localShowPlay }
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player:surface").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            if (!usable) { holdRefresh = true; group.refresh(); scheduler.runCurrent() }
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
            output.onSyncPauseRequested = { pauseFeedback++; feedback() }
            native.addListener(nativeListener)
            sent.clear(); nativeChanges.clear(); pauseFeedback = 0
        }
        primary().assertIsDisplayed()
        return case
    }
    private fun remoteClick() {
        primary().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        primary().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }
    private fun assertGroupedRequest(operation: String, nativePlaying: Boolean, feedback: Int) {
        compose.runOnIdle {
            assertEquals(feedback, pauseFeedback, "Immediate interception feedback precedes the group scheduler")
            assertEquals(nativePlaying, native.playWhenReady, "Group action cannot flip the held local decoder")
            assertTrue(nativeChanges.isEmpty(), "No raw native intent transition: $nativeChanges")
            scheduler.runCurrent()
            assertEquals(listOf(operation), transportSent(), "Actual group gateway transport")
        }
    }

    @Test fun groupedPlayingNativeHoldOffersPause() {
        val case = launch("group-playing-local-True")
        primary().assertTextContains(expectedLabel(case))
    }
    @Test fun groupedPlayingNativeHoldRequestsPauseWithoutNativeFlipAndShowsImmediateFeedback() {
        launch("group-playing-local-True"); remoteClick(); assertGroupedRequest("Pause", false, 1)
    }
    @Test fun groupedPausedNativeRunningOffersPlay() {
        val case = launch("group-paused-local-False")
        primary().assertTextContains(expectedLabel(case))
    }
    @Test fun groupedPausedNativeRunningRequestsUnpauseWithoutNativeFlip() {
        launch("group-paused-local-False"); remoteClick(); assertGroupedRequest("Unpause", true, 0)
    }
    @Test fun unusableGroupDisablesActualTvPrimaryAction() {
        launch("group-unavailable-False-1"); primary().assertIsNotEnabled()
    }
    @Test fun emptyGroupQueueDisablesActualTvPrimaryAction() {
        launch("group-unavailable-True-0"); primary().assertIsNotEnabled()
    }
    /** Inspect the already opened output handle; never open a replacement session or install a fake resolver. */
    private fun mediaToggle(action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) {
        compose.runOnIdle {
            val handle = Media3PlaybackOutput::class.java.getDeclaredField("mediaSession")
                .apply { isAccessible = true }.get(output) as PlaybackMediaSession.Handle
            assertEquals(native.playWhenReady, handle.session.player.playWhenReady, "Actual session wrapper must expose the mounted output intent")
            val caller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME, 123, 2000,
                MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION, 0, false, Bundle.EMPTY, false
            )
            assertTrue(AnonymousMediaButtons.onMediaButtonEvent(handle.session, caller,
                Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT,
                    KeyEvent(0, 0, action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat))))
        }
    }
    @Test fun actualMediaTogglePlayingNativeHoldRequestsPause() {
        launch("group-playing-local-True"); mediaToggle(); assertGroupedRequest("Pause", false, 1)
    }
    @Test fun actualMediaTogglePausedNativeRunningRequestsUnpause() {
        launch("group-paused-local-False"); mediaToggle(); assertGroupedRequest("Unpause", true, 0)
    }
    /** Real decoder intentionally remains buffering, independently of the authoritative group timeline. */
    private class WaitingSource : DataSource {
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            manifest = if (dataSpec.uri.path?.endsWith(".m3u8") == true)
                ("#EXTM3U\n#EXT-X-TARGETDURATION:61\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                    "#EXTINF:60.008,\nsegment.ts\n#EXT-X-ENDLIST\n").byteInputStream() else null
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            manifest?.let { return it.read(buffer, offset, length) }
            return try { Thread.sleep(Long.MAX_VALUE); C.RESULT_END_OF_INPUT }
            catch (_: InterruptedException) { throw InterruptedIOException() }
        }
        override fun close() = Unit
    }
}
