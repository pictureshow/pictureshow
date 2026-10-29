package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.HomeRow
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.assembleHome
import app.pictureshow.core.isEmptyContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared across an account's back stack so detail changes immediately reach existing screens. */
class CatalogSession(val service: CatalogService, private val accounts: AccountStore, private val onRefresh: () -> Unit = {}) {
    data class Changes(
        val revision: Long = 0,
        val users: Map<String, ItemUserData> = emptyMap(),
        val refreshRevision: Long = 0,
        val pendingPlayback: Set<String> = emptySet(),
        val userRevisions: Map<String, Long> = emptyMap()
    )
    private val mutableChanges = MutableStateFlow(Changes())
    val changes = mutableChanges.asStateFlow()
    private var completedUsers = emptyMap<String, ItemUserData>()
    private var unresolvedPlayback = emptySet<String>()

    private var pendingReports = emptyMap<Pair<String, Any>, Set<String>>()

    fun playbackPending(itemId: String, report: Any = itemId, relatedIds: Set<String> = emptySet()) {
        val affected = relatedIds + itemId
        pendingReports = pendingReports + ((itemId to report) to affected)
        unresolvedPlayback = unresolvedPlayback + affected
        mutableChanges.update { it.copy(pendingPlayback = pendingReports.values.flatten().toSet()) }
    }

    internal fun playbackUnresolved(itemId: String) = itemId in unresolvedPlayback

    internal fun playbackRead(itemId: String) {
        if (itemId !in changes.value.pendingPlayback) unresolvedPlayback = unresolvedPlayback - itemId
    }

    internal fun playbackCompleted(item: MediaItem) {
        completedUsers = completedUsers + (item.id to item.userData)
    }

    fun refresh(finishedPlaybackId: String? = null, report: Any? = finishedPlaybackId) {
        if (finishedPlaybackId != null && report != null) pendingReports = pendingReports - (finishedPlaybackId to report)
        val pending = pendingReports.values.flatten().toSet()
        onRefresh()
        // Publish the acknowledged completion with the refresh atomically, so retained or newly
        // opened details cannot show pre-completion state while their fresh server read is pending.
        val confirmed = completedUsers
        completedUsers = emptyMap()
        unresolvedPlayback = unresolvedPlayback - (confirmed.keys - pending)
        mutableChanges.update {
            it.copy(
                revision = it.revision + 1,
                refreshRevision = it.refreshRevision + 1,
                users = confirmed,
                userRevisions = confirmed.keys.associateWith { _ -> it.revision + 1 },
                pendingPlayback = pending
            )
        }
    }

    fun changed(item: MediaItem) {
        onRefresh()
        mutableChanges.update {
            it.copy(
                revision = it.revision + 1,
                users = it.users + (item.id to item.userData),
                userRevisions = it.userRevisions + (item.id to it.revision + 1)
            )
        }
    }

    suspend fun <T> call(account: Account, block: suspend CatalogService.() -> ClientResult<T>): ClientResult<T> {
        val result = service.block()
        if (result is ClientResult.Failure &&
            result.error.kind in listOf(ErrorKind.INVALID_CREDENTIALS, ErrorKind.EXPIRED)
        ) {
            accounts.expire(account.key, account.accessToken)
        }
        return result
    }
}

/** Refresh only retained cards, independently of paging. Never replace IDs, order or metadata.
 * A revision plus cancellation guard rejects transports that finish non-cancellably after a
 * newer change, sort or query. Explicit IDs use the same Items list projection as the grid.
 */
