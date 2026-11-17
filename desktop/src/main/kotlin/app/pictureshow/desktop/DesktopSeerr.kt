/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.normalizeSearch
import app.pictureshow.core.seerr.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AES-GCM over each saved Seerr secret, authenticated to its Pictureshow account (the account key is the AAD, so a
 * secret moved to another account fails to open). Desktop has no AndroidKeyStore: the key is a random 256-bit file
 * beside the data, written owner-only like the Jellyfin sign-ins. It keeps the cookie, password and API key out of the
 * readable connection file and away from any other account; it is not an OS keychain.
 */
internal class DesktopSeerrCipher(private val keyFile: File) {
    private val random = SecureRandom()
    @Synchronized private fun key(): SecretKeySpec {
        if (keyFile.exists()) keyFile.readBytes().takeIf { it.size == 32 }?.let { return SecretKeySpec(it, "AES") }
        val bytes = ByteArray(32).also(random::nextBytes)
        atomicPrivateWrite(keyFile, bytes)
        return SecretKeySpec(bytes, "AES")
    }
    fun encrypt(account: String, plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(account: String, ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}

@Serializable
internal data class DesktopSeerrSaved(val config: SeerrConfig, val encryptedSecret: String)

/** Seerr's own client: no redirects (a redirect could carry the cookie or key elsewhere), no shared headers or cookies. */
internal fun desktopSeerrHttp(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    followRedirects = false
    engine { config { followRedirects(false); followSslRedirects(false) } }
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
}

/**
 * SEERR-01/02: each Pictureshow account's Seerr connection, keyed by [Account.key], in `seerr.json`. Only the address
 * and the Seerr user are readable; the secret is [DesktopSeerrCipher] ciphertext. Nothing is saved until Seerr says who
 * the viewer is.
 */
class DesktopSeerrStore(
    directory: File,
    private val http: HttpClient = desktopSeerrHttp()
) {
    private val file = File(directory, "seerr.json")
    private val cipher = DesktopSeerrCipher(File(directory, "seerr.key"))
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val saved = MutableStateFlow(read())
    val connections: StateFlow<Map<String, SeerrConfig>> = saved.map { all -> all.mapValues { it.value.config } }
        .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, saved.value.mapValues { it.value.config })

    /** A corrupt file reads as no connections rather than failing every page that asks. */
    private fun read(): Map<String, DesktopSeerrSaved> = runCatching {
        if (file.exists()) json.decodeFromString<Map<String, DesktopSeerrSaved>>(file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private suspend fun write(next: Map<String, DesktopSeerrSaved>) = withContext(Dispatchers.IO) {
        atomicPrivateWrite(file, json.encodeToString(next).toByteArray(Charsets.UTF_8))
        saved.value = next
    }

    /**
     * Signs in with the viewer's Jellyfin name and password, or links an API key to the viewer's own Seerr user; checks
     * who Seerr says that is; then saves it, unless [stillSignedIn] says the account was signed out meanwhile.
     */
    suspend fun connect(account: Account, url: String, apiKey: String?, username: String, password: String, stillSignedIn: () -> Boolean): SeerrConfig {
        val address = try { normalizeSeerrUrl(url) } catch (_: IllegalArgumentException) { throw SeerrFailure(SeerrError.ADDRESS) }
        val secret: SeerrSecret
        val config: SeerrConfig
        if (apiKey != null) {
            config = SeerrClient.apiKey(http, address, apiKey.trim(), account.user.id)
            secret = SeerrSecret(apiKey = apiKey.trim())
        } else {
            val login = SeerrClient.login(http, address, username.trim(), password)
            config = SeerrConfig(address, SeerrMode.JELLYFIN, login.first)
            secret = login.second
        }
        val verified = config.copy(user = SeerrClient(http, config, secret).me())
        if (apiKey != null && verified.user.id != config.user.id) throw SeerrFailure(SeerrError.NOT_LINKED)
        val encrypted = try { cipher.encrypt(account.key, json.encodeToString(secret)) } catch (_: Exception) { throw SeerrFailure(SeerrError.STORAGE) }
        mutex.withLock {
            if (!stillSignedIn()) throw SeerrFailure(SeerrError.EXPIRED)
            try { write(saved.value + (account.key to DesktopSeerrSaved(verified, encrypted))) } catch (_: Exception) { throw SeerrFailure(SeerrError.STORAGE) }
        }
        return verified
    }

    suspend fun disconnect(key: String) = mutex.withLock { if (key in saved.value) write(saved.value - key) }

    /** SEERR-02: signing out or forgetting a Jellyfin account drops its Seerr connection. */
    suspend fun retain(keys: Set<String>) = mutex.withLock {
        val kept = saved.value.filterKeys { it in keys }
        if (kept.size != saved.value.size) write(kept)
    }

    /** The client for [key]'s saved connection, or null when that account hasn't connected Seerr. */
    fun client(key: String): SeerrClient? {
        val entry = saved.value[key] ?: return null
        val secret = try { json.decodeFromString<SeerrSecret>(cipher.decrypt(key, entry.encryptedSecret)) } catch (_: Exception) { throw SeerrFailure(SeerrError.STORAGE) }
        var expected = entry.encryptedSecret
        return SeerrClient(http, entry.config, secret) { renewed ->
            val encrypted = cipher.encrypt(key, json.encodeToString(renewed))
            mutex.withLock {
                // A renewal that finishes after Disconnect or sign-out can't bring the connection back.
                val now = saved.value[key]
                if (now?.encryptedSecret == expected) { write(saved.value + (key to now.copy(encryptedSecret = encrypted))); expected = encrypted }
            }
        }
    }
}

enum class DesktopRequestsTab { Trending, Search, Mine }

data class DesktopSeerrRequestRow(val request: SeerrRequest, val title: SeerrTitle?)

data class DesktopSeerrState(
    val accountKey: String? = null,
    val config: SeerrConfig? = null,
    val tab: DesktopRequestsTab = DesktopRequestsTab.Trending,
    val trending: List<SeerrTitle> = emptyList(),
    val trendingLoading: Boolean = false,
    val trendingError: SeerrError? = null,
    val query: String = "",
    val results: List<SeerrTitle> = emptyList(),
    val searching: Boolean = false,
    val searched: String? = null,
    val searchError: SeerrError? = null,
    val requests: List<DesktopSeerrRequestRow> = emptyList(),
    val requestsLoading: Boolean = false,
    val requestsError: SeerrError? = null,
    val title: SeerrTitle? = null,
    val titleLoading: Boolean = false,
    val titleError: SeerrError? = null,
    val chosen: Set<Int> = emptySet(),
    val sending: Boolean = false,
    val sendError: SeerrError? = null,
    val sent: Boolean = false,
    val connectOpen: Boolean = false,
    val connecting: Boolean = false,
    val connectError: SeerrError? = null,
    /** SEERR-07: Seerr's results for the app search's words, kept apart from the Requests search. */
    val offersTerm: String? = null,
    val offers: List<SeerrTitle> = emptyList()
) {
    val connected: Boolean get() = config != null
}

/**
 * The current account's Seerr: Settings' connection, the Requests destination, a Seerr title page and search's "Not in
 * your library" row. Every rule (address, query encoding, statuses, seasons, offers, errors, headers, renewal) is core's.
 */
class DesktopSeerr(
    private val store: DesktopSeerrStore,
    private val scope: CoroutineScope,
    private val account: () -> Account?,
    private val log: (String) -> Unit = {},
    private val searchDelayMillis: Long = 350
) {
    private val mutable = MutableStateFlow(DesktopSeerrState())
    val state: StateFlow<DesktopSeerrState> = mutable.asStateFlow()
    private var client: SeerrClient? = null
    private var searchJob: Job? = null
    private var offersJob: Job? = null
    private var titleJob: Job? = null

    init {
        scope.launch { store.connections.collect { all -> mutable.update { s -> s.accountKey?.let { key -> configChanged(s, all[key]) } ?: s } } }
    }

    private fun configChanged(s: DesktopSeerrState, config: SeerrConfig?): DesktopSeerrState {
        if (config == s.config) return s
        client = null
        return if (config == null) DesktopSeerrState(accountKey = s.accountKey, connectOpen = s.connectOpen, connecting = s.connecting, connectError = s.connectError)
        else s.copy(config = config)
    }

    /** A new viewer starts from its own connection; nothing of the last viewer's Seerr stays on screen. */
    @Synchronized fun viewerChanged(account: Account?) {
        val key = account?.takeIf { it.signedIn }?.key
        if (key == mutable.value.accountKey) return
        searchJob?.cancel(); offersJob?.cancel(); titleJob?.cancel(); client = null
        mutable.value = DesktopSeerrState(accountKey = key, config = key?.let { store.connections.value[it] })
    }

    fun retain(keys: Set<String>) = scope.launch { runCatching { store.retain(keys) } }

    private fun client(): SeerrClient = client ?: (mutable.value.accountKey?.let(store::client) ?: throw SeerrFailure(SeerrError.EXPIRED)).also { client = it }

    private suspend fun <T> attempt(block: suspend (SeerrClient) -> T): Result<T> = try { Result.success(block(client())) }
    catch (cancel: CancellationException) { throw cancel }
    catch (failure: SeerrFailure) { Result.failure(failure) }
    catch (_: IllegalArgumentException) { Result.failure(SeerrFailure(SeerrError.SERVER)) }

    private fun Throwable.reason(): SeerrError = (this as? SeerrFailure)?.reason ?: SeerrError.SERVER

    private fun sameAccount(key: String?) = key != null && key == mutable.value.accountKey

    fun tab(tab: DesktopRequestsTab) {
        mutable.update { it.copy(tab = tab) }
        when (tab) {
            DesktopRequestsTab.Trending -> loadTrending()
            DesktopRequestsTab.Mine -> loadRequests()
            DesktopRequestsTab.Search -> Unit
        }
    }

    fun loadTrending(force: Boolean = false) {
        val now = mutable.value
        if (!now.connected || now.trendingLoading || (!force && now.trending.isNotEmpty())) return
        val key = now.accountKey
        mutable.update { it.copy(trendingLoading = true, trendingError = null) }
        scope.launch {
            // Two pages: about 40 titles, enough for a movies row and a series row.
            val result = attempt { seerr -> (1..2).flatMap { seerr.trending(it).results } }
            if (!sameAccount(key)) return@launch
            mutable.update {
                it.copy(trendingLoading = false,
                    trending = result.getOrNull()?.filter { t -> t.mediaType in setOf("movie", "tv") }?.distinctBy { t -> t.mediaType to t.id } ?: it.trending,
                    trendingError = result.exceptionOrNull()?.reason())
            }
            result.exceptionOrNull()?.let { log("seerr trending failed reason=${it.reason().name.lowercase()}") }
        }
    }

    /** Debounced like the library search; under 2 characters clears the results. */
    fun search(query: String, immediate: Boolean = false) {
        mutable.update { it.copy(query = query) }
        searchJob?.cancel()
        val term = normalizeSearch(query)
        if (term.length < 2 || !mutable.value.connected) {
            mutable.update { it.copy(results = emptyList(), searching = false, searched = null, searchError = null) }
            return
        }
        val key = mutable.value.accountKey
        searchJob = scope.launch {
            if (!immediate) delay(searchDelayMillis)
            mutable.update { it.copy(searching = true, searchError = null) }
            val result = attempt { it.search(term).results.filter { t -> t.mediaType in setOf("movie", "tv") } }
            if (!sameAccount(key)) return@launch
            mutable.update { it.copy(searching = false, searched = term, results = result.getOrNull() ?: emptyList(), searchError = result.exceptionOrNull()?.reason()) }
        }
    }

    /** SEERR-07: the app search's words, for its "Not in your library" row. A failure simply leaves the row out. */
    fun searchOffers(query: String) {
        offersJob?.cancel()
        val term = normalizeSearch(query)
        if (term.length < 2 || !mutable.value.connected) { mutable.update { it.copy(offersTerm = null, offers = emptyList()) }; return }
        val key = mutable.value.accountKey
        offersJob = scope.launch {
            delay(searchDelayMillis)
            val result = attempt { it.search(term).results.filter { t -> t.mediaType in setOf("movie", "tv") } }
            if (!sameAccount(key)) return@launch
            mutable.update { it.copy(offersTerm = term, offers = result.getOrNull() ?: emptyList()) }
        }
    }

    fun loadRequests() {
        if (!mutable.value.connected || mutable.value.requestsLoading) return
        val key = mutable.value.accountKey
        mutable.update { it.copy(requestsLoading = true, requestsError = null) }
        scope.launch {
            val result = attempt { seerr ->
                val requests = seerr.requests(take = 30).results
                // A request carries only TMDB ids; each row's title and poster come from the title's own page.
                val titles = coroutineScope {
                    requests.map { request ->
                        async {
                            try { seerr.detail(if (request.type == "tv") "tv" else "movie", request.media.tmdbId) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { null }
                        }
                    }.awaitAll()
                }
                requests.zip(titles) { request, title -> DesktopSeerrRequestRow(request, title) }
            }
            if (!sameAccount(key)) return@launch
            mutable.update { it.copy(requestsLoading = false, requests = result.getOrNull() ?: it.requests, requestsError = result.exceptionOrNull()?.reason()) }
        }
    }

    fun openTitle(type: String, id: Int) {
        if (type !in setOf("movie", "tv") || id <= 0) { mutable.update { it.copy(titleError = SeerrError.SERVER) }; return }
        val key = mutable.value.accountKey
        mutable.update {
            // "Requested" belongs to the title it was sent for; reopening that title after the request keeps it.
            val same = it.title?.let { now -> now.mediaType == type && now.id == id } == true
            it.copy(title = if (same) it.title else null, titleLoading = true, titleError = null, sendError = null, sent = it.sent && same, chosen = if (same) it.chosen else emptySet())
        }
        titleJob?.cancel()
        titleJob = scope.launch {
            val result = attempt { it.detail(type, id) }
            if (!sameAccount(key)) return@launch
            mutable.update { now ->
                val title = result.getOrNull()
                now.copy(titleLoading = false, title = title ?: now.title, titleError = result.exceptionOrNull()?.reason(),
                    // A series starts with every season that can still be requested chosen, so one press asks for all.
                    chosen = if (title != null && !now.sent) seerrSelectableSeasons(title).toSet() else now.chosen)
            }
        }
    }

    fun toggleSeason(season: Int) {
        val title = mutable.value.title ?: return
        if (season !in seerrSelectableSeasons(title)) return
        mutable.update { it.copy(chosen = if (season in it.chosen) it.chosen - season else it.chosen + season, sendError = null) }
    }

    fun toggleAll() {
        val title = mutable.value.title ?: return
        val all = seerrSelectableSeasons(title).toSet()
        mutable.update { it.copy(chosen = if (it.chosen.containsAll(all)) emptySet() else all, sendError = null) }
    }

    /** SEERR-06: a movie, or the chosen seasons. Nothing is sent until a season is picked. */
    fun request() {
        val now = mutable.value
        val title = now.title ?: return
        if (now.sending) return
        val seasons = if (title.mediaType == "tv") seerrSelectableSeasons(title).filter { it in now.chosen } else emptyList()
        if (title.mediaType == "tv" && seasons.isEmpty()) return
        if (title.mediaType == "movie" && !seerrMovieRequestable(title)) return
        val key = now.accountKey
        mutable.update { it.copy(sending = true, sendError = null) }
        scope.launch {
            val result = attempt { it.request(title, seasons) }
            if (!sameAccount(key)) return@launch
            val reason = result.exceptionOrNull()?.reason()
            log("seerr request ${if (result.isSuccess) "sent" else "failed reason=${reason?.name?.lowercase()}"}")
            mutable.update { it.copy(sending = false, sent = result.isSuccess, sendError = reason, requests = if (result.isSuccess) emptyList() else it.requests) }
            // The page then shows the new state: requested seasons stop being selectable.
            if (result.isSuccess || reason == SeerrError.ALREADY_REQUESTED) openTitle(title.mediaType, title.id)
        }
    }

    fun openConnect() = mutable.update { it.copy(connectOpen = true, connectError = null) }
    fun closeConnect() = mutable.update { it.copy(connectOpen = false, connectError = null) }

    /** SEERR-01: [apiKey] null is Jellyfin sign-in mode. Secrets are never logged. */
    fun connect(url: String, apiKey: String?, username: String, password: String, stillSignedIn: (String) -> Boolean) {
        val a = account()?.takeIf { it.signedIn } ?: return
        if (mutable.value.connecting) return
        mutable.update { it.copy(connecting = true, connectError = null) }
        scope.launch {
            val error = try { store.connect(a, url, apiKey, username, password) { stillSignedIn(a.key) }; null }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: SeerrFailure) { failure.reason }
            catch (_: Exception) { SeerrError.UNREACHABLE }
            log("seerr connect mode=${if (apiKey != null) "api-key" else "jellyfin"} ${error?.let { "failed reason=${it.name.lowercase()}" } ?: "ok"}")
            client = null
            mutable.update { s -> (if (s.accountKey == a.key) s.copy(config = store.connections.value[a.key] ?: s.config) else s).copy(connecting = false, connectError = error, connectOpen = error != null && s.connectOpen) }
        }
    }

    fun disconnect() {
        val key = mutable.value.accountKey ?: return
        scope.launch {
            runCatching { store.disconnect(key) }
            client = null
            if (sameAccount(key)) mutable.value = DesktopSeerrState(accountKey = key)
        }
    }
}

internal fun desktopSeerrErrorText(error: SeerrError): String = when (error) {
    SeerrError.UNREACHABLE -> "Can't reach Seerr. Check the address and that this computer is on the same network, then try again."
    SeerrError.PASSWORD -> "Seerr didn't accept that Jellyfin username and password."
    SeerrError.API_KEY -> "Seerr didn't accept that API key."
    SeerrError.EXPIRED -> "Your Seerr sign-in has ended. Connect again in Settings."
    SeerrError.PERMISSION -> "Your Seerr account doesn't have permission to request. Ask whoever runs Seerr."
    SeerrError.QUOTA -> "You've reached your request limit on Seerr. Try again later."
    SeerrError.ALREADY_REQUESTED -> "This was already requested."
    SeerrError.BLOCKED -> "Seerr has blocked requests for this title."
    SeerrError.NOT_LINKED -> "Your Jellyfin account isn't linked in Seerr, so requests can't be made as you. Ask whoever runs Seerr to import your Jellyfin user, or sign in with Jellyfin instead."
    SeerrError.SERVER -> "Seerr answered with an error. Try again later."
    SeerrError.ADDRESS -> "That doesn't look like a Seerr address. Use the address you open Seerr with in a browser, including http:// if it doesn't use https."
    SeerrError.STORAGE -> "The saved Seerr sign-in can't be read on this computer. Connect again in Settings."
}

internal fun desktopSeerrStatusText(status: SeerrStatus): String = when (status) {
    SeerrStatus.UNKNOWN, SeerrStatus.DELETED -> "Not requested"
    SeerrStatus.PENDING -> "Pending approval"
    SeerrStatus.APPROVED -> "Approved"
    SeerrStatus.PROCESSING -> "Downloading"
    SeerrStatus.PARTIAL -> "Partly available"
    SeerrStatus.AVAILABLE, SeerrStatus.COMPLETED -> "Available"
    SeerrStatus.DECLINED -> "Declined"
    SeerrStatus.FAILED -> "Failed"
    SeerrStatus.BLOCKED -> "Blocked"
}

/** A card's line under the title: year · Movie/Series · what Seerr says about it. */
internal fun desktopSeerrCardLine(title: SeerrTitle, withStatus: Boolean = true): String =
    listOfNotNull(title.year.takeIf { it.isNotBlank() }, if (title.mediaType == "tv") "Series" else "Movie",
        if (withStatus) desktopSeerrStatusText(title.status) else null).joinToString(" · ")
