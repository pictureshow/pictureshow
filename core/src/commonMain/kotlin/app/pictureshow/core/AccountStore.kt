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
    val recentSearches: Map<String, List<String>> = emptyMap(),
    val lastServer: Server? = null,
    val retiredDeviceAccounts: List<Account> = emptyList(),
    val deviceUpgradeRetryAfter: Map<String, Long> = emptyMap(),
    val homePreferences: Map<String, HomePreferences> = emptyMap(),
    val pendingHomePreferences: Set<String> = emptySet()
) {
    val currentAccount: Account? get() = accounts.firstOrNull { it.key == currentKey }
    val rememberedServers: List<Server> get() = (listOfNotNull(lastServer) + accounts.map { it.server }).distinctBy { it.id to it.url }
}

/**
 * What an entry for this server is called, against the others listed with it. Jellyfin's name is free text and defaults to
 * the machine's hostname, so two entries can share it, and one server added by several addresses always does. The address
 * then tells them apart. A server with no name is its address.
 */
fun serverLabel(server: Server, all: List<Server>): String {
    val name = server.name.trim()
    if (name.isEmpty()) return server.url
    val repeats = all.distinctBy { it.id to it.url }.count { it.name.trim().equals(name, ignoreCase = true) } > 1
    return if (repeats) "$name · ${server.url}" else name
}

fun AccountSnapshot.serverLabel(server: Server): String = serverLabel(server, rememberedServers)

/** Writes commit before publishing state, so failed persistence never pretends a sign-in was saved. */
class AccountStore(private val persistence: TextPersistence) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableSnapshots = MutableStateFlow(AccountSnapshot())
    val snapshots: StateFlow<AccountSnapshot> = mutableSnapshots.asStateFlow()
    private var loaded = false

    suspend fun load(): AccountSnapshot = mutex.withLock { loadLocked() }

    suspend fun remember(account: Account): Account? = mutex.withLock {
        val before = loadLocked()
        val previous = before.accounts.firstOrNull { it.key == account.key }
        val next = before.copy(
            accounts = before.accounts.filterNot { it.key == account.key } + account,
            currentKey = account.key,
            retiredDeviceAccounts = (
                before.retiredDeviceAccounts + listOfNotNull(
                    previous?.takeIf {
                        it.accessToken.isNotBlank() && it.accessToken != account.accessToken && it.deviceId != account.deviceId
                    }
                )
                ).distinctBy { it.key to it.accessToken }
        )
        persistence.write(json.encodeToString(next))
        mutableSnapshots.value = next
        previous
    }

    /** Replace matching credentials without resurrecting a removed or concurrently replaced viewer. */
    suspend fun replaceCredentials(expected: List<Account>, replacements: List<Account>): Boolean = mutex.withLock {
        require(expected.map { it.key }.toSet() == replacements.map { it.key }.toSet())
        val before = loadLocked()
        if (expected.any { old -> before.accounts.firstOrNull { it.key == old.key } != old }) return@withLock false
        val byKey = replacements.associateBy { it.key }
        val next = before.copy(
            accounts = before.accounts.map { byKey[it.key] ?: it },
            retiredDeviceAccounts = (before.retiredDeviceAccounts + expected).distinctBy { it.key to it.accessToken }
        )
        persistence.write(json.encodeToString(next))
        mutableSnapshots.value = next
        true
    }

    suspend fun completeDeviceRetirement(account: Account) = update { snapshot ->
        snapshot.copy(
            retiredDeviceAccounts = snapshot.retiredDeviceAccounts.filterNot {
                it.key == account.key && it.accessToken == account.accessToken && it.deviceId == account.deviceId
            }
        )
    }

    suspend fun deferDeviceUpgrade(serverId: String, untilMillis: Long) = update {
        it.copy(deviceUpgradeRetryAfter = it.deviceUpgradeRetryAfter + (serverId to untilMillis))
    }

    /** Public metadata cannot replace identity, addresses or credentials, including after a delayed reply. */
    suspend fun refreshServer(expected: Server, reported: Server) = mutex.withLock {
        if (reported.id != expected.id) return@withLock
        val before = loadLocked()
        val after = before.copy(
            lastServer = before.lastServer?.let { server ->
                if (server.id == expected.id && server.url == expected.url) {
                    server.copy(name = reported.name, version = reported.version)
                } else {
                    server
                }
            },
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
            recentSearches = snapshot.recentSearches - key,
            homePreferences = snapshot.homePreferences - key,
            pendingHomePreferences = snapshot.pendingHomePreferences - key,
            retiredDeviceAccounts = snapshot.retiredDeviceAccounts.filterNot { it.key == key },
            // Retain only the public server record, never the signed-out user's identity or token.
            lastServer = snapshot.accounts.firstOrNull { it.key == key }?.server ?: snapshot.lastServer
        )
    }

    /** Forget one viewer, retaining the public server for another sign-in. */
    suspend fun removeAccount(key: String) = signOut(key)

    /** A server entry is an exact identity/address pair; other addresses stay independent. */
    suspend fun removeServer(server: Server) = update { snapshot ->
        val removed = snapshot.accounts.filter { it.server.id == server.id && it.server.url == server.url }.map { it.key }.toSet()
        snapshot.copy(
            accounts = snapshot.accounts.filterNot { it.key in removed },
            currentKey = snapshot.currentKey.takeUnless { it in removed },
            recentSearches = snapshot.recentSearches - removed,
            homePreferences = snapshot.homePreferences - removed,
            pendingHomePreferences = snapshot.pendingHomePreferences - removed,
            retiredDeviceAccounts = snapshot.retiredDeviceAccounts.filterNot { it.server.id == server.id && it.server.url == server.url },
            deviceUpgradeRetryAfter = snapshot.deviceUpgradeRetryAfter - server.id,
            lastServer = snapshot.lastServer?.takeUnless { it.id == server.id && it.url == server.url }
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

    suspend fun saveHomePreferences(key: String, preferences: HomePreferences, pending: Boolean) = update { snapshot ->
        require(snapshot.accounts.any { it.key == key }) { "That account is not saved." }
        snapshot.copy(
            homePreferences = snapshot.homePreferences + (key to preferences),
            pendingHomePreferences = if (pending) snapshot.pendingHomePreferences + key else snapshot.pendingHomePreferences - key
        )
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