internal class CatalogCardRefresher(
    private val scope: CoroutineScope,
    private val account: Account,
    private val session: CatalogSession,
    private val items: () -> List<MediaItem>,
    private val apply: (Map<String, ItemUserData>) -> Unit,
    private val failed: (String?) -> Unit
) {
    private var job: Job? = null
    private var generation = 0
    var needsRetry = false
        private set

    fun cancel() {
        generation++
        job?.cancel()
        needsRetry = false
    }

    /** A second Retry while a snapshot is in flight must not fall through to page loading. */
    fun retry(): Boolean {
        if (job?.isActive == true) return true
        if (!needsRetry) return false
        refresh()
        return true
    }

    fun refresh() {
        cancel()
        val ids = items().map(MediaItem::id).distinct()
        if (ids.isEmpty()) return
        val epoch = generation
        val revision = session.changes.value.revision
        job = scope.launch {
            val users = mutableMapOf<String, ItemUserData>()
            for (batch in ids.chunked(100)) {
                val result = session.call(account) { items(account, BrowseQuery("", limit = batch.size, ids = batch)) }
                currentCoroutineContext().ensureActive()
                if (epoch != generation || revision != session.changes.value.revision) return@launch
                when (result) {
                    is ClientResult.Failure -> {
                        needsRetry = true
                        failed(result.error.message)
                        return@launch
                    }
                    is ClientResult.Success -> result.value.items.filter { it.id in batch }.forEach { users[it.id] = it.userData }
                }
            }
            // Keep the current direct leaf acknowledgement immediate. Older patches must not
            // mask a later list projection, and container counts always come from that list.
            val changes = session.changes.value
            val leaves = items().filter { !it.isFolder && it.type !in CONTAINER_TYPES }.map { it.id }.toSet()
            val direct = changes.users.filterKeys { it in leaves && changes.userRevisions[it] == revision }
            apply(users + direct)
            failed(null)
        }
    }
}

internal val CONTAINER_TYPES = setOf("Series", "Season", "BoxSet", "Playlist", "Folder", "CollectionFolder")

internal fun CatalogSession.Changes.usersAfter(revision: Long): Map<String, ItemUserData> =
    users.filterKeys { (userRevisions[it] ?: 0) > revision }

internal fun List<MediaItem>.withUserData(users: Map<String, ItemUserData>): List<MediaItem> = map { item ->
    users[item.id]?.let { item.copy(userData = it) } ?: item
}

data class CatalogHomeState(
    val rows: List<HomeRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false
)

class CatalogHomeViewModel(private val account: Account, private val session: CatalogSession) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogHomeState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { session.changes.collect { reload() } }
    }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            mutableState.update { it.copy(loading = it.rows.isEmpty(), error = null) }
            coroutineScope {
                val resume = async { session.call(account) { resume(account) } }
                val next = async { session.call(account) { nextUp(account) } }
                val views = session.call(account) { views(account) }
                val libraries = (views as? ClientResult.Success)?.value?.items.orEmpty()
                val latest = libraries.filter { it.collectionType in listOf("movies", "tvshows") }.associate { library ->
                    library.id to async { session.call(account) { latest(account, library.id) } }
                }.mapValues { it.value.await() }
                val resumed = resume.await()
                val upcoming = next.await()
                val failures = listOf(views, resumed, upcoming) + latest.values
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(
                        loading = false,
                        rows = assembleHome(
                            libraries,
                            (resumed as? ClientResult.Success)?.value?.items.orEmpty(),
                            (upcoming as? ClientResult.Success)?.value?.items.orEmpty(),
                            latest.mapValues { (_, result) -> (result as? ClientResult.Success)?.value.orEmpty() }
                        ),
                        error = failures.filterIsInstance<ClientResult.Failure>().firstOrNull()?.error?.message
                    )
                }
            }
        }
    }
}

data class CatalogGridState(
    val account: Account,
    val parent: MediaItem,
    val items: List<MediaItem> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val sortBy: String = "SortName",
    val descending: Boolean = false,
    val refreshError: String? = null
)

