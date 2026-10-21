package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SAVE_ERROR = "Could not save accounts on this device. Please try again."

class SessionViewModel(private val accounts: AccountStore) : ViewModel() {
    data class State(val loading: Boolean = true, val snapshot: AccountSnapshot = AccountSnapshot(), val error: String? = null)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null

    init {
        load()
    }

    fun load() {
        if (loading?.isActive == true) return
        loading = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                accounts.load()
                accounts.snapshots.collect { snapshot -> mutableState.value = State(false, snapshot) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = State(false, error = "Could not read saved accounts. Please retry.")
            }
        }
    }
}

data class ServerState(
    val address: String = "",
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
    val connected: Server? = null,
    val error: String? = null
)

class ServerViewModel(private val service: JellyfinService, private val discovery: ServerDiscovery) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerState())
    val state = mutableState.asStateFlow()
    private var scanJob: Job? = null
    private var connectJob: Job? = null

    fun address(value: String) {
        mutableState.update { it.copy(address = value, error = null) }
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutableState.update { it.copy(scanning = true, error = null) }
            try {
                val found = discovery.discover()
                mutableState.update { it.copy(discovered = found) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(error = "Discovery is unavailable. Enter the server address instead.") }
            } finally {
                mutableState.update { it.copy(scanning = false) }
            }
        }
    }

    fun connect(address: String = state.value.address) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            mutableState.update { it.copy(connecting = true, connected = null, error = null) }
            when (val result = service.probe(address)) {
                is ClientResult.Success -> mutableState.update { it.copy(connecting = false, connected = result.value) }
                is ClientResult.Failure -> mutableState.update { it.copy(connecting = false, error = result.error.message) }
            }
        }
    }

    fun consumeConnected() {
        mutableState.update { it.copy(connected = null) }
    }

    fun leave() {
        scanJob?.cancel()
        connectJob?.cancel()
        mutableState.update { it.copy(connected = null, connecting = false) }
    }
}

data class LoginState(
    val loading: Boolean = true,
    val users: List<User> = emptyList(),
    val username: String = "",
    val password: String = "",
    val quickConnect: Boolean = false,
    val busy: Boolean = false,
    val signedIn: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

class LoginViewModel(val server: Server, private val service: JellyfinService, private val accounts: AccountStore) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginState())
    val state = mutableState.asStateFlow()
    private var signInJob: Job? = null

    init {
        viewModelScope.launch {
            val users = service.publicUsers(server)
            val quick = service.quickConnectEnabled(server)
            mutableState.update {
                it.copy(
                    loading = false,
                    users = (users as? ClientResult.Success)?.value.orEmpty(),
                    quickConnect = (quick as? ClientResult.Success)?.value == true,
                    notice = if (users is ClientResult.Failure || quick is ClientResult.Failure) {
                        "Some sign-in options could not be loaded. You can still enter a username and password."
                    } else {
                        null
                    }
                )
            }
        }
    }

    fun username(value: String) {
        mutableState.update { it.copy(username = value, error = null) }
    }
    fun password(value: String) {
        mutableState.update { it.copy(password = value, error = null) }
    }

    fun signIn() {
        if (signInJob?.isActive == true || state.value.signedIn) return
        val input = state.value
        if (input.username.isBlank()) {
            mutableState.update { it.copy(error = "Enter your username.") }
            return
        }
        signInJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            when (val result = service.authenticate(server, input.username.trim(), input.password)) {
                is ClientResult.Failure -> mutableState.update { it.copy(busy = false, error = result.error.message) }
                is ClientResult.Success -> try {
                    accounts.remember(result.value)
                    mutableState.update { it.copy(busy = false, signedIn = true, password = "") }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.update { it.copy(busy = false, password = "", error = SAVE_ERROR) }
                }
            }
        }
    }

    fun leave() {
        signInJob?.cancel()
        mutableState.update { it.copy(password = "", busy = false) }
    }
}

data class QuickState(
    val code: String? = null,
    val busy: Boolean = true,
    val signedIn: Boolean = false,
    val disabled: Boolean = false,
    val error: String? = null
)

class QuickConnectViewModel(val server: Server, private val service: JellyfinService, private val accounts: AccountStore) : ViewModel() {
    private val mutableState = MutableStateFlow(QuickState())
    val state = mutableState.asStateFlow()
    private var polling: Job? = null

    fun start() {
        if (polling?.isActive == true || state.value.signedIn) return
        mutableState.value = QuickState()
        polling = viewModelScope.launch {
            service.quickConnect(server).collect { result ->
                when (result) {
                    is QuickConnectState.Code -> mutableState.value = QuickState(code = result.code, busy = false)
                    is QuickConnectState.Failed -> mutableState.update { it.copy(busy = false, error = result.error.message) }
                    QuickConnectState.Disabled -> mutableState.value = QuickState(busy = false, disabled = true)
                    is QuickConnectState.Authorized -> try {
                        accounts.remember(result.account)
                        mutableState.update { it.copy(busy = false, signedIn = true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        mutableState.update { it.copy(busy = false, error = SAVE_ERROR) }
                    }
                }
            }
        }
    }

    fun stop() {
        polling?.cancel()
        polling = null
    }
}

data class AccountsState(val busy: Boolean = false, val selected: Boolean = false, val error: String? = null)

class AccountsViewModel(private val accounts: AccountStore) : ViewModel() {
    private val mutableState = MutableStateFlow(AccountsState())
    val state = mutableState.asStateFlow()
    val snapshots = accounts.snapshots

    fun select(key: String) {
        if (state.value.busy) return
        mutableState.value = AccountsState(busy = true)
        viewModelScope.launch {
            try {
                accounts.select(key)
                mutableState.value = AccountsState(selected = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = AccountsState(error = SAVE_ERROR)
            }
        }
    }
}

data class HomeState(val account: Account, val busy: Boolean = false, val finished: Boolean = false, val error: String? = null)

class HomeViewModel(account: Account, private val service: JellyfinService, private val accounts: AccountStore) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeState(account))
    val state = mutableState.asStateFlow()

    private val verifyJob: Job

    init {
        verifyJob = viewModelScope.launch {
            when (val result = service.me(account)) {
                is ClientResult.Success -> {
                    val updated = account.copy(user = result.value)
                    mutableState.update { it.copy(account = updated) }
                }
                is ClientResult.Failure -> mutableState.update {
                    it.copy(
                        error = if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                            "This saved sign-in has expired. Sign out and sign in again."
                        } else {
                            "Could not verify the server connection. Your saved sign-in is still available."
                        }
                    )
                }
            }
        }
    }

    fun switchUser() = changeAccount(signOut = false)
    fun signOut() = changeAccount(signOut = true)

    private fun changeAccount(signOut: Boolean) {
        if (state.value.busy) return
        verifyJob.cancel()
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val account = state.value.account
                // Best effort remote revocation; a network failure must still allow local sign-out.
                if (signOut) service.logout(account)
                if (signOut) accounts.signOut(account.key) else accounts.select(null)
                mutableState.update { it.copy(busy = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.update { it.copy(busy = false, error = SAVE_ERROR) }
            }
        }
    }
}
