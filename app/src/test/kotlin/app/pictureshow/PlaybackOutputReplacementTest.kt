/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackOutputReplacementTest {
    @Test
    fun pausedSourceReplacementKeepsThePlatformTokenAndStopTargetsTheNewPlayer() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("server", "Test", "https://example.test", "12.0"))
        var stops = 0
        output.onStop = { stops++ }
        fun decision(id: String) = PlayDecision(
            "film",
            MediaSourceInfo(id = "source", runTimeTicks = 12_000_000_000),
            PlayMethod.Transcode,
            "https://example.test/Videos/film/stream.ts?PlaySessionId=$id",
            emptyMap(),
            id
        )
        fun handle(): PlaybackMediaSession.Handle {
            val field = Media3PlaybackOutput::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
            return field.get(output) as PlaybackMediaSession.Handle
        }
        try {
            output.open(decision("first"), 0, -1, -1, true)
            val first = handle()
            fun field(target: Any, name: String): Any = requireNotNull(
                target.javaClass.getDeclaredField(name)
                    .apply { isAccessible = true }.get(target)
            )
            val compat = field(field(field(first.session, "impl"), "sessionLegacyStub"), "sessionCompat")
            val platform = compat.javaClass.getMethod("getMediaSession").invoke(compat) as android.media.session.MediaSession
            assertTrue(platform.isActive)
            output.beginSeek()
            output.open(decision("second"), 120_000, -1, -1, true)
            val next = handle()
            assertEquals(first.session.platformToken, next.session.platformToken, "Paused seek must retain external controllers")
            assertTrue(platform.isActive, "Paused replacement must keep the platform session active")
            assertSame(output.player.value, (next.session.player as androidx.media3.common.ForwardingPlayer).wrappedPlayer)
            next.session.player.play()
            assertTrue(output.player.value!!.playWhenReady)
            next.session.player.stop()
            assertEquals(1, stops)
        } finally {
            output.release()
        }
    }
}
