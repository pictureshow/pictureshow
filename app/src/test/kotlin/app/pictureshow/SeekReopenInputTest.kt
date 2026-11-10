/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.InterruptedIOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SeekReopenInputTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private lateinit var host: android.view.View

    @After fun cleanup() {
        publicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    private val requests = java.util.concurrent.CopyOnWriteArrayList<Long>()
    private val preparing = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val releasePrepare = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val publicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var keyAtHandoff = false
    private var handoffInjected = false

    @Test fun aKeyDeliveredBeforeReplacementRecompositionStillCountsAndTheClockKeepsItsTarget() {
        launch(false)
        compose.onNodeWithTag("player:surface").assertIsFocused()
        val retired = checkNotNull(output.player.value)
        compose.mainClock.autoAdvance = false
        fun key(code: Int) = compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        fun drainUntil(predicate: () -> Boolean) = compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            predicate()
        }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        drainUntil { preparing.isCompleted }
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.mainClock.advanceTimeBy(3_000)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        compose.waitForIdle()
        assertTrue(
            compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("1:40"),
            "The clock must retain the accumulated 100 s target during blocked preparation after preview timeout"
        )
        assertLiveClock(compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().id, "1:40")
        compose.runOnUiThread {
            keyAtHandoff = true
            releasePrepare.complete(Unit)
        }
        drainUntil { handoffInjected && output.player.value !== retired && output.player.value != null }
        // The third key was delivered at publication, before the screen observed the replacement.
        try {
            drainUntil { requests.lastOrNull() == 110_000L || requests.size >= 4 }
        } finally {
            println("deliveredRight=3 playbackInfoTargetsMs=$requests")
        }
        assertEquals(110_000L, requests.last(), "Three delivered Right presses must survive the old-player callback window")
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val node = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
        assertTrue(
            node.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("1:50"),
            "The displayed target must survive both player replacement and the preview timeout"
        )
        assertLiveClock(node.id, "1:50")
    }

    private fun assertLiveClock(id: Int, time: String) = compose.runOnUiThread {
        val provider = checkNotNull(host.accessibilityNodeProvider)
        val live = checkNotNull(provider.createAccessibilityNodeInfo(id))
        assertTrue(live.stateDescription.toString().contains(time), "The live clock and bar must retain $time")
    }

    private fun launch(accessible: Boolean) {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        val item = MediaItem("episode", "An episode", "Episode", seriesId = "series")
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                        val body = app.pictureshow.core.playback.PlaybackJson.parseToJsonElement(
                            (request.body as io.ktor.http.content.TextContent).text
                        ) as kotlinx.serialization.json.JsonObject
                        requests +=
                            (body["StartTimeTicks"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong()?.div(10_000) ?: 0L
                        if (requests.size == 2) {
                            preparing.complete(Unit)
                            releasePrepare.await()
                        }
                    }
                    val json = when {
                        request.url.encodedPath.endsWith(
                            "/PlaybackInfo"
                        ) ->
                            """{"PlaySessionId":"viewing","MediaSources":[{"Id":"source","RunTimeTicks":1800000000,
                                "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                                "TranscodingUrl":"/Videos/episode/master.m3u8?VideoCodec=h264&MaxFramerate=30&AudioCodec=aac","MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264","RealFrameRate":60.0}]}]}"""
                        request.url.encodedPath.endsWith(
                            "/Episodes"
                        ) ->
                            """{"Items":[{"Id":"episode","Type":"Episode"},
                                {"Id":"next","Name":"Next story","Type":"Episode"}]}"""
                        request.url.encodedPath == "/Items/episode" ->
                            """{"Id":"episode","Name":"An episode",
                                "Type":"Episode","SeriesId":"series"}"""
                        request.url.encodedPath.startsWith(
                            "/MediaSegments"
                        ) -> """{"Items":[{"Id":"intro","Type":"Intro","StartTicks":0,"EndTicks":200000000}]}"""
                        else -> return@addHandler respond("", HttpStatusCode.NoContent)
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
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
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        publicationScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            output.player.collect { replacement ->
                if (replacement != null && keyAtHandoff) {
                    keyAtHandoff = false
                    handoffInjected = true
                    compose.activity.dispatchKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    )
                    compose.activity.dispatchKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    )
                }
            }
        }
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setScreenReader(accessible)
        compose.setContent {
            host = androidx.compose.ui.platform.LocalView.current
            PictureshowTheme { PlayerItemScreen(account, item, 80_000, catalog, {}, {}, providedOutput = output) }
        }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            output.player.value != null
        }
        compose.waitForIdle()
    }

    /** The decoder is deliberately held; renderer timing is supplied at the real output callback boundary. */
    private class WaitingSource : DataSource {
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
