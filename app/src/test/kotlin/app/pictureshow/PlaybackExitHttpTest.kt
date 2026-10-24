/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlaybackClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Delayed wire acknowledgements reproduce the details read that used to overtake natural completion. */
class PlaybackExitHttpTest {
    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(60_000, false)
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() = Unit
        override fun release() = Unit
    }

    @Test
    fun naturalEndExitsPromptlyAndWithholdsWatchStateUntilAcknowledged() = exitScenario(natural = true)

    @Test
    fun ordinaryStopStillReturnsWhileItsReportIsPending() = exitScenario(natural = false)

    @Test
    fun backDuringPendingNextEpisodeCompletionExitsPromptlyWithoutStartingNext() = exitScenario(natural = true, closingForNext = true)

    @Test
    fun retainedDetailsUseAcknowledgedCompletionWhileThePostPlaybackReadIsBlocked() = exitScenario(natural = true, retainedDetails = true)

    @Test
    fun acknowledgedAlreadyPlayedStateReachesRetainedDetailsWithoutAnotherPlayedMutation() =
        exitScenario(natural = true, retainedDetails = true, stopMarksPlayed = true)

    @Test
    fun exhaustedStopReportsKeepWatchStatePendingUntilAnAuthoritativeReadCompletes() = exitScenario(natural = true, failReports = true)

    @Test
    fun newDetailsStartWithAcknowledgedStateBeforeTheirCollectorRuns() =
        exitScenario(natural = true, reopenAfterCompletion = true)

    @Test
    fun reopenedDetailsWithholdCachedStateAfterExhaustedReportsBeforeTheirCollectorRuns() =
        exitScenario(natural = true, failReports = true, reopenAfterCompletion = true)

    private fun exitScenario(
        natural: Boolean,
        closingForNext: Boolean = false,
        retainedDetails: Boolean = false,
        stopMarksPlayed: Boolean = false,
        failReports: Boolean = false,
        reopenAfterCompletion: Boolean = false
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stop = CompletableDeferred<Unit>()
        val played = CompletableDeferred<Unit>()
        val detailReads = CompletableDeferred<Unit>()
        var holdDetails = false
        var serverPlayed = false
        var stopAcknowledged = false
        var exits = 0
        var exitRequested = false
        var nextEpisodes = 0
        var details: CatalogDetailViewModel? = null
        val detailModels = mutableListOf<CatalogDetailViewModel>()
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val path = request.url.encodedPath
                    calls += path
                    assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("Token=\"token\""))
                    when (path) {
                        "/Items/film/PlaybackInfo" -> respond(
                            """{"PlaySessionId":"encode","MediaSources":[{"Id":"source","RunTimeTicks":42949822950000,
                                "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                                "TranscodingUrl":"/Videos/film/master.m3u8?VideoCodec=h264&AudioCodec=aac&PlaySessionId=encode",
                                "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,
                                "MediaStreams":[{"Type":"Video","Codec":"h264"},
                                {"Type":"Audio","Index":1,"Codec":"aac","Channels":2}]}]}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                        "/Sessions/Playing/Stopped" -> {
                            stop.await()
                            stopAcknowledged = true
                            serverPlayed = stopMarksPlayed
                            if (failReports) holdDetails = true
                            respond("", if (failReports) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NoContent)
                        }
                        "/UserPlayedItems/film" -> {
                            assertTrue(stopAcknowledged)
                            played.await()
                            serverPlayed = true
                            respond(
                                """{"Played":true,"PlaybackPositionTicks":0}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        "/Items/film", "/Items" -> {
                            if (holdDetails) detailReads.await()
                            val data = """{"Id":"film","Type":"Movie","UserData":{"Played":$serverPlayed,"PlaybackPositionTicks":0}}"""
                            respond(
                                if (path == "/Items") """{"Items":[$data]}""" else data,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        else -> respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
        val identity = ClientIdentity("TV", "device")
        val catalog = CatalogSession(JellyfinClient(http, identity), accounts)
        fun newDetails() = CatalogDetailViewModel(account, app.pictureshow.core.MediaItem("film", type = "Movie"), catalog)
            .also { detailModels += it }
        val session = PlaybackSession(
            ServerPlaybackGateway(
                PlaybackClient(http, identity),
                account,
                "film",
                { DeviceCapabilities("TV", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2)) },
                markPlayed = {
                    val result = catalog.completePlayback(account, "film")
                    holdDetails = true
                    result
                }
            ),
            Output(),
            backgroundScope,
            "en",
            onFinished = { catalog.refresh() },
            onCompletionPending = { catalog.playbackPending("film") }
        )
        try {
            if (retainedDetails) {
                details = newDetails()
                runCurrent()
                assertFalse(assertNotNull(details).state.value.item!!.userData.played)
            }
            session.start(0)
            runCurrent()
            assertNotNull(session.state.value.decision)
            session.ready()
            runCurrent()
            if (natural) session.ended()
            if (closingForNext) session.close { if (!exitRequested) nextEpisodes++ }
            exitRequested = true
            val onExit = {
                exits++
                if (details == null) {
                    details = newDetails()
                }
            }
            finishPlayback(session, onExit)
            runCurrent()
            assertEquals(1, exits, "Exit must not wait for a blocked server report")
            assertEquals(natural, assertNotNull(details).state.value.playbackPending)
            if (natural) assertFalse(assertNotNull(details).state.value.item!!.userData.played)
            stop.complete(Unit)
            runCurrent()
            if (natural && !stopMarksPlayed && !failReports) {
                assertTrue(calls.contains("/UserPlayedItems/film"))
                assertEquals(1, exits)
                assertTrue(assertNotNull(details).state.value.playbackPending, "The fallback played mutation is still pending")
                played.complete(Unit)
                runCurrent()
            }
            assertEquals(1, exits)
            assertEquals(0, nextEpisodes)
            assertEquals(natural && !failReports, assertNotNull(details).state.value.item!!.userData.played)
            assertEquals(failReports, assertNotNull(details).state.value.playbackPending)
            if (natural) assertTrue(assertNotNull(details).state.value.loading, "The fresh detail response is still blocked")
            if (reopenAfterCompletion) {
                details = newDetails()
                // Read synchronously: neither the collector nor the held HTTP item read has run.
                val initial = assertNotNull(details).state.value
                if (failReports) {
                    assertNull(initial.item, "A reopened detail must withhold unconfirmed cached watch state")
                } else {
                    assertTrue(assertNotNull(initial.item).userData.played, "Acknowledged state must be present on the first frame")
                }
                runCurrent()
            }
            detailReads.complete(Unit)
            runCurrent()
            assertEquals(natural && !failReports, assertNotNull(details).state.value.item!!.userData.played)
            assertFalse(assertNotNull(details).state.value.playbackPending)
            assertFalse(assertNotNull(details).state.value.loading)
            assertEquals(if (natural && !stopMarksPlayed && !failReports) 1 else 0, calls.count { it == "/UserPlayedItems/film" })
            assertEquals(if (failReports) 2 else 1, calls.count { it == "/Sessions/Playing/Stopped" })
        } finally {
            stop.complete(Unit)
            played.complete(Unit)
            detailReads.complete(Unit)
            session.close()
            runCurrent()
            detailModels.forEach { model ->
                androidx.lifecycle.ViewModelStore().apply {
                    put("detail", model)
                    clear()
                }
            }
            http.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ordinaryDetailsKeepTheirCachedItemWhenTheAuthoritativeReadFails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond("", HttpStatusCode.ServiceUnavailable) }
            }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
        val cached = app.pictureshow.core.MediaItem("ordinary", name = "Cached film", type = "Movie")
        val catalog = CatalogSession(JellyfinClient(http, ClientIdentity("TV", "device")), accounts)
        val model = CatalogDetailViewModel(account, cached, catalog)
        try {
            assertEquals(cached, model.state.value.item, "Ordinary cached details remain available on their first frame")
            assertFalse(model.state.value.playbackPending)
            runCurrent()
            assertEquals(cached, model.state.value.item, "A failed refresh must preserve ordinary cached details and actions")
            assertFalse(model.state.value.loading)
            assertNotNull(model.state.value.error)
        } finally {
            androidx.lifecycle.ViewModelStore().apply {
                put("detail", model)
                clear()
            }
            http.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun exitBeforeAPlaybackSessionExistsStillReturnsImmediately() {
        var exits = 0
        finishPlayback(null) { exits++ }
        assertEquals(1, exits)
    }
}
