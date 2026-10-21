package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

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
