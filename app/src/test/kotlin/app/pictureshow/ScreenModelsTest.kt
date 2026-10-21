package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val owned = mutableListOf<ViewModelStore>()
    private val server = Server("server-1", "Living room", "https://media.example.test/jellyfin", "12.0.0")
    private val account = Account(server, User("user-1", "Pat"), "made-up-token")
    private lateinit var memory: Memory
    private lateinit var accounts: AccountStore
    private lateinit var service: FakeService

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        memory = Memory()
        accounts = AccountStore(memory)
        service = FakeService(account)
    }

    @AfterTest
    fun tearDown() {
        owned.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> own(vm: T): T = vm.also { model -> owned += ViewModelStore().also { it.put("test", model) } }

    @Test
    fun startupRestoresSelectedAccountAndObservesSwitches() = runTest(dispatcher) {
        accounts.remember(account)
        val reopened = AccountStore(memory)
        val vm = own(SessionViewModel(reopened))
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertEquals(account, vm.state.value.snapshot.currentAccount)
        assertFalse(vm.state.value.loading)
        reopened.select(null)
        runCurrent()
        assertNull(vm.state.value.snapshot.currentKey)
        assertEquals(listOf(account), vm.state.value.snapshot.accounts)
    }

    @Test
    fun startupReadFailureCanBeRetried() = runTest(dispatcher) {
        memory.failRead = true
        val vm = own(SessionViewModel(accounts))
        runCurrent()
        assertTrue(vm.state.value.error != null)
        memory.failRead = false
        vm.load()
        runCurrent()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun discoveryAndTypedAddressReachConnectedServer() = runTest(dispatcher) {
        val found = DiscoveredServer(server.url, server.id, server.name)
        val vm = own(
            ServerViewModel(
                service,
                discovery {
                    delay(100)
                    listOf(found)
                }
            )
        )
        vm.scan()
        runCurrent()
        assertTrue(vm.state.value.scanning)
        advanceUntilIdle()
        assertEquals(listOf(found), vm.state.value.discovered)
        vm.address("media.example.test/jellyfin")
        vm.connect()
        advanceUntilIdle()
        assertEquals("media.example.test/jellyfin", service.probed)
        assertEquals(server, vm.state.value.connected)
        vm.leave()
        assertNull(vm.state.value.connected)
    }

    @Test
    fun serverErrorsAndDiscoveryFailuresRemainActionable() = runTest(dispatcher) {
        service.probeResult = failure(ErrorKind.NOT_JELLYFIN)
        val vm = own(ServerViewModel(service, discovery { error("network interface unavailable") }))
        vm.scan()
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("Enter"))
        vm.connect("wrong.example.test")
        advanceUntilIdle()
        assertEquals("NOT_JELLYFIN", vm.state.value.error)
        assertFalse(vm.state.value.connecting)
    }

    @Test
    fun leavingServerScreenCancelsOutstandingDiscovery() = runTest(dispatcher) {
        var completed = false
        val vm = own(
            ServerViewModel(
                service,
                discovery {
                    delay(5000)
                    completed = true
                    emptyList()
                }
            )
        )
        vm.scan()
        runCurrent()
        vm.leave()
        advanceUntilIdle()
        assertFalse(completed)
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun hiddenUsersStillAllowPasswordSignInAndClearPasswordOnSuccess() = runTest(dispatcher) {
        val vm = own(LoginViewModel(server, service, accounts))
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.users)
        assertFalse(vm.state.value.quickConnect)
        vm.username("Pat")
        vm.password("test password")
        vm.signIn()
        advanceUntilIdle()
        assertEquals("Pat" to "test password", service.credentials)
        assertEquals(account, accounts.snapshots.value.currentAccount)
        assertTrue(vm.state.value.signedIn)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun publicUsersAndEnabledQuickConnectAppear() = runTest(dispatcher) {
        service.users = ClientResult.Success(listOf(account.user))
        service.enabled = ClientResult.Success(true)
        val vm = own(LoginViewModel(server, service, accounts))
        advanceUntilIdle()
        assertEquals(listOf(account.user), vm.state.value.users)
        assertTrue(vm.state.value.quickConnect)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun passwordErrorsDoNotSaveAccountAndCanBeRetried() = runTest(dispatcher) {
        for (kind in listOf(ErrorKind.INVALID_CREDENTIALS, ErrorKind.NETWORK, ErrorKind.SERVER)) {
            service.auth = failure(kind)
            val vm = own(LoginViewModel(server, service, accounts))
            advanceUntilIdle()
            vm.username("Pat")
            vm.password("wrong")
            vm.signIn()
            advanceUntilIdle()
            assertEquals(kind.name, vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertNull(accounts.snapshots.value.currentAccount)
            service.auth = ClientResult.Success(account)
            vm.signIn()
            advanceUntilIdle()
            assertTrue(vm.state.value.signedIn)
            accounts.signOut(account.key)
        }
    }

    @Test
    fun blankUsernameIsRejectedAndLeavingClearsPassword() = runTest(dispatcher) {
        val vm = own(LoginViewModel(server, service, accounts))
        advanceUntilIdle()
        vm.password("not persisted")
        vm.signIn()
        assertTrue(vm.state.value.error != null)
        assertNull(service.credentials)
        vm.leave()
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun passwordSignInDoesNotClaimSuccessWhenPersistenceFails() = runTest(dispatcher) {
        val vm = own(LoginViewModel(server, service, accounts))
        advanceUntilIdle()
        memory.failWrite = true
        vm.username("Pat")
        vm.signIn()
        advanceUntilIdle()
        assertFalse(vm.state.value.signedIn)
        assertTrue(vm.state.value.error!!.contains("save"))
        assertNull(accounts.snapshots.value.currentAccount)
    }

    @Test
    fun quickConnectCodeThenTokenSavesAccount() = runTest(dispatcher) {
        service.quick = flow {
            emit(QuickConnectState.Code("123456"))
            delay(5000)
            emit(QuickConnectState.Authorized(account))
        }
        val vm = own(QuickConnectViewModel(server, service, accounts))
        vm.start()
        runCurrent()
        assertEquals("123456", vm.state.value.code)
        assertFalse(vm.state.value.signedIn)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(vm.state.value.signedIn)
        assertEquals(account, accounts.snapshots.value.currentAccount)
    }

    @Test
    fun quickConnectDisabledErrorsAndCancellationAreVisible() = runTest(dispatcher) {
        service.quick = flowOf(QuickConnectState.Disabled)
        val vm = own(QuickConnectViewModel(server, service, accounts))
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.disabled)
        service.quick = flowOf(QuickConnectState.Failed(ClientError(ErrorKind.SERVER, "Unavailable")))
        vm.start()
        runCurrent()
        assertEquals("Unavailable", vm.state.value.error)
        var authorized = false
        service.quick = flow {
            emit(QuickConnectState.Code("654321"))
            delay(5000)
            authorized = true
            emit(QuickConnectState.Authorized(account))
        }
        vm.start()
        runCurrent()
        vm.stop()
        advanceUntilIdle()
        assertFalse(authorized)
        assertNull(accounts.snapshots.value.currentAccount)
    }

    @Test
    fun quickConnectPersistenceFailureCanStartAgain() = runTest(dispatcher) {
        service.quick = flowOf(QuickConnectState.Authorized(account))
        memory.failWrite = true
        val vm = own(QuickConnectViewModel(server, service, accounts))
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.error!!.contains("save"))
        assertFalse(vm.state.value.signedIn)
        memory.failWrite = false
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.signedIn)
    }

    @Test
    fun accountsScreenSwitchesSelectedAccountAndReportsFailedWrites() = runTest(dispatcher) {
        val other = account.copy(user = User("other", "Robin"))
        accounts.remember(account)
        accounts.remember(other)
        val vm = own(AccountsViewModel(accounts))
        vm.select(account.key)
        advanceUntilIdle()
        assertTrue(vm.state.value.selected)
        assertEquals(account.key, accounts.snapshots.value.currentKey)
        memory.failWrite = true
        vm.select(other.key)
        advanceUntilIdle()
        assertTrue(vm.state.value.error != null)
        assertEquals(account.key, accounts.snapshots.value.currentKey)
    }

    @Test
    fun homeShowsVerifiedNameAndSwitchUserKeepsAllAccounts() = runTest(dispatcher) {
        accounts.remember(account)
        service.user = ClientResult.Success(account.user.copy(name = "Updated name"))
        val vm = own(HomeViewModel(account, service, accounts))
        advanceUntilIdle()
        assertEquals("Updated name", vm.state.value.account.user.name)
        vm.switchUser()
        advanceUntilIdle()
        assertEquals(listOf(account), accounts.snapshots.value.accounts)
        assertNull(accounts.snapshots.value.currentKey)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun signOutRevokesTokenAndRemovesOnlyCurrentAccountEvenOffline() = runTest(dispatcher) {
        val other = account.copy(server = server.copy(id = "server-2"), user = User("u2", "Robin"))
        accounts.remember(other)
        accounts.remember(account)
        service.logoutResult = failure(ErrorKind.NETWORK)
        val vm = own(HomeViewModel(account, service, accounts))
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        assertEquals(account, service.loggedOut)
        assertEquals(listOf(other), accounts.snapshots.value.accounts)
        assertNull(accounts.snapshots.value.currentKey)
    }

    @Test
    fun switchingWhileVerificationIsPendingCannotReselectAccount() = runTest(dispatcher) {
        val pending = CompletableDeferred<ClientResult<User>>()
        service.meBlock = { pending.await() }
        accounts.remember(account)
        val vm = own(HomeViewModel(account, service, accounts))
        runCurrent()
        vm.switchUser()
        runCurrent()
        pending.complete(ClientResult.Success(account.user))
        advanceUntilIdle()
        assertNull(accounts.snapshots.value.currentKey)
    }

    @Test
    fun homeVerificationFailureRetainsSavedAccount() = runTest(dispatcher) {
        accounts.remember(account)
        service.user = failure(ErrorKind.INVALID_CREDENTIALS)
        val vm = own(HomeViewModel(account, service, accounts))
        vm.state.test {
            assertEquals(account, awaitItem().account)
            runCurrent()
            assertTrue(awaitItem().error!!.contains("expired"))
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(account, accounts.snapshots.value.currentAccount)
    }

    private fun discovery(block: suspend () -> List<DiscoveredServer>) = object : ServerDiscovery {
        override suspend fun discover() = block()
    }

    private fun failure(kind: ErrorKind) = ClientResult.Failure(ClientError(kind, kind.name))

    private class Memory : TextPersistence {
        var value: String? = null
        var failWrite = false
        var failRead = false
        override suspend fun read(): String? {
            check(!failRead)
            return value
        }
        override suspend fun write(value: String) {
            check(!failWrite)
            this.value = value
        }
    }

    private class FakeService(private val account: Account) : JellyfinService {
        var probed: String? = null
        var credentials: Pair<String, String>? = null
        var loggedOut: Account? = null
        var probeResult: ClientResult<Server> = ClientResult.Success(account.server)
        var users: ClientResult<List<User>> = ClientResult.Success(emptyList())
        var enabled: ClientResult<Boolean> = ClientResult.Success(false)
        var auth: ClientResult<Account> = ClientResult.Success(account)
        var user: ClientResult<User> = ClientResult.Success(account.user)
        var logoutResult: ClientResult<Unit> = ClientResult.Success(Unit)
        var quick: Flow<QuickConnectState> = flowOf(QuickConnectState.Disabled)
        var meBlock: (suspend () -> ClientResult<User>)? = null
        override suspend fun probe(address: String): ClientResult<Server> {
            probed = address
            return probeResult
        }
        override suspend fun publicUsers(server: Server) = users
        override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> {
            credentials =
                username to password
            return auth
        }
        override suspend fun quickConnectEnabled(server: Server) = enabled
        override fun quickConnect(server: Server) = quick
        override suspend fun me(account: Account) = meBlock?.invoke() ?: user
        override suspend fun logout(account: Account): ClientResult<Unit> {
            loggedOut = account
            return logoutResult
        }
    }
}
