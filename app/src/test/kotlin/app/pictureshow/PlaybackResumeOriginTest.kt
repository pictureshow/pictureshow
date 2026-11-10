/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.net.Uri
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.playback.PlaybackTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackResumeOriginTest {
    @Test fun receiverClockAndExplicitRemotePositionRemainExactWhileSavedResumeRewinds() {
        val item = MediaItem("item", "Film", userData = ItemUserData(playbackPositionTicks = 900_000_000L))
        val server = Server("s", "Server", "https://example.test", "12.0.0")
        val context = RuntimeEnvironment.getApplication()
        // Cast's local-start navigation uses the explicit-position overload, as does an explicit controller/deep link.
        val routes = listOf(playerRoute(item, 90_000L) to 90_000L, playerRoute(item, true) to 60_000L, playerRoute(item, false) to 0L)
        for ((route, expected) in routes) {
            val args = Uri.parse(route)
            val host = PlaybackHostState(
                item,
                args.getQueryParameter("start")!!.toLong(),
                Media3PlaybackOutput(context, server),
                {},
                {},
                startOrigin = playbackStartOrigin(args.getQueryParameter("savedResume").toBoolean())
            )
            try {
                host.localPreferences = PlaybackPreferences(tuning = PlaybackTuning(rewindSeconds = 30))
                assertEquals(expected, host.initialPositionMs, route)
            } finally {
                host.reportScope.cancel()
            }
        }
    }
}
