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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.common.Player
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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
class NextEpisodeResolutionTest {
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
    private val holdReopenedManifest = AtomicBoolean()
    private val reopenedManifest = CountDownLatch(1)
    private var exits = 0
    private val next = mutableListOf<String>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient

    @After fun cleanup() {
        reopenedManifest.countDown()
        releaseLookup.complete(Unit)
        metadataGate.complete(Unit)
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun pausedSeekToEndAfterTrackReopenWaitsForPendingLookupThenPlaysExactlyOnce() {
        launch()
        reopenAndEnd(holdTimeline = true)
        compose.runOnIdle { assertEquals(0, exits, "Pending does not mean there is no next episode") }
        releaseLookup.complete(Unit)
        waitUntil { next.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("next"), next)
            assertEquals(0, exits)
            assertEquals(1, reports.count { it == "/Sessions/Playing" })
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
        }
    }

    @Test fun failedInitialLookupIsRetriedAtEndInsteadOfSilentlyLosingNextEpisode() {
        launch(failFirst = true)
        waitUntil { lookups.get() >= 1 }
        releaseLookup.complete(Unit)
        reopenAndEnd()
        waitUntil { next.isNotEmpty() || exits > 0 }
        compose.runOnIdle {
            assertEquals(listOf("next"), next)
            assertEquals(lookupsAtEnd + 1, lookups.get(), "End retries once after the initial failure")
            assertEquals(0, exits)
        }
    }

    @Test fun talkBackWaitsForExplicitPlayWhenLookupCompletesAfterTheEnd() {
        launch()
        reopenAndEnd(enableAccessibilityAtEnd = true)
        compose.runOnIdle { assertEquals(0, exits) }
        releaseLookup.complete(Unit)
        waitUntil {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("next:play")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertTrue(next.isEmpty()) }
        compose.onNodeWithTag("next:play").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        waitUntil { next.isNotEmpty() }
        assertEquals(listOf("next"), next)
    }

    @Test fun completedLookupWithNoNextEpisodeExitsAfterThePendingResponseArrives() {
        launch(noNext = true)
        reopenAndEnd()
        compose.runOnIdle { assertEquals(0, exits) }
        releaseLookup.complete(Unit)
        waitUntil { exits > 0 }
        assertEquals(1, exits)
        assertTrue(next.isEmpty())
    }

    @Test fun equalLookupFailureAfterRetryExitsOnceWithoutAnotherAttempt() {
        launch(failAlways = true)
        reopenAndEnd()
        waitUntil { exits > 0 }
        compose.runOnIdle {
            assertEquals(lookupsAtEnd + 1, lookups.get(), "End retries once after the initial failure")
            assertEquals(1, exits)
            assertTrue(next.isEmpty())
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
        }
    }

    @Test fun backDuringPendingEndExitsOnceAndNeverStartsTheLateNextEpisode() {
        launch()
        reopenAndEnd()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onRoot().performKeyInput {
            pressKey(Key.Back)
            pressKey(Key.Back)
        }
        // Back exits immediately; its finite reporting scope finishes Stop independently.
        waitUntil { exits > 0 && reports.contains("/Sessions/Playing/Stopped") }
        releaseLookup.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertTrue(next.isEmpty())
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
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
                        path.startsWith("/MediaSegments") -> """{"Items":[]}"""
                        else -> {
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
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(
            compose.activity,
            account.server,
            upstream = DataSource.Factory {
                WaitingSource { if (holdReopenedManifest.get()) reopenedManifest.await() }
            }
        )
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

    private fun reopenAndEnd(enableAccessibilityAtEnd: Boolean = false, holdTimeline: Boolean = false) {
        val previous = output.player.value
        compose.onNodeWithText("Subtitles").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        // Robolectric does not grant Dialog window focus; model the native focused window.
        compose.runOnIdle {
            org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true)
        }
        // The chooser initially scrolls to the selected subtitle; Off is just above it.
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("track:0").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("track:0").performKeyInput { pressKey(Key.DirectionUp) }
        holdReopenedManifest.set(holdTimeline)
        compose.onNodeWithTag("track:-1").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        try {
            waitUntil { preparations.get() == 2 }
            waitUntil { output.player.value != null && output.player.value !== previous }
        } catch (error: Throwable) {
            val logs = org.robolectric.shadows.ShadowLog.getLogs()
                .filter { it.tag in setOf("PictureshowPlayback", "NextEpisode") }.map { it.msg }
            throw AssertionError("Reopen preparations=${preparations.get()} reports=$reports decision=${decision()} logs=$logs", error)
        }
        ready()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        if (holdTimeline) {
            compose.runOnIdle {
                val active = output.player.value!!
                assertEquals(C.TIME_UNSET, active.duration, "The replacement manifest is still held")
                assertTrue(!active.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                assertTrue(!active.playWhenReady, "Pending seeks preserve pause intent")
            }
        }
        reopenedManifest.countDown()
        // Compose idle does not mean the replacement HLS timeline/seek command is available.
        // Both Rights must survive that interval and clamp once the native timeline arrives.
        waitUntil {
            val active = output.player.value!!
            active.duration > 0 && active.currentPosition in (active.duration - 1)..active.duration
        }
        compose.runOnIdle {
            val active = output.player.value!!
            assertTrue(!active.playWhenReady, "Applying a pending seek must not resume playback")
            // An end-list HLS window can expose its last seekable microsecond one rounded millisecond below duration.
            assertTrue(active.currentPosition in (active.duration - 1)..active.duration, "Two Rights must reach the end clamp")
        }
        if (enableAccessibilityAtEnd) {
            compose.runOnIdle {
                shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(true)
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            lookupsAtEnd = lookups.get()
            reachedEnd.set(true)
            output.onEnded(decision())
        }
        compose.waitForIdle()
    }

    /** The decoder is deliberately held; renderer timing is supplied at the real output callback boundary. */
    private class WaitingSource(private val awaitManifest: () -> Unit) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            if (dataSpec.uri.path?.endsWith(".m3u8") == true) awaitManifest()
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
