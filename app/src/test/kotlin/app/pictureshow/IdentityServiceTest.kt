package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.UserConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class IdentityServiceTest {
    private val brokenPreferences = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("fake disk failure") }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("fake disk failure")
    }

    @Test
    fun deviceIdentityFailureIsAnActionableResultBeforeAnyNetworkRequest() = runTest {
        HttpClient(MockEngine { error("No network request should be made without a device ID") }).use { http ->
            val service = PersistentIdentityService(http, DeviceIdentityStore(brokenPreferences), "Test TV")
            val result = assertIs<ClientResult.Failure>(service.probe("media.example.test"))
            assertEquals(ErrorKind.LOCAL_STORAGE, result.error.kind)
            val account = Account(Server("s", "Test", "https://media.example.test", "12.0.0"), User("u", "Sam"), "fake")
            assertEquals(ErrorKind.LOCAL_STORAGE, assertIs<ClientResult.Failure>(service.configuration(account)).error.kind)
            assertEquals(
                ErrorKind.LOCAL_STORAGE,
                assertIs<ClientResult.Failure>(service.saveConfiguration(account, UserConfiguration(JsonObject(emptyMap())))).error.kind
            )
        }
    }

    @Test
    fun actualAppServiceForwardsHomeStorageAndFilterChoicesWithViewerDeviceIdentity() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine(
            io.ktor.client.engine.mock.MockEngineConfig().apply {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    paths += request.url.encodedPath
                    assertEquals("u", request.url.parameters["userId"])
                    kotlin.test.assertTrue(request.headers[io.ktor.http.HttpHeaders.Authorization].orEmpty().contains("viewer-device"))
                    when (request.url.encodedPath) {
                        "/DisplayPreferences/usersettings" -> {
                            assertEquals("pictureshow", request.url.parameters["client"])
                            if (request.method == io.ktor.http.HttpMethod.Post) {
                                assertEquals("{\"CustomPrefs\":{}}", request.body.toByteArray().decodeToString())
                                respond("", io.ktor.http.HttpStatusCode.NoContent)
                            } else {
                                respond("{\"CustomPrefs\":{}}")
                            }
                        }
                        "/Items/lib" -> respond("{\"Id\":\"lib\"}")
                        "/Items/Filters" -> respond("{\"Genres\":[\"Animation\"]}")
                        "/Persons" -> respond("{\"Items\":[{\"Id\":\"person\",\"Name\":\"Permitted person\"}]}")
                        else -> error("Unexpected route")
                    }
                }
            }
        )
        HttpClient(engine).use { http ->
            val service = PersistentIdentityService(http, DeviceIdentityStore(brokenPreferences), "Test TV")
            val account =
                Account(Server("s", "Test", "https://media.example.test", "12.0.0"), User("u", "Sam"), "fake", deviceId = "viewer-device")
            assertIs<ClientResult.Success<JsonObject>>(service.displayPreferences(account))
            assertIs<ClientResult.Success<Unit>>(
                service.saveDisplayPreferences(
                    account,
                    JsonObject(
                        mapOf(
                            "CustomPrefs" to JsonObject(emptyMap())
                        )
                    )
                )
            )
            assertEquals(
                listOf("Animation"),
                assertIs<ClientResult.Success<app.pictureshow.core.LibraryFacets>>(service.facets(account, "lib")).value.genres
            )
            assertEquals(
                "person",
                assertIs<ClientResult.Success<app.pictureshow.core.ItemPage>>(
                    service.namedItems(account, "Persons", "lib", "", 0)
                ).value.items.single().id
            )
            assertEquals(
                listOf(
                    "/DisplayPreferences/usersettings",
                    "/DisplayPreferences/usersettings",
                    "/Items/lib",
                    "/Items/Filters",
                    "/Items/lib",
                    "/Persons"
                ),
                paths
            )
        }
    }

    @Test
    fun quickConnectIdentityFailureEndsWithAnErrorState() = runTest {
        HttpClient(MockEngine { error("No request should be made") }).use { http ->
            val service = PersistentIdentityService(http, DeviceIdentityStore(brokenPreferences), "Test TV")
            val states = service.quickConnect(Server("s", "Home", "https://media.example.test", "12.0.0")).toList()
            assertEquals(1, states.size)
            assertEquals(ErrorKind.LOCAL_STORAGE, assertIs<QuickConnectState.Failed>(states.single()).error.kind)
        }
    }
}
