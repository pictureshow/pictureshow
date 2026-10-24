/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Real HTTP logout plus persisted account state; failures must not erase a different viewer. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountRemovalTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val server = Server("server", "Cinema", "https://media.example.test/base", "12.0.0")
    private val first = Account(server, User("first", "First viewer"), "fake-first-token")
    private val second = first.copy(user = User("second", "Second viewer"), accessToken = "fake-second-token")
    private val elsewhere = first.copy(
        server = server.copy(id = "other", url = "https://other.example.test"),
        accessToken = "fake-other-token"
    )
    private val memory = object : TextPersistence {
        var value: String? = null
        var fail = false
        override suspend fun read() = value
        override suspend fun write(value: String) {
            check(!fail) { "Storage unavailable" }
            this.value = value
        }
    }
    private val accounts = AccountStore(memory)

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun cleanup() {
        models.clear()
        Dispatchers.resetMain()
    }

    private fun model(http: HttpClient) = AccountsViewModel(accounts, JellyfinClient(http, ClientIdentity("Test TV", "fake-device")))
        .also { models.put("accounts", it) }

    @Test
    fun removesCurrentAccountLocallyBeforeRevokingAndSurvivesNavigationDisposal() = runTest(dispatcher) {
        listOf(second, first).forEach { accounts.remember(it) }
        accounts.recordSearch(first.key, "private query")
        accounts.recordSearch(second.key, "retained query")
        val response = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = this@AccountRemovalTest.dispatcher
                addHandler {
                    requests += it.url.encodedPath to it.headers["Authorization"]
                    response.await()
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        try {
            val vm = model(http)
            vm.removeAccount(first.key)
            runCurrent()
            assertNull(accounts.snapshots.value.currentAccount)
            assertEquals(listOf(second), accounts.snapshots.value.accounts)
            assertFalse(accounts.snapshots.value.recentSearches.containsKey(first.key))
            assertEquals(listOf("retained query"), accounts.snapshots.value.recentSearches[second.key])
            assertEquals(listOf(server), accounts.snapshots.value.rememberedServers)
            models.clear() // Removing the selected viewer resets the navigation graph and clears its VM.
            response.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, requests.size)
            assertEquals("/base/Sessions/Logout", requests.single().first)
            assertTrue(requests.single().second.orEmpty().contains(first.accessToken))
            assertEquals(accounts.snapshots.value, AccountStore(memory).load())
            assertFalse(memory.value.orEmpty().contains(first.accessToken))
            assertFalse(memory.value.orEmpty().contains("private query"))
        } finally {
            http.close()
        }
    }

    @Test
    fun graphDisposalDuringFirstLogoutDoesNotCancelTheSecondLogoutOrBlockChooser() = runTest(dispatcher) {
        listOf(first, second).forEach { accounts.remember(it) }
        val response = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = this@AccountRemovalTest.dispatcher
                addHandler {
                    requests += it.headers["Authorization"].orEmpty()
                    if (requests.size == 1) response.await()
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        try {
            val vm = model(http)
            vm.removeServer(server)
            runCurrent()
            assertTrue(accounts.snapshots.value.accounts.isEmpty())
            assertFalse(vm.state.value.busy)
            models.clear()
            response.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, requests.size)
            assertTrue(requests.last().contains(second.accessToken))
        } finally {
            http.close()
        }
    }

    @Test
    fun removingAServerRevokesEverySavedSessionEvenWhenOneIsOffline() = runTest(dispatcher) {
        listOf(first, second, elsewhere).forEach { accounts.remember(it) }
        accounts.recordSearch(first.key, "forget this")
        accounts.recordSearch(elsewhere.key, "keep this")
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = this@AccountRemovalTest.dispatcher
                addHandler {
                    val auth = it.headers["Authorization"].orEmpty()
                    requests += auth
                    if (auth.contains(first.accessToken)) error("Offline")
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        try {
            val vm = model(http)
            vm.removeServer(server)
            advanceUntilIdle()
            val saved = AccountStore(memory).load()
            assertEquals(listOf(elsewhere), saved.accounts)
            assertEquals(elsewhere, saved.currentAccount)
            assertEquals(listOf(elsewhere.server), saved.rememberedServers)
            assertEquals(mapOf(elsewhere.key to listOf("keep this")), saved.recentSearches)
            assertEquals(2, requests.size)
            assertTrue(requests.any { it.contains(first.accessToken) })
            assertTrue(requests.any { it.contains(second.accessToken) })
            assertNull(vm.state.value.error)
        } finally {
            http.close()
        }
    }

    @Test
    fun failedLocalRemovalPublishesAnErrorAndDoesNotRevokeAnySession() = runTest(dispatcher) {
        accounts.remember(first)
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = this@AccountRemovalTest.dispatcher
                addHandler {
                    requests += it.url.toString()
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        try {
            val vm = model(http)
            memory.fail = true
            vm.removeServer(server)
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)
            assertEquals(first, accounts.snapshots.value.currentAccount)
            assertEquals(first, AccountStore(memory).load().currentAccount)
            assertTrue(requests.isEmpty())
            memory.fail = false
            vm.removeServer(server)
            advanceUntilIdle()
            assertTrue(accounts.snapshots.value.accounts.isEmpty())
            assertNull(vm.state.value.error)
        } finally {
            http.close()
        }
    }

    @Test
    fun removingLastSignedOutServerPersistsAbsenceWithoutTouchingAnotherAddress() = runTest(dispatcher) {
        accounts.remember(first)
        accounts.signOut(first.key)
        accounts.removeServer(server.copy(url = "https://different.example.test"))
        assertEquals(listOf(server), accounts.load().rememberedServers)
        accounts.removeServer(server)
        assertTrue(AccountStore(memory).load().rememberedServers.isEmpty())
        assertNull(AccountStore(memory).load().lastServer)
    }
}
