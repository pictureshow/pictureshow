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
    private val store: AccountStore = AccountStore(DesktopTextPersistence(File(directory, "accounts.json"))),
    private val discovery: ServerDiscovery = JvmServerDiscovery(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playbackFactory: (File) -> DesktopPlaybackEngine = { NativeDesktopPlayback(it) },
    /** PLAY-17's countdown after a genuine end before the next episode opens. */
    private val endCountdownSeconds: Int = 10
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = mutable.asStateFlow()
    private val settingsStore = DesktopSettingsStore(File(directory, "settings.properties"))
    private val identity = ClientIdentity("Pictureshow Desktop", installationId())
    private val client = JellyfinClient(http, identity)
    private val playbackClient = PlaybackClient(http, identity)
    val liveTv = DesktopLiveTv(LiveTvClient(http, identity, DesktopLivePendingWrites(File(directory, "live-pending"))), scope, { mutable.value.account })
    private val extras = PlaybackExtrasClient(http, identity)
    private val generation = AtomicLong()
    private var pageJob: Job? = null
    private var discoveryJob: Job? = null
    private val history = mutableListOf<DesktopState>()
    private var pendingQuick: PendingQuickConnect? = null
    private var quickOwner: Server? = null
    @Volatile private var window: Window? = null
    @Volatile private var engine: DesktopPlaybackEngine? = null
    private var reportJob: Job? = null
    @Volatile private var viewing: Viewing? = null
    @Volatile private var closing = false
    @Volatile private var nextPageIndex = 0
    private val viewingMutex = kotlinx.coroutines.sync.Mutex()
    private val events = DesktopEventLog(File(directory, "app.log"))
    /** A report error from a viewing that has closed; shown by whatever opens next instead of being wiped (D-4). */
    @Volatile private var carriedReportError: String? = null
    private var endJob: Job? = null
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
        @Volatile var muted: Boolean = false
    )
    private fun takeCarriedReportError(): String? = carriedReportError.also { carriedReportError = null }

    private fun installationId(): String {
        val file = File(directory, "device-id")
        if (file.exists()) return file.readText().trim().also { require(it.isNotEmpty()) }
        return UUID.randomUUID().toString().also { atomicPrivateWrite(file, it.toByteArray()) }
    }
    private fun identityClient() = JellyfinClient(http, identity.copy(deviceId = UUID.randomUUID().toString()))
    private fun snapshotAccounts() { val s = store.snapshots.value; mutable.update { it.copy(accounts = s.accounts, rememberedServers = s.rememberedServers) } }
    @Synchronized private fun page(route: DesktopRoute, remember: Boolean = false, block: suspend (Long) -> Unit) {
        val before = mutable.value
        if (remember && before.route !in setOf(DesktopRoute.Loading, DesktopRoute.Player)) synchronized(history) { history.add(before) }
        val g = generation.incrementAndGet()
        pageJob?.cancel()
        if (route !in setOf(DesktopRoute.SignIn, DesktopRoute.QuickConnect)) { pendingQuick = null; quickOwner = null }
        mutable.update { it.copy(route = route, busy = true, error = null, errorKind = null, root = desktopRootFor(route, it.root)) }
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
    @Synchronized private fun publish(g: Long, change: (DesktopState) -> DesktopState) { if (generation.get() == g && !closing) mutable.update(change) }
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
                        mutable.update { it.copy(account = null, server = account.server, route = DesktopRoute.SignIn,
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
    private suspend fun <T> attempt(g: Long, account: Account? = null, call: suspend () -> ClientResult<T>): T? = try { result(account, call) }
    catch (e: DesktopFailure) { publish(g) { it.copy(error = e.message, errorKind = e.kind) }; null }

    fun clearError() { mutable.update { it.copy(error = null, errorKind = null) } }
    /** The page that honoured [DesktopState.returnFocus] clears it, so it applies once. */
    fun returnFocusDone() { mutable.update { it.copy(returnFocus = null) } }

    fun start() = page(DesktopRoute.Loading) { g ->
        val s = store.load()
        val settings = settingsStore.read()
        snapshotAccounts()
        publish(g) { it.copy(settings = settings, server = s.lastServer) }
        val account = s.currentAccount
        if (account != null && !account.expired && account.accessToken.isNotBlank()) {
            when (val checked = client.me(account)) {
                is ClientResult.Success -> { mutable.update { it.copy(account = account) }; loadHome(g) }
                is ClientResult.Failure -> if (checked.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                    store.expire(account.key, account.accessToken); snapshotAccounts()
                    publish(g) { it.copy(route = DesktopRoute.SignIn, server = account.server, error = "Your session ended. Sign in again.", errorKind = ErrorKind.EXPIRED) }
                } else publish(g) { it.copy(route = DesktopRoute.Home, account = account, error = checked.error.message, errorKind = checked.error.kind) }
            }
        } else publish(g) { it.copy(route = if (s.accounts.isNotEmpty()) DesktopRoute.Accounts else DesktopRoute.Servers) }
    }
    fun discover() {
        if (discoveryJob?.isActive == true) return
        mutable.update { it.copy(discovering = true, discovered = emptyList()) }
        discoveryJob = scope.launch {
            try { discovery.discover { found -> mutable.update { it.copy(discovered = (it.discovered + found).distinctBy(DiscoveredServer::id)) } } }
            catch (_: Exception) { mutable.update { it.copy(error = "Network discovery is unavailable. Enter your server address to connect.") } }
            finally { mutable.update { it.copy(discovering = false) } }
        }
    }
    fun connect(address: String) {
        mutable.update { it.copy(serverAddress = address) }
        page(DesktopRoute.Servers) { g ->
            val server = attempt(g) { client.probe(address) } ?: return@page
            publish(g) { it.copy(server = server, route = DesktopRoute.SignIn, publicUsers = emptyList(), quickCode = null) }
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
        store.remember(account)
        snapshotAccounts()
        synchronized(history) { history.clear() }
        pendingQuick = null; quickOwner = null
        publish(g) { it.copy(account = account, server = account.server, quickCode = null, detail = null, playback = null, homeRows = emptyList()) }
        loadHome(g)
    }
    fun quickConnect() {
        val server = mutable.value.server ?: return
        if (mutable.value.busy && mutable.value.route == DesktopRoute.QuickConnect) return
        if (quickOwner != server) { pendingQuick = null; quickOwner = server }
        page(DesktopRoute.QuickConnect) { g ->
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
    fun addServer() = page(DesktopRoute.Servers) { _ -> }
    /** AUTH-07: another viewer on a saved server goes straight to its sign-in; Back returns to the chooser. */
    fun addAccountOn(server: Server) {
        if (mutable.value.busy && mutable.value.route == DesktopRoute.SignIn) return
        page(DesktopRoute.SignIn, remember = true) { g ->
            publish(g) { it.copy(server = server, publicUsers = emptyList(), quickCode = null) }
            val users = attempt(g) { client.publicUsers(server) }.orEmpty()
            publish(g) { it.copy(publicUsers = users) }
        }
    }
    fun selectAccount(key: String) = page(DesktopRoute.Accounts) { g ->
        val account = store.snapshots.value.accounts.firstOrNull { it.key == key } ?: return@page
        if (account.expired || account.accessToken.isBlank()) { publish(g) { it.copy(server = account.server, route = DesktopRoute.SignIn) }; return@page }
        if (attempt(g, account) { client.me(account) } == null) {
            if (store.snapshots.value.accounts.firstOrNull { it.key == key }?.expired == true) {
                publish(g) { it.copy(server = account.server, route = DesktopRoute.SignIn,
                    error = "Your session ended. Sign in again.", errorKind = ErrorKind.EXPIRED) }
            }
            return@page
        }
        liveTv.clear()
        store.select(key); snapshotAccounts(); synchronized(history) { history.clear() }
        publish(g) { it.copy(account = account, server = account.server, detail = null, playback = null, homeRows = emptyList()) }; loadHome(g)
    }
    fun signOut() {
        val account = mutable.value.account ?: return
        page(DesktopRoute.Accounts) { g ->
            finishViewing()
            store.signOut(account.key); snapshotAccounts(); synchronized(history) { history.clear() }
            publish(g) { DesktopState(route = if (store.snapshots.value.accounts.isEmpty()) DesktopRoute.SignIn else DesktopRoute.Accounts,
                server = account.server, accounts = store.snapshots.value.accounts, rememberedServers = store.snapshots.value.rememberedServers, settings = it.settings) }
            liveTv.clear()
            runCatching { client.logout(account) }
        }
    }
    fun removeAccount(key: String) = page(DesktopRoute.Accounts) { g ->
        if (mutable.value.account?.key == key) finishViewing()
        store.removeAccount(key); snapshotAccounts()
        publish(g) { if (it.account?.key == key) it.copy(account = null, homeRows = emptyList(), items = emptyList(), detail = null) else it }
    }
    fun home() = page(DesktopRoute.Home) { g -> synchronized(history) { history.clear() }; loadHome(g) }
    private suspend fun loadHome(g: Long) = coroutineScope {
        val a = mutable.value.account ?: return@coroutineScope
        publish(g) { it.copy(route = DesktopRoute.Home, root = DESKTOP_ROOT_HOME) }
        val views = async { attempt(g, a) { client.views(a) }?.items.orEmpty() }
        val resume = async { attempt(g, a) { client.resume(a) }?.items.orEmpty() }
        val next = async { attempt(g, a) { client.nextUp(a) }?.items.orEmpty() }
        val libraries = views.await()
        val latest = libraries.filter { it.collectionType in listOf("movies", "tvshows") }.map { library ->
            async { library.id to attempt(g, a) { client.latest(a, library.id) }.orEmpty() }
        }.awaitAll().toMap()
        val rows = assembleHome(libraries, resume.await(), next.await(), latest)
        publish(g) { it.copy(homeRows = rows) }
    }
    /** A sidebar library: its top page, lit as its own root (TOUCH-02). Folders opened inside it keep this root. */
    fun browseRoot(library: MediaItem) { browse(library); mutable.update { it.copy(root = desktopLibraryRoot(library.id)) } }
    /** Live TV (gap C34) has its own leg; until then the view opens an honest interim page, never "empty". */
    internal fun isLiveTvView(item: MediaItem) = item.collectionType.equals("livetv", ignoreCase = true)
    fun openLiveTv(item: MediaItem? = null) = page(DesktopRoute.LiveTv, remember = mutable.value.route != DesktopRoute.LiveTv) { g ->
        val a = mutable.value.account ?: return@page
        publish(g) { it.copy(library = item, root = item?.let { view -> desktopLibraryRoot(view.id) } ?: "live-tv") }
        liveTv.open(a)
    }
    fun open(item: MediaItem) {
        if (isLiveTvView(item)) { openLiveTv(item); return }
        if (item.type in setOf("CollectionFolder", "Folder", "BoxSet", "Playlist") || item.isFolder && item.type !in setOf("Series", "Season")) browse(item) else detail(item)
    }
    fun browse(item: MediaItem, sortBy: String = "SortName", descending: Boolean = false) {
        if (isLiveTvView(item)) { openLiveTv(item); return }
        require(sortBy in listOf("SortName", "DateCreated", "PremiereDate"))
        page(DesktopRoute.Library, remember = mutable.value.library?.id != item.id) { g ->
            val a = mutable.value.account ?: return@page
            nextPageIndex = 0
            publish(g) { it.copy(library = item, sortBy = sortBy, descending = descending, items = emptyList(), total = 0) }
            // D-5 interim: the Live TV view has no browsable children here; LibraryPage explains instead of "empty".
            if (isLiveTvView(item)) return@page
            val p = attempt(g, a) { client.items(a, BrowseQuery(item.id, sortBy = sortBy, descending = descending)) } ?: return@page
            if (generation.get() == g) nextPageIndex = p.startIndex + p.items.size
            publish(g) { it.copy(items = p.items.distinctBy(MediaItem::id), total = p.totalRecordCount) }
        }
    }
    fun loadMore() {
        val before = mutable.value
        if (before.route != DesktopRoute.Library || before.busy || nextPageIndex >= before.total || before.library?.let(::isLiveTvView) == true) return
        val a = before.account ?: return; val library = before.library ?: return
        val g = generation.get(); val startIndex = nextPageIndex; mutable.update { it.copy(busy = true) }
        pageJob = scope.launch {
            try {
                val p = attempt(g, a) { client.items(a, BrowseQuery(library.id, sortBy = before.sortBy, descending = before.descending, startIndex = startIndex)) }
                if (p != null && generation.get() == g) {
                    nextPageIndex = if (p.items.isEmpty()) p.totalRecordCount else startIndex + p.items.size
                    publish(g) { it.copy(items = (it.items + p.items).distinctBy(MediaItem::id), total = p.totalRecordCount) }
                }
            } finally { publish(g) { it.copy(busy = false) } }
        }
    }
    fun detail(item: MediaItem) = page(DesktopRoute.Detail, remember = true) { g -> loadDetail(g, item) }
    private suspend fun loadDetail(g: Long, item: MediaItem) {
        val a = mutable.value.account ?: return
        publish(g) { it.copy(detail = item, seasons = emptyList(), episodes = emptyList(), selectedSeason = null) }
        val fresh = attempt(g, a) { client.item(a, item.id) } ?: return
        publish(g) { it.copy(detail = fresh) }
        if (fresh.type == "Series") {
            val seasons = attempt(g, a) { client.seasons(a, fresh.id) }?.items.orEmpty()
            publish(g) { it.copy(seasons = seasons) }
            seasons.firstOrNull()?.let { loadSeason(g, a, fresh.id, it) }
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
            publish(g) { it.copy(detail = it.detail?.copy(userData = ud), homeRows = it.homeRows.map { row -> row.copy(items = row.items.map { card -> if (card.id == d.id) card.copy(userData = ud) else card }) }, items = it.items.map { card -> if (card.id == d.id) card.copy(userData = ud) else card }) }
        }
    }
    fun settings() = page(DesktopRoute.Settings, remember = mutable.value.route != DesktopRoute.Settings) { _ -> }
    fun saveSettings(settings: DesktopSettings) = page(DesktopRoute.Settings) { g -> settingsStore.write(settings); publish(g) { it.copy(settings = settings) }; engine?.volume(settings.volume) }
    fun back() {
        val s = mutable.value
        if (s.route == DesktopRoute.Player) { stopPlayback(); return }
        if (s.route == DesktopRoute.QuickConnect) { page(DesktopRoute.SignIn) { _ -> }; return }
        val previous = synchronized(history) { if (history.isEmpty()) null else history.removeAt(history.lastIndex) }
        generation.incrementAndGet(); pageJob?.cancel()
        if (previous != null) mutable.value = previous.copy(busy = false, error = null, errorKind = null, settings = s.settings)
        else when (s.route) {
            DesktopRoute.SignIn -> addServer()
            DesktopRoute.Accounts, DesktopRoute.Servers -> if (s.account != null) home() else addServer()
            else -> if (s.account != null) home() else addServer()
        }
    }
    fun retry() {
        val s = mutable.value
        when (s.route) {
            DesktopRoute.Loading -> start()
            DesktopRoute.Home -> home()
            DesktopRoute.LiveTv -> liveTv.refresh()
            DesktopRoute.Library -> s.library?.let { browse(it, s.sortBy, s.descending) }
            DesktopRoute.Detail -> s.detail?.let { page(DesktopRoute.Detail) { g -> loadDetail(g, it) } }
            DesktopRoute.QuickConnect -> quickConnect()
            DesktopRoute.Servers -> s.serverAddress?.let(::connect)
            else -> mutable.update { it.copy(error = null, errorKind = null) }
        }
    }
    fun attachWindow(window: Window) { this.window = window; engine?.attach(window) }
    fun detachWindow() { window = null }

    fun play(item: MediaItem, fromStart: Boolean = false) {
        if (mutable.value.busy) return
        page(DesktopRoute.Player, remember = true) { g ->
            finishViewing()
            val a = mutable.value.account ?: return@page
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
            } else detailPlayItem(fresh, emptyList(), emptyList())
            if (playable == null || playable.isFolder || playable.type !in setOf("Movie", "Episode", "Video", "MusicVideo", "Recording")) {
                publish(g) { it.copy(error = "There is no playable video in this item.") }; return@page
            }
            prepareViewing(g, a, playable, if (fromStart) 0.0 else playable.userData.playbackPositionTicks.coerceAtLeast(0) / 10_000_000.0)
        }
    }
    fun tuneChannel(channel: LiveChannel) {
        val a = mutable.value.account ?: return
        if (!liveTv.state.value.actionsAvailable(System.currentTimeMillis()) || channel.id !in liveTv.state.value.channels.map { it.id }) return
        page(DesktopRoute.Player, remember = true) { g ->
            finishViewing()
            val policy = attempt(g, a) { LiveTvClient(http, identity).policy(a) } ?: return@page
            if (!policy.access) { publish(g) { it.copy(error = "Live TV access is disabled for this viewer.") }; return@page }
            val fresh = attempt(g, a) { LiveTvClient(http, identity).channel(a, channel.id) } ?: return@page
            prepareViewing(g, a, MediaItem(fresh.id, fresh.name, "TvChannel"), 0.0, liveChannel = fresh)
        }
    }
    private suspend fun prepareViewing(g: Long, a: Account, item: MediaItem, start: Double, audio: Int? = null, subtitle: Int? = null, sourceId: String? = null, audioChosen: Boolean = audio != null, subtitleChosen: Boolean = subtitle != null, subtitleCodec: String? = null, liveChannel: LiveChannel? = null) {
        publish(g) { it.copy(detail = item, playback = null) }
        val player = try { playbackFactory(File(directory, "playback")) }
        catch (e: LinkageError) { recordDesktopPlayerFailure("factory", e); publish(g) { it.copy(error = "The desktop player is not installed correctly. Check libmpv and its native adapter.") }; return }
        val profile = try { player.profile(mutable.value.settings).forDesktopSubtitle(subtitleCodec) }
        catch (e: Throwable) {
            recordDesktopPlayerFailure("profile", e)
            runCatching { player.close() }
            if (e is CancellationException || e is VirtualMachineError) throw e
            publish(g) { it.copy(error = "The desktop player could not load its decoder capabilities. Check the installed libmpv build.") }; return
        }
        val maxBitrate = mutable.value.settings.preferences().deviceSettings().maxStreamingBitrate
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
        var outcome = negotiate { playbackClient.prepareFor(a, item.id, profile, maxBitrate,
            PlaybackRequest(startPosition = start.seconds, audioStreamIndex = audio, subtitleStreamIndex = subtitle, mediaSourceId = sourceId)) }
        var negotiatedAudio = audio
        var negotiatedSubtitle = subtitle
        if (outcome is PlayOutcome.Play) {
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
        val decision = outcome.decision
        // A subtitle retry may allocate a different owned live source. Release superseded
        // ownership before adoption, while preserving identities shared with the final decision.
        try {
            withContext(NonCancellable) {
                rejectedDecisions.mapNotNull { it.ownedLiveStreamId }.distinct()
                    .filter { it != decision.ownedLiveStreamId }
                    .forEach { id -> runCatching { playbackClient.closeLiveStream(a, id) } }
                rejectedDecisions.distinctBy { it.playSessionId }
                    .filter { it.method == PlayMethod.Transcode && it.playSessionId != null && it.playSessionId != decision.playSessionId }
                    .forEach { old -> runCatching { playbackClient.stopEncoding(a, requireNotNull(old.playSessionId)) } }
                rejectedDecisions.clear()
                rejectedDecisions += decision
            }
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) { discardPreparedPlayer(); throw e }
        val streams = decision.mediaSource.mediaStreams
        val ai = negotiatedAudio ?: StreamDefaults.choose(streams, MediaStreamType.Audio, decision.mediaSource.defaultAudioStreamIndex, java.util.Locale.getDefault().language)
        val si = negotiatedSubtitle ?: StreamDefaults.choose(streams, MediaStreamType.Subtitle, decision.mediaSource.defaultSubtitleStreamIndex, java.util.Locale.getDefault().language)
        window?.let(player::attach)
        engine = player
        val v = Viewing(a, item, decision, player, audioChosen = audioChosen, subtitleChosen = subtitleChosen)
        viewing = v
        val notice = if (mutable.value.settings.conversionNotice) ConversionNotice.of(decision, si, "this computer") else null
        publish(g) { it.copy(playback = DesktopPlaybackState(item, decision, notice, streams.filter { t -> t.type == MediaStreamType.Audio }, streams.filter { t -> t.type == MediaStreamType.Subtitle }, ai, si, positionSeconds = start, durationSeconds = (item.runTimeTicks ?: 0).toDouble() / 10_000_000, liveChannel = liveChannel), error = takeCarriedReportError()) }
        seekTarget = null; pausedLivePosition = null
        try {
            viewingMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (viewing !== v) return
                player.open(a, decision, start, ai, si, mutable.value.settings.volume)
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
                    mutable.update { s -> if (viewing === v) s.copy(playback = s.playback?.copy(positionSeconds = snap.positionSeconds, durationSeconds = snap.durationSeconds.takeIf { it > 0 } ?: s.playback.durationSeconds,
                        paused = snap.paused, ready = snap.ready, liveWindow = snap.liveWindow, bufferExpired = snap.liveWindow?.pausedPositionExpired(pausedLivePosition) == true)) else s }
                    if (snap.error != null && !snap.ended) { v.playbackFailed = true; events.log("playback error item=${v.item.id}"); mutable.update { it.copy(error = snap.error) }; break }
                    if (!snap.ready && elapsed >= 60) { mutable.update { it.copy(error = "The video did not start. Return to the title and try again.") }; break }
                    if (snap.ready && !v.startAttempted) v.reports.withLock {
                        if (!v.closing && !v.startAttempted) {
                            val p = mutable.value.playback
                            if (p != null && viewing === v) {
                                val observed = v.engine.snapshot()
                                v.startAttempted = true
                                val report = PlaybackReports(decision).progress(observed.positionSeconds.seconds, observed.paused,
                                    p.audioIndex.takeIf { v.audioChosen }, p.subtitleIndex.takeIf { v.subtitleChosen })
                                when (val sent = playbackClient.reportStart(a, report)) {
                                    is ClientResult.Failure -> { events.log("playback start item=${v.item.id} report=failed:${sent.error.kind}"); if (viewing === v) mutable.update { it.copy(error = sent.error.message) } }
                                    is ClientResult.Success -> { v.startReported = true; events.log("playback start item=${v.item.id} method=${decision.method} report=ok") }
                                }
                            }
                        }
                    }
                    elapsed++
                    if (v.startAttempted && elapsed % 10 == 0) sendProgress(v)
                    if (snap.exitRequested) { stopPlayback(); break }
                    if (snap.ended) {
                        if (liveChannel != null || v.decision.isLive) {
                            mutable.update { it.copy(error = "The live stream ended. Return to Live TV and tune again.") }
                            scope.launch { finishViewing() }
                        } else onPhysicalEnd(v, snap)
                        break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (viewing === v && !v.ended) {
                    events.log("playback loop-failed item=${v.item.id} type=${e.javaClass.simpleName}")
                    mutable.update { it.copy(error = "Playback stopped unexpectedly. Return to the title and try again.") }
                }
            }
        }
        if (item.type == "Episode" && item.seriesId != null) {
            val next = attempt(g, a) { extras.nextEpisode(a, item) }
            publish(g) { it.copy(playback = it.playback?.copy(nextEpisode = next)) }
        }
    }
    private suspend fun sendProgress(v: Viewing) = v.reports.withLock {
        if (!v.startAttempted || v.stopped || v.closing || viewing !== v) return@withLock
        val p = mutable.value.playback ?: return@withLock
        val observed = runCatching { v.engine.snapshot() }.getOrElse {
            mutable.update { state -> state.copy(error = "Progress could not be read from the player. Return to the title and try again.") }
            return@withLock
        }
        val r = playbackClient.reportProgress(v.account, PlaybackReports(v.decision).progress(observed.positionSeconds.seconds, observed.paused,
            p.audioIndex.takeIf { v.audioChosen }, p.subtitleIndex.takeIf { v.subtitleChosen }).copy(isMuted = v.muted))
        if (r is ClientResult.Failure && viewing === v) mutable.update { it.copy(error = "Progress could not be saved. ${r.error.message}") }
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
        if (viewing?.closing == true) return; engine?.togglePause(); viewing?.let { v -> scope.launch { delay(100); if (viewing === v) sendProgress(v) } } }
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
        engine?.seek(target)
        seekTarget = target; seekTargetAt = System.nanoTime()
        mutable.update { s -> s.copy(playback = s.playback?.copy(positionSeconds = target)) }
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
        mutable.update { s -> s.copy(playback = s.playback?.copy(muted = muted)) }
        scope.launch { if (viewing === v) sendProgress(v) }
    }
    /** Arrow-key volume: saved like the Settings volume, without leaving the player. Raising it unmutes. */
    fun changeVolume(delta: Int) {
        val settings = mutable.value.settings
        val volume = (settings.volume + delta).coerceIn(0, 100)
        val updated = settings.copy(volume = volume)
        mutable.update { it.copy(settings = updated) }
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
            mutable.update { it.copy(playback = it.playback?.copy(audioIndex = audio ?: p.audioIndex, subtitleIndex = subtitle ?: p.subtitleIndex)) }
            scope.launch { if (viewing === current) sendProgress(current) }
            return
        }
        val requestedSubtitleCodec = subtitle?.takeIf { it >= 0 }?.let { index -> p.subtitleTracks.firstOrNull { it.index == index }?.codec }
        page(DesktopRoute.Player) { g ->
            finishViewing()
            prepareViewing(g, a, p.item, if (p.isLiveChannel) 0.0 else p.positionSeconds, audio ?: p.audioIndex, subtitle ?: p.subtitleIndex, p.decision.mediaSourceId,
                current.audioChosen || audio != null, current.subtitleChosen || subtitle != null, requestedSubtitleCodec, p.liveChannel)
        }
    }
    /** Manual "Next episode" in the control bar, or Play now on the end card. */
    fun nextEpisode() {
        val p = mutable.value.playback ?: return
        val next = p.end?.next ?: p.nextEpisode
        if (next == null) { stopPlayback(); return }
        val a = mutable.value.account ?: return
        page(DesktopRoute.Player) { g -> finishViewing(); prepareViewing(g, a, next, 0.0) }
    }
    /** End card Cancel: the countdown stops and the card stays, offering Play now and Back to title. */
    fun cancelEndCountdown() {
        countdownCancelled = true
        mutable.update { s -> s.copy(playback = s.playback?.let { p -> p.end?.let { p.copy(end = it.copy(secondsLeft = null)) } ?: p }) }
    }
    @Volatile private var countdownCancelled = false
    /** End card Replay: a new viewing of the same item from the start. */
    fun replay() {
        val item = mutable.value.playback?.item ?: return
        val a = mutable.value.account ?: return
        page(DesktopRoute.Player) { g -> finishViewing(); prepareViewing(g, a, item, 0.0) }
    }
    fun stopPlayback() {
        if (mutable.value.playback?.let { it.liveChannel != null || it.decision.isLive } == true) {
            synchronized(history) { if (history.lastOrNull()?.route == DesktopRoute.LiveTv) history.removeAt(history.lastIndex) }
            page(DesktopRoute.LiveTv) { g ->
                finishViewing()
                publish(g) { it.copy(playback = null, detail = null) }
            }
            return
        }
        // page() clears the playback before this block runs, so read the end state first.
        val finished = mutable.value.playback?.end != null
        page(DesktopRoute.Detail) { g -> stopPlaybackPage(g, finished) }
    }
    private suspend fun stopPlaybackPage(g: Long, finished: Boolean) {
        val old = mutable.value.playback?.item ?: mutable.value.detail
        finishViewing()
        publish(g) { it.copy(playback = null, returnFocus = if (finished) "play" else null) }
        takeCarriedReportError()?.let { e -> publish(g) { it.copy(error = e) } }
        if (old != null) loadDetail(g, old)
    }
    /**
     * PLAY-17/18 at the physical end: show the end card at once, close the viewing (one Stop, then one watched
     * mark) without waiting for the viewer, then count down to the next episode when autoplay is on.
     */
    private fun onPhysicalEnd(v: Viewing, snap: EngineSnapshot) {
        if (v.ended || viewing !== v) return
        v.endPosition = maxOf(snap.positionSeconds, snap.durationSeconds)
        v.ended = true
        val next = mutable.value.playback?.nextEpisode
        val countdown = if (next != null && mutable.value.settings.autoplayNext) endCountdownSeconds.coerceAtLeast(0) else null
        countdownCancelled = false
        mutable.update { s -> if (viewing === v) s.copy(playback = s.playback?.copy(end = DesktopEndCard(next, countdown),
            positionSeconds = v.endPosition, paused = true)) else s }
        events.log("playback end item=${v.item.id} next=${next?.id ?: "none"} countdown=${countdown ?: "off"}")
        val g = generation.get()
        endJob = scope.launch {
            finishViewing()
            if (countdown == null || next == null) return@launch
            var left = countdown
            while (left > 0) {
                delay(1000)
                if (generation.get() != g || countdownCancelled || mutable.value.playback?.end?.next?.id != next.id) return@launch
                left--
                mutable.update { s -> s.copy(playback = s.playback?.let { p -> p.end?.takeIf { it.secondsLeft != null }?.let { p.copy(end = it.copy(secondsLeft = left)) } ?: p }) }
            }
            if (generation.get() == g && !countdownCancelled && mutable.value.route == DesktopRoute.Player &&
                mutable.value.playback?.end?.let { it.next?.id == next.id && it.secondsLeft != null } == true) nextEpisode()
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
        carriedReportError?.let { e -> mutable.update { it.copy(error = e) } }
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
                        val failed = v.playbackFailed && !v.ended
                        runCatching {
                            playbackClient.reportStopped(v.account, PlaybackReports(v.decision).stopped(position.seconds, failed = failed))
                        }.onSuccess { stopped ->
                            if (stopped is ClientResult.Failure) {
                                events.log("playback stop item=${v.item.id} position=${position.toLong()} failed=$failed ended=${v.ended} report=failed:${stopped.error.kind}")
                                carriedReportError = "Playback ended, but progress could not be saved. ${stopped.error.message}"
                                mutable.update { it.copy(error = carriedReportError) }
                            } else events.log("playback stop item=${v.item.id} position=${position.toLong()} failed=$failed ended=${v.ended} report=ok")
                        }.onFailure { e ->
                            events.log("playback stop item=${v.item.id} report=failed:${e.javaClass.simpleName}")
                            carriedReportError = "Playback ended, but progress could not be saved. Check the connection."
                            mutable.update { it.copy(error = carriedReportError) }
                        }
                    }
                    if (v.ended) completeWatched(v)
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
                    if (!v.ended) mutable.update { it.copy(error = "Playback ended, but the player could not finish every cleanup step. You can return to the title and try again.") }
                }
            }
        }
    }
    suspend fun shutdown() { closing = true; generation.incrementAndGet(); pageJob?.cancelAndJoin(); withContext(NonCancellable) { finishViewing() }; scope.cancel(); http.close() }
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
