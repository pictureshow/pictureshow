/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
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
import app.pictureshow.core.playback.PlayDecision
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
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

/** Drive the real screen, gateway, track reopen, native seek and output's EOS callback; hold only the optional API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class NextEpisodePlayedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
    private val episode = MediaItem("episode", "An episode", "Episode", seriesId = "series")
    private val releaseLookup = CompletableDeferred<Unit>()
    private val metadataGate = CompletableDeferred<Unit>()
    private val lookups = AtomicInteger()
    private val reachedEnd = java.util.concurrent.atomic.AtomicBoolean()
    private var lookupsAtEnd = 0
    private val preparations = AtomicInteger()
    private val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var exits = 0
    private val next = mutableListOf<String>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient

    @After fun cleanup() {
        releaseLookup.complete(Unit)
        metadataGate.complete(Unit)
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun playNowMarksLeftEpisodeOnceAfterStopAndRefreshesCatalog() {
        launchCard()
        click("next:play")
        waitUntil { next.isNotEmpty() }
        assertCompletion()
    }

    @Test fun countdownMarksLeftEpisodeOnceAfterStopAndRefreshesCatalog() {
        launchCard()
        compose.runOnIdle {
            // The held source has no decoder clock. Supply native READY/play intent at the output
            // boundary so this test exercises the screen's real countdown and completion wiring.
            val active = output.player.value!!
            val readyPlayer = object : androidx.media3.common.ForwardingPlayer(active) {
                override fun getPlaybackState() = androidx.media3.common.Player.STATE_READY
                override fun getPlayWhenReady() = true
            }

            @Suppress("UNCHECKED_CAST")
            val flow = Media3PlaybackOutput::class.java.getDeclaredField("mutablePlayer")
                .apply { isAccessible = true }.get(output) as MutableStateFlow<androidx.media3.common.Player?>
            flow.value = readyPlayer
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(11_000)
        waitUntil { next.isNotEmpty() }
        assertCompletion()
    }

    @Test fun cancelThenBackDoesNotMarkPlayed() {
        launchCard()
        click("next:cancel")
        compose.onRoot().performKeyInput {
            pressKey(Key.Back)
            pressKey(Key.Back)
        }
        waitUntil { exits > 0 }
        assertEquals(0, service.actions.size)
        assertTrue(next.isEmpty())
    }

    @Test fun backDuringSwitchCancelsCompletionAndNextStart() {
        stopGate = CompletableDeferred()
        launchCard()
        click("next:play")
        waitUntil { reports.contains("stop-pending") }
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        waitUntil { exits > 0 }
        stopGate!!.complete(Unit)
        waitUntil { reports.contains("/Sessions/Playing/Stopped") }
        compose.waitForIdle()
        assertEquals(0, service.actions.size)
        assertTrue(next.isEmpty())
    }

    private val service = CatalogModelsTest.FakeCatalog()
    private lateinit var catalog: CatalogSession
    private var stopGate: CompletableDeferred<Unit>? = null
    private fun click(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    private fun launchCard() {
        releaseLookup.complete(Unit)
        metadataGate.complete(Unit)
        service.detail = app.pictureshow.core.ClientResult.Success(episode)
        service.actionResult = app.pictureshow.core.ClientResult.Success(app.pictureshow.core.ItemUserData(played = true))
        launch()
        waitUntil { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("next:play")).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertCompletion() {
        compose.runOnIdle {
            assertEquals(listOf(Triple("played", "episode", true)), service.actions)
            assertEquals(listOf("next"), next)
            assertEquals(1, reports.count { it == "/Sessions/Playing" })
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
            assertTrue(catalog.changes.value.users["episode"]?.played == true)
            assertTrue(catalog.changes.value.pendingPlayback.isEmpty())
            assertEquals(1, catalog.changes.value.refreshRevision)
        }
    }

    private fun launch(failFirst: Boolean = false, accessible: Boolean = false, noNext: Boolean = false, failAlways: Boolean = false) {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val json = when {
                        path.endsWith("/Episodes") -> {
                            lookups.incrementAndGet()
                            if (failAlways ||
                                (failFirst && !reachedEnd.get())
                            ) {
                                return@addHandler respond("{}", HttpStatusCode.ServiceUnavailable)
                            }
                            releaseLookup.await()
                            if (noNext) {
                                """{"Items":[{"Id":"episode","Type":"Episode"}]}"""
                            } else {
                                """{"Items":[{"Id":"episode","Type":"Episode"},{"Id":"next","Name":"Another episode","Type":"Episode"}]}"""
                            }
                        }
                        path.endsWith("/PlaybackInfo") -> {
                            val count = preparations.incrementAndGet()
                            """{"PlaySessionId":"encode-$count","MediaSources":[{"Id":"source","RunTimeTicks":600000000,
                            "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                            "TranscodingUrl":"/Videos/episode/master.m3u8","DefaultSubtitleStreamIndex":0,
                            "MediaStreams":[{"Type":"Video","Index":2,"Codec":"av1","RealFrameRate":29.97},
                            {"Type":"Audio","Index":1,"Codec":"opus"},{"Type":"Subtitle","Index":0,"Codec":"subrip","IsExternal":true}]}]}"""
                        }
                        path == "/Items/episode" -> {
                            // Keep initial episode metadata stable while independently controlling the adjacency response.
                            metadataGate.await()
                            """{"Id":"episode","Name":"An episode","Type":"Episode","SeriesId":"series"}"""
                        }
                        path.startsWith("/MediaSegments") -> """{"Items":[{"Type":"Outro","StartTicks":400000000,"EndTicks":600000000}]}"""
                        else -> {
                            if (path == "/Sessions/Playing/Stopped") {
                                reports += "stop-pending"
                                stopGate?.await()
                            }
                            reports += path
                            return@addHandler respond("", HttpStatusCode.NoContent)
                        }
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
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
        catalog = CatalogSession(service, accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(accessible)
        compose.setContent {
            PictureshowTheme {
                PlayerItemScreen(account, episode, 42_000, catalog, { next += it.id }, { exits++ }, providedOutput = output)
            }
        }
        try {
            waitUntil { (output.player.value?.duration ?: 0) > 0 && lookups.get() > 0 }
        } catch (error: Throwable) {
            val logs = org.robolectric.shadows.ShadowLog.getLogs()
                .filter { it.tag in setOf("PictureshowPlayback", "NextEpisode") }.map { it.msg }
            throw AssertionError(
                "lookups=${lookups.get()} preparations=${preparations.get()} output=${output.player.value} logs=$logs",
                error
            )
        }
        ready()
    }

    // The screen's finite reporting scope uses Android's Main dispatcher, outside Compose's test scheduler.
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun decision(): PlayDecision = Media3PlaybackOutput::class.java.getDeclaredField("current")
        .apply { isAccessible = true }.get(output) as PlayDecision

    private fun ready() = compose.runOnIdle {
        output.player.value!!.pause()
        output.onReady(decision(), output.position(), output.position().milliseconds)
        shadowOf(android.os.Looper.getMainLooper()).idle()
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
