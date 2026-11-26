/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.core.livetv.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.awt.Window
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Desktop owns lifecycle and I/O; server policy, catalog rules and playback decisions come directly from core. */
class DesktopController(
    private val directory: File = desktopDataDirectory(),
    private val http: HttpClient = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false; jellyfinApiTransport() },
    internal val store: AccountStore = AccountStore(DesktopTextPersistence(File(directory, "accounts.json"))),
    private val discovery: ServerDiscovery = JvmServerDiscovery(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playbackFactory: (File) -> DesktopPlaybackEngine = { NativeDesktopPlayback(it) },
    /** PLAY-17's countdown after a genuine end before the next episode opens. */
    /** Tests pin the end countdown; null follows Settings (PLAY-30). */
    private val endCountdownSeconds: Int? = null
) : AutoCloseable {
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
    internal val mutable = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = mutable.asStateFlow()
    private val settingsStore = DesktopSettingsStore(File(directory, "settings.properties"))
    private val identity = ClientIdentity("Pictureshow Desktop", installationId()).let {
        System.getProperty("jpackage.app-version")?.let { version -> it.copy(version = version) } ?: it
    }
    internal val client = JellyfinClient(http, identity) { url, failure -> macLocalNetworkHint(url, failure) }
    /** Stream C1 (catalog): Home preferences, queues, filters and the landing view (DesktopCatalogStream.kt). */
    internal val catalogSession by lazy { DesktopCatalogSession(HomePreferencesStore(client, store)) }
    private val playbackClient = PlaybackClient(http, identity)
    val liveTv = DesktopLiveTv(LiveTvClient(http, identity, DesktopLivePendingWrites(File(directory, "live-pending"))), scope, { mutable.value.account })
    private val extras = PlaybackExtrasClient(http, identity)
    /** AUTH-16/18/19 (DesktopProfileLock.kt): PINs, the lock and the time away. */
    val pins = DesktopProfileLock(directory, scope, log = { events.log(it) })
    /** AUTH-15 (DesktopQuickConnectApproval.kt). */
    val approval = DesktopQuickConnectApproval(QuickConnectApprovalClient(http, identity), scope, { mutable.value.account }, log = { events.log(it) })
    /** SEERR-01..07 (DesktopSeerr.kt). Its own HTTP client: the Jellyfin token never goes to Seerr. */
    val seerr = DesktopSeerr(DesktopSeerrStore(directory), scope, { mutable.value.account }, log = { events.log(it) })
    /** INFO-01..08 (DesktopInformation.kt). */
    val information = DesktopInformation(scope, { mutable.value.settings }, nativeMpvProbe(File(directory, "probe")))
    internal val appVersion: String get() = identity.version
    /** Stream E (DesktopTogether.kt): remote control both ways and Watch together. The app starts it; tests don't. */
    @Volatile internal var together: DesktopTogether? = null
    internal val generation = AtomicLong()
    private val extrasLoader = DesktopPlayerExtrasLoader(http, identity)
    internal val viewerInput = DesktopViewerInput()
    internal val serverPreferences by lazy { DesktopServerPreferences(UserConfigurationClient(http, identity), scope) { mutable.value.account } }
    private val subtitleClient = app.pictureshow.core.subtitles.RemoteSubtitleClient(http, identity)
    private val offlineApi = DesktopOfflineApi(identity)
    /** OFF-01..12: downloads and offline viewing, under this computer's own data directory (OFF-10). */
    internal val offline = DesktopOffline(DesktopOfflineEngine(directory, store, identity, { line -> events.log(line) }, api = offlineApi,
        scope = scope, storageBase = MacStoreStorage.packaged?.downloadFolder ?: directory),
        offlineApi, scope, state.map { it.account }, { mutable.value.account })
    private var pageJob: Job? = null
    private var discoveryJob: Job? = null
    internal val history = mutableListOf<DesktopState>()
    private var pendingQuick: PendingQuickConnect? = null
    private var quickOwner: Server? = null
    @Volatile private var window: Window? = null
    @Volatile private var engine: DesktopPlaybackEngine? = null
    private var reportJob: Job? = null
    @Volatile private var viewing: Viewing? = null
    @Volatile private var closing = false
    @Volatile private var nextPageIndex = 0
    internal fun setNextPage(index: Int) { nextPageIndex = index }
    private val viewingMutex = kotlinx.coroutines.sync.Mutex()
    internal val events = DesktopEventLog(File(directory, "app.log"))
    /** Music (leg 172 stream B): its own audio-only engine and queue, apart from the video player. */
    val music = DesktopMusic(scope, store, DesktopMusicStore(File(directory, "music")), ClientDesktopMusicGateway(playbackClient, client),
        { volume -> MpvMusicOutput(File(directory, "music-output"), volume) }, { mutable.value.settings.musicQuality }, events::log)
    val musicBrowse = DesktopMusicBrowse(client, store, scope)
    init {
        // MUS-10: downloaded tracks play from their files and their listening goes to the offline outbox; Downloads' Play on music opens the queue.
        music.local = DesktopEngineMusicLocal(offline.engine)
        offline.musicPlayer = music::request
    }
    private val inputDiagnostics = DesktopInputDiagnostics(events::log)
    private val inputListener = java.awt.event.AWTEventListener { event ->
        val source = event.source as? java.awt.Component
        val eventWindow = source?.let { if (it is Window) it else javax.swing.SwingUtilities.getWindowAncestor(it) }
        val ourWindow = window
        if (ourWindow != null && generateSequence(eventWindow) { it.owner }.any { it === ourWindow }) {
            when (event) {
                is java.awt.event.KeyEvent -> { inputDiagnostics.key(); if (event.id == java.awt.event.KeyEvent.KEY_PRESSED) viewerInput.input() }
                is java.awt.event.MouseEvent -> { inputDiagnostics.pointer(); if (event.id == java.awt.event.MouseEvent.MOUSE_PRESSED) viewerInput.input() }
            }
        }
    }
    private var inputAttached = false
    init {
        // AUTH-18: a relock closes the open player; the lock screen then asks like a fresh start.
        pins.onRelock = { if (mutable.value.route == DesktopRoute.Player) stopPlayback(); music.pause() }
        // AUTH-18: music playing in an unfocused window is not time away, as video isn't.
        scope.launch { music.state.map { it.isPlaying }.distinctUntilChanged().collect { pins.playing(desktopVideoPlaying(mutable.value) || it) } }
        inputDiagnostics.route(mutable.value.route)
        scope.launch { while (isActive) { delay(10_000); inputDiagnostics.heartbeat() } }
    }
    /** A report error from a viewing that has closed; shown by whatever opens next instead of being wiped (D-4). */
    @Volatile private var carriedReportError: String? = null
    private var endJob: Job? = null
    @Volatile private var informationOpen = false
    fun playbackInformationOpen(open: Boolean) { informationOpen = open }
    @Volatile private var seekTarget: Double? = null
    @Volatile private var seekTargetAt = 0L
    private data class Viewing(
        val account: Account, val item: MediaItem, val decision: PlayDecision, val engine: DesktopPlaybackEngine,
        var startReported: Boolean = false, var startAttempted: Boolean = false, var audioChosen: Boolean = false, var subtitleChosen: Boolean = false,
        @Volatile var stopped: Boolean = false, @Volatile var closing: Boolean = false,
        val reports: Mutex = Mutex(),
        /** The engine reached the physical end of the media (PLAY-18): watched state completes on close. */
        @Volatile var ended: Boolean = false, @Volatile var endPosition: Double = 0.0,
        /** The media itself failed (decoder/stream error). Only this is reported to the server as Failed. */
        @Volatile var playbackFailed: Boolean = false,
        @Volatile var muted: Boolean = false,
        /** A downloaded copy playing from this computer: progress goes to its outbox, never to a play session (OFF-05/07). */
        val local: DesktopLocalPlay? = null,
        /** Stream E: where this viewing started, for a title picked while in a Watch-together group. */
        val startMs: Long = 0
    )
    private fun takeCarriedReportError(): String? = carriedReportError.also { carriedReportError = null }

    private fun installationId(): String {
        val file = File(directory, "device-id")
        if (file.exists()) return file.readText().trim().also { require(it.isNotEmpty()) }
        return UUID.randomUUID().toString().also { atomicPrivateWrite(file, it.toByteArray()) }
    }
    private fun identityClient() = JellyfinClient(http, identity.copy(deviceId = UUID.randomUUID().toString()))
    /** Record every committed transition, including quick intermediate routes that StateFlow can conflate. */
    @Synchronized internal fun updateState(change: (DesktopState) -> DesktopState) {
        val previous = mutable.value
        val next = change(previous)
        mutable.value = next
        inputDiagnostics.route(next.route)
        desktopAccountsObserve(previous, next, pins, seerr, music.state.value.isPlaying)
    }
    private fun snapshotAccounts() {
        val s = store.snapshots.value; updateState { it.copy(accounts = s.accounts, rememberedServers = s.rememberedServers) }
        pins.retain(s.accounts.map { it.key }.toSet()); seerr.retain(s.accounts.filter { !it.signedOut }.map { it.key }.toSet())
    }
    @Synchronized internal fun page(route: DesktopRoute, remember: Boolean = false, block: suspend (Long) -> Unit) {
        val before = mutable.value
        if (remember && before.route !in setOf(DesktopRoute.Loading, DesktopRoute.Player)) synchronized(history) { history.add(before) }
        val g = generation.incrementAndGet()
        pageJob?.cancel()
        if (route !in setOf(DesktopRoute.SignIn, DesktopRoute.QuickConnect)) { pendingQuick = null; quickOwner = null }
        updateState { it.copy(route = route, busy = true, error = null, errorKind = null, root = desktopRootFor(route, it.root), finished = null) }
        pageJob = scope.launch {
            try {
                if (before.route == DesktopRoute.Player && route != DesktopRoute.Player) {
                    finishViewing()
                    publish(g) { it.copy(playback = null) }
                }
                block(g)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { publish(g) { it.copy(error = "Could not complete this action. Check the connection or local storage and try again.", errorKind = null) } }
            finally { publish(g) { it.copy(busy = false) } }
        }
    }
    @Synchronized internal fun publish(g: Long, change: (DesktopState) -> DesktopState) { if (generation.get() == g && !closing) updateState(change) }
    private suspend fun <T> result(account: Account? = null, call: suspend () -> ClientResult<T>): T {
        val response = call()
        currentCoroutineContext().ensureActive()
        return when (response) {
            is ClientResult.Success -> response.value
            is ClientResult.Failure -> {
                if (account != null && response.error.kind in setOf(ErrorKind.INVALID_CREDENTIALS, ErrorKind.EXPIRED)) {
                    store.expire(account.key, account.accessToken)
                    if (mutable.value.account?.accessToken == account.accessToken) {
                        synchronized(history) { history.clear() }
                        updateState { it.copy(account = null, server = account.server, route = DesktopRoute.SignIn,
                            homeRows = emptyList(), items = emptyList(), library = null, detail = null,
                            seasons = emptyList(), episodes = emptyList(), selectedSeason = null, playback = null) }
                    }
                    snapshotAccounts()
                    throw DesktopFailure("Your session ended. Sign in again.", ErrorKind.EXPIRED)
                }
                throw DesktopFailure(response.error.message, response.error.kind)
            }
        }
    }
    private class DesktopFailure(override val message: String, val kind: ErrorKind) : Exception(message)
    internal suspend fun <T> attempt(g: Long, account: Account? = null, call: suspend () -> ClientResult<T>): T? = try { result(account, call) }
    catch (e: DesktopFailure) { publish(g) { it.copy(error = e.message, errorKind = e.kind) }; null }

    fun clearError() { updateState { it.copy(error = null, errorKind = null) } }
    /** The page that honoured [DesktopState.returnFocus] clears it, so it applies once. */
    fun returnFocusDone() { updateState { it.copy(returnFocus = null) } }

    fun start() = page(DesktopRoute.Loading) { g ->
        val s = store.load()
        pins.load()
        val settings = settingsStore.read()
        snapshotAccounts()
        publish(g) { it.copy(settings = settings, server = s.lastServer) }
        offline.start()
        val account = s.currentAccount
        if (account != null && !account.expired && account.accessToken.isNotBlank()) {
            when (val checked = client.me(account)) {
                is ClientResult.Success -> { offline.serverReach(false); updateState { it.copy(account = account) }; loadHome(g) }
                is ClientResult.Failure -> if (checked.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                    store.expire(account.key, account.accessToken); snapshotAccounts()
                    publish(g) { it.copy(route = DesktopRoute.SignIn, server = account.server, signInUsername = account.user.name, error = "Your session ended. Sign in again.", errorKind = ErrorKind.EXPIRED) }
                } else if (checked.error.kind in setOf(ErrorKind.NETWORK, ErrorKind.SERVER) && offline.readyFor(account)) {
                    // Offline start: the server is out of reach and something saved can play (OFF-04, HOME_UNREACHABLE).
                    offline.serverReach(true); events.log("offline start route=downloads kind=${checked.error.kind}")
                    publish(g) { it.copy(route = DesktopRoute.Downloads, account = account, root = DESKTOP_ROOT_DOWNLOADS) }
                } else publish(g) { it.copy(route = DesktopRoute.Home, account = account, error = checked.error.message, errorKind = checked.error.kind) }
            }
        } else publish(g) { it.copy(route = if (s.accounts.isNotEmpty()) DesktopRoute.Accounts else DesktopRoute.Servers) }
    }
    fun discover() {
        if (discoveryJob?.isActive == true) return
        updateState { it.copy(discovering = true, discovered = emptyList()) }
        discoveryJob = scope.launch {
            try { discovery.discover { found -> updateState { it.copy(discovered = (it.discovered + found).distinctBy(DiscoveredServer::id)) } } }
            catch (_: Exception) { updateState { it.copy(error = "Network discovery is unavailable. Enter your server address to connect.") } }
            finally { updateState { it.copy(discovering = false) } }
        }
    }
    fun connect(address: String) {
        updateState { it.copy(serverAddress = address, signInUsername = null) }
        page(DesktopRoute.Servers) { g ->
            val server = attempt(g) { client.probe(address) } ?: return@page
            publish(g) { it.copy(server = server, route = DesktopRoute.SignIn, publicUsers = emptyList(), quickCode = null, signInUsername = null) }
            val users = attempt(g) { client.publicUsers(server) }.orEmpty()
            publish(g) { it.copy(publicUsers = users) }
        }
    }
    fun signIn(username: String, password: String) {
        if (mutable.value.busy || username.isBlank()) return
        val server = mutable.value.server ?: return
        page(DesktopRoute.SignIn) { g ->
            val account = attempt(g) { identityClient().authenticate(server, username, password) } ?: return@page
            acceptAccount(g, account)
        }
    }
    private suspend fun acceptAccount(g: Long, account: Account) {
        currentCoroutineContext().ensureActive()
        liveTv.clear()
        pins.provenByPassword(account.key)
        store.remember(account)
        snapshotAccounts()
        synchronized(history) { history.clear() }
        pendingQuick = null; quickOwner = null
        publish(g) { it.copy(search = DesktopSearchState(), account = account, server = account.server, quickCode = null, signInUsername = null, detail = null, playback = null, homeRows = emptyList(), catalog = DesktopCatalogState()) }
        loadHome(g)
    }
    fun quickConnect() {
        val server = mutable.value.server ?: return
        if (mutable.value.busy && mutable.value.route == DesktopRoute.QuickConnect) return
        if (quickOwner != server) { pendingQuick = null; quickOwner = server }
        page(DesktopRoute.QuickConnect) { g ->
            publish(g) { it.copy(signInUsername = null) }
            identityClient().quickConnect(server, pendingQuick) { pendingQuick = it }.collect { q ->
                when (q) {
                    is QuickConnectState.Code -> publish(g) { it.copy(quickCode = q.code, busy = false, error = null, errorKind = null) }
                    is QuickConnectState.Authorized -> acceptAccount(g, q.account)
                    is QuickConnectState.Failed -> publish(g) { it.copy(error = q.error.message, errorKind = q.error.kind, busy = false) }
                    QuickConnectState.Disabled -> publish(g) { it.copy(error = "Quick Connect is disabled on this server. Sign in with your username and password.", errorKind = null, busy = false) }
                }
            }
        }
    }
    // Back from the picker returns to the page it was opened from (D-6); re-opening it doesn't stack.
    fun accounts() = page(DesktopRoute.Accounts, remember = mutable.value.route != DesktopRoute.Accounts) { _ -> snapshotAccounts() }
    fun addServer() = page(DesktopRoute.Servers) { g -> publish(g) { it.copy(signInUsername = null) } }
    /** AUTH-07: another viewer on a saved server goes straight to its sign-in; Back returns to the chooser. */
    fun addAccountOn(server: Server) {
        if (mutable.value.busy && mutable.value.route == DesktopRoute.SignIn) return
        page(DesktopRoute.SignIn, remember = true) { g ->
            publish(g) { it.copy(server = server, publicUsers = emptyList(), quickCode = null, signInUsername = null) }
            val users = attempt(g) { client.publicUsers(server) }.orEmpty()
            publish(g) { it.copy(publicUsers = users) }
        }
    }
    /** AUTH-16: the chooser asks for a viewer's PIN before switching to it. */
    fun selectAccount(key: String) {
        val account = store.snapshots.value.accounts.firstOrNull { it.key == key }
        if (account != null && account.signedIn && pins.neededOnSwitch(key, mutable.value.account?.key)) pins.askBeforeSwitch(account) { selectAccountNow(key) }
        else selectAccountNow(key)
    }
    /** Forgot PIN: the viewer's own sign-in page, with its name filled; the password sign-in unlocks it. */
    fun signInAgain(key: String) {
        val account = store.snapshots.value.accounts.firstOrNull { it.key == key } ?: return
        page(DesktopRoute.SignIn, remember = mutable.value.account != null) { g ->
            publish(g) { it.copy(server = account.server, signInUsername = account.user.name, publicUsers = emptyList(), quickCode = null) }
        }
    }
    /** The lock screen's ways out: neither opens anything of the locked viewer. */
    fun lockedSwitchUser() = leaveLocked { page(DesktopRoute.Accounts) { _ -> snapshotAccounts() } }
    fun lockedUsePassword() {
        val account = mutable.value.account ?: return
        leaveLocked { page(DesktopRoute.SignIn) { g -> publish(g) { it.copy(server = account.server, signInUsername = account.user.name, publicUsers = emptyList(), quickCode = null) } } }
    }
    private fun leaveLocked(then: () -> Unit) {
        if (!pins.gated()) return
        synchronized(history) { history.clear() }
        scope.launch {
            finishViewing()
            runCatching { store.select(null) }; snapshotAccounts()
            updateState { it.copy(account = null, homeRows = emptyList(), items = emptyList(), detail = null, playback = null, search = DesktopSearchState()) }
            liveTv.clear()
            then()
        }
    }
    /** A page of another stream's feature, remembered for Back like Settings. */
    internal fun openPage(route: DesktopRoute, block: suspend () -> Unit = {}) = page(route, remember = mutable.value.route != route) { _ -> block() }
    private fun selectAccountNow(key: String) = page(DesktopRoute.Accounts) { g ->
        val account = store.snapshots.value.accounts.firstOrNull { it.key == key } ?: return@page
        if (account.expired || account.accessToken.isBlank()) {
            publish(g) { it.copy(server = account.server, route = DesktopRoute.SignIn, signInUsername = account.user.name,
                publicUsers = emptyList(), quickCode = null, returnFocus = null) }
            return@page
        }
        if (attempt(g, account) { client.me(account) } == null) {
            if (store.snapshots.value.accounts.firstOrNull { it.key == key }?.expired == true) {
                publish(g) { it.copy(server = account.server, route = DesktopRoute.SignIn, signInUsername = account.user.name,
                    error = "Your session ended. Sign in again.", errorKind = ErrorKind.EXPIRED) }
            }
            return@page
        }
        liveTv.clear()
        store.select(key); snapshotAccounts(); synchronized(history) { history.clear() }
        publish(g) { it.copy(search = DesktopSearchState(), account = account, server = account.server, signInUsername = null, detail = null, playback = null, homeRows = emptyList(), catalog = DesktopCatalogState()) }; loadHome(g)
    }
    fun signOut() {
        val account = mutable.value.account ?: return
        page(DesktopRoute.Accounts) { g ->
            finishViewing()
            store.signOutKeepingAccount(account.key); snapshotAccounts(); synchronized(history) { history.clear() }
            publish(g) { DesktopState(route = if (store.snapshots.value.accounts.isEmpty()) DesktopRoute.SignIn else DesktopRoute.Accounts,
                server = account.server, accounts = store.snapshots.value.accounts, rememberedServers = store.snapshots.value.rememberedServers,
                returnFocus = "account:${account.key}", settings = it.settings) }
            liveTv.clear()
            runCatching { client.logout(account) }
        }
    }
    fun removeAccount(key: String) = page(DesktopRoute.Accounts) { g ->
        if (mutable.value.account?.key == key) finishViewing()
        store.removeAccount(key); snapshotAccounts()
        publish(g) { if (it.account?.key == key) it.copy(account = null, homeRows = emptyList(), items = emptyList(), detail = null) else it }
    }
    @Synchronized fun search() {
        if (mutable.value.account == null || pins.gated()) return
        if (mutable.value.route == DesktopRoute.Search) {
            updateState { it.copy(returnFocus = "search-field") }
            return
        }
        val a = mutable.value.account ?: return
        val previous = synchronized(history) {
            val index = history.indexOfLast { it.route == DesktopRoute.Search && it.account?.key == a.key }
            if (index < 0) null else history[index].also { while (history.size > index) history.removeAt(history.lastIndex) }
        }
        if (previous != null) {
            generation.incrementAndGet(); pageJob?.cancel()
            updateState { previous.copy(busy = false, settings = mutable.value.settings, returnFocus = "search-field",
                search = previous.search.copy(recent = store.snapshots.value.recentSearches[a.key].orEmpty(), storageError = mutable.value.search.storageError)) }
            searchQuery(previous.search.query, immediate = true, retainResults = true)
            return
        }
        page(DesktopRoute.Search, remember = true) { g ->
            publish(g) { it.copy(search = DesktopSearchState(recent = store.snapshots.value.recentSearches[a.key].orEmpty()), returnFocus = "search-field") }
        }
    }
    @Synchronized fun searchQuery(value: String, immediate: Boolean = false, retainResults: Boolean = false) {
        if (mutable.value.route != DesktopRoute.Search) return
        val bounded = value.take(SEARCH_QUERY_LIMIT)
        val term = normalizeSearch(bounded)
        updateState { it.copy(search = it.search.copy(query = bounded, rows = if (retainResults) it.search.rows else emptyList(), loading = term.length >= 2,
            completed = false, someFailed = false, allFailed = false, libraries = if (retainResults) it.search.libraries else emptyMap(), libraryError = false)) }
        page(DesktopRoute.Search) { g ->
            if (term.length < 2) return@page
            if (!immediate) delay(300)
            val a = mutable.value.account ?: return@page
            val results = coroutineScope {
                SearchKind.entries.map { kind -> async { kind to client.search(a, term, kind) } }.awaitAll().toMap()
            }
            if (searchSessionEnded(g, a, results.values)) return@page
            val rows = searchRows(results, term)
            val duplicates = searchDuplicateIds(rows.flatMap { it.items })
            val ancestors = coroutineScope {
                duplicates.map { id -> async { id to client.searchAncestors(a, id) } }.awaitAll().toMap()
            }
            if (searchSessionEnded(g, a, ancestors.values)) return@page
            val labels = ancestors.mapValues { (_, response) ->
                (response as? ClientResult.Success)?.value?.let { parents ->
                    searchLibraryName(parents, mutable.value.sidebarLibraries())
                } ?: if (response is ClientResult.Failure) "Library unavailable" else "Library not provided"
            }
            publish(g) { it.copy(search = it.search.copy(rows = rows, loading = false, completed = true,
                someFailed = results.values.any { result -> result is ClientResult.Failure },
                allFailed = results.values.all { result -> result is ClientResult.Failure },
                libraries = labels, libraryError = ancestors.values.any { response -> response is ClientResult.Failure })) }

        }
    }
    private suspend fun searchSessionEnded(g: Long, account: Account, results: Collection<ClientResult<*>>): Boolean {
        currentCoroutineContext().ensureActive()
        if (generation.get() != g) return true
        val failure = results.filterIsInstance<ClientResult.Failure>().firstOrNull {
            it.error.kind in setOf(ErrorKind.EXPIRED, ErrorKind.INVALID_CREDENTIALS)
        } ?: return false
        attempt<Unit>(g, account) { failure }
        return true
    }
    fun rememberSearch(clear: Boolean = false) {
        val a = mutable.value.account ?: return
        val term = normalizeSearch(mutable.value.search.query)
        scope.launch {
            try {
                if (clear) store.clearSearches(a.key) else if (term.length >= 2) store.recordSearch(a.key, term)
                updateState { if (it.account?.key == a.key) it.copy(search = it.search.copy(recent = store.snapshots.value.recentSearches[a.key].orEmpty(), storageError = null)) else it }
            } catch (_: Exception) {
                updateState { if (it.account?.key == a.key) it.copy(search = it.search.copy(storageError = "Could not save recent searches.")) else it }
            }
        }
    }
    fun openSearchResult(item: MediaItem) { rememberSearch(); open(item) }
    fun person(item: MediaItem) = page(DesktopRoute.Person, remember = true) { g ->
        publish(g) { it.copy(person = item, items = emptyList(), total = 0) }
        val a = mutable.value.account ?: return@page
        val p = attempt(g, a) { client.personItems(a, item.id) } ?: return@page
        publish(g) { it.copy(items = p.items, total = p.totalRecordCount) }
    }
    fun morePerson() {
        if (mutable.value.busy) return
        val before = mutable.value
        val person = before.person ?: return
        page(DesktopRoute.Person) { g ->
            val a = mutable.value.account ?: return@page
            val p = attempt(g, a) { client.personItems(a, person.id, before.items.size) } ?: return@page
            publish(g) { it.copy(items = (it.items + p.items).distinctBy(MediaItem::id), total = if (p.items.isEmpty()) it.items.size else p.totalRecordCount) }
        }
    }
    fun home() = page(DesktopRoute.Home) { g -> synchronized(history) { history.clear() }; loadHome(g) }
    private suspend fun loadHome(g: Long) = loadCatalogHome(g)
    /** A sidebar library: its top page, lit as its own root (TOUCH-02). Folders opened inside it keep this root. */
    fun browseRoot(library: MediaItem) { if (library.isDesktopMusic()) { musicLibrary(library); return }; browse(library); updateState { it.copy(root = desktopLibraryRoot(library.id)) } }
    /** Live TV (gap C34) has its own leg; until then the view opens an honest interim page, never "empty". */
    internal fun isLiveTvView(item: MediaItem) = item.collectionType.equals("livetv", ignoreCase = true)
    fun openLiveTv(item: MediaItem? = null) = page(DesktopRoute.LiveTv, remember = mutable.value.route != DesktopRoute.LiveTv) { g ->
        val a = mutable.value.account ?: return@page
        publish(g) { it.copy(library = item, root = item?.let { view -> desktopLibraryRoot(view.id) } ?: "live-tv") }
        liveTv.open(a)
    }
    fun open(item: MediaItem) {
        if (item.type == "Person") { person(item); return }
        if (isLiveTvView(item)) { openLiveTv(item); return }
        if (item.isDesktopMusic()) { if (item.collectionType == "music") musicLibrary(item) else musicItem(item); return }
        if (item.type == "Photo") { openPhoto(item); return }
        if (item.type in setOf("CollectionFolder", "Folder", "BoxSet", "Playlist") || item.isFolder && item.type !in setOf("Series", "Season")) browse(item) else detail(item)
    }
    /** BROWSE-01..06, HOME-06: [sortBy]/[descending] null use this viewer's remembered display for the library. */
    fun browse(item: MediaItem, sortBy: String? = null, descending: Boolean? = null) = browseLibrary(item, sortBy, descending)
    fun loadMore() {
        val before = mutable.value
        if (before.route != DesktopRoute.Library || before.busy || nextPageIndex >= before.total || before.library?.let(::isLiveTvView) == true) return
        val a = before.account ?: return; val library = before.library ?: return
        val g = generation.get(); val startIndex = nextPageIndex; updateState { it.copy(busy = true) }
        pageJob = scope.launch {
            try {
                val p = attempt(g, a) { client.items(a, libraryQuery(library, before.sortBy, before.descending, before.catalog.filters, startIndex = startIndex)) }
                if (p != null && generation.get() == g) {
                    nextPageIndex = if (p.items.isEmpty()) p.totalRecordCount else startIndex + p.items.size
                    publish(g) { it.copy(items = (it.items + p.items).distinctBy(MediaItem::id), total = p.totalRecordCount) }
                }
            } finally { publish(g) { it.copy(busy = false) } }
        }
    }
    fun detail(item: MediaItem) = page(DesktopRoute.Detail, remember = true) { g -> loadDetail(g, item) }
    /** MUS-01: a music library opens its own tabbed page, lit as its sidebar root. */
    fun musicLibrary(library: MediaItem) = page(DesktopRoute.MusicLibrary, remember = mutable.value.library?.id != library.id || mutable.value.route != DesktopRoute.MusicLibrary) { g ->
        publish(g) { it.copy(library = library, root = desktopLibraryRoot(library.id)) }
    }
    /** MUS-02: an album, artist, genre, playlist or song. [role] is the credit an artist page reads. */
    fun musicItem(item: MediaItem, role: app.pictureshow.core.music.ArtistRole? = null, library: String? = null) = page(DesktopRoute.MusicItem, remember = true) { g ->
        mutable.value.account?.let { musicBrowse.forget(it, item) }
        publish(g) { it.copy(detail = item, detailLibrary = library ?: it.library?.takeIf { l -> l.collectionType == "music" }?.id, musicRole = role?.name) }
    }
    /** MUS-03: Now playing and the queue. */
    fun nowPlaying() { if (mutable.value.route != DesktopRoute.MusicNow) page(DesktopRoute.MusicNow, remember = true) { _ -> } }
    internal suspend fun loadDetail(g: Long, item: MediaItem, selected: MediaItem? = null) {
        val a = mutable.value.account ?: return
        publish(g) { it.copy(detail = item, detailLibrary = null, seasons = emptyList(), episodes = emptyList(), selectedSeason = null) }
        val fresh = attempt(g, a) { client.item(a, item.id) } ?: return
        publish(g) { it.copy(detail = fresh) }
        loadDetailDiscovery(g, a, fresh)
        val parents = (client.searchAncestors(a, fresh.id) as? ClientResult.Success)?.value.orEmpty()
        val libraryName = searchLibraryName(parents, mutable.value.sidebarLibraries())
        publish(g) { it.copy(detailLibrary = libraryName) }
        if (fresh.type == "Series") {
            val seasons = attempt(g, a) { client.seasons(a, fresh.id) }?.items.orEmpty()
            publish(g) { it.copy(seasons = seasons) }
            (seasons.firstOrNull { it.id == selected?.id } ?: seasons.firstOrNull())?.let { loadSeason(g, a, fresh.id, it) }
        } else if (fresh.type == "Season") {
            fresh.seriesId?.let { loadSeason(g, a, it, fresh) }
        }
    }
    fun selectSeason(season: MediaItem) = page(DesktopRoute.Detail) { g ->
        val a = mutable.value.account ?: return@page
        val seriesId = mutable.value.detail?.takeIf { it.type == "Series" }?.id ?: season.seriesId ?: return@page
        loadSeason(g, a, seriesId, season)
    }
    private suspend fun loadSeason(g: Long, a: Account, seriesId: String, season: MediaItem) {
        publish(g) { it.copy(selectedSeason = season, episodes = emptyList()) }
        val episodes = attempt(g, a) { client.episodes(a, seriesId, season.id) }?.items.orEmpty()
        publish(g) { it.copy(episodes = episodes) }
    }
    fun toggleFavorite() = userDataAction(false)
    fun toggleWatched() = userDataAction(true)
    private fun userDataAction(watched: Boolean) {
        val d = mutable.value.detail ?: return
        page(DesktopRoute.Detail) { g ->
            val a = mutable.value.account ?: return@page
            val ud = attempt(g, a) { if (watched) client.played(a, d.id, !d.isWatched) else client.favorite(a, d.id, !d.userData.isFavorite) } ?: return@page
            if (generation.get() == g) patchItemUserData(d.id) { ud }
        }
    }
    fun settings() { if (!pins.gated()) page(DesktopRoute.Settings, remember = mutable.value.route != DesktopRoute.Settings) { _ -> } }
    fun downloads() = page(DesktopRoute.Downloads, remember = mutable.value.route != DesktopRoute.Downloads) { _ -> }
    /** Downloads' "Try the server again": the same check a launch makes, landing on Home when it answers. */
    fun retryServer() = start()
    /** Plays a ready download from Downloads, with or without the server (OFF-05). */
    fun playDownload(entry: app.pictureshow.core.offline.OfflineEntry) {
        if (mutable.value.busy || !pins.admits(mutable.value.account?.key) || pins.gated()) return
        page(DesktopRoute.Player, remember = true) { g ->
            finishViewing()
            val a = mutable.value.account ?: return@page
            playLocal(g, a, entry, fromStart = false, server = null)
        }
    }
    private suspend fun playLocal(g: Long, a: Account, entry: app.pictureshow.core.offline.OfflineEntry, fromStart: Boolean, server: ItemUserData?) {
        val local = offline.prepareLocal(a, entry, fromStart, server)
        if (local == null) { publish(g) { it.copy(error = "This download is no longer on this computer. Download it again from its title.") }; return }
        events.log("playback local item=${entry.itemId}")
        prepareViewing(g, a, local.item, local.startSeconds, local = local)
    }
    /** A title's own Play uses its ready download unless the server is the only way (offlineDetailPlaySource). Returns true when it did. */
    private suspend fun playLocalCopy(g: Long, a: Account, item: MediaItem, fromStart: Boolean): Boolean {
        val entry = offline.state.value.readyFor(item.id) ?: return false
        if (offline.detailSource(a, item.id) != app.pictureshow.core.offline.OfflineDetailSource.local) return false
        playLocal(g, a, entry, fromStart, item.userData.takeIf { !offline.state.value.serverUnreachable })
        return true
    }
    fun saveSettings(settings: DesktopSettings) = page(DesktopRoute.Settings) { g -> settingsStore.write(settings); publish(g) { it.copy(settings = settings) }; engine?.volume(settings.volume) }
    @Synchronized fun back() {
        val s = mutable.value
        if (pins.gated()) return
        if (s.route == DesktopRoute.Player) { stopPlayback(); return }
        if (s.route == DesktopRoute.QuickConnect) { page(DesktopRoute.SignIn) { _ -> }; return }
        val previous = synchronized(history) { if (history.isEmpty()) null else history.removeAt(history.lastIndex) }
        generation.incrementAndGet(); pageJob?.cancel()
        if (previous != null) {
            updateState { previous.copy(busy = false, error = null, errorKind = null, settings = s.settings,
                search = previous.search.copy(recent = store.snapshots.value.recentSearches[previous.account?.key].orEmpty(), storageError = s.search.storageError),
                catalog = previous.catalog.copy(preferences = s.catalog.preferences ?: previous.catalog.preferences, saving = s.catalog.saving, queueBusy = false)) }
            refreshReturnedPage(previous)
        } else when (s.route) {
            DesktopRoute.SignIn -> addServer()
            DesktopRoute.Accounts, DesktopRoute.Servers -> if (s.account != null) home() else addServer()
            else -> if (s.account != null) home() else addServer()
        }
    }
    /** Layout/history may be cached; user data never becomes authoritative just because Back restored it. */
    private fun refreshReturnedPage(previous: DesktopState) {
        when (catalogReturnRefresh(previous.route.name)) {
            CatalogReturnRefresh.HomeRows -> page(DesktopRoute.Home) { g -> loadHome(g) }
            CatalogReturnRefresh.TitleAndChildren -> previous.detail?.let { item -> page(DesktopRoute.Detail) { g -> loadDetail(g, item, previous.selectedSeason) } }
            CatalogReturnRefresh.LoadedCards -> page(previous.route) { g ->
                val a = mutable.value.account ?: return@page
                val count = maxOf(60, previous.items.size)
                val result = if (previous.route == DesktopRoute.Library) previous.library?.let {
                    attempt(g, a) { client.items(a, libraryQuery(it, previous.sortBy, previous.descending, previous.catalog.filters, limit = count.coerceAtMost(200))) }
                } else previous.person?.let { attempt(g, a) { client.personItems(a, it.id, limit = count) } }
                if (result != null) {
                    nextPageIndex = result.startIndex + result.items.size
                    publish(g) { it.copy(items = result.items.distinctBy(MediaItem::id), total = result.totalRecordCount) }
                }
            }
            CatalogReturnRefresh.SearchResults -> searchQuery(previous.search.query, immediate = true, retainResults = true)
            CatalogReturnRefresh.None -> Unit
        }
    }

    /** Patch every cached occurrence before a slow/offline return can paint a pre-play badge or resume label. */
    private fun patchPlaybackData(item: MediaItem, position: Double, ended: Boolean) {
        patchItemUserData(item.id) { playbackReturnData(it, (position.coerceAtLeast(0.0) * 10_000_000).toLong(), ended) }
    }
    private fun patchItemUserData(itemId: String, update: (ItemUserData) -> ItemUserData) {
        fun patch(card: MediaItem) = if (card.id == itemId) card.copy(userData = update(card.userData)) else card
        fun patch(s: DesktopState) = s.copy(detail = s.detail?.let(::patch), items = s.items.map(::patch),
            seasons = s.seasons.map(::patch), episodes = s.episodes.map(::patch),
            homeRows = s.homeRows.map { it.copy(items = it.items.map(::patch)) },
            search = s.search.copy(rows = s.search.rows.map { it.copy(items = it.items.map(::patch)) }))
        updateState(::patch)
        synchronized(history) { history.indices.forEach { history[it] = patch(history[it]) } }
    }

    fun retry() {
        val s = mutable.value
        when (s.route) {
            DesktopRoute.Loading -> start()
            DesktopRoute.Home -> home()
            DesktopRoute.Search -> searchQuery(s.search.query, immediate = true)
            DesktopRoute.Person -> s.person?.let { item -> page(DesktopRoute.Person) { g ->
                val a = mutable.value.account ?: return@page
                val p = attempt(g, a) { client.personItems(a, item.id) } ?: return@page
                publish(g) { it.copy(items = p.items, total = p.totalRecordCount) }
            } }
            DesktopRoute.LiveTv -> liveTv.refresh()
            DesktopRoute.MusicLibrary -> s.account?.let { a -> s.library?.let { musicBrowse.library(a, it).retry() } }
            DesktopRoute.MusicItem -> s.account?.let { a -> s.detail?.let { musicBrowse.detail(a, it, desktopMusicRole(s.musicRole), s.detailLibrary).reload() } }
            DesktopRoute.Library -> s.library?.let { browse(it, s.sortBy, s.descending) }
            DesktopRoute.Detail -> s.detail?.let { page(DesktopRoute.Detail) { g -> loadDetail(g, it) } }
            DesktopRoute.QuickConnect -> quickConnect()
            DesktopRoute.Servers -> s.serverAddress?.let(::connect)
            else -> updateState { it.copy(error = null, errorKind = null) }
        }
    }
    /** Supply the desktop Supporter screen with the provider for its current package. */
    internal fun packagedSupporterStore(): StoreKitBridge? = packagedStoreKit ?: window?.let(::windowsSupporterStore)

    fun attachWindow(window: Window) {
        this.window = window
        attachPinWindow(window, pins)
        if (!inputAttached) {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(inputListener,
                java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK or
                    java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            inputAttached = true
        }
        engine?.attach(window)
    }
    fun detachWindow() {
        if (inputAttached) java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener)
        inputAttached = false; window = null
    }

    /** [queue] (BROWSE-06) advances like episodes; [returnToOpener] makes Stop return to the page that started it. */
    fun play(item: MediaItem, fromStart: Boolean = false, queue: List<MediaItem> = emptyList(), returnToOpener: Boolean = false) {
        if (mutable.value.busy || !pins.admits(mutable.value.account?.key) || pins.gated()) return
        catalogSession.queue = queue; catalogSession.returnToOpener = returnToOpener
        page(DesktopRoute.Player, remember = true) { g ->
            finishViewing()
            val a = mutable.value.account ?: return@page
            if (playLocalCopy(g, a, item, fromStart)) return@page
            val fresh = attempt(g, a) { client.item(a, item.id) } ?: return@page
            val playable = if (fresh.type in setOf("Series", "Season")) {
                val next = if (fresh.type == "Series") attempt(g, a) { client.nextUp(a, fresh.id) }?.items.orEmpty() else emptyList()
                val seasons = if (fresh.type == "Series") attempt(g, a) { client.seasons(a, fresh.id) }?.items.orEmpty() else listOf(fresh)
                val episodes = mutableListOf<MediaItem>()
                for (season in seasons) {
                    val seriesId = if (fresh.type == "Series") fresh.id else fresh.seriesId ?: break
                    episodes += attempt(g, a) { client.episodes(a, seriesId, season.id) }?.items.orEmpty()
                    if (episodes.any { !it.isWatched }) break
                }
                detailPlayItem(fresh, next, episodes)
            } else if (fresh.type == "Trailer") fresh else detailPlayItem(fresh, emptyList(), emptyList())
            if (playable == null || playable.isFolder || playable.type !in setOf("Movie", "Episode", "Video", "MusicVideo", "Recording", "Trailer")) {
                publish(g) { it.copy(error = "There is no playable video in this item.") }; return@page
            }
            // SET-18: rewind applies once, to a fresh resume only.
            val saved = playable.userData.playbackPositionTicks.coerceAtLeast(0)
            val tuning = mutable.value.settings.preferences().tuning.normalized()
            val start = when { fromStart -> 0.0; tuning.rewindSeconds == 0 -> saved / 10_000_000.0; else -> tuning.resumedPositionMs(saved / 10_000) / 1000.0 }
            prepareViewing(g, a, playable, start)
        }
    }
    fun tuneChannel(channel: LiveChannel) {
        val a = mutable.value.account ?: return
        if (!pins.admits(a.key) || pins.gated()) return
        if (!liveTv.state.value.actionsAvailable(System.currentTimeMillis()) || channel.id !in liveTv.state.value.channels.map { it.id }) return
        page(DesktopRoute.Player, remember = true) { g ->
            finishViewing()
            val policy = attempt(g, a) { LiveTvClient(http, identity).policy(a) } ?: return@page
            if (!policy.access) { publish(g) { it.copy(error = "Live TV access is disabled for this viewer.") }; return@page }
            val fresh = attempt(g, a) { LiveTvClient(http, identity).channel(a, channel.id) } ?: return@page
            prepareViewing(g, a, MediaItem(fresh.id, fresh.name, "TvChannel"), 0.0, liveChannel = fresh)
        }
    }
    private suspend fun prepareViewing(g: Long, a: Account, item: MediaItem, start: Double, audio: Int? = null, subtitle: Int? = null, sourceId: String? = null, audioChosen: Boolean = audio != null, subtitleChosen: Boolean = subtitle != null, subtitleCodec: String? = null, liveChannel: LiveChannel? = null, carry: DesktopViewingCarry? = null, local: DesktopLocalPlay? = null) {
        music.beforeVideo() // MUS-04: a video start stops music, which resumes only by the viewer's Play.
        publish(g) { it.copy(detail = item, playback = null) }
        val player = try { playbackFactory(File(directory, "playback")) }
        catch (e: LinkageError) { recordDesktopPlayerFailure("factory", e); publish(g) { it.copy(error = "The desktop player is not installed correctly. Check libmpv and its native adapter.") }; return }
        val prefs = mutable.value.settings.preferences()
        val maxBitrate = prefs.deviceSettings(desktopPlaybackNetwork(a.server.url)).maxStreamingBitrate
        val profile = try { player.profile(mutable.value.settings).forDesktopPreferences(prefs, maxBitrate).forDesktopSubtitle(subtitleCodec) }
        catch (e: Throwable) {
            recordDesktopPlayerFailure("profile", e)
            runCatching { player.close() }
            if (e is CancellationException || e is VirtualMachineError) throw e
            publish(g) { it.copy(error = "The desktop player could not load its decoder capabilities. Check the installed libmpv build.") }; return
        }
        val rejectedDecisions = mutableListOf<PlayDecision>()
        suspend fun discardPreparedPlayer() {
            runCatching { player.close() }
            withContext(NonCancellable) {
                rejectedDecisions.mapNotNull { it.ownedLiveStreamId }.distinct().forEach { id -> runCatching { playbackClient.closeLiveStream(a, id) } }
                rejectedDecisions.distinctBy { it.playSessionId }.filter { it.method == PlayMethod.Transcode && it.playSessionId != null }
                    .forEach { decision -> runCatching { playbackClient.stopEncoding(a, requireNotNull(decision.playSessionId)) } }
            }
        }
        // A bounded negotiation may open a server source. Capture its ownership before observing a
        // cancelled page job, then close it if this selection lost the race. Never discard an owned ID.
        suspend fun negotiate(call: suspend () -> ClientResult<PlayOutcome>): PlayOutcome? {
            try {
                val answer = withContext(NonCancellable) {
                    attempt(g, a, call).also { outcome ->
                        (outcome as? PlayOutcome.Play)?.decision?.let(rejectedDecisions::add)
                    }
                }
                currentCoroutineContext().ensureActive()
                return answer
            } catch (e: Throwable) { discardPreparedPlayer(); throw e }
        }
        // A download needs no negotiation: its decision is the file on this computer (OFF-05).
        var outcome = if (local != null) PlayOutcome.Play(local.decision) else negotiate { playbackClient.prepareFor(a, item.id, profile, maxBitrate,
            PlaybackRequest(startPosition = start.seconds, audioStreamIndex = audio, subtitleStreamIndex = subtitle, mediaSourceId = sourceId)) }
        var negotiatedAudio = audio
        var negotiatedSubtitle = subtitle
        if (outcome is PlayOutcome.Play && local == null) {
            val firstDecision = outcome.decision
            rejectedDecisions += firstDecision
            val firstStreams = firstDecision.mediaSource.mediaStreams
            val initialAudio = audio ?: StreamDefaults.choose(firstStreams, MediaStreamType.Audio, firstDecision.mediaSource.defaultAudioStreamIndex, java.util.Locale.getDefault().language)
            val initialSubtitle = subtitle ?: StreamDefaults.choose(firstStreams, MediaStreamType.Subtitle, firstDecision.mediaSource.defaultSubtitleStreamIndex, java.util.Locale.getDefault().language)
            val selected = firstStreams.firstOrNull { it.type == MediaStreamType.Subtitle && it.index == initialSubtitle }
            if (selected != null) {
                val delivery = selected.deliveryUrl?.takeIf { it.isNotBlank() }
                val safeDelivery = delivery?.let { candidate ->
                    runCatching { PlayDecisions.isSameOrigin(a.server, PlayDecisions.resolve(a.server, candidate)) }.getOrDefault(false)
                } ?: false
                if (selected.requiresExternalSubtitleDelivery() && delivery != null && !safeDelivery) {
                    discardPreparedPlayer()
                    publish(g) { it.copy(error = "The selected subtitle could not be loaded safely. Choose another subtitle or turn subtitles off.") }
                    return
                }

                val bitmapFormat = desktopBitmapSubtitleFormat(selected.codec)
                    ?.takeIf { !selected.isExternal && selected.deliveryMethod != SubtitleDeliveryMethod.Embed }
                val needsExternalRetry = selected.requiresExternalSubtitleDelivery() && delivery == null
                val needsBitmapRetry = bitmapFormat != null
                if (needsExternalRetry || needsBitmapRetry) {
                    val retryProfile = if (needsBitmapRetry) profile.forDesktopSubtitle(selected.codec) else profile.forDesktopSubtitle(null)
                    outcome = negotiate {
                        playbackClient.prepareFor(a, item.id, retryProfile, maxBitrate,
                            PlaybackRequest(startPosition = start.seconds, audioStreamIndex = initialAudio,
                                subtitleStreamIndex = initialSubtitle, mediaSourceId = firstDecision.mediaSourceId))
                    }
                    val retryDecision = (outcome as? PlayOutcome.Play)?.decision
                    if (retryDecision != null) rejectedDecisions += retryDecision
                    val retriedSubtitle = retryDecision?.mediaSource?.mediaStreams?.firstOrNull {
                        it.type == MediaStreamType.Subtitle && it.index == initialSubtitle
                    }
                    val retryUrl = retriedSubtitle?.deliveryUrl?.takeIf { it.isNotBlank() }
                    val retryUrlIsSafe = retryUrl?.let { candidate ->
                        runCatching { PlayDecisions.isSameOrigin(a.server, PlayDecisions.resolve(a.server, candidate)) }.getOrDefault(false)
                    } ?: false
                    val bitmapReady = retriedSubtitle != null &&
                        desktopBitmapSubtitleFormat(retriedSubtitle.codec) == bitmapFormat &&
                        !retriedSubtitle.isExternal && retriedSubtitle.deliveryMethod == SubtitleDeliveryMethod.Embed
                    val externalReady = retriedSubtitle?.requiresExternalSubtitleDelivery() == true && retryUrlIsSafe
                    if (outcome == null) {
                        discardPreparedPlayer()
                        return
                    }
                    if (outcome !is PlayOutcome.Play || (needsBitmapRetry && !bitmapReady) || (needsExternalRetry && !externalReady)) {
                        discardPreparedPlayer()
                        val message = (outcome as? PlayOutcome.Unplayable)?.message
                            ?: if (needsBitmapRetry)
                                "The server could not prepare the selected bitmap subtitle for this player. Choose another subtitle or turn subtitles off."
                            else "The server did not provide a safe URL for the selected text subtitle. Choose another subtitle or turn subtitles off."
                        publish(g) { it.copy(playback = null, error = message) }
                        return
                    }
                    negotiatedAudio = initialAudio
                    negotiatedSubtitle = initialSubtitle
                }
            }
        }
        if (outcome !is PlayOutcome.Play) {
            discardPreparedPlayer()
            if (outcome is PlayOutcome.Unplayable) publish(g) { it.copy(error = outcome.message) }
            return
        }
        informationOpen = false
        val decision = outcome.decision
        // A subtitle retry may allocate a different owned live source. Release superseded
        // ownership before adoption, while preserving identities shared with the final decision.
        var supersededClean = true
        try {
            withContext(NonCancellable) {
                rejectedDecisions.mapNotNull { it.ownedLiveStreamId }.distinct()
                    .filter { it != decision.ownedLiveStreamId }
                    .forEach { id -> if (runCatching { playbackClient.closeLiveStream(a, id) }.getOrNull() !is ClientResult.Success) supersededClean = false }
                rejectedDecisions.distinctBy { it.playSessionId }
                    .filter { it.method == PlayMethod.Transcode && it.playSessionId != null && it.playSessionId != decision.playSessionId }
                    .forEach { old -> if (runCatching { playbackClient.stopEncoding(a, requireNotNull(old.playSessionId)) }.getOrNull() !is ClientResult.Success) supersededClean = false }
                if (supersededClean) {
                    rejectedDecisions.clear()
                    rejectedDecisions += decision
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) { discardPreparedPlayer(); throw e }
        if (!supersededClean) {
            discardPreparedPlayer()
            events.log("playback superseded-source cleanup=failed")
            publish(g) { it.copy(error = "The previous live source could not be released. Playback did not start. Check the server connection before tuning again.") }
            return
        }
        val streams = decision.mediaSource.mediaStreams
        val ai = negotiatedAudio ?: StreamDefaults.choose(streams, MediaStreamType.Audio, decision.mediaSource.defaultAudioStreamIndex, java.util.Locale.getDefault().language)
        val si = negotiatedSubtitle ?: StreamDefaults.choose(streams, MediaStreamType.Subtitle, decision.mediaSource.defaultSubtitleStreamIndex, java.util.Locale.getDefault().language)
        window?.let(player::attach)
        engine = player
        val v = Viewing(a, item, decision, player, audioChosen = audioChosen, subtitleChosen = subtitleChosen, local = local, startMs = (start * 1000).toLong())
        viewing = v
        val notice = if (mutable.value.settings.conversionNotice) ConversionNotice.of(decision, si, "this computer") else null
        publish(g) { it.copy(playback = DesktopPlaybackState(item, decision, notice, streams.filter { t -> t.type == MediaStreamType.Audio }, streams.filter { t -> t.type == MediaStreamType.Subtitle }, ai, si, positionSeconds = start, durationSeconds = (item.runTimeTicks ?: 0).toDouble() / 10_000_000, liveChannel = liveChannel,
            extras = DesktopPlayerExtras(speed = carry?.speed ?: 1f, subtitleDelayMs = carry?.subtitleDelayMs ?: 0, autoSkipped = carry?.autoSkipped.orEmpty())), error = takeCarriedReportError()) }
        seekTarget = null; pausedLivePosition = null
        try {
            viewingMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (viewing !== v) return
                player.informationQuality(mutable.value.settings.bitrateMbps)
                player.preferences(prefs)
                player.open(a, decision, start, ai, si, mutable.value.settings.volume)
                carry?.let { k -> if (k.speed != 1f && !player.speed(k.speed)) updateExtras { it.copy(speed = 1f) }; if (k.subtitleDelayMs != 0) player.subtitleDelay(k.subtitleDelayMs) }
            }
            currentCoroutineContext().ensureActive()
        }
        catch (e: Throwable) {
            if (e is CancellationException) {
                withContext(NonCancellable) { finishViewing() }
                throw e
            }
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            recordDesktopPlayerFailure("open", e)
            runCatching { player.close() }
            withContext(NonCancellable) { releaseEncoding(v) }
            v.stopped = true
            if (viewing === v) viewing = null
            if (engine === player) engine = null
            publish(g) { it.copy(error = "The video player could not start. Check that the matching libmpv and native surface adapter are installed.") }
            return
        }
        reportJob = scope.launch {
            try {
                var elapsed = 0
                while (isActive && viewing === v && !v.closing && !v.stopped) {
                    delay(500)
                    if (v.closing || viewing !== v) break
                    val snap = player.snapshot()
                    updateState { s -> if (viewing === v) s.copy(playback = s.playback?.copy(positionSeconds = snap.positionSeconds, durationSeconds = snap.durationSeconds.takeIf { it > 0 } ?: s.playback.durationSeconds,
                        paused = snap.paused, ready = snap.ready, liveWindow = snap.liveWindow, bufferExpired = snap.liveWindow?.pausedPositionExpired(pausedLivePosition) == true, received = snap.received)) else s }
                    if (snap.error != null && !snap.ended) { v.playbackFailed = true; events.log("playback error item=${v.item.id}"); updateState { it.copy(error = snap.error) }; break }
                    if (!snap.ready && elapsed >= 60) { updateState { it.copy(error = "The video did not start. Return to the title and try again.") }; break }
                    if (snap.ready && !v.startAttempted) v.reports.withLock {
                        if (!v.closing && !v.startAttempted) {
                            val p = mutable.value.playback
                            if (p != null && viewing === v) {
                                val observed = v.engine.snapshot()
                                v.startAttempted = true
                                val report = PlaybackReports(decision).progress(observed.positionSeconds.seconds, observed.paused,
                                    p.audioIndex.takeIf { v.audioChosen }, p.subtitleIndex.takeIf { v.subtitleChosen })
                                when (val sent = if (v.local != null) ClientResult.Success(Unit) else playbackClient.reportStart(a, report)) {
                                    is ClientResult.Failure -> { events.log("playback start item=${v.item.id} report=failed:${sent.error.kind}"); if (viewing === v) updateState { it.copy(error = sent.error.message) } }
                                    is ClientResult.Success -> { v.startReported = true; events.log("playback start item=${v.item.id} method=${decision.method} report=ok") }
                                }
                            }
                        }
                    }
                    elapsed++
                    if (v.startAttempted && elapsed % 10 == 0) sendProgress(v)
                    if (snap.exitRequested) { stopPlayback(); break }
                    if (!snap.ended) extrasTick(v, snap)
                    if (snap.ended && !informationOpen && !snap.informationOpen) {
                        if (liveChannel != null || v.decision.isLive) {
                            updateState { it.copy(error = "The live stream ended. Return to Live TV and tune again.") }
                            scope.launch { finishViewing() }
                        } else onPhysicalEnd(v, snap)
                        break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (viewing === v && !v.ended) {
                    events.log("playback loop-failed item=${v.item.id} type=${e.javaClass.simpleName}")
                    updateState { it.copy(error = "Playback stopped unexpectedly. Return to the title and try again.") }
                }
            }
        }
        if (liveChannel == null && !decision.isLive && local == null) scope.launch {
            val loaded = extrasLoader.load(a, item, decision.mediaSourceId)
            if (viewing === v) updateExtras { it.copy(chapters = loaded.chapters, trickplay = loaded.trickplay, segments = loaded.segments) }
        }
        if (catalogSession.queue.any { it.id == item.id }) {
            publish(g) { it.copy(playback = it.playback?.copy(nextEpisode = desktopQueueNext(catalogSession.queue, item))) }
        } else if (item.type == "Episode" && item.seriesId != null) {
            // A local viewing never asks the server: the next downloaded episode, by the downloads' own order.
            val next = if (local != null) offline.localNext(a, local, serverAnswered = false, serverNext = null) else attempt(g, a) { extras.nextEpisode(a, item) }
            publish(g) { it.copy(playback = it.playback?.copy(nextEpisode = next)) }
        }
    }
    private suspend fun sendProgress(v: Viewing) = v.reports.withLock {
        if (!v.startAttempted || v.stopped || v.closing || viewing !== v) return@withLock
        val p = mutable.value.playback ?: return@withLock
        v.local?.let { local -> runCatching { v.engine.snapshot() }.getOrNull()?.let { offline.localProgress(local, it.positionSeconds) }; return@withLock }
        val observed = runCatching { v.engine.snapshot() }.getOrElse {
            updateState { state -> state.copy(error = "Progress could not be read from the player. Return to the title and try again.") }
            return@withLock
        }
        val r = playbackClient.reportProgress(v.account, PlaybackReports(v.decision).progress(observed.positionSeconds.seconds, observed.paused,
            p.audioIndex.takeIf { v.audioChosen }, p.subtitleIndex.takeIf { v.subtitleChosen }).copy(isMuted = v.muted))
        if (r is ClientResult.Failure && viewing === v) updateState { it.copy(error = "Progress could not be saved. ${r.error.message}") }
    }
    fun fullscreen(enabled: Boolean) { engine?.fullscreen(enabled) }
    fun videoViewport(topRatio: Double, bottomRatio: Double) { engine?.viewport(topRatio,bottomRatio) }
    fun videoViewportReceipt(): String? = engine?.viewportReceipt()
    fun nativeTrackSelection(): DesktopSelectedTracks? = engine?.selectedTracks()
    fun pauseForBackground() {
        val active = engine ?: return
        if (mutable.value.route == DesktopRoute.Player && runCatching { !active.snapshot().paused }.getOrDefault(false)) {
            runCatching { active.togglePause() }
            viewing?.let { v -> scope.launch { delay(100); if (viewing === v) sendProgress(v) } }
        }
    }
    @Volatile private var pausedLivePosition: Long? = null
    fun goLive() {
        val p = mutable.value.playback ?: return
        val w = runCatching { engine?.snapshot()?.liveWindow }.getOrNull() ?: return
        val edge = w.goLivePosition() ?: return
        engine?.seek((w.offsetMs + edge) / 1000.0)
        pausedLivePosition = null
        if (p.paused) engine?.togglePause()
    }
    fun playerToggle() {
        val p = mutable.value.playback
        if (p?.liveChannel != null || p?.decision?.isLive == true) {
            val snap = runCatching { engine?.snapshot() }.getOrNull() ?: return
            val w = snap.liveWindow ?: return
            if (!w.canTimeShift) return
            if (snap.paused && w.pausedPositionExpired(pausedLivePosition)) { goLive(); return }
            pausedLivePosition = if (!snap.paused) (snap.positionSeconds * 1000).toLong() else null
        }
        if (viewing?.closing == true) return
        if (together?.transport(if (p?.paused == true) "play" else "pause") == true) return
        engine?.togglePause(); viewing?.let { v -> scope.launch { delay(100); if (viewing === v) sendProgress(v) } } }
    fun seek(seconds: Double) {
        if (viewing?.closing == true || !seconds.isFinite()) return
        val p = mutable.value.playback
        if (p?.liveChannel != null || p?.decision?.isLive == true) {
            val w = runCatching { engine?.snapshot()?.liveWindow }.getOrNull() ?: return
            val edge = w.goLivePosition() ?: return
            if (!w.canTimeShift || p.bufferExpired) return
            val target = (seconds * 1000).toLong().coerceIn(w.offsetMs, w.offsetMs + edge) / 1000.0
            engine?.seek(target)
            seekTarget = target; seekTargetAt = System.nanoTime()
            if (p.paused) pausedLivePosition = (target * 1000).toLong()
            return
        }
        val duration = mutable.value.playback?.durationSeconds?.takeIf { it > 0 }
        val target = seconds.coerceAtLeast(0.0).let { if (duration != null) it.coerceAtMost(duration) else it }
        if (together?.transport("seek", (target * 1000).toLong()) == true) return
        engine?.seek(target)
        seekTarget = target; seekTargetAt = System.nanoTime()
        updateState { s -> s.copy(playback = s.playback?.copy(positionSeconds = target)) }
    }
    /** Relative seek for keys and the 10 s buttons. Repeats inside 1.5 s build on the last target, so the
     * 500 ms clock poll cannot swallow a second press (PLAY-07's intent, desktop's version). */
    fun seekBy(deltaSeconds: Double) {
        val recent = seekTarget?.takeIf { System.nanoTime() - seekTargetAt < 1_500_000_000L }
        seek((recent ?: mutable.value.playback?.positionSeconds ?: return) + deltaSeconds)
    }
    fun toggleMute() {
        val v = viewing ?: return
        if (v.closing || v.stopped) return
        val muted = !v.muted
        if (runCatching { v.engine.mute(muted) }.isFailure) return
        v.muted = muted
        updateState { s -> s.copy(playback = s.playback?.copy(muted = muted)) }
        scope.launch { if (viewing === v) sendProgress(v) }
    }
    /** Arrow-key volume: saved like the Settings volume, without leaving the player. Raising it unmutes. */
    fun changeVolume(delta: Int) {
        val settings = mutable.value.settings
        val volume = (settings.volume + delta).coerceIn(0, 100)
        val updated = settings.copy(volume = volume)
        updateState { it.copy(settings = updated) }
        engine?.let { e -> runCatching { e.volume(volume) } }
        if (delta > 0 && viewing?.muted == true) toggleMute()
        scope.launch { runCatching { settingsStore.write(updated) } }
    }
    fun selectAudio(index: Int) = changeTrack(index, null)
    fun selectSubtitle(index: Int) = changeTrack(null, index)
    private fun changeTrack(audio: Int?, subtitle: Int?) {
        val p = mutable.value.playback ?: return; val a = mutable.value.account ?: return
        if (audio != null && p.audioTracks.none { it.index == audio }) return
        if (subtitle != null && subtitle != -1 && p.subtitleTracks.none { it.index == subtitle }) return
        val current = viewing ?: return
        if (current.closing || current.stopped) return
        val applied = if (audio != null) engine?.selectAudio(audio) == true else engine?.selectSubtitle(requireNotNull(subtitle)) == true
        if (applied) {
            if (audio != null) current.audioChosen = true else current.subtitleChosen = true
            updateState { it.copy(playback = it.playback?.copy(audioIndex = audio ?: p.audioIndex, subtitleIndex = subtitle ?: p.subtitleIndex)) }
            scope.launch { if (viewing === current) sendProgress(current) }
            return
        }
        if (current.local != null) { updateState { it.copy(error = "That track could not be switched in this download.") }; return }
        val requestedSubtitleCodec = subtitle?.takeIf { it >= 0 }?.let { index -> p.subtitleTracks.firstOrNull { it.index == index }?.codec }
        page(DesktopRoute.Player) { g ->
            finishViewing()
            prepareViewing(g, a, p.item, if (p.isLiveChannel) 0.0 else p.positionSeconds, audio ?: p.audioIndex, subtitle ?: p.subtitleIndex, p.decision.mediaSourceId,
                current.audioChosen || audio != null, current.subtitleChosen || subtitle != null, requestedSubtitleCodec, p.liveChannel, p.extras.carry())
        }
    }
    // Stream A player extras (PLAY-11/13/14/15/25/30, SUB-DL). Rules live in DesktopPlayerExtras.kt.
    private fun updateExtras(change: (DesktopPlayerExtras) -> DesktopPlayerExtras) =
        updateState { s -> s.copy(playback = s.playback?.let { it.copy(extras = change(it.extras)) }) }
    @Volatile private var creditsWallMs = 0L
    @Volatile private var creditsAdvanced: Any? = null
    private fun extrasTick(v: Viewing, snap: EngineSnapshot) {
        val p = mutable.value.playback ?: return
        if (viewing !== v || v.closing || p.end != null || p.isLiveChannel) return
        val prefs = mutable.value.settings.preferences()
        val positionMs = (snap.positionSeconds * 1000).toLong()
        val action = desktopSegmentAction(p.extras, positionMs, prefs.segmentModes)
        if (action is DesktopSegmentAction.Skip) {
            updateExtras { it.copy(autoSkipped = it.autoSkipped + action.segment.id) }
            events.log("playback segment-skip type=${action.segment.type} mode=automatic")
            seek(action.segment.endMs / 1000.0)
            return
        }
        // PLAY-30 / SET-17: "Next episode when credits start" counts down over the credits while they play.
        val tuning = prefs.tuning.normalized()
        val durationMs = (p.durationSeconds * 1000).toLong()
        val credits = creditsStartMs(durationMs, p.extras.segments)
        val count = endCountdownSeconds ?: tuning.nextEpisodeCountdownSeconds
        val eligible = p.nextEpisode != null && mutable.value.settings.autoplayNext && tuning.postCredits == PostCredits.NextEpisode &&
            credits != null && positionMs >= credits && !p.extras.creditsCancelled && count > 0 && !viewerInput.due(tuning.stillWatching)
        if (!eligible) {
            creditsWallMs = 0
            if (p.extras.creditsSecondsLeft != null) updateExtras { it.copy(creditsSecondsLeft = null) }
            return
        }
        if (p.extras.creditsSecondsLeft != null && !snap.paused) creditsWallMs += 500
        val left = nextEpisodeCardSeconds((count - creditsWallMs / 1000).coerceAtLeast(0), true, (durationMs - positionMs).coerceAtLeast(0),
            p.extras.speed.toDouble(), autoPlay = true, ended = false, suspended = snap.paused)?.toInt() ?: p.extras.creditsSecondsLeft ?: count
        if (left != p.extras.creditsSecondsLeft) updateExtras { it.copy(creditsSecondsLeft = left) }
        if (left <= 0 && creditsAdvanced !== v) {
            creditsAdvanced = v
            events.log("playback credits-advance item=${v.item.id}")
            viewerInput.automaticallyAdvanced()
            nextEpisode()
        }
    }
    /** The credits card's Cancel: the credits play on and the episode ends normally. */
    fun cancelCreditsCountdown() = updateExtras { it.copy(creditsSecondsLeft = null, creditsCancelled = true) }
    /** "Are you still watching?" answered yes. */
    fun continueWatching() { viewerInput.input(); nextEpisode() }
    /** PLAY-15 Ask: the Skip button jumps to the segment's end, once. */
    fun skipSegment() {
        val p = mutable.value.playback ?: return
        val action = desktopSegmentAction(p.extras, (p.positionSeconds * 1000).toLong(), mutable.value.settings.preferences().segmentModes)
        if (action is DesktopSegmentAction.Ask) { events.log("playback segment-skip type=${action.segment.type} mode=ask"); seek(action.segment.endMs / 1000.0) }
    }
    /** PLAY-13: one seek to the chapter's start. */
    fun seekChapter(index: Int) {
        val chapter = mutable.value.playback?.extras?.chapters?.getOrNull(index) ?: return
        seek(chapter.startMs / 1000.0)
    }
    /** PLAY-25: the seven speeds; never claims a rate the engine refused or encoded audio can't follow. */
    fun setSpeed(speed: Float) {
        val p = mutable.value.playback ?: return
        val e = engine ?: return
        val encoded = runCatching { e.passthroughActive() }.getOrDefault(false)
        if (!PlaybackSpeeds.allowed(speed, encoded)) { updateExtras { it.copy(speedMessage = PlaybackSpeeds.ENCODED_AUDIO) }; return }
        val applied = runCatching { e.speed(speed) }.getOrDefault(false)
        updateExtras {
            if (applied) it.copy(speed = speed, speedMessage = if (speed != 1f && p.decision.method == PlayMethod.Transcode) PlaybackSpeeds.TRANSCODE else null)
            else it.copy(speedMessage = "This player couldn't change speed.")
        }
    }
    /** PLAY-11: Earlier (-1), Later (+1) or Reset (0). */
    fun stepSubtitleDelay(direction: Int) {
        val e = engine ?: return
        val current = mutable.value.playback?.extras?.subtitleDelayMs ?: return
        val next = if (direction == 0) 0 else SubtitleTiming.step(current, direction)
        runCatching { e.subtitleDelay(next) }
        updateExtras { it.copy(subtitleDelayMs = next) }
    }
    /** PLAY-14: one trickplay sheet for the preview, or null. */
    suspend fun trickplaySheet(sheet: Int): ByteArray? {
        val p = mutable.value.playback ?: return null
        val a = mutable.value.account ?: return null
        val info = p.extras.trickplay ?: return null
        val source = p.decision.mediaSourceId ?: return null
        return extrasLoader.sheet(a, p.item.id, source, info.width, sheet)
    }
    /** SUB-DL from the player: a search bound to this viewing's item and source. */
    internal fun subtitleSearch(): app.pictureshow.core.subtitles.RemoteSubtitleSearch? {
        val a = mutable.value.account ?: return null
        val p = mutable.value.playback ?: return null
        if (p.isLiveChannel || p.decision.isLive) return null
        return app.pictureshow.core.subtitles.RemoteSubtitleSearch(subtitleClient, a, scope, java.util.Locale.getDefault().language).also {
            it.open(app.pictureshow.core.subtitles.RemoteSubtitleTarget(p.item.id, p.item.type, true, p.decision.mediaSourceId, p.decision.mediaSource.mediaStreams, p.audioIndex))
        }
    }
    /** SUB-DL: the server listed the downloaded file; reopen at the same place with it selected (speed and timing kept). */
    fun adoptDownloadedSubtitle(subtitleIndex: Int, audioIndex: Int) {
        val p = mutable.value.playback ?: return; val a = mutable.value.account ?: return
        val current = viewing ?: return
        if (current.closing || current.stopped || current.local != null) return
        page(DesktopRoute.Player) { g ->
            finishViewing()
            prepareViewing(g, a, p.item, p.positionSeconds, audioIndex.takeIf { it >= 0 } ?: p.audioIndex, subtitleIndex, p.decision.mediaSourceId,
                true, true, null, null, p.extras.carry())
        }
    }
    /** Manual "Next episode" in the control bar, or Play now on the end card. */
    fun nextEpisode() {
        val p = mutable.value.playback ?: return
        if (p.end == null && together?.transport("next") == true) return
        val next = p.end?.next ?: p.nextEpisode
        if (next == null) { stopPlayback(); return }
        val a = mutable.value.account ?: return
        page(DesktopRoute.Player) { g -> finishViewing(); if (!playLocalCopy(g, a, next, true)) prepareViewing(g, a, next, 0.0) }
    }
    /** End card Cancel: the countdown stops and the card stays, offering Play now and Back to title. */
    fun cancelEndCountdown() {
        countdownCancelled = true
        updateState { s -> s.copy(playback = s.playback?.let { p -> p.end?.let { p.copy(end = it.copy(secondsLeft = null)) } ?: p }) }
    }
    @Volatile private var countdownCancelled = false
    /** End card Replay: a new viewing of the same item from the start. */
    fun replay() {
        val item = mutable.value.playback?.item ?: return
        val a = mutable.value.account ?: return
        page(DesktopRoute.Player) { g -> finishViewing(); if (!playLocalCopy(g, a, item, true)) prepareViewing(g, a, item, 0.0) }
    }
    fun stopPlayback() {
        together?.closing(if (mutable.value.playback?.end != null) app.pictureshow.core.sync.SyncCloseReason.CLOSE else app.pictureshow.core.sync.SyncCloseReason.STOP_BUTTON)
        if (mutable.value.playback?.let { it.liveChannel != null || it.decision.isLive } == true) {
            synchronized(history) { if (history.lastOrNull()?.route == DesktopRoute.LiveTv) history.removeAt(history.lastIndex) }
            page(DesktopRoute.LiveTv) { g ->
                finishViewing()
                publish(g) { it.copy(playback = null, detail = null) }
            }
            return
        }
        if (viewing?.local != null && synchronized(history) { history.lastOrNull()?.route == DesktopRoute.Downloads }) {
            synchronized(history) { history.removeAt(history.lastIndex) }
            page(DesktopRoute.Downloads) { g -> finishViewing(); publish(g) { it.copy(playback = null, root = DESKTOP_ROOT_DOWNLOADS) } }
            return
        }
        val playback = mutable.value.playback
        val finished = desktopFinished(playback)
        val old = playback?.item ?: mutable.value.detail
        if (old != null && playback != null) patchPlaybackData(old, playback.positionSeconds, playback.end != null)
        if (returnToOpener()) return
        // Stop replaces the playback opener, including after an autoplay chain; Back must reach its parent.
        synchronized(history) { if (history.lastOrNull()?.route == DesktopRoute.Detail) history.removeAt(history.lastIndex) }
        page(DesktopRoute.Detail) { g ->
            // page() finishes/report-stops first. Use its patched final engine position, not the poll's older item.
            val patched = mutable.value.detail?.takeIf { it.id == old?.id } ?: old
            publish(g) { it.copy(playback = null, returnFocus = "play", finished = finished) }
            takeCarriedReportError()?.let { e -> publish(g) { it.copy(error = e) } }
            if (patched != null) loadDetail(g, patched)
        }
    }
    /**
     * PLAY-17/18 at the physical end: show the end card at once, close the viewing (one Stop, then one watched
     * mark) without waiting for the viewer, then count down to the next episode when autoplay is on.
     */
    private fun onPhysicalEnd(v: Viewing, snap: EngineSnapshot) {
        if (v.ended || viewing !== v) return
        v.endPosition = maxOf(snap.positionSeconds, snap.durationSeconds)
        v.ended = true
        patchPlaybackData(v.item, v.endPosition, true)
        val next = mutable.value.playback?.nextEpisode
        val configured = endCountdownSeconds?.coerceAtLeast(0) ?: mutable.value.settings.preferences().tuning.nextEpisodeCountdownSeconds.takeIf { it > 0 }
        // SYN: in a group the group decides what plays next; this device doesn't continue on its own.
        val grouped = together?.ended() == true
        val autoplay = !grouped && next != null && mutable.value.settings.autoplayNext && configured != null
        // PLAY-30: after enough episodes in a row, or long enough without a key press, ask instead of counting down.
        val ask = autoplay && viewerInput.due(mutable.value.settings.preferences().tuning.stillWatching)
        val countdown = if (autoplay && !ask) configured else null
        countdownCancelled = false
        updateState { s -> if (viewing === v) s.copy(playback = s.playback?.copy(end = DesktopEndCard(next, countdown, stillWatching = ask),
            positionSeconds = v.endPosition, paused = true)) else s }
        events.log("playback end item=${v.item.id} next=${next?.id ?: "none"} countdown=${countdown ?: if (ask) "ask" else "off"}")
        val g = generation.get()
        endJob = scope.launch {
            finishViewing()
            if (countdown == null || next == null) return@launch
            var left = countdown
            while (left > 0) {
                delay(1000)
                if (generation.get() != g || countdownCancelled || mutable.value.playback?.end?.next?.id != next.id) return@launch
                left--
                updateState { s -> s.copy(playback = s.playback?.let { p -> p.end?.takeIf { it.secondsLeft != null }?.let { p.copy(end = it.copy(secondsLeft = left)) } ?: p }) }
            }
            if (generation.get() == g && !countdownCancelled && mutable.value.route == DesktopRoute.Player &&
                mutable.value.playback?.end?.let { it.next?.id == next.id && it.secondsLeft != null } == true) { viewerInput.automaticallyAdvanced(); nextEpisode() }
        }
    }
    private suspend fun releaseEncoding(v: Viewing): Boolean {
        val live = v.decision.ownedLiveStreamId
        val closed = live == null || runCatching { playbackClient.closeLiveStream(v.account, live) }.getOrNull() is ClientResult.Success
        val encoding = releaseEncodingOnly(v)
        return closed && encoding
    }
    private suspend fun releaseEncodingOnly(v: Viewing): Boolean {
        val session = v.decision.playSessionId ?: return true
        if (v.decision.method != PlayMethod.Transcode) return true
        return runCatching { playbackClient.stopEncoding(v.account, session) }.getOrNull() is ClientResult.Success
    }
    /** Marks a genuinely finished item watched once (PLAY-18). The Stop report usually did it already; then no mutation. */
    private suspend fun completeWatched(v: Viewing) {
        val current = runCatching { client.item(v.account, v.item.id) }.getOrNull()
        if (current is ClientResult.Success && current.value.isWatched) {
            events.log("playback mark item=${v.item.id} result=already-played"); return
        }
        var marked = runCatching { client.played(v.account, v.item.id, true) }.getOrNull()
        if (marked !is ClientResult.Success) marked = runCatching { client.played(v.account, v.item.id, true) }.getOrNull()
        when (marked) {
            is ClientResult.Success -> events.log("playback mark item=${v.item.id} result=marked")
            is ClientResult.Failure -> {
                events.log("playback mark item=${v.item.id} result=failed:${marked.error.kind}")
                carriedReportError = "${v.item.name} finished, but it could not be marked as watched. ${marked.error.message}"
            }
            null -> {
                events.log("playback mark item=${v.item.id} result=failed:exception")
                carriedReportError = "${v.item.name} finished, but it could not be marked as watched. Check the connection."
            }
        }
        carriedReportError?.let { e -> updateState { it.copy(error = e) } }
    }
    private suspend fun finishViewing() = withContext(NonCancellable) {
        viewingMutex.withLock {
            val v = viewing ?: return@withLock
            if (v.stopped) return@withLock
            v.closing = true
            // Cleanup is local housekeeping. Its failure is logged and never sent to the server as a failed
            // playback: Jellyfin skips every play-state update for a Failed stop, which left finished episodes
            // unwatched (D-4). Only a media error observed during playback is Failed.
            val cleanupFailures = mutableListOf<String>()
            var observed: EngineSnapshot? = null
            try {
                // Each phase is independent: an unavailable property, failed GPU drain or
                // rejected report must not prevent the other resources being released.
                runCatching { v.engine.snapshot() }.onSuccess { observed = it }.onFailure { cleanupFailures += "snapshot" }
                if (observed?.paused == false && observed?.ended != true) runCatching { v.engine.togglePause() }.onFailure { cleanupFailures += "pause" }
                runCatching { reportJob?.join() }.onFailure { cleanupFailures += "report-loop" }
                reportJob = null
                runCatching { v.engine.snapshot() }.onSuccess { observed = it }.onFailure { cleanupFailures += "final-snapshot" }
                runCatching { v.engine.close() }.onFailure { cleanupFailures += "close" }
                if (engine === v.engine) engine = null
                v.reports.withLock {
                    if (v.startAttempted) {
                        val seen = observed?.positionSeconds?.takeIf { it > 0 } ?: mutable.value.playback?.positionSeconds ?: 0.0
                        val position = if (v.ended) maxOf(seen, v.endPosition) else seen
                        patchPlaybackData(v.item, position, v.ended)
                        val failed = v.playbackFailed && !v.ended
                        val local = v.local
                        if (local != null) {
                            // OFF-07: durable here first; settled with the server when it answers (OFF-08).
                            offline.localProgress(local, position)
                            if (v.ended) offline.localCompleted(local)
                            offline.localEnded(local)
                            events.log("playback stop item=${v.item.id} position=${position.toLong()} ended=${v.ended} local=outbox")
                        } else runCatching {
                            playbackClient.reportStopped(v.account, PlaybackReports(v.decision).stopped(position.seconds, failed = failed))
                        }.onSuccess { stopped ->
                            if (stopped is ClientResult.Failure) {
                                events.log("playback stop item=${v.item.id} position=${position.toLong()} failed=$failed ended=${v.ended} report=failed:${stopped.error.kind}")
                                carriedReportError = "Playback ended, but progress could not be saved. ${stopped.error.message}"
                                updateState { it.copy(error = carriedReportError) }
                            } else {
                                events.log("playback stop item=${v.item.id} position=${position.toLong()} failed=$failed ended=${v.ended} report=ok")
                                offline.onlineViewingEnded(v.account, v.item.id)
                            }
                        }.onFailure { e ->
                            events.log("playback stop item=${v.item.id} report=failed:${e.javaClass.simpleName}")
                            carriedReportError = "Playback ended, but progress could not be saved. Check the connection."
                            updateState { it.copy(error = carriedReportError) }
                        }
                    }
                    if (v.ended && v.local == null) completeWatched(v)
                }
            } finally {
                // The encoder may already exist even if native startup or Start failed.
                if (!releaseEncoding(v)) cleanupFailures += "encoding"
                v.stopped = true
                if (engine === v.engine) engine = null
                if (viewing === v) viewing = null
                if (cleanupFailures.isNotEmpty()) {
                    events.log("playback cleanup item=${v.item.id} failed=${cleanupFailures.joinToString(",")}")
                    // After a genuine end the viewer has nothing to do about it; otherwise keep the existing hint.
                    if (!v.ended) updateState { it.copy(error = "Playback ended, but the player could not finish every cleanup step. You can return to the title and try again.") }
                }
            }
        }
    }
    /** Stream E: start being a remote target, a remote controller and a Watch-together member (DesktopTogether.kt). */
    fun startTogether(hostDispatcher: CoroutineDispatcher = Dispatchers.Main) {
        if (together != null) return
        val t = DesktopTogether(togetherPlayback, http, identity, hostDispatcher, log = { events.log(it) }, admitted = { pins.admits(it.key) },
            allowed = { a -> mutable.value.account?.key == a.key && pins.admits(a.key) && !pins.gated() })
        t.lookups(item = { a, id -> client.item(a, id) }, items = { a, ids -> desktopItemsById(client, a, ids) })
        together = t
        t.scope.launch {
            t.start(state.map { it.account }.distinctUntilChanged(), state.map { it.settings.playback.remoteReceiver }.distinctUntilChanged())
            pins.state.map { pins.gated() || !pins.admits(mutable.value.account?.key) }.distinctUntilChanged().collect { t.lockChanged(it) }
        }
    }
    /** What the remote and group hosts may do to playback; the group's corrections go straight to the engine. */
    internal val togetherPlayback = object : DesktopTogetherPlayback {
        override fun video(): DesktopTogetherVideo? {
            val v = viewing?.takeIf { !it.closing && !it.stopped } ?: return null
            val p = mutable.value.playback?.takeIf { it.item.id == v.item.id } ?: return null
            val snap = runCatching { v.engine.snapshot() }.getOrNull()
            val duration = snap?.durationSeconds?.takeIf { it > 0 } ?: p.durationSeconds
            return DesktopTogetherVideo(v, v.item.id, v.item.type, ((snap?.positionSeconds ?: p.positionSeconds) * 1000).toLong(), (duration * 1000).toLong(),
                (snap?.ready ?: p.ready) && p.end == null, snap?.paused ?: p.paused, p.liveChannel != null || p.decision.isLive, v.playbackFailed || snap?.error != null, v.startMs, offline = v.local != null)
        }
        override fun music(): DesktopMusic = this@DesktopController.music
        override fun playMusic(play: DesktopMusicPlay) { this@DesktopController.music.request(play) }
        override fun queueMusic(owner: Account, tracks: List<DesktopMusicTrack>, next: Boolean) {
            if (next) this@DesktopController.music.playNext(owner, tracks) else this@DesktopController.music.append(owner, tracks)
        }
        override fun open(itemId: String, startMs: Long?, admit: suspend (Boolean) -> Boolean) { openFor(itemId, startMs, admit) }
        override fun toggle() = playerToggle()
        override fun seek(positionMs: Long) = seek(positionMs / 1000.0)
        override fun stop() = stopPlayback()
        override fun nextEpisode() = this@DesktopController.nextEpisode()
        override fun audio(index: Int) = selectAudio(index)
        override fun subtitle(index: Int) = selectSubtitle(index)
        override fun engineSeek(positionMs: Long) { engine?.seek(positionMs / 1000.0) }
        override fun enginePlaying(playing: Boolean) {
            val e = engine ?: return
            if (runCatching { e.snapshot().paused }.getOrDefault(playing) == playing) e.togglePause()
        }
        override fun engineSpeed(rate: Double): Boolean = runCatching { engine?.speed(rate.toFloat()) == true }.getOrDefault(false)
    }
    /**
     * Stream E: a title a remote or the Watch-together group opens. It resolves first and asks [admit] (on the host's thread)
     * before anything changes on screen, so a stale or refused command leaves the page as it is.
     */
    internal fun openFor(itemId: String, startMs: Long?, admit: suspend (Boolean) -> Boolean) {
        val host = together?.hostDispatcher
        suspend fun decide(ok: Boolean): Boolean = if (host == null) admit(ok) else withContext(host) { admit(ok) }
        scope.launch {
            val a = mutable.value.account?.takeIf { pins.admits(it.key) && !pins.gated() }
            val fresh = a?.let { (client.item(it, itemId) as? ClientResult.Success)?.value }
            val playable = fresh?.takeIf { !it.isFolder && it.type in DESKTOP_REMOTE_VIDEO_TYPES }
            if (!decide(playable != null) || a == null || playable == null) {
                events.log("together open refused resolved=${playable != null}")
                return@launch
            }
            val start = (startMs ?: (playable.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000)) / 1000.0
            catalogSession.queue = emptyList(); catalogSession.returnToOpener = false
            page(DesktopRoute.Player, remember = mutable.value.route != DesktopRoute.Player) { g -> finishViewing(); prepareViewing(g, a, playable, start) }
        }
    }
    suspend fun shutdown() { together?.close(); music.closeNow(); detachWindow(); inputDiagnostics.heartbeat(); closing = true; generation.incrementAndGet(); pageJob?.cancelAndJoin(); withContext(NonCancellable) { finishViewing() }; scope.cancel(); http.close() }
    override fun close() { runBlocking { shutdown() } }
}

/** mpv's bundled FFmpeg demuxes these originals; output adaptation remains with the qualified native renderer. */
internal val DESKTOP_EXTERNAL_TEXT_SUBTITLE_FORMATS = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")
internal val DESKTOP_BITMAP_SUBTITLE_FORMATS = mapOf(
    "pgssub" to "pgssub", "hdmv_pgs_subtitle" to "pgssub",
    "dvdsub" to "dvdsub", "dvd_subtitle" to "dvdsub",
    "dvbsub" to "dvbsub", "dvb_subtitle" to "dvbsub"
)
internal fun desktopBitmapSubtitleFormat(codec: String?): String? = codec?.lowercase()?.let(DESKTOP_BITMAP_SUBTITLE_FORMATS::get)

/** Keep profile formats disjoint: Jellyfin can convert text into an advertised bitmap Embed format. */
internal fun DeviceProfile.forDesktopSubtitle(codec: String?): DeviceProfile {
    val bitmapFormat = desktopBitmapSubtitleFormat(codec)
    val subtitleProfiles = if (bitmapFormat != null) {
        listOf(SubtitleProfile(bitmapFormat, SubtitleDeliveryMethod.Embed))
    } else {
        DESKTOP_EXTERNAL_TEXT_SUBTITLE_FORMATS.sorted().map { SubtitleProfile(it, SubtitleDeliveryMethod.External) }
    }
    return copy(subtitleProfiles = subtitleProfiles)
}

fun desktopMpvProfile(settings: DesktopSettings, availableCodecs: Set<String>? = null): DeviceProfile {
    val bitrate = settings.preferences().deviceSettings().maxStreamingBitrate
    // Decoder codec names are FFmpeg's names used by Jellyfin. Lists match the source's
    // actual codec; a decoder of another media type cannot invent a missing codec.
    // Keep every real decoder (including FFV1/ProRes), not an Android-shaped whitelist.
    val video = (availableCodecs ?: setOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg2video", "mpeg4", "vc1")).sorted().joinToString(",")
    val audio = (availableCodecs ?: setOf("aac", "ac3", "eac3", "truehd", "dts", "flac", "alac", "opus", "vorbis", "mp3", "mp2", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le")).sorted().joinToString(",")
    // mpv's decoder-list reports FFmpeg video/audio decoders, not the separate
    // libass/text-subtitle pipeline. Negotiate known text tracks as URL delivery
    // and bitmap tracks only when embedded in a format mpv can render.
    // Start with text-only profiles. Selected bitmap tracks get a targeted
    // profile after PlaybackInfo identifies their codec.
    val subtitleDeliveryProfiles = DESKTOP_EXTERNAL_TEXT_SUBTITLE_FORMATS.sorted().map { SubtitleProfile(it, SubtitleDeliveryMethod.External) }
    return DeviceProfile(
        name = "Pictureshow Desktop libmpv", maxStreamingBitrate = bitrate, maxStaticBitrate = bitrate,
        directPlayProfiles = listOf(DirectPlayProfile("mkv,matroska,mp4,m4v,mov,avi,ts,mpegts,m2ts,webm,mpg,mpeg,ogv,asf,wmv", audio, video, DlnaProfileType.Video)),
        transcodingProfiles = listOf(TranscodingProfile("ts", DlnaProfileType.Video, "h264", listOf("aac", "ac3", "mp3").filter { availableCodecs == null || it in availableCodecs }.joinToString(","), MediaStreamProtocol.Hls, maxAudioChannels = "8", minSegments = 1, segmentLength = 3)),
        codecProfiles = listOf(CodecProfile(CodecType.Video, conditions = listOf(ProfileCondition(ProfileConditionType.EqualsAny, ProfileConditionValue.VideoRangeType, "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR")))),
        subtitleProfiles = subtitleDeliveryProfiles
    )
}