class CatalogGridViewModel(val account: Account, parent: MediaItem, private val session: CatalogSession) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogGridState(account, parent))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var offset = 0
    private var ended = false
    private var generation = 0
    private val refresher = CatalogCardRefresher(
        viewModelScope,
        account,
        session,
        { state.value.items },
        { users -> mutableState.update { it.copy(items = it.items.withUserData(users)) } },
        { error -> mutableState.update { it.copy(refreshError = error) } }
    )

    init {
        load()
        viewModelScope.launch {
            var revision = session.changes.value.revision
            session.changes.collect { changes ->
                mutableState.update { it.copy(items = it.items.withUserData(changes.usersAfter(revision))) }
                if (changes.revision != revision) {
                    revision = changes.revision
                    refresher.refresh()
                }
            }
        }
    }

    fun nearEnd(index: Int) {
        if (index >= state.value.items.size - 12 && state.value.error == null) load()
    }

    fun retry() {
        val retryPage = state.value.error != null
        val refreshing = refresher.retry()
        if (retryPage || !refreshing) load()
    }

    fun sort(sortBy: String, descending: Boolean) {
        if (sortBy == state.value.sortBy && descending == state.value.descending) return
        generation++
        job?.cancel()
        refresher.cancel()
        offset = 0
        ended = false
        mutableState.update {
            it.copy(
                items = emptyList(),
                total = 0,
                sortBy = sortBy,
                descending = descending,
                loading = false,
                error = null,
                refreshError = null
            )
        }
        load()
    }

    private fun load() {
        if (job?.isActive == true || ended) return
        val before = state.value
        val epoch = generation
        val revision = session.changes.value.revision
        val query = BrowseQuery(
            parentId = before.parent.id,
            includeItemTypes = when (before.parent.collectionType) {
                "movies" -> "Movie"
                "tvshows" -> "Series"
                "boxsets" -> "BoxSet"
                else -> null
            },
            recursive = before.parent.collectionType in listOf("movies", "tvshows", "boxsets"),
            sortBy = before.sortBy,
            descending = before.descending,
            startIndex = offset,
            limit = 60
        )
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val result = session.call(account) { items(account, query) }
            currentCoroutineContext().ensureActive()
            if (epoch != generation) return@launch
            when (result) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message) }
                is ClientResult.Success -> if (epoch == generation) {
                    val page = result.value
                    offset += page.items.size
                    ended = offset >= page.totalRecordCount || page.items.isEmpty()
                    mutableState.update { current ->
                        current.copy(
                            loading = false,
                            total = page.totalRecordCount,
                            items = (current.items + page.items.withUserData(session.changes.value.usersAfter(revision))).distinctBy {
                                it.id
                            }
                        )
                    }
                    if (revision != session.changes.value.revision) refresher.refresh()
                }
            }
        }
    }
}

data class CatalogDetailState(
    val account: Account,
    val item: MediaItem? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val nextUp: List<MediaItem> = emptyList(),
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val playbackPending: Boolean = false,
    // The stale-data guard outlives reporting (including exhausted failures) until a fresh read.
    val playbackReportFinished: Boolean = false,
    // Failure category and attempt identity survive StateFlow coalescing a fast loading frame.
    val readFailed: Boolean = false,
    val readAttempt: Long = 0
)

