/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import com.google.common.util.concurrent.Futures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the real host's immediate and deferred native-seek paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackSeekFeedbackTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    private class LocalPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var seekable = true
        private var position = 1_000L
        private var playing = false
        override fun getState(): State = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().apply {
                    add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    add(Player.COMMAND_GET_TIMELINE)
                    add(Player.COMMAND_RELEASE)
                    add(Player.COMMAND_PLAY_PAUSE)
                    if (seekable) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }.build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("entry")
                        .setMediaItem(androidx.media3.common.MediaItem.fromUri("https://media.example.test/video"))
                        .setDurationUs(600_000_000).setIsSeekable(true).build()
                )
            )
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(position)
            .build()
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks += positionMs
            position = positionMs.takeUnless { it == C.TIME_UNSET } ?: 0
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also { playing = playWhenReady }
        fun moveTo(value: Long) {
            position = value
            invalidateState()
        }
        fun allowSeek(value: Boolean) {
            seekable = value
            invalidateState()
        }
    }

    @Test fun automaticAlignmentDoesNotOpenOrReplaceTheViewersSeekPreview() {
        for (prior in listOf(null, 1_234L)) {
            val output =
                Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://media.example.test", "12"))
            val player = LocalPlayer()
            try {
                val host = PlaybackHostState(MediaItem("item", "Film"), 0, output, {}, {})
                host.playerSource = mutableStateOf(player)
                host.seekPreview = prior
                var revealed = 0
                host.onSeekShown = { revealed++ }
                host.seekToLocal(7_000, revealControls = false)
                assertEquals(listOf(7_000L), player.seeks)
                assertEquals(7_000L, host.position)
                assertEquals(prior, host.seekPreview, "automatic alignment must not create or overwrite user seek feedback")
                assertEquals(0, revealed)
                assertNull(host.pendingSeek)
            } finally {
                player.release()
                output.release()
            }
        }
    }

    @Test fun aCorrectionResamplesLivePositionAndAdvancesItsTargetBeforeDecidingToSeek() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://media.example.test", "12"))
        val player = LocalPlayer()
        try {
            val host = PlaybackHostState(MediaItem("item", "Film"), 0, output, {}, {})
            host.playerSource = mutableStateOf(player)
            host.duration = 600_000
            player.moveTo(36_983)
            // The host's older36.917s sample was117.8569ms behind37.0348569s.
            // Fifteen milliseconds later the native position is36.983s: the
            // moving target is37.0498569s, now inside the80ms execution band.
            val changed = host.applySyncTimeline(370_348_569, true, 1.0, 0, executionNow = { 15 })
            assertEquals(false, changed)
            assertEquals(emptyList(), player.seeks)
            assertEquals(true, player.playWhenReady)
            assertNull(host.seekPreview)
            // Explicit group transport retains its separate authoritative-position behavior.
            assertEquals(true, host.applySyncTimeline(370_348_569, true, 1.0))
            assertEquals(listOf(37_034L), player.seeks)
            assertNull(host.seekPreview)
        } finally {
            player.release()
            output.release()
        }
    }

    @Test fun deferredSeekReportsActualApplicationEvenWhenNoBufferingSampleExists() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://media.example.test", "12"))
        val player = LocalPlayer()
        try {
            val host = PlaybackHostState(MediaItem("item", "Film"), 0, output, {}, {})
            host.playerSource = mutableStateOf(player)
            var applications = 0
            val listener = syncPositionApplicationListener { applications++ }
            player.addListener(listener)
            player.allowSeek(false)
            assertEquals(false, host.applySyncTimeline(70_000_000, false, 1.0))
            assertEquals(0, applications)
            assertEquals(7_000L, host.pendingSeek)
            player.allowSeek(true)
            host.applyPendingSeek()
            android.os.Looper.getMainLooper().let { org.robolectric.Shadows.shadowOf(it).idle() }
            assertEquals(listOf(7_000L), player.seeks)
            assertEquals(1, applications, "actual native completion must be observed even while both sampled states are READY")
            assertNull(host.pendingSeek)
            assertNull(host.seekPreview)
            player.removeListener(listener)
        } finally {
            player.release()
            output.release()
        }
    }

    @Test fun deferredServerAlignmentRetainsItsBypassInsteadOfEchoingAGroupSeek() {
        val vectors = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it, "spec/vectors/sync-application.json") }.first { it.isFile }
        val cases = Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(3, cases.size)
        for (value in cases) {
            val row = value.jsonObject
            val id = row.getValue("id").jsonPrimitive.content
            assertEquals("deferred-seek", row.getValue("operation").jsonPrimitive.content, id)
            val target = row.getValue("targetMs").jsonPrimitive.long
            val output =
                Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://media.example.test", "12"))
            val native = LocalPlayer()
            val exposed = SyncTransportPlayer(native, output::interceptSync)
            val requests = mutableListOf<Pair<String, Long?>>()
            output.syncRequest = { command, position ->
                requests += command to position
                true
            }
            try {
                val host = PlaybackHostState(MediaItem("item", "Film"), 0, output, {}, {})
                host.playerSource = mutableStateOf(exposed)
                native.allowSeek(false)
                if (row.getValue("source").jsonPrimitive.content == "server") {
                    host.applySyncTimeline(target * 10_000, false, 1.0)
                } else {
                    host.seekToLocal(target, revealControls = false)
                }
                assertEquals(target, host.pendingSeek, id)
                assertEquals(emptyList(), requests, id)
                native.allowSeek(true)
                host.applyPendingSeek()
                assertEquals(row.getValue("expectedNativeSeeks").jsonArray.map { it.jsonPrimitive.long }, native.seeks, id)
                val expectedRequests: List<Pair<String, Long?>> = if (row.getValue(
                        "expectedGroupSeekAfterCompletion"
                    ).jsonPrimitive.boolean
                ) {
                    listOf("seek" to target)
                } else {
                    emptyList()
                }
                assertEquals(expectedRequests, requests, id)
                assertNull(host.seekPreview, id)
                val next = row.getValue("nextViewerSeekMs").jsonPrimitive.long
                exposed.seekTo(next)
                assertEquals(expectedRequests + ("seek" to next), requests, id)
                assertEquals(row.getValue("expectedNativeSeeks").jsonArray.map { it.jsonPrimitive.long }, native.seeks, id)
            } finally {
                exposed.release()
                output.release()
            }
        }
    }

    @Test fun deferredAutomaticAlignmentRetainsItsFeedbackIntentUntilSeekingIsAvailable() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://media.example.test", "12"))
        val player = LocalPlayer()
        try {
            val host = PlaybackHostState(MediaItem("item", "Film"), 0, output, {}, {})
            host.playerSource = mutableStateOf(player)
            host.seekPreview = 1_234
            player.allowSeek(false)
            host.seekToLocal(7_000, revealControls = false)
            assertEquals(7_000L, host.pendingSeek)
            assertEquals(1_234L, host.seekPreview)
            player.allowSeek(true)
            host.applyPendingSeek()
            assertEquals(listOf(7_000L), player.seeks)
            assertEquals(1_234L, host.seekPreview, "deferred completion must preserve the automatic intent")
            host.seekToLocal(8_000)
            assertEquals(8_000L, host.seekPreview, "a subsequent deliberate seek still gets feedback")
        } finally {
            player.release()
            output.release()
        }
    }
}
