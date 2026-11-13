/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.pictureshow

import android.app.Application
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncDepartureCause
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** PROPOSAL: actual finish() integration, not a fake mapping helper or a decoder/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackHostDepartureTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    @Test fun actualPlaybackHostFinishClassifiesExitAndDoesNotDuplicateLeave() = runTest {
        // These are actual current callers' reason strings; fallback must not expose its diagnostic string.
        val reasons = listOf(
            "stop-button" to SyncDepartureCause.PLAYBACK_STOPPED,
            "remote-stop" to SyncDepartureCause.PLAYBACK_STOPPED,
            "system-stop" to SyncDepartureCause.PLAYBACK_STOPPED,
            "natural-end" to SyncDepartureCause.PLAYBACK_ENDED,
            "back" to SyncDepartureCause.PLAYBACK_CLOSED,
            "error-back" to SyncDepartureCause.PLAYBACK_CLOSED,
            "back-during-next" to SyncDepartureCause.PLAYBACK_CLOSED,
            "opaque-internal-exit-code" to SyncDepartureCause.PLAYBACK_CLOSED
        )
        for ((reason, expected) in reasons) {
            val account = Account(Server("s", "Fixture", "https://media.example.test", "12"), User("a", "Ana"), "test-token")
            val sent = mutableListOf<String>()
            val epoch = 1_762_776_000_000L
            val gateway = object : SyncPlayGateway {
                override suspend fun groups(account: Account) = ClientResult.Success(
                    listOf(SyncGroup("g", "Evening", "Paused", emptyList(), epoch * 10_000)))
                override suspend fun access(account: Account) = ClientResult.Success("CreateAndJoinGroups")
                override suspend fun time(account: Account) = ClientResult.Success(SyncUtcTime(epoch, epoch))
                override suspend fun send(account: Account, request: SyncRequest): Int { sent += request.operation; return 204 }
            }
            val sync = SyncPlayHost(backgroundScope, gateway, { epoch + testScheduler.currentTime }, { testScheduler.currentTime })
            val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), account.server)
            var exits = 0
            val playback = PlaybackHostState(MediaItem("film", "Film"), 0, output, {}, { exits++ }).also { it.sync = sync }
            try {
                sync.bind(account); sync.socket(true); sync.refresh(); runCurrent()
                sync.join("g"); runCurrent()
                sync.receive(buildJsonObject {
                    put("MessageType", "SyncPlayGroupUpdate")
                    put("Data", buildJsonObject {
                        put("GroupId", "g"); put("Type", "GroupJoined")
                        put("Data", buildJsonObject {
                            put("GroupId", "g"); put("GroupName", "Evening"); put("State", "Paused")
                            put("Participants", JsonArray(emptyList())); put("LastUpdatedAt", syncUtcTimestamp(epoch))
                        })
                    })
                }.toString())
                assertTrue(sync.participating)
                sent.clear()
                playback.finish(reason = reason); runCurrent()
                val fact = assertNotNull(sync.state.value.departure, reason)
                assertEquals(expected, fact.cause, reason)
                assertEquals("Evening", fact.groupName, reason)
                assertEquals(1, exits, reason)
                assertEquals(listOf("Leave"), sent, reason)
                playback.finish(reason = reason); runCurrent()
                assertEquals(fact, sync.state.value.departure, reason)
                assertEquals(1, exits, reason)
                assertEquals(listOf("Leave"), sent, reason)
            } finally {
                playback.reportScope.cancel()
                output.release()
                sync.bind(null)
                runCurrent()
            }
        }
    }
}
