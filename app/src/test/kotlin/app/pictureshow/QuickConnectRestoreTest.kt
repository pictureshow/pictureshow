/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.PendingQuickConnect
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class QuickConnectRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val server = Server("server", "Test server", "https://media.example.test", "12")
    private val account = Account(server, User("viewer", "Viewer"), "fake-token")
    private var persisted: String? = null
    private var failWrite = false
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read() = persisted
        override suspend fun write(value: String) {
            check(!failWrite)
            persisted = value
        }
    })
    private val service = FakeService()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        owners.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun model(saved: SavedStateHandle) = QuickConnectViewModel(server, service, accounts, saved).also {
        owners += ViewModelStore().apply { put("quick", it) }
    }

    /** Parcel the actual Android saved state, then construct a fresh VM with no old flow or scope. */
    @Test fun processDeathRestoresTheCodeAndPollsTheSameSecret() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        first.start()
        runCurrent()
        assertEquals("123456", first.state.value.code)
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(Bundle::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }
        owners.first().clear()
        val next = model(restored)
        next.start()
        runCurrent()
        assertEquals("123456", next.state.value.code)
        assertEquals(1, service.initiations)
        assertEquals(listOf("fake-secret", "fake-secret"), service.polledSecrets)
    }

    @Test fun processDeathRestoresTheInitiatingDeviceWithItsSecret() = runTest(dispatcher) {
        service.nextDeviceId = "fake-initiating-device"
        val saved = SavedStateHandle()
        val first = model(saved)
        first.start()
        runCurrent()
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(saved.savedStateProvider().saveState())
            parcel.setDataPosition(0)
            SavedStateHandle.createHandle(parcel.readBundle(Bundle::class.java.classLoader), null)
        } finally {
            parcel.recycle()
        }
        owners.first().clear()
        model(restored).start()
        runCurrent()
        assertEquals(1, service.initiations)
        assertEquals(listOf<String?>("fake-initiating-device", "fake-initiating-device"), service.polledDeviceIds)
    }

    @Test fun retryResumesThePendingSecretAndSuccessfulSignInClearsIt() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        vm.start()
        runCurrent()
        vm.stop()
        service.authorized = true
        vm.start()
        runCurrent()
        assertEquals(1, service.initiations)
        assertEquals(account, accounts.snapshots.value.currentAccount)
        assertTrue(vm.state.value.signedIn)
        assertTrue(saved.keys().isEmpty())
        assertFalse(persisted!!.contains("fake-secret"))
    }

    @Test
    fun failedAccountSaveRetriesWithoutAnotherExchangeAndKeepsCredentialsOutOfSavedState() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = model(saved)
        service.authorized = true
        failWrite = true
        vm.start()
        runCurrent()
        assertFalse(vm.state.value.signedIn)
        assertTrue(vm.state.value.error!!.contains("save"))
        assertNull(accounts.snapshots.value.currentAccount)
        assertTrue(saved.keys().isEmpty())
        failWrite = false
        vm.start()
        runCurrent()
        assertEquals(1, service.initiations)
        assertEquals(1, service.polledSecrets.size)
        assertEquals(account, accounts.snapshots.value.currentAccount)
    }

    private inner class FakeService : JellyfinService {
        var initiations = 0
        var nextDeviceId: String? = null
        val polledDeviceIds = mutableListOf<String?>()
        var authorized = false
        val polledSecrets = mutableListOf<String>()
        override suspend fun probe(address: String) = ClientResult.Success(server)
        override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
        override suspend fun authenticate(server: Server, username: String, password: String) = ClientResult.Success(account)
        override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(true)
        override suspend fun me(account: Account) = ClientResult.Success(account.user)
        override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        override fun quickConnect(server: Server) = quickConnect(server, null) {}
        override fun quickConnect(
            server: Server,
            pending: PendingQuickConnect?,
            onPending: (PendingQuickConnect?) -> Unit
        ): Flow<QuickConnectState> = flow {
            val attempt = pending ?: PendingQuickConnect("123456", "fake-secret", nextDeviceId).also { initiations++ }
            onPending(attempt)
            emit(QuickConnectState.Code(attempt.code))
            polledSecrets += attempt.secret
            polledDeviceIds += attempt.deviceId
            if (authorized) {
                emit(QuickConnectState.Authorized(account))
            } else {
                awaitCancellation()
            }
        }
    }
}
