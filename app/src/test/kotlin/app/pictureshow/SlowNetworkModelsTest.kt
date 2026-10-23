/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SlowNetworkModelsTest {
    private val server = Server("s", "Test", "https://example.test", "10.10.7")
    private val identity = ClientIdentity("Test TV", "retry-test")
    private val info = """{"Id":"s","ServerName":"Test","Version":"10.10.7","ProductName":"Jellyfin Server"}"""
    private val auth = """{"ServerId":"s","AccessToken":"test-token","User":{"Id":"u","Name":"Viewer"}}"""
    private val accounts get() = AccountStore(object : TextPersistence {
        private var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(value: String) {
            text = value
        }
    })

    @Test
    fun passwordTimeoutClearsBusyAndViewerCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val store = ViewModelStore()
        val http = HttpClient(MockEngine) {
            configureApiHttpClient()
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    when (it.url.encodedPath) {
                        "/Users/Public" -> respond("[]")
                        "/QuickConnect/Enabled" -> respond("true")
                        else -> {
                            if (++attempts == 1) awaitCancellation()
                            delay(6_000)
                            respond(auth)
                        }
                    }
                }
            }
        }
        try {
            val vm = LoginViewModel(server, JellyfinClient(http, identity), accounts).also { store.put("test", it) }
            runCurrent()
            vm.username("Viewer")
            vm.password("test")
            vm.signIn()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.error.orEmpty().contains("too long"))
            vm.signIn()
            advanceUntilIdle()
            assertTrue(vm.state.value.signedIn)
            assertNull(vm.state.value.error)
        } finally {
            store.clear()
            http.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun serverProbeTimeoutClearsConnectingAndViewerCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val store = ViewModelStore()
        val http = HttpClient(MockEngine) {
            configureApiHttpClient()
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    if (++attempts == 1) awaitCancellation()
                    respond(info)
                }
            }
        }
        try {
            val vm = ServerViewModel(
                JellyfinClient(http, identity),
                object : ServerDiscovery {
                    override suspend fun discover(): List<DiscoveredServer> = emptyList()
                }
            ).also { store.put("test", it) }
            vm.connect("https://example.test:8096")
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(vm.state.value.connecting)
            assertTrue(vm.state.value.error.orEmpty().contains("too long"))
            vm.connect("https://example.test:8096")
            advanceUntilIdle()
            assertNotNull(vm.state.value.connected)
            assertNull(vm.state.value.error)
        } finally {
            store.clear()
            http.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun quickConnectTimeoutStopsBusyAndStartRetriesTheFlow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var attempts = 0
        val store = ViewModelStore()
        val http = HttpClient(MockEngine) {
            configureApiHttpClient()
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    if (++attempts == 1) awaitCancellation()
                    respond(
                        when (it.url.encodedPath) {
                            "/QuickConnect/Enabled" -> "true"
                            "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"test-secret"}"""
                            "/QuickConnect/Connect" -> """{"Authenticated":true}"""
                            else -> auth
                        }
                    )
                }
            }
        }
        try {
            val vm = QuickConnectViewModel(server, JellyfinClient(http, identity), accounts).also { store.put("test", it) }
            vm.start()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.error.orEmpty().contains("too long"))
            vm.start()
            advanceUntilIdle()
            assertTrue(vm.state.value.signedIn)
            assertNull(vm.state.value.error)
        } finally {
            store.clear()
            http.close()
            Dispatchers.resetMain()
        }
    }
}
