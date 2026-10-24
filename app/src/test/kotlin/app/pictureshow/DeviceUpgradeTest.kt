/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class DeviceUpgradeTest {
    private val server = Server("server", "Cinema", "https://media.example.test", "12.0.0")
    private val first = Account(server, User("a", "Viewer A"), "fake-old-a")
    private val second = first.copy(user = User("b", "Viewer B"), accessToken = "fake-old-b")
    private var saved: String? = null
    private var failSave = false
    private val persistence = object : TextPersistence {
        override suspend fun read() = saved
        override suspend fun write(value: String) {
            check(!failSave)
            saved = value
        }
    }
    private val accounts = AccountStore(persistence)
    private val revoked = mutableListOf<Account>()
    private val attempted = mutableListOf<Account>()
    private var failureFor: String? = null
    private var failureKind = ErrorKind.NETWORK
    private var failLogout = false
    private var allowLegacySiblingOnLogout = false
    private var now = 1_000L
    private var attemptDuration = 0L
    private val service = object : JellyfinService {
        override suspend fun probe(address: String) = ClientResult.Success(server)
        override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
        override suspend fun authenticate(server: Server, username: String, password: String) = ClientResult.Success(first)
        override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(true)
        override fun quickConnect(server: Server) = emptyFlow<QuickConnectState>()
        override suspend fun me(account: Account) = ClientResult.Success(account.user)
        override suspend fun separateDevice(account: Account): ClientResult<Account> {
            assertEquals(account, accounts.snapshots.value.accounts.first { it.key == account.key })
            attempted += account
            now += attemptDuration
            if (account.key == failureFor) return ClientResult.Failure(ClientError(failureKind, "Unavailable"))
            return ClientResult.Success(account.copy(accessToken = "fake-new-${account.user.id}", deviceId = "device-${account.user.id}"))
        }
        override suspend fun logout(account: Account): ClientResult<Unit> {
            if (account.deviceId == null && !allowLegacySiblingOnLogout) {
                assertFalse(accounts.snapshots.value.accounts.any { !it.expired && it.deviceId == null && it.accessToken.isNotBlank() })
            }
            revoked += account
            return if (failLogout) ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Unavailable")) else ClientResult.Success(Unit)
        }
    }

    private suspend fun seed(current: Account = second) {
        accounts.remember(first)
        accounts.remember(second)
        accounts.select(current.key)
    }

    @Test fun eachViewerIsSavedBeforeRetirementAndCurrentSelectionSurvives() = runTest {
        seed()
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf("device-a", "device-b"), accounts.snapshots.value.accounts.map { it.deviceId })
        assertEquals(listOf(second, first), attempted)
        assertEquals(listOf(second, first), revoked)
        assertEquals(second.key, accounts.snapshots.value.currentKey)
        assertTrue(accounts.snapshots.value.retiredDeviceAccounts.isEmpty())
        assertEquals(accounts.snapshots.value, AccountStore(persistence).load())
    }

    @Test fun partialUpgradeConvergesWithoutLoggingOutTheRemainingLegacyViewer() = runTest {
        seed(first)
        failureFor = second.key
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf("device-a", null), accounts.snapshots.value.accounts.map { it.deviceId })
        assertEquals(listOf(first), accounts.snapshots.value.retiredDeviceAccounts)
        assertTrue(revoked.isEmpty())
        assertEquals(accounts.snapshots.value, AccountStore(persistence).load())
        // A new launch during backoff makes no new server device rows.
        separateLegacyDevices(accounts, service) { now }
        assertEquals(2, attempted.size)
        now += 31 * 60 * 1_000L
        failureFor = null
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf("device-a", "device-b"), accounts.snapshots.value.accounts.map { it.deviceId })
        assertEquals(listOf(first, second), revoked)
        assertTrue(accounts.snapshots.value.retiredDeviceAccounts.isEmpty())
    }

    @Test fun serverRefusalKeepsValidLegacyTokensAndBacksOff() = runTest {
        seed()
        failureFor = second.key
        failureKind = ErrorKind.SERVER
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(first, second), accounts.snapshots.value.accounts)
        assertEquals(now + 30 * 60 * 1_000L, accounts.snapshots.value.deviceUpgradeRetryAfter[server.id])
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(second), attempted)
        assertTrue(revoked.isEmpty())
    }

    @Test fun revokedLegacyTokenExpiresWithoutBlockingAnotherViewer() = runTest {
        seed()
        failureFor = second.key
        failureKind = ErrorKind.INVALID_CREDENTIALS
        separateLegacyDevices(accounts, service) { now }
        val snapshot = accounts.snapshots.value
        assertEquals("device-a", snapshot.accounts.first().deviceId)
        assertTrue(snapshot.accounts.last().expired)
        assertEquals("", snapshot.accounts.last().accessToken)
        assertEquals(listOf(first), revoked)
        assertFalse(snapshot.deviceUpgradeRetryAfter.containsKey(server.id))
    }

    @Test fun slowViewerCommitsAndTheNextLaunchContinuesWhereItStopped() = runTest {
        seed()
        attemptDuration = 3_100
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(null, "device-b"), accounts.snapshots.value.accounts.map { it.deviceId })
        assertEquals(listOf(second), attempted)
        assertTrue(revoked.isEmpty())
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf("device-a", "device-b"), accounts.snapshots.value.accounts.map { it.deviceId })
        assertEquals(listOf(second, first), revoked)
    }

    @Test fun failedSavePreservesOriginalTokensAndCleansTheFreshAttempt() = runTest {
        seed()
        failSave = true
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(first, second), accounts.snapshots.value.accounts)
        assertEquals(listOf("device-b"), revoked.map { it.deviceId })
    }

    @Test fun failedRetirementIsPersistedAndRetriedOnTheNextLaunch() = runTest {
        seed()
        failLogout = true
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(second, first), accounts.snapshots.value.retiredDeviceAccounts)
        assertEquals(accounts.snapshots.value, AccountStore(persistence).load())
        failLogout = false
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(second, first, second, first), revoked)
        assertTrue(accounts.snapshots.value.retiredDeviceAccounts.isEmpty())
        assertEquals(2, attempted.size)
    }

    @Test fun signingInAnAlreadySavedAccountRevokesItsReplacedTokenAfterSavingTheNewOne() = runTest {
        val old = first.copy(deviceId = "device-before")
        accounts.remember(old)
        val replacement = old.copy(deviceId = "device-after", accessToken = "fake-replacement")
        rememberSignedIn(accounts, service, replacement)
        assertEquals(replacement, accounts.snapshots.value.currentAccount)
        assertEquals(listOf(old), revoked)
    }

    @Test fun replacingOneLegacyAccountQueuesCleanupUntilTheSharedDeviceIsUnused() = runTest {
        seed()
        rememberSignedIn(accounts, service, first.copy(deviceId = "device-new", accessToken = "fake-new"))
        assertTrue(revoked.isEmpty())
        assertEquals(listOf(first), accounts.snapshots.value.retiredDeviceAccounts)
        assertEquals(second, accounts.snapshots.value.accounts.first { it.key == second.key })
        separateLegacyDevices(accounts, service) { now }
        assertEquals(listOf(first, second), revoked)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun profileSignOutRevokesBothTheCurrentAndQueuedCredential() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = androidx.lifecycle.ViewModelStore()
        try {
            seed()
            val fresh = first.copy(deviceId = "device-new", accessToken = "fake-new")
            accounts.remember(fresh)
            assertEquals(listOf(first), accounts.snapshots.value.retiredDeviceAccounts)
            allowLegacySiblingOnLogout = true
            val model = HomeViewModel(fresh, service, accounts)
            models.put("profile", model)
            runCurrent()
            model.signOut()
            advanceUntilIdle()
            assertEquals(listOf(fresh, first), revoked)
            assertEquals(listOf(second), accounts.snapshots.value.accounts)
            assertTrue(accounts.snapshots.value.retiredDeviceAccounts.isEmpty())
            assertTrue(model.state.value.finished)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun explicitRemovalAlsoForgetsQueuedCredentials() = runTest {
        seed()
        rememberSignedIn(accounts, service, first.copy(deviceId = "device-new", accessToken = "fake-new"))
        accounts.removeAccount(first.key)
        assertTrue(accounts.snapshots.value.retiredDeviceAccounts.isEmpty())
        assertEquals(listOf(second), accounts.snapshots.value.accounts)
    }
}
