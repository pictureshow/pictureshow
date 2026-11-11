/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the same host construction and renderer snapshot used by the TV and touch playback shells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SyncOfflineSourceTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private val local = mutableStateOf(true)
    private lateinit var host: PlaybackHostState
    private lateinit var output: Media3PlaybackOutput
    private val hosts = mutableSetOf<PlaybackHostState>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun close() {
        compose.runOnIdle {
            scopes.forEach { it.cancel() }
            hosts.forEach { it.reportScope.cancel() }
            if (::output.isInitialized) output.release()
        }
    }

    private fun mount(type: String = "Episode", downloaded: Boolean = true) {
        local.value = downloaded
        compose.runOnIdle { output = Media3PlaybackOutput(compose.activity, account.server) }
        val binding = OfflinePlaybackBinding(ReconnectLocalGateway(), emptyList())
        val handoff = OfflineHandoffStart("episode", PlaybackStartTracks("source", null, null)) { it }
        compose.setContent {
            // A completed handoff is a new destination/host, as in OfflineStreamPlayer.
            key(local.value) {
                CompositionLocalProvider(
                    LocalOfflinePlayback provides binding.takeIf { local.value },
                    LocalOfflineHandoff provides handoff.takeUnless { local.value }
                ) {
                    host = rememberPlaybackHostState(
                        account,
                        MediaItem("episode", "An episode", type),
                        0,
                        {},
                        {},
                        providedOutput = output
                    )
                    hosts += host
                }
            }
        }
        compose.waitForIdle()
    }

    /** Actual source/renderer facts at the adapter boundary; no decoder thread or networking is required. */
    private fun prepared(source: MediaSourceInfo = MediaSourceInfo(runTimeTicks = 600_000_000, protocol = "File", path = "/server/media")) {
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getPlaybackState" -> Player.STATE_READY
                "getDuration" -> 60_000L
                "isCurrentMediaItemSeekable" -> true
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Prepared source boundary"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 1f
                    else -> null
                }
            }
        } as Player
        host.playerSource = mutableStateOf(player)
        host.stateSource = mutableStateOf(
            PlaybackState(
                decision = PlayDecision("episode", source, PlayMethod.DirectPlay, "https://example.test/video", emptyMap(), "viewing"),
                preparing = false
            )
        )
        host.duration = 60_000
    }

    private fun assertGroupRequestsRefused() {
        val sent = mutableListOf<SyncRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val sync = SyncPlayHost(
            scope,
            object : SyncPlayGateway {
                override suspend fun access(account: Account) = ClientResult.Success("CreateAndJoinGroups")
                override suspend fun groups(account: Account) = ClientResult.Success(emptyList<SyncGroup>())
                override suspend fun time(account: Account): ClientResult<SyncUtcTime> =
                    error("An offline renderer cannot calibrate a group")
                override suspend fun send(account: Account, request: SyncRequest): Int {
                    sent += request
                    return 403
                }
            },
            wall = { 0L },
            monotonic = { 0L }
        )
        sync.bind(account)
        sync.socket(true)
        sync.refresh()
        assertTrue(sync.state.value.accessAllowed)
        sync.bindRenderer(
            Any(),
            object : SyncRenderer {
                override fun snapshot() = host.syncRendererSnapshot()
                override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double) = false
            }
        )
        sync.create("Evening")
        sync.join("group")
        assertTrue(sent.isEmpty(), "Neither Create nor Join may dispatch for this local source")
        assertTrue(sync.state.value.status.contains("offline", ignoreCase = true))
        assertFalse(sync.participating)
    }

    @Test fun localProvenanceExistsBeforeNegotiationAndBlocksBothGroupRequests() {
        mount()
        compose.runOnIdle {
            assertTrue(host.localCopy)
            assertTrue(host.state.preparing)
            assertFalse(host.syncRendererSnapshot().seekable)
            assertGroupRequestsRefused()
        }
    }

    @Test fun aFiniteReadySeekableDownloadStillCannotCreateOrJoin() {
        mount()
        compose.runOnIdle {
            prepared()
            val snapshot = host.syncRendererSnapshot()
            assertTrue(snapshot.ready)
            assertEquals(600_000_000L, snapshot.durationTicks)
            assertFalse(snapshot.seekable, "Physical seeking and a finite duration do not authorize a local copy")
            assertGroupRequestsRefused()
        }
    }

    @Test fun convertedAudioDownloadsUseTheSameLocalSourceRestriction() {
        mount(type = "Audio")
        compose.runOnIdle {
            prepared()
            assertTrue(host.syncRendererSnapshot().ready)
            assertFalse(host.syncRendererSnapshot().seekable)
            assertGroupRequestsRefused()
        }
    }

    @Test fun aFreshOnlineHandoffDoesNotInheritThePreviousDownloadRestriction() {
        mount()
        val download = host
        compose.runOnIdle {
            prepared()
            assertFalse(host.syncRendererSnapshot().seekable)
            local.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame(download, host)
            assertTrue(download.localCopy)
            assertFalse(host.localCopy)
            assertFalse(host.syncRendererSnapshot().ready, "The new server source must prepare independently")
            prepared()
            assertTrue(host.syncRendererSnapshot().seekable, "The same cached item/source metadata is eligible when streamed online")
        }
    }

    @Test fun onlineServerFileMetadataRemainsEligibleButLiveAndInfiniteSourcesDoNot() {
        mount(downloaded = false)
        compose.runOnIdle {
            prepared()
            assertFalse(host.localCopy)
            assertTrue(host.syncRendererSnapshot().seekable)
            prepared(MediaSourceInfo(runTimeTicks = 600_000_000, liveStreamId = "live"))
            assertFalse(host.syncRendererSnapshot().seekable)
            prepared(MediaSourceInfo(runTimeTicks = 600_000_000, isInfiniteStream = true))
            assertFalse(host.syncRendererSnapshot().seekable)
        }
    }
}