class CatalogDetailViewModel(private val account: Account, private val original: MediaItem, private val session: CatalogSession) :
    ViewModel() {
    private val initialChanges = session.changes.value
    private val mutableState = MutableStateFlow(
        CatalogDetailState(
            account,
            item = initialChanges.users[original.id]?.let { original.copy(userData = it) }
                ?: original.takeUnless { original.id in initialChanges.pendingPlayback || session.playbackUnresolved(original.id) },
            playbackPending = original.id in initialChanges.pendingPlayback || session.playbackUnresolved(original.id),
            playbackReportFinished = original.id !in initialChanges.pendingPlayback
        )
    )
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var action: Job? = null
    private var seasonJob: Job? = null
    private var seasonGeneration = 0

    init {
        reload()
        viewModelScope.launch {
            var refreshRevision = 0L
            session.changes.collect { changes ->
                val refreshed = changes.refreshRevision != refreshRevision
                refreshRevision = changes.refreshRevision
                // Refresh discards older local mutations and carries only a just-acknowledged
                // playback completion. Apply that state while loading; the fresh read then wins.
                mutableState.update { state ->
                    state.copy(
                        item = state.item?.let { item -> changes.users[item.id]?.let { item.copy(userData = it) } ?: item },
                        episodes = state.episodes.map { item -> changes.users[item.id]?.let { item.copy(userData = it) } ?: item },
                        playbackPending = original.id in changes.pendingPlayback ||
                            session.playbackUnresolved(original.id) ||
                            (state.playbackPending && !(refreshed && original.id in changes.users)),
                        playbackReportFinished = original.id !in changes.pendingPlayback
                    )
                }
                if (changes.revision > 0 && (refreshed || original.type in CONTAINER_TYPES)) {
                    reload(retainRows = !refreshed && state.value.item != null)
                }
            }
        }
    }

    fun reload() = reload(retainRows = false)

    private fun reload(retainRows: Boolean) {
        job?.cancel()
        seasonJob?.cancel()
        seasonGeneration++
        job = viewModelScope.launch {
            mutableState.update { it.copy(loading = !retainRows, error = null, readAttempt = it.readAttempt + 1) }
            val result = session.call(account) { item(account, original.id) }
            // A canceled RESUMED request must not overwrite the post-report refresh, even when
            // its transport finishes cancellation in a non-cancellable cleanup block.
            currentCoroutineContext().ensureActive()
            when (result) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message, readFailed = true) }
                is ClientResult.Success -> {
                    val item = result.value
                    mutableState.update { it.copy(item = item) }
                    // A parent header says nothing about its episode rows or Next up. Keep the
                    // same stale-data guard until all of that page's authoritative reads land.
                    if (item.type !in listOf("Series", "Season")) playbackRead()
                    if (item.type == "Series") {
                        val next = session.call(account) { nextUp(account, item.id) }
                        currentCoroutineContext().ensureActive()
                        val seasons = session.call(account) { seasons(account, item.id) }
                        currentCoroutineContext().ensureActive()
                        mutableState.update {
                            it.copy(
                                nextUp = (next as? ClientResult.Success)?.value?.items.orEmpty(),
                                seasons = (seasons as? ClientResult.Success)?.value?.items.orEmpty(),
                                error = listOf(next, seasons).filterIsInstance<ClientResult.Failure>().firstOrNull()?.error?.message,
                                readFailed = listOf(next, seasons).any { result -> result is ClientResult.Failure }
                            )
                        }
                        val selected = state.value.seasons.firstOrNull { it.id == state.value.selectedSeasonId }
                            ?: state.value.seasons.firstOrNull()
                        if (selected !=
                            null
                        ) {
                            loadSeason(selected, preserveError = true, retainRows = retainRows)
                        } else {
                            mutableState.update { it.copy(loading = false) }
                            if (state.value.error == null) playbackRead()
                        }
                    } else if (item.type == "Season") {
                        loadSeason(item, preserveError = false, retainRows = retainRows)
                    } else {
                        mutableState.update { it.copy(loading = false) }
                    }
                }
            }
        }
    }

    fun season(season: MediaItem) = loadSeason(season, preserveError = false)

    private fun loadSeason(season: MediaItem, preserveError: Boolean, retainRows: Boolean = false) {
        seasonJob?.cancel()
        val generation = ++seasonGeneration
        mutableState.update {
            it.copy(
                selectedSeasonId = season.id,
                episodes = if (retainRows && it.selectedSeasonId == season.id) it.episodes else emptyList(),
                loading = !retainRows,
                error = if (preserveError) it.error else null
            )
        }
        seasonJob = viewModelScope.launch {
            val seriesId = if (original.type == "Series") original.id else season.seriesId
            if (seriesId == null) {
                mutableState.update { it.copy(loading = false, error = "This season has no series information.", readFailed = true) }
                return@launch
            }
            val result = session.call(account) { episodes(account, seriesId, season.id) }
            if (generation != seasonGeneration) return@launch
            when (result) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message, readFailed = true) }
                is ClientResult.Success -> {
                    mutableState.update { it.copy(loading = false, episodes = result.value.items) }
                    if (state.value.error == null) playbackRead()
                }
            }
        }
    }

    private fun playbackRead() {
        session.playbackRead(original.id)
        mutableState.update { it.copy(playbackPending = original.id in session.changes.value.pendingPlayback, readFailed = false) }
    }

    fun played() = mutate(true)
    fun favorite() = mutate(false)

    private fun mutate(watched: Boolean) {
        if (action?.isActive == true || state.value.loading || state.value.playbackPending) return
        val item = state.value.item ?: return
        if (watched && item.isEmptyContainer) return
        val old = item.userData
        val optimistic = if (watched) old.copy(played = !old.played) else old.copy(isFavorite = !old.isFavorite)
        mutableState.update { it.copy(item = item.copy(userData = optimistic), busy = true, error = null) }
        action = viewModelScope.launch {
            val result = session.call(account) {
                if (watched) played(account, item.id, !old.played) else favorite(account, item.id, !old.isFavorite)
            }
            when (result) {
                is ClientResult.Failure -> mutableState.update { it.copy(item = item, busy = false, error = result.error.message) }
                is ClientResult.Success -> {
                    val updated = item.copy(userData = result.value)
                    mutableState.update { it.copy(item = updated, busy = false) }
                    session.changed(updated)
                }
            }
        }
    }
}
