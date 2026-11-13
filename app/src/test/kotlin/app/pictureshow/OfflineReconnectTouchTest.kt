/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The same offer in the touch sheet, by tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class OfflineReconnectTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var host: PlaybackHostState
    private lateinit var fixture: ReconnectFixture
    private var exits = 0
    private var binding: OfflinePlaybackBinding? = null

    @After fun release() {
        compose.runOnIdle { if (::output.isInitialized) output.release() }
    }

    private fun launch(network: String = "unmetered", offline: Boolean = true, configure: ReconnectFixture.() -> Unit = {}) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            host = PlaybackHostState(MediaItem("episode", "An episode"), 20_000, output, {}, { exits++ })
            val decision = PlayDecision("episode", reconnectLocalSource, PlayMethod.DirectPlay, "file:///media", emptyMap(), null)
            host.stateSource = mutableStateOf(PlaybackState(decision = decision, preparing = false, audioIndex = 1, subtitleIndex = -1))
            host.duration = 60_000
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            fixture = remember { ReconnectFixture(scope, network).apply(configure) }
            binding = remember {
                if (offline) {
                    OfflinePlaybackBinding(
                        ReconnectLocalGateway(),
                        emptyList<File>(),
                        offlineUndownloadedStreams(reconnectEntry),
                        fixture.reconnect
                    )
                } else {
                    null
                }
            }
            CompositionLocalProvider(LocalOfflinePlayback provides binding) {
                MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, host) }
            }
        }
        compose.onNodeWithTag("touch:open:Audio").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("touch:sheet:Audio").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun await(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun sheetGone() = compose.waitUntil(5_000) { compose.onAllNodesWithTag("touch:sheet:Audio").fetchSemanticsNodes().isEmpty() }

    @Test fun `rows mark what needs a connection`() {
        launch()
        compose.onNodeWithTag("touch:track:6").assertTextContains("Japanese", substring = true)
        compose.onNodeWithTag("touch:track:6").assertTextContains("needs a connection", substring = true)
        compose.onNodeWithTag("touch:track:1").assertTextContains("English", substring = true)
        compose.onNodeWithTag("touch:track:1").assert(hasText("needs a connection", substring = true).not())
    }

    @Test fun `an online player's sheet has no such rows`() {
        launch(offline = false)
        compose.onNodeWithTag("touch:track:1").assertIsDisplayed()
        compose.onNodeWithTag("touch:track:6").assertDoesNotExist()
    }

    @Test fun `tapping one offers the choice in the sheet, and staying closes it with nothing changed`() {
        launch()
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:stream")
        compose.onNodeWithTag("reconnect:message").assertTextContains("Japanese isn't in this download", substring = true)
        compose.onNodeWithTag("reconnect:stay").performClick()
        sheetGone()
        compose.runOnIdle {
            assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state)
            assertTrue(fixture.settles.isEmpty())
            assertEquals(false, host.exitRequested)
        }
    }

    @Test fun `a metered connection is named in the offer`() {
        launch(network = "metered")
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:stream")
        compose.onNodeWithTag("reconnect:message").assertTextContains("mobile data", substring = true)
    }

    @Test fun `no connection says so and OK closes`() {
        launch(network = "none")
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:ok")
        compose.onNodeWithTag("reconnect:message").assertTextContains("no connection", substring = true)
        compose.onNodeWithTag("reconnect:stream").assertDoesNotExist()
        compose.onNodeWithTag("reconnect:ok").performClick()
        sheetGone()
        compose.runOnIdle { assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state) }
    }

    @Test fun `streaming settles, ends this session and moves with the picked track, not via Back`() {
        launch { settleGate = CompletableDeferred() }
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:stream")
        compose.onNodeWithTag("reconnect:stream").performClick()
        await("reconnect:stay")
        compose.runOnIdle {
            assertTrue(fixture.streams.isEmpty())
            fixture.settleGate!!.complete(Unit)
        }
        compose.waitUntil(5_000) { fixture.streams.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(6, fixture.streams.single().audioIndex)
            assertEquals(-1, fixture.streams.single().subtitleIndex)
            assertEquals(true, host.exitRequested)
            assertEquals(0, exits)
        }
    }

    @Test fun `a failed settle stays on the download and says so`() {
        launch { settled = OfflineSettleResult.Failed }
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:stream")
        compose.onNodeWithTag("reconnect:stream").performClick()
        await("reconnect:ok")
        compose.onNodeWithTag("reconnect:message").assertTextContains("isn't answering", substring = true)
        compose.runOnIdle {
            assertEquals(1, fixture.reopens)
            assertEquals(false, host.exitRequested)
        }
    }

    @Test fun `dismissing the sheet is a no`() {
        launch { settleGate = CompletableDeferred() }
        compose.onNodeWithTag("touch:track:6").performClick()
        await("reconnect:stream")
        compose.onNodeWithTag("reconnect:stream").performClick()
        await("reconnect:stay")
        compose.onNodeWithText("Done").performClick()
        sheetGone()
        compose.runOnIdle {
            assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state)
            assertEquals(1, fixture.reopens)
            assertTrue(fixture.streams.isEmpty())
        }
    }
}
