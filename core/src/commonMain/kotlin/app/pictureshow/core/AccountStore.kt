package app.pictureshow.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface TextPersistence {
    suspend fun read(): String?

    suspend fun write(value: String)
}

@Serializable
data class AccountSnapshot(val accounts: List<Account> = emptyList(), val currentKey: String? = null) {
    val currentAccount: Account? get() = accounts.firstOrNull { it.key == currentKey }
}

/** Writes commit before publishing state, so failed persistence never pretends a sign-in was saved. */
class AccountStore(private val persistence: TextPersistence) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableSnapshots = MutableStateFlow(AccountSnapshot())
    val snapshots: StateFlow<AccountSnapshot> = mutableSnapshots.asStateFlow()
    private var loaded = false

    suspend fun load(): AccountSnapshot = mutex.withLock { loadLocked() }

    suspend fun remember(account: Account) = update { snapshot ->
        AccountSnapshot(snapshot.accounts.filterNot { it.key == account.key } + account, account.key)
    }

    suspend fun select(key: String?) = update { snapshot ->
        require(key == null || snapshot.accounts.any { it.key == key }) { "That account is not saved." }
        snapshot.copy(currentKey = key)
    }

    suspend fun expire(key: String, accessToken: String) = update { snapshot ->
        snapshot.copy(
            accounts = snapshot.accounts.map {
                if (it.key == key &&
                    it.accessToken == accessToken
                ) {
                    it.copy(accessToken = "", expired = true)
                } else {
                    it
                }
            }
        )
    }

    suspend fun signOut(key: String) = update { snapshot ->
        snapshot.copy(
            accounts = snapshot.accounts.filterNot { it.key == key },
            currentKey = snapshot.currentKey.takeUnless { it == key }
        )
    }

    private suspend fun loadLocked(): AccountSnapshot {
        if (!loaded) {
            val text = persistence.read()
            val snapshot = text?.let { json.decodeFromString<AccountSnapshot>(it) } ?: AccountSnapshot()
            mutableSnapshots.value = snapshot.copy(currentKey = snapshot.currentAccount?.key)
            loaded = true
        }
        return mutableSnapshots.value
    }

    private suspend fun update(transform: (AccountSnapshot) -> AccountSnapshot) = mutex.withLock {
        val next = transform(loadLocked())
        persistence.write(json.encodeToString(next))
        mutableSnapshots.value = next
    }
}
