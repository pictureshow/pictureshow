/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real TouchPlayerContent → bound player → real interception → group gateway recorder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w640dp-h360dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncTouchSpeedContextRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val epoch = 1_762_776_000_000L
    private val sent = mutableListOf<String>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var playback: PlaybackHostState
    private var pauseFeedback = 0
    private var sync: SyncPlayHost? = null

    @After fun close() {
        compose.runOnIdle {
            if (::playback.isInitialized) playback.reportScope.cancel()
            scope.cancel()
            scheduler.runCurrent()
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }
    private fun vector(id: String): JsonObject {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-player-intent-presentation.json") }.first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
    }
    private fun launch(case: JsonObject, state: Int = Player.STATE_READY) {
        val input = case.getValue("input").jsonObject
        launch(
            input.getValue("participating").jsonPrimitive.boolean,
            input.getValue("phase").jsonPrimitive.content,
            !input.getValue("localShowPlayButton").jsonPrimitive.boolean,
            state
        )
    }
    private fun launch(grouped: Boolean, phase: String, nativePlaying: Boolean, state: Int = Player.STATE_READY) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(state, nativePlaying)
            if (grouped) {
                val gateway = object : SyncPlayGateway {
                    override suspend fun groups(account: Account) = ClientResult.Success(
                        listOf(
                            SyncGroup(
                                "g",
                                "Friday films",
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
                            put("GroupName", "Friday films")
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
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, playback) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
    }
    private fun expectedLabel(case: JsonObject) = if (case.getValue("expected").jsonObject.getValue("action").jsonPrimitive.content ==
        "pause"
    ) {
        "Pause"
    } else {
        "Play"
    }

    private fun openSpeed(id: String): JsonObject {
        val case = vector(id)
        launch(
            case.getValue("input").jsonObject.getValue("participating").jsonPrimitive.boolean,
            "Playing",
            false
        )
        compose.onNodeWithTag("touch:open:Speed").performScrollTo().performClick()
        compose.waitForIdle()
        return case.getValue("expected").jsonObject
    }

    @Test fun realGroupedSpeedContextDisablesSelectionAndExplainsAtPointOfUse() {
        val expected = openSpeed("speed-selection-grouped-True")
        assertEquals(false, expected.getValue("selectionAllowed").jsonPrimitive.boolean)
        assertEquals(true, expected.getValue("explanationRequired").jsonPrimitive.boolean)
        compose.onNodeWithTag("touch:speed:150").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_speed)).assertIsDisplayed()
        compose.runOnIdle {
            scheduler.runCurrent()
            assertTrue(sent.isEmpty())
            assertEquals(1f, output.speed.value.speed)
        }
    }

    @Test fun realStandaloneSpeedContextStillSelectsAndDismisses() {
        val expected = openSpeed("speed-selection-grouped-False")
        assertEquals(true, expected.getValue("selectionAllowed").jsonPrimitive.boolean)
        compose.onNodeWithTag("touch:speed:150").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1.5f, output.speed.value.speed)
            assertTrue(sent.isEmpty())
        }
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
    }

    @Test fun mountedSpeedContextTracksActualMembershipLeaving() {
        openSpeed("speed-selection-grouped-True")
        compose.onNodeWithTag("touch:speed:150").assertIsNotEnabled()
        compose.runOnIdle {
            requireNotNull(sync).leave()
            scheduler.runCurrent()
            sent.clear()
        }
        compose.waitForIdle()
        val expected = vector("speed-selection-grouped-False").getValue("expected").jsonObject
        assertEquals(true, expected.getValue("selectionAllowed").jsonPrimitive.boolean)
        compose.onNodeWithTag("touch:speed:150").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1.5f, output.speed.value.speed)
            assertTrue(sent.isEmpty())
        }
    }

    private class Decoder(state: Int, playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        var nativeIntentChanges = 0
        var prepares = 0
        var defaultSeeks = 0
        private var value = State.Builder().setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE
            ).build()
        ).setPlaylist(listOf(MediaItemData.Builder("film").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(state).setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(20_000).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            nativeIntentChanges++
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handlePrepare() = Futures.immediateVoidFuture().also {
            prepares++
            value = value.buildUpon().setPlaybackState(Player.STATE_READY).build()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            defaultSeeks++
            value = value.buildUpon().setContentPositionMs(positionMs).setPlaybackState(Player.STATE_READY).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
