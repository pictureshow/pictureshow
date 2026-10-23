/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.cash.turbine.test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class QuickConnectResumeTest {
    private val server = Server("server", "Test server", "https://media.example.test", "12")
    private val identity = ClientIdentity("Test TV", "test-device")

    @Test
    fun restoredAttemptPollsOriginalSecretWithoutInitiatingAndClearsAfterExchange() = runTest {
        var initiations = 0
        var polled: String? = null
        var polledAt = -1L
        var saved: PendingQuickConnect? = PendingQuickConnect("123456", "fake-original-secret")
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/QuickConnect/Enabled" -> respond("true")
                        "/QuickConnect/Initiate" -> {
                            initiations++
                            respond("""{"Code":"654321","Secret":"fake-new-secret"}""")
                        }
                        "/QuickConnect/Connect" -> {
                            polled = request.url.parameters["secret"]
                            polledAt = testScheduler.currentTime
                            respond("""{"Authenticated":true}""")
                        }
                        "/Users/AuthenticateWithQuickConnect" -> respond(
                            """{"AccessToken":"fake-token","ServerId":"server","User":{"Id":"viewer","Name":"Viewer"}}"""
                        )
                        else -> error("Unexpected request")
                    }
                }
            }
        }
        try {
            JellyfinClient(http, identity).quickConnect(server, saved) { saved = it }.test {
                assertEquals(QuickConnectState.Code("123456"), awaitItem())
                runCurrent()
                assertIs<QuickConnectState.Authorized>(awaitItem())
                awaitComplete()
            }
            assertEquals(0, initiations)
            assertEquals("fake-original-secret", polled)
            assertEquals(0L, polledAt)
            assertNull(saved)
        } finally {
            http.close()
        }
    }

    @Test
    fun disabledQuickConnectDiscardsTheSavedCredentialWithoutPolling() = runTest {
        var saved: PendingQuickConnect? = PendingQuickConnect("123456", "fake-secret")
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("/QuickConnect/Enabled", request.url.encodedPath)
                    respond("false")
                }
            }
        }
        try {
            JellyfinClient(http, identity).quickConnect(server, saved) { saved = it }.test {
                assertEquals(QuickConnectState.Disabled, awaitItem())
                awaitComplete()
            }
            assertNull(saved)
        } finally {
            http.close()
        }
    }

    @Test
    fun onlyServerExpiryReplacesTheRestoredCodeAndPublishesTheNewSecret() = runTest {
        val updates = mutableListOf<PendingQuickConnect?>()
        var initiations = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/QuickConnect/Enabled" -> respond("true")
                        "/QuickConnect/Initiate" -> {
                            initiations++
                            respond("""{"Code":"654321","Secret":"fake-new-secret"}""")
                        }
                        "/QuickConnect/Connect" -> respond("", HttpStatusCode.NotFound)
                        else -> error("Unexpected request")
                    }
                }
            }
        }
        try {
            JellyfinClient(http, identity).quickConnect(server, PendingQuickConnect("123456", "fake-secret"), updates::add).test {
                assertEquals(QuickConnectState.Code("123456"), awaitItem())
                assertEquals(QuickConnectState.Code("654321"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, initiations)
            assertTrue(updates.any { it == null })
            assertEquals("fake-new-secret", updates.last()?.secret)
            assertFalse(updates.last().toString().contains("fake-new-secret"))
        } finally {
            http.close()
        }
    }
}
