/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.seerr.SeerrError
import app.pictureshow.core.seerr.SeerrFailure
import app.pictureshow.core.seerr.SeerrMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SeerrStoreTest {
    private val server = Server("srv", "Home", "https://jellyfin.example.test", "10.11.0")
    private val pilot = Account(server, User("db7d5bcbc3cf4c0d88a2ff7e124a2c4b", "pilot"), "token")
    private val other = Account(server, User("0123456789abcdef0123456789abcdef", "guest"), "token2")
    private val softwareKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = SeerrCipher { softwareKey }
    private val pilotJson = """{"id":2,"displayName":"pilot","jellyfinUserId":"db7d5bcbc3cf4c0d88a2ff7e124a2c4b","permissions":32}"""

    private val seerr = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                when {
                    path == "auth/jellyfin" -> respond(
                        pilotJson,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.SetCookie, "connect.sid=s%3Afresh; Path=/; HttpOnly")
                    )
                    path == "user" -> respond(
                        """{"pageInfo":{"pages":1,"results":1},"results":[{"id":1,"jellyfinUserId":"ab3c4f85f313450683238943da5e73b9","permissions":2}]}""",
                        HttpStatusCode.OK
                    )
                    request.headers["Cookie"] == "connect.sid=s%3Afresh" -> respond(pilotJson, HttpStatusCode.OK)
                    else -> respond("{}", HttpStatusCode.Unauthorized)
                }
            }
        }
    }

    private fun <T> withStore(block: suspend (SeerrStore, DataStore<Preferences>) -> T): T = runBlocking {
        val directory = Files.createTempDirectory("seerr-store").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "settings.preferences_pb") }
            block(SeerrStore(data, cipher, seerr), data)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            directory.deleteRecursively()
        }
    }

    @Test fun aJellyfinSignInIsSavedEncryptedForThatAccountOnly() = withStore { store, data ->
        val config = store.connect(pilot, "seerr.example.test/", null, "pilot", "secret-password")
        assertEquals("https://seerr.example.test", config.url)
        assertEquals(SeerrMode.JELLYFIN, config.mode)
        assertEquals(2, config.user.id)
        assertEquals(mapOf(pilot.key to config), store.connections.first())
        // Neither the password nor the cookie is readable in the stored preferences.
        val raw = data.data.first()[SEERR_CONNECTIONS].orEmpty()
        assertFalse("secret-password" in raw)
        assertFalse("fresh" in raw)
        // The stored secret is bound to its account: another account's key can't open it.
        val saved = decodeSeerrConnections(data.data.first()).getValue(pilot.key)
        assertFailsWith<Exception> { cipher.decrypt(other.key, saved.encryptedSecret) }
        assertTrue(store.client(pilot.key) != null)
        assertNull(store.client(other.key))
    }

    @Test fun anApiKeyThatMatchesNoOneConnectsNoOne() = withStore { store, _ ->
        val failure = assertFailsWith<SeerrFailure> { store.connect(pilot, "https://seerr.example.test", "key", "", "") }
        assertEquals(SeerrError.NOT_LINKED, failure.reason)
        assertTrue(store.connections.first().isEmpty())
    }

    @Test fun aBadAddressIsRefusedBeforeAnyCall() = withStore { store, _ ->
        val failure = assertFailsWith<SeerrFailure> { store.connect(pilot, "https://user:pw@seerr.example.test", null, "pilot", "pw") }
        assertEquals(SeerrError.ADDRESS, failure.reason)
    }

    @Test fun signingOutOrForgettingAnAccountDropsItsSeerrConnection() = withStore { store, data ->
        store.connect(pilot, "https://seerr.example.test", null, "pilot", "pw")
        data.edit { retainSeerrAccounts(it, AccountSnapshot(listOf(pilot, other))) }
        assertEquals(setOf(pilot.key), store.connections.first().keys)
        data.edit { retainSeerrAccounts(it, AccountSnapshot(listOf(pilot.copy(signedOut = true), other))) }
        assertTrue(store.connections.first().isEmpty())
        store.connect(pilot, "https://seerr.example.test", null, "pilot", "pw")
        data.edit { retainSeerrAccounts(it, AccountSnapshot(listOf(other))) }
        assertTrue(store.connections.first().isEmpty())
    }

    @Test fun disconnectRemovesOnlyThatAccount() = withStore { store, data ->
        store.connect(pilot, "https://seerr.example.test", null, "pilot", "pw")
        val kept = decodeSeerrConnections(data.data.first()).getValue(pilot.key)
        data.edit { it[SEERR_CONNECTIONS] = seerrJson.encodeToString(mapOf(pilot.key to kept, other.key to kept)) }
        store.disconnect(pilot.key)
        assertEquals(setOf(other.key), store.connections.first().keys)
    }

    @Test fun aCorruptEntryReadsAsNotConnected() = withStore { store, data ->
        data.edit { it[SEERR_CONNECTIONS] = "{not json" }
        assertTrue(store.connections.first().isEmpty())
        assertNull(store.client(pilot.key))
    }
}
