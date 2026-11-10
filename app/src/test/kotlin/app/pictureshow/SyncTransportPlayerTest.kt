/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SyncTransportPlayerTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    /** A delegate with observable calls, rather than a second implementation of the transport policy. */
    private class Output {
        var position = 30_000L
        var duration = 120_000L
        val mutations = mutableListOf<String>()
        val defaultPositionIndices = mutableListOf<Int?>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentPosition" -> position

                "getDuration" -> duration

                "getSeekBackIncrement", "getSeekForwardIncrement" -> 10_000L

                "getApplicationLooper" -> android.os.Looper.getMainLooper()

                "getPlaybackParameters" -> PlaybackParameters.DEFAULT

                "seekToDefaultPosition" -> {
                    mutations += method.name
                    defaultPositionIndices += args?.firstOrNull() as? Int
                    null
                }

                "equals" -> proxy === args?.firstOrNull()

                "hashCode" -> System.identityHashCode(proxy)

                "toString" -> "Synthetic SyncPlay player"

                else -> {
                    if (method.name in mutationsNames) mutations += method.name
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 1f
                        java.lang.Double.TYPE -> 1.0
                        else -> null
                    }
                }
            }
        } as Player
        companion object {
            val mutationsNames = setOf(
                "play", "pause", "setPlayWhenReady", "seekTo", "seekBack", "seekForward", "seekToNext", "seekToNextMediaItem",
                "seekToPrevious", "seekToPreviousMediaItem", "stop", "setPlaybackSpeed", "setPlaybackParameters"
            )
        }
    }

    @Test fun participatingTransportNeverMutatesTheDecoderBeforeTheServerResponse() {
        val output = Output()
        val requests = mutableListOf<Pair<String, Long?>>()
        val player = SyncTransportPlayer(output.player) { command, position ->
            requests += command to position
            true
        }
        player.play()
        player.pause()
        player.playWhenReady = true
        player.playWhenReady = false
        player.seekTo(45_000)
        player.seekTo(0, 60_000)
        player.seekBack()
        player.seekForward()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.stop()
        player.setPlaybackSpeed(1.5f)
        player.playbackParameters = PlaybackParameters(0.5f)
        assertEquals(
            listOf(
                "play" to null, "pause" to null, "play" to null, "pause" to null,
                "seek" to 45_000L, "seek" to 60_000L, "seek" to 20_000L, "seek" to 40_000L,
                "next" to null, "next" to null, "previous" to null, "previous" to null,
                "stop" to null, "speed" to null, "speed" to null
            ),
            requests
        )
        assertTrue(output.mutations.isEmpty(), output.mutations.toString())
    }

    @Test fun leavingRestoresOrdinaryLocalTransportWithoutRetainingQueuedGroupInput() {
        val output = Output()
        var grouped = true
        val requests = mutableListOf<String>()
        val player = SyncTransportPlayer(output.player) { command, _ ->
            if (grouped) requests += command
            grouped
        }
        player.pause()
        assertEquals(listOf("pause"), requests)
        assertTrue(output.mutations.isEmpty())
        grouped = false
        player.play()
        player.pause()
        player.seekTo(10_000)
        player.stop()
        assertEquals(listOf("play", "pause", "seekTo", "stop"), output.mutations)
        assertEquals(listOf("pause"), requests)
    }

    @Test fun localPlaybackReceivesEveryUnconsumedTransportCall() {
        val output = Output()
        val player = SyncTransportPlayer(output.player) { _, _ -> false }
        player.playWhenReady = true
        player.seekTo(0, 20_000)
        player.seekBack()
        player.seekForward()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        player.setPlaybackSpeed(1.5f)
        player.playbackParameters = PlaybackParameters(0.5f)
        assertEquals(
            listOf(
                "setPlayWhenReady", "seekTo", "seekBack", "seekForward", "seekToNext", "seekToNextMediaItem",
                "seekToPrevious", "seekToPreviousMediaItem", "setPlaybackSpeed", "setPlaybackParameters"
            ),
            output.mutations
        )
    }

    @Test fun serverApplicationCanUseTheUnderlyingOutputWithoutEchoingARequest() {
        val output = Output()
        val requests = mutableListOf<String>()
        val player = SyncTransportPlayer(output.player) { command, _ ->
            requests += command
            true
        }
        assertEquals(30_000L, player.currentPosition)
        output.player.seekTo(50_000)
        output.player.play()
        assertEquals(listOf("seekTo", "play"), output.mutations)
        assertTrue(requests.isEmpty())
    }

    @Test fun backwardSeekNeverSendsANegativePosition() {
        val output = Output().apply { position = 2_000L }
        val requests = mutableListOf<Long?>()
        val player = SyncTransportPlayer(output.player) { _, position ->
            requests += position
            true
        }
        player.seekBack()
        assertEquals(listOf<Long?>(0L), requests)
        assertFalse(output.mutations.isNotEmpty())
    }

    @Test fun bothDefaultPositionOverloadsRequestTheSharedEntryStartWithoutLocalMutation() {
        val output = Output()
        val requests = mutableListOf<Pair<String, Long?>>()
        val player = SyncTransportPlayer(output.player) { command, position ->
            requests += command to position
            true
        }
        player.seekToDefaultPosition()
        player.seekToDefaultPosition(2)
        assertEquals(listOf<Pair<String, Long?>>("seek" to 0L, "seek" to 0L), requests)
        assertTrue(output.mutations.isEmpty())
        assertTrue(output.defaultPositionIndices.isEmpty())
    }

    @Test fun ungroupedDefaultPositionCallsDelegateTheirOriginalOverloads() {
        val output = Output()
        val player = SyncTransportPlayer(output.player) { _, _ -> false }
        player.seekToDefaultPosition()
        player.seekToDefaultPosition(2)
        assertEquals(listOf("seekToDefaultPosition", "seekToDefaultPosition"), output.mutations)
        assertEquals(listOf<Int?>(null, 2), output.defaultPositionIndices)
    }
}
