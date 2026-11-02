/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class PlaybackHttpOutcomeTest {
    private val account = Account(Server("server", "Fixture", "https://example.test", "12"), User("viewer", "Viewer"), "fictional")
    private val info =
        PlaybackProgressInfo("item", playMethod = PlayMethod.DirectPlay, positionTicks = 120_000_000, subtitleStreamIndex = -1)

    @Test
    fun everyReportEndpointObservesTheRealHttpStatusIncludingAuthorizationAndServerFailures() = runTest {
        for (code in listOf(204, 401, 503)) {
            val paths = mutableListOf<String>()
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        paths += request.url.encodedPath
                        respond("", HttpStatusCode.fromValue(code))
                    }
                }
            }
            try {
                val client = PlaybackClient(http, ClientIdentity("TV", "fixture"))
                val outcomes = mutableListOf<PlaybackHttpOutcome>()
                client.reportStart(account, info, outcomes::add)
                client.reportProgress(account, info, outcomes::add)
                client.reportStopped(account, PlaybackStopInfo("item", positionTicks = 120_000_000), outcomes::add)
                assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), paths)
                assertEquals(List(3) { PlaybackHttpOutcome(code, if (code == 204) null else PlaybackReportFailure.Http) }, outcomes)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun timeoutObservesOneFixedOutcomeAndDoesNotRetryInsideTheClient() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    requests++
                    delay(60_000)
                    respond("")
                }
            }
        }
        try {
            val outcomes = mutableListOf<PlaybackHttpOutcome>()
            val result = PlaybackClient(http, ClientIdentity("TV", "fixture")).reportProgress(account, info, outcomes::add)
            assertIs<ClientResult.Failure>(result)
            assertEquals(1, requests)
            assertEquals(listOf(PlaybackHttpOutcome(null, PlaybackReportFailure.Timeout)), outcomes)
            assertEquals(30_000, testScheduler.currentTime)
        } finally {
            http.close()
        }
    }

    @Test
    fun networkExceptionsNeverExposeTheirMessage() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { error("private exception URL and credential") }
            }
        }
        try {
            val outcomes = mutableListOf<PlaybackHttpOutcome>()
            val result = PlaybackClient(http, ClientIdentity("TV", "fixture")).reportStart(account, info, outcomes::add)
            assertIs<ClientResult.Failure>(result)
            assertEquals(listOf(PlaybackHttpOutcome(null, PlaybackReportFailure.Network)), outcomes)
        } finally {
            http.close()
        }
    }

    @Test
    fun cancellationIsObservedOnceAndStillPropagates() = runTest {
        val entered = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    entered.complete(Unit)
                    delay(60_000)
                    respond("")
                }
            }
        }
        try {
            val outcomes = mutableListOf<PlaybackHttpOutcome>()
            val job =
                async {
                    PlaybackClient(
                        http,
                        ClientIdentity("TV", "fixture")
                    ).reportStopped(account, PlaybackStopInfo("item", positionTicks = 0), outcomes::add)
                }
            entered.await()
            job.cancelAndJoin()
            assertEquals(listOf(PlaybackHttpOutcome(null, PlaybackReportFailure.Cancelled)), outcomes)
        } finally {
            http.close()
        }
    }
}
