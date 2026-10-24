/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import app.pictureshow.core.MediaItem
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackPublicationTest {
    private val decision = PlayDecision(
        "m",
        MediaSourceInfo(id = "source"),
        PlayMethod.DirectPlay,
        "https://media.example.test/video",
        emptyMap(),
        "session"
    )

    @Test fun initialAndReplacementSessionsAlreadyHaveFactualMetadata() {
        val output = PublishingOutput()
        var item = MediaItem("m", "A Story", "Episode", seriesName = "A Series", parentIndexNumber = 2, indexNumber = 3)
        val decorated = MetadataPlaybackOutput(output) { playbackMetadata(item) }
        try {
            decorated.open(decision, 12_345, 2, -1, false)
            assertEquals("A Series", output.published.last().artist.toString())
            assertTrue(output.published.last().title.toString().contains("A Story"))
            assertEquals(12_345, output.position().milliseconds)
            assertEquals(false, output.position().paused)
            val token = output.handle?.session?.token
            item = MediaItem("m", "A Film", "Movie")
            decorated.open(decision, 45_678, 2, -1, true)
            assertEquals("Movie", output.published.last().artist.toString())
            assertEquals("A Film", output.published.last().title.toString())
            assertEquals(token, output.handle?.session?.token)
            assertEquals(45_678, output.position().milliseconds)
            assertTrue(output.position().paused)
            assertEquals(decision.url, output.players.last().currentMediaItem?.localConfiguration?.uri.toString())
        } finally {
            decorated.release()
        }
    }

    @Test fun failedOpeningDoesNotLeakMetadataIntoAnotherSession() {
        val output = PublishingOutput()
        val decorated = MetadataPlaybackOutput(output) { playbackMetadata(MediaItem("m", "A Film", "Movie")) }
        try {
            output.fail = true
            assertFailsWith<IllegalStateException> { decorated.open(decision, 0, 2, -1, false) }
            output.fail = false
            output.open(decision, 0, 2, -1, false)
            assertNull(output.published.single().artist)
        } finally {
            output.release()
        }
    }

    private class PublishingOutput : PlaybackOutput {
        val players = mutableListOf<ExoPlayer>()
        val published = mutableListOf<MediaMetadata>()
        var handle: PlaybackMediaSession.Handle? = null
        var fail = false
        override fun position() = players.last().let { PlaybackPosition(it.currentPosition, !it.playWhenReady) }
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            check(!fail)
            val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            players += player
            player.setMediaItem(PlayerItem.fromUri(decision.url))
            player.seekTo(positionMs)
            player.playWhenReady = !paused
            val existing = handle
            if (existing == null) {
                handle = PlaybackMediaSession.open(RuntimeEnvironment.getApplication(), player)
            } else {
                existing.replacePlayer(player)
            }
            published += requireNotNull(handle).session.player.mediaMetadata
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() = players.last().pause()
        override fun release() {
            handle?.close()
            players.forEach(ExoPlayer::release)
        }
    }
}
