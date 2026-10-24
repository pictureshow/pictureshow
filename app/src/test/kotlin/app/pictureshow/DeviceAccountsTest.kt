package app.pictureshow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class DeviceAccountsTest {
    private val server = Server("s", "Cinema", "https://media.example.test", "12.0.0")
    private val auth = """{"ServerId":"s","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"fake-token"}"""

    @Test
    fun currentReleaseSnapshotKeepsBothTokensAndTheirOriginalDeviceAfterUpgrade() = runTest {
        val dir = Files.createTempDirectory("legacy-accounts").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("accounts.preferences_pb") }
        val oldJson = """
            {"accounts":[
              {"server":{"id":"s","name":"Cinema","url":"https://media.example.test","version":"12.0.0"},
               "user":{"id":"a","name":"Viewer A"},"accessToken":"fake-a"},
              {"server":{"id":"s","name":"Cinema","url":"https://media.example.test","version":"12.0.0"},
               "user":{"id":"b","name":"Viewer B"},"accessToken":"fake-b"}
            ],"currentKey":"s:a"}
        """.trimIndent()
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests += it.headers[HttpHeaders.Authorization]!!
                        respond("""{"Id":"viewer","Name":"Viewer"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        )
        try {
            preferences.updateData { before ->
                before.toMutablePreferences().apply {
                    this[androidx.datastore.preferences.core.stringPreferencesKey("accounts_v1")] = oldJson
                    this[androidx.datastore.preferences.core.stringPreferencesKey("device_id")] = "original-install-id"
                }
            }
            val accounts = AccountStore(AccountPersistence(preferences))
            val loaded = accounts.load()
            assertEquals("s:a", loaded.currentKey)
            val service = PersistentIdentityService(http, DeviceIdentityStore(preferences), "Test TV")
            loaded.accounts.forEach { assertIs<ClientResult.Success<*>>(service.me(it)) }
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.contains("DeviceId=\"original-install-id\"") })
            assertTrue(requests[0].contains("Token=\"fake-a\""))
            assertTrue(requests[1].contains("Token=\"fake-b\""))
            accounts.select("s:b")
            assertEquals("fake-b", AccountStore(AccountPersistence(preferences)).load().currentAccount!!.accessToken)
        } finally {
            http.close()
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            dir.deleteRecursively()
        }
    }

    @Test
    fun newPasswordSignInsHaveDistinctIdsAndRememberTheIdUsedForAuthentication() = runTest {
        val dir = Files.createTempDirectory("device-accounts").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("accounts.preferences_pb") }
        val ids = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        ids += Regex("DeviceId=\"([^\"]+)\"").find(it.headers[HttpHeaders.Authorization]!!)!!.groupValues[1]
                        respond(
                            if (it.url.encodedPath == "/Users/Me") """{"Id":"viewer","Name":"Viewer"}""" else auth,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val service = PersistentIdentityService(http, DeviceIdentityStore(preferences), "Test TV")
            val first = assertIs<ClientResult.Success<app.pictureshow.core.Account>>(service.authenticate(server, "viewer", "fake")).value
            val second = assertIs<ClientResult.Success<app.pictureshow.core.Account>>(service.authenticate(server, "viewer", "fake")).value
            assertNotEquals(ids[0], ids[1])
            val accounts = AccountStore(AccountPersistence(preferences))
            accounts.remember(second)
            val loaded = AccountStore(AccountPersistence(preferences)).load().currentAccount!!
            assertIs<ClientResult.Success<*>>(service.me(loaded))
            assertEquals(ids[1], ids[2])
            assertNotEquals(first, second)
            assertTrue(ids.all { it.matches(Regex("[a-f0-9-]{36}")) })
        } finally {
            http.close()
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            dir.deleteRecursively()
        }
    }

    @Test
    fun quickConnectCanResumeOnANewServiceWithoutChangingTheInitiatingId() = runTest {
        val dir = Files.createTempDirectory("device-accounts").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("accounts.preferences_pb") }
        val ids = mutableListOf<String>()
        var pending: app.pictureshow.core.PendingQuickConnect? = null
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        ids += Regex("DeviceId=\"([^\"]+)\"").find(it.headers[HttpHeaders.Authorization]!!)!!.groupValues[1]
                        respond(
                            when (it.url.encodedPath) {
                                "/QuickConnect/Enabled" -> "true"
                                "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"fake-secret"}"""
                                "/QuickConnect/Connect" -> """{"Authenticated":true}"""
                                "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                                else -> auth
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val identity = DeviceIdentityStore(preferences)
            val service = PersistentIdentityService(http, identity, "Test TV")
            // Cancel at the first code, as a process death would; the pending attempt is retained.
            service.quickConnect(server, null) { pending = it }.first()
            val initialId = ids.last()
            assertNotEquals(identity.getOrCreate(), initialId)
            val resumed = PersistentIdentityService(http, DeviceIdentityStore(preferences), "Test TV")
            val states = resumed.quickConnect(server, pending) {}.toList()
            val account = assertIs<QuickConnectState.Authorized>(states.last()).account
            assertIs<ClientResult.Success<*>>(resumed.me(account))
            assertTrue(ids.all { it == initialId })
        } finally {
            http.close()
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            dir.deleteRecursively()
        }
    }
}
