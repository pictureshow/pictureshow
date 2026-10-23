/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.DeviceProfile
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackInfoRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SlowServerTest {
    private val identity = ClientIdentity("Test TV", "slow-server-test")
    private val server = Server("s", "Test", "https://example.test", "10.10.7")
    private val account = Account(server, User("u", "Viewer"), "test-token")
    private val auth = """{"ServerId":"s","AccessToken":"test-token","User":{"Id":"u","Name":"Viewer"}}"""

    private fun TestScope.http(stalled: Boolean = false): HttpClient = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (stalled) awaitCancellation()
                delay(25_000)
                respond(
                    when (it.url.encodedPath) {
                        "/System/Info/Public" -> """{"Id":"s","ServerName":"Test","Version":"10.10.7","ProductName":"Jellyfin Server"}"""
                        "/Users/AuthenticateByName", "/Users/AuthenticateWithQuickConnect" -> auth
                        "/Users/Public" -> "[]"
                        "/QuickConnect/Enabled" -> "true"
                        "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"test-secret"}"""
                        "/QuickConnect/Connect" -> """{"Authenticated":true}"""
                        "/Users/Me" -> """{"Id":"u","Name":"Viewer"}"""
                        "/Users/u" -> """{"Configuration":{}}"""
                        "/Items/i" -> """{"Id":"i","Name":"Test","Type":"Movie"}"""
                        "/Items/i/PlaybackInfo" -> """{"MediaSources":[],"PlaySessionId":"p"}"""
                        else -> """{"Items":[],"TotalRecordCount":0}"""
                    }
                )
            }
        }
    }.also { http -> backgroundScope.coroutineContext.job.invokeOnCompletion { http.close() } }

    @Test
    fun fullySpecifiedServerCanTakeTwentyFiveSecondsToIdentifyItself() = runTest {
        assertIs<ClientResult.Success<Server>>(JellyfinClient(http(), identity).probe("https://example.test:8096"))
    }

    @Test
    fun passwordAndRememberedSignInCanTakeTwentyFiveSeconds() = runTest {
        val service = JellyfinClient(http(), identity)
        assertIs<ClientResult.Success<Account>>(service.authenticate(server, "Viewer", "password"))
        assertIs<ClientResult.Success<User>>(service.me(account))
        assertIs<ClientResult.Success<Unit>>(service.logout(account))
    }

    @Test
    fun publicUsersAndQuickConnectAllowSlowResponsesAtEveryStep() = runTest {
        val service = JellyfinClient(http(), identity)
        assertIs<ClientResult.Success<List<User>>>(service.publicUsers(server))
        val states = service.quickConnect(server).toList()
        assertEquals(QuickConnectState.Code("123456"), states.first())
        assertIs<QuickConnectState.Authorized>(states.last())
    }

    @Test
    fun homeLibrariesDetailsAndSearchCanTakeTwentyFiveSeconds() = runTest {
        val service = JellyfinClient(http(), identity)
        assertIs<ClientResult.Success<ItemPage>>(service.views(account))
        assertIs<ClientResult.Success<ItemPage>>(service.resume(account))
        assertIs<ClientResult.Success<ItemPage>>(service.nextUp(account))
        assertIs<ClientResult.Success<ItemPage>>(service.items(account, BrowseQuery(parentId = "library")))
        assertIs<ClientResult.Success<MediaItem>>(service.item(account, "i"))
        assertIs<ClientResult.Success<ItemPage>>(service.search(account, "Test", SearchKind.MOVIES))
    }

    @Test
    fun preferencesCanTakeTwentyFiveSeconds() = runTest {
        val service = UserConfigurationClient(http(), identity)
        val config = assertIs<ClientResult.Success<UserConfiguration>>(service.configuration(account)).value
        assertIs<ClientResult.Success<Unit>>(service.saveConfiguration(account, config))
    }

    @Test
    fun playbackInfoCanTakeTwentyFiveSeconds() = runTest {
        assertIs<ClientResult.Success<*>>(
            PlaybackClient(http(), identity).playbackInfo(account, "i", PlaybackInfoRequest(userId = "u", deviceProfile = DeviceProfile()))
        )
    }

    private suspend fun TestScope.assertDeadline(call: suspend (HttpClient) -> ClientResult<*>) {
        val start = testScheduler.currentTime
        val pending = async { call(http(stalled = true)) }
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(!pending.isCompleted, "The server still has time to answer")
        advanceTimeBy(1)
        runCurrent()
        val failure = assertIs<ClientResult.Failure>(pending.await())
        assertEquals(ErrorKind.NETWORK, failure.error.kind)
        assertTrue(failure.error.message.contains("too long"))
        assertTrue(failure.error.message.contains("try again", ignoreCase = true))
        assertEquals(30_000L, testScheduler.currentTime - start)
    }

    @Test
    fun stalledSignInEndsAtThirtySeconds() = runTest {
        assertDeadline { JellyfinClient(it, identity).authenticate(server, "Viewer", "password") }
    }

    @Test
    fun stalledCatalogEndsAtThirtySeconds() = runTest {
        assertDeadline { JellyfinClient(it, identity).views(account) }
    }

    @Test
    fun stalledPreferencesEndAtThirtySeconds() = runTest {
        assertDeadline { UserConfigurationClient(it, identity).configuration(account) }
    }

    @Test
    fun stalledPlaybackInfoEndsAtThirtySeconds() = runTest {
        assertDeadline {
            PlaybackClient(it, identity).playbackInfo(account, "i", PlaybackInfoRequest(userId = "u", deviceProfile = DeviceProfile()))
        }
    }

    @Test
    fun headersWithoutAResponseBodyStillTimeOut() = runTest {
        val channel = ByteChannel()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond(channel) }
            }
        }
        try {
            val pending = async { JellyfinClient(http, identity).authenticate(server, "Viewer", "password") }
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(ErrorKind.NETWORK, assertIs<ClientResult.Failure>(pending.await()).error.kind)
            assertEquals(30_000L, testScheduler.currentTime)
        } finally {
            channel.cancel(null)
            http.close()
        }
    }

    @Test
    fun stalledQuickConnectEndsWithARetryableError() = runTest {
        val states = JellyfinClient(http(stalled = true), identity).quickConnect(server).toList()
        val failure = assertIs<QuickConnectState.Failed>(states.single())
        assertEquals(ErrorKind.NETWORK, failure.error.kind)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun candidateProbesKeepTheirShortBound() = runTest {
        val start = testScheduler.currentTime
        val failure = assertIs<ClientResult.Failure>(JellyfinClient(http(stalled = true), identity).probe("example.test"))
        assertTrue(failure.error.message.contains("too long"))
        assertEquals(16_000L, testScheduler.currentTime - start)
    }
}
