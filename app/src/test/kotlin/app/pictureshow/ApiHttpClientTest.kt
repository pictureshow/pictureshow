/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class ApiHttpClientTest {
    @Test
    fun actualAppTransportDoesNotCutOffSlowSignInAndKeepsConnectLimit() = runTest {
        val engine = MockEngine {
            val timeout = requireNotNull(it.getCapabilityOrNull(HttpTimeoutCapability))
            assertEquals(3_000L, timeout.connectTimeoutMillis)
            delay(6_000)
            respond("""{"ServerId":"s","AccessToken":"test-token","User":{"Id":"u","Name":"Viewer"}}""")
        }.also { it.config.dispatcher = StandardTestDispatcher(testScheduler) }
        HttpClient(engine) { configureApiHttpClient() }.use { http ->
            val client = JellyfinClient(http, ClientIdentity("Test", "test-device"))
            assertIs<ClientResult.Success<*>>(client.authenticate(Server("s", "Test", "https://example.test", "10.10.7"), "Viewer", "test"))
        }
    }
}
