/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.window.DialogWindowProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineReconnectFacts
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.InterruptedIOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The whole TV player with a local binding: rows, offer and handoff, by remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineReconnectTvTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private lateinit var fixture: ReconnectFixture
    private var exits = 0

    @After fun cleanup() {
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    private fun idle(until: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        until()
    }

    private fun launch(network: String = "unmetered", configure: ReconnectFixture.() -> Unit = {}) {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        val item = MediaItem("episode", "An episode", "Episode")
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) { engine { addHandler { respond("", HttpStatusCode.NoContent) } } }
        startKoin {
            modules(
                module {
                    single { inertRemoteTarget() }
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                }
            )
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { HeldSource() })
        compose.setContent {
            val scope = rememberCoroutineScope()
            fixture = androidx.compose.runtime.remember { ReconnectFixture(scope, network).apply(configure) }
            val binding = androidx.compose.runtime.remember {
                OfflinePlaybackBinding(
                    ReconnectLocalGateway(),
                    emptyList<File>(),
                    offlineUndownloadedStreams(reconnectEntry),
                    fixture.reconnect
                )
            }
            CompositionLocalProvider(LocalOfflinePlayback provides binding) {
                PictureshowTheme { PlayerItemScreen(account, item, 0, catalog, {}, { exits++ }, providedOutput = output) }
            }
        }
        idle { output.player.value != null }
        compose.waitForIdle()
    }

    private fun openAudio() {
        press(Key.DirectionUp)
        idle { compose.onAllNodesWithText("Audio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Audio").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Audio").assertIsFocused()
        compose.onNodeWithText("Audio").performKeyInput { pressKey(Key.DirectionCenter) }
        // The panel lands on the selected row.
        idle { runCatching { compose.onNodeWithTag("track:1").assertIsFocused() }.isSuccess }
    }

    /** The chooser is its own window, so a key goes to the topmost root. */
    private fun press(key: Key) = compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }

    private fun pickJapanese() {
        openAudio()
        press(Key.DirectionDown)
        idle { runCatching { compose.onNodeWithTag("track:6").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("track:6").performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun awaitTag(tag: String) = idle { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitPanelGone() = idle { compose.onAllNodesWithTag("player:panel").fetchSemanticsNodes().isEmpty() }

    private fun pressCenterOn(tag: String) {
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun `the undownloaded track is listed, marked, and the downloaded one is unmarked`() {
        launch()
        openAudio()
        compose.onNodeWithTag("track:6").assertContentDescriptionContains("Japanese", substring = true)
        compose.onNodeWithTag("track:6").assertContentDescriptionContains("needs a connection", substring = true)
        compose.onNodeWithTag("track:1").assertContentDescriptionContains("English", substring = true)
        compose.onNodeWithTag("track:1").assert(hasContentDescription("needs a connection", substring = true).not())
    }

    @Test fun `the offer opens over running playback, focused on streaming, and staying changes nothing`() {
        launch()
        pickJapanese()
        awaitTag("reconnect:stream")
        compose.onNodeWithTag("reconnect:message").assertTextContains("Japanese isn't in this download", substring = true)
        compose.onNodeWithTag("reconnect:stream").assertIsFocused()
        compose.onNodeWithTag("reconnect:stay").assertIsDisplayed()
        assertTrue(output.player.value!!.playWhenReady, "playback keeps running behind the offer")
        press(Key.DirectionDown)
        idle { runCatching { compose.onNodeWithTag("reconnect:stay").assertIsFocused() }.isSuccess }
        pressCenterOn("reconnect:stay")
        awaitPanelGone()
        compose.runOnIdle {
            assertTrue(output.player.value!!.playWhenReady)
            assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state)
            assertTrue(fixture.settles.isEmpty())
            assertEquals(0, exits)
        }
        compose.onNodeWithText("Audio").assertIsFocused()
    }

    @Test fun `Back from the offer is a no`() {
        launch()
        pickJapanese()
        awaitTag("reconnect:stream")
        // A dialog takes Back from its window, the way the remote delivers it.
        val top = compose.onAllNodes(isRoot()).onLast().fetchSemanticsNode().root as ViewRootForTest
        val window = (top.view.parent as DialogWindowProvider).window
        compose.runOnIdle {
            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        awaitPanelGone()
        compose.runOnIdle {
            assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state)
            assertTrue(output.player.value!!.playWhenReady)
            assertEquals(0, exits)
        }
    }

    @Test fun `no connection says so and OK returns to the download`() {
        launch(network = "none")
        pickJapanese()
        awaitTag("reconnect:ok")
        compose.onNodeWithTag("reconnect:message").assertTextContains("no connection", substring = true)
        compose.onNodeWithTag("reconnect:stream").assertDoesNotExist()
        assertTrue(output.player.value!!.playWhenReady)
        pressCenterOn("reconnect:ok")
        awaitPanelGone()
        compose.runOnIdle { assertEquals(OfflineReconnectState.Idle, fixture.reconnect.state) }
    }

    @Test fun `a server refusal says which, with no way to accept`() {
        launch { facts = OfflineReconnectFacts("ok", false, null) }
        pickJapanese()
        awaitTag("reconnect:ok")
        compose.onNodeWithTag("reconnect:message").assertTextContains("isn't allowed", substring = true)
        compose.onNodeWithTag("reconnect:stream").assertDoesNotExist()
    }

    @Test fun `streaming closes the local session once settled and moves with the picked track`() {
        launch { settleGate = CompletableDeferred() }
        pickJapanese()
        awaitTag("reconnect:stream")
        pressCenterOn("reconnect:stream")
        awaitTag("reconnect:stay")
        compose.runOnIdle {
            assertTrue(fixture.reconnect.state is OfflineReconnectState.Connecting)
            assertEquals(false, output.player.value!!.playWhenReady, "held while the place is saved")
            assertTrue(fixture.streams.isEmpty(), "nothing moves before the position is on the server")
            fixture.settleGate!!.complete(Unit)
        }
        idle { fixture.streams.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(6, fixture.streams.single().audioIndex)
            assertEquals(-1, fixture.streams.single().subtitleIndex)
            assertEquals(fixture.streams.single().positionMs * 10_000, fixture.settles.single())
            assertEquals(0, exits, "this isn't Back")
            assertEquals(null, output.player.value, "the local session ended")
        }
    }

    @Test fun `a failed settle stays on the download, playing, and says so`() {
        launch { settled = OfflineSettleResult.Failed }
        pickJapanese()
        awaitTag("reconnect:stream")
        pressCenterOn("reconnect:stream")
        awaitTag("reconnect:ok")
        compose.onNodeWithTag("reconnect:message").assertTextContains("isn't answering", substring = true)
        compose.runOnIdle {
            assertEquals(1, fixture.reopens)
            assertTrue(output.player.value!!.playWhenReady, "resumed")
            assertTrue(fixture.streams.isEmpty())
        }
    }

    @Test fun `staying while connecting resumes and cancels the move`() {
        launch { settleGate = CompletableDeferred() }
        pickJapanese()
        awaitTag("reconnect:stream")
        pressCenterOn("reconnect:stream")
        idle { fixture.reconnect.state is OfflineReconnectState.Connecting }
        awaitTag("reconnect:stay")
        idle { runCatching { compose.onNodeWithTag("reconnect:stay").assertIsFocused() }.isSuccess }
        pressCenterOn("reconnect:stay")
        awaitPanelGone()
        compose.runOnIdle {
            fixture.settleGate!!.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, fixture.reopens)
            assertTrue(output.player.value!!.playWhenReady)
            assertTrue(fixture.streams.isEmpty())
            assertEquals(0, exits)
        }
    }

    private class HeldSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            manifest = if (dataSpec.uri.path?.endsWith(".m3u8") == true) {
                (
                    "#EXTM3U\n#EXT-X-TARGETDURATION:61\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:60.008,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    ).byteInputStream()
            } else {
                null
            }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            manifest?.let { return it.read(buffer, offset, length) }
            return try {
                Thread.sleep(Long.MAX_VALUE)
                C.RESULT_END_OF_INPUT
            } catch (_: InterruptedException) {
                throw InterruptedIOException()
            }
        }
        override fun close() = Unit
    }
}
