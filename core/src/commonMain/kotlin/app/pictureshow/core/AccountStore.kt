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
data class AccountSnapshot(
    val accounts: List<Account> = emptyList(),
    val currentKey: String? = null,
    val recentSearches: Map<String, List<String>> = emptyMap()
) {
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
        snapshot.copy(accounts = snapshot.accounts.filterNot { it.key == account.key } + account, currentKey = account.key)
    }

    /** Public metadata cannot replace identity, addresses or credentials, including after a delayed reply. */
    suspend fun refreshServer(expected: Server, reported: Server) = mutex.withLock {
        if (reported.id != expected.id) return@withLock
        val before = loadLocked()
        val after = before.copy(
            accounts = before.accounts.map { account ->
                if (account.server.id == expected.id && account.server.url == expected.url) {
                    account.copy(server = account.server.copy(name = reported.name, version = reported.version))
                } else {
                    account
                }
            }
        )
        if (after == before) return@withLock
        persistence.write(json.encodeToString(after))
        mutableSnapshots.value = after
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
            currentKey = snapshot.currentKey.takeUnless { it == key },
            recentSearches = snapshot.recentSearches - key
        )
    }

    suspend fun recordSearch(key: String, query: String) = update { snapshot ->
        val term = normalizeSearch(query)
        if (term.length < 2 || snapshot.accounts.none { it.key == key }) {
            snapshot
        } else {
            val recent = listOf(term) + snapshot.recentSearches[key].orEmpty().filterNot { it.equals(term, ignoreCase = true) }
            snapshot.copy(recentSearches = snapshot.recentSearches + (key to recent.take(8)))
        }
    }

    suspend fun clearSearches(key: String) = update { it.copy(recentSearches = it.recentSearches - key) }

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
