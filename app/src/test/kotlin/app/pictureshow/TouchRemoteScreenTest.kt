/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.control.CommandState
import app.pictureshow.core.control.RemoteControllerState
import app.pictureshow.core.control.RemoteFollowUp
import app.pictureshow.core.control.RemoteNowPlaying
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.RemoteTrack
import app.pictureshow.core.control.ViewingIdentity
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The remote's controls take repeated taps: anything that moves them turns a second Forward into Previous, which seeks to 0.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h2400dp-port-xhdpi")
class TouchRemoteScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = RemoteNowPlaying(
        item = "item",
        title = "A film",
        durationTicks = 36_000_000_000,
        positionTicks = 600_000_000,
        canSeek = true,
        tracks = listOf(
            RemoteTrack("Audio", 1, "English"),
            RemoteTrack("Audio", 2, "Spanish"),
            RemoteTrack("Subtitle", 3, "English")
        )
    )
    private val tv = RemoteTarget(
        session = "s1",
        device = "tv",
        name = "Living room",
        viewer = "me",
        sameViewer = true,
        commands = listOf("MoveUp", "SendString"),
        now = now
    )
    private val host = RemoteControllerHost(
        CoroutineScope(Dispatchers.Unconfined),
        object : RemoteControllerGateway {
            override suspend fun targets(account: Account) = ClientResult.Success(emptyList<RemoteTarget>())
            override suspend fun send(account: Account, request: RemoteRequest) = 204
        }
    ) { 0L }
    private val controls = listOf(
        "remote:seek", "remote:previous", "remote:back", "remote:playpause", "remote:forward", "remote:next",
        "remote:stop", "remote:dpad-toggle", "remote:text", "remote:send", "remote:disconnect"
    )

    private fun attached(result: String?, playing: RemoteNowPlaying = now, fresh: Boolean = true) = RemoteControllerUi(
        RemoteControllerState(
            "server",
            "me",
            phase = "attached",
            target = tv.copy(now = playing),
            command = CommandState(ViewingIdentity("server", "me", "tv", "s1", 1), fresh = fresh),
            followUp = result?.let { RemoteFollowUp(1, "next", 0, result = it) }
        )
    )

    private fun bounds() = controls.associateWith { compose.onNodeWithTag(it).getBoundsInRoot() }

    private fun assertNothingMoves(fontScale: Float?) {
        var state by mutableStateOf(attached(null))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme { TouchRemoteScreen(state, host, {}, {}) }
            }
        }
        compose.waitForIdle()
        val start = bounds()
        listOf("sent", "unknown", "rejected", null).forEach { result ->
            compose.runOnIdle { state = attached(result) }
            compose.waitForIdle()
            if (result != null) compose.onNodeWithTag("remote:status").assertExists()
            assertEquals(start, bounds(), "status from result $result moved a control")
        }
        compose.runOnIdle { state = attached(null, fresh = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote:checking").assertExists()
        assertEquals(start, bounds(), "Checking moved a control")
        compose.runOnIdle { state = attached(null, now.copy(paused = true)) }
        compose.waitForIdle()
        assertEquals(start, bounds(), "pausing moved a control")
        compose.runOnIdle { state = attached(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote:text").performTextInput("x".repeat(300))
        compose.onNodeWithTag("remote:send").performClick()
        compose.waitForIdle()
        assertEquals(start, bounds(), "the text failure moved a control")
    }

    @Test fun noControlMovesWhenAStatusComesOrGoes() = assertNothingMoves(null)

    @Config(qualifiers = "w320dp-h2400dp-port-xhdpi")
    @Test
    fun noControlMovesAtALargeFontInANarrowWindow() = assertNothingMoves(1.5f)

    @Test fun everyStatusWordingHasItsRoomReserved() {
        listOf("next", "queue", "seek").forEach { op ->
            listOf("sent", "unknown", "rejected").forEach { result ->
                val wording = remoteCommandStatus(attached(null).controller!!.copy(followUp = RemoteFollowUp(1, op, 0, result = result)), 0)
                assertTrue(wording in REMOTE_STATUS_WORDINGS, "$op $result: $wording")
            }
        }
    }
}
