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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared across an account's back stack so detail changes immediately reach existing screens. */
class CatalogSession(val service: CatalogService, private val accounts: AccountStore, private val onRefresh: () -> Unit = {}) {
    data class Changes(val revision: Long = 0, val users: Map<String, ItemUserData> = emptyMap())
    private val mutableChanges = MutableStateFlow(Changes())
    val changes = mutableChanges.asStateFlow()

    fun refresh() {
        onRefresh()
        mutableChanges.update { it.copy(revision = it.revision + 1) }
    }

    fun changed(item: MediaItem) {
        onRefresh()
        mutableChanges.update { it.copy(revision = it.revision + 1, users = it.users + (item.id to item.userData)) }
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
            mutableState.update { it.copy(loading = true, error = null) }
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
    val descending: Boolean = false
)

class CatalogGridViewModel(val account: Account, parent: MediaItem, private val session: CatalogSession) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogGridState(account, parent))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var offset = 0
    private var ended = false
    private var generation = 0

    init {
        load()
        viewModelScope.launch {
            session.changes.collect { changes ->
                mutableState.update { state ->
                    state.copy(items = state.items.map { item -> changes.users[item.id]?.let { item.copy(userData = it) } ?: item })
                }
            }
        }
    }

    fun nearEnd(index: Int) {
        if (index >= state.value.items.size - 12 && state.value.error == null) load()
    }

    fun retry() = load()

    fun sort(sortBy: String, descending: Boolean) {
        if (sortBy == state.value.sortBy && descending == state.value.descending) return
        generation++
        job?.cancel()
        offset = 0
        ended = false
        mutableState.update {
            it.copy(items = emptyList(), total = 0, sortBy = sortBy, descending = descending, loading = false, error = null)
        }
        load()
    }

    private fun load() {
        if (job?.isActive == true || ended) return
        val before = state.value
        val epoch = generation
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
            when (val result = session.call(account) { items(account, query) }) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message) }
                is ClientResult.Success -> if (epoch == generation) {
                    val page = result.value
                    offset += page.items.size
                    ended = offset >= page.totalRecordCount || page.items.isEmpty()
                    mutableState.update { current ->
                        current.copy(
                            loading = false,
                            total = page.totalRecordCount,
                            items = (current.items + page.items).distinctBy { it.id }.map { item ->
                                session.changes.value.users[item.id]?.let { item.copy(userData = it) } ?: item
                            }
                        )
                    }
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
    val episodes: List<MediaItem> = emptyList()
)

class CatalogDetailViewModel(private val account: Account, private val original: MediaItem, private val session: CatalogSession) :
    ViewModel() {
    private val mutableState = MutableStateFlow(CatalogDetailState(account, original))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var action: Job? = null
    private var seasonJob: Job? = null
    private var seasonGeneration = 0

    init {
        reload()
        viewModelScope.launch {
            session.changes.collect { changes ->
                mutableState.update { state ->
                    state.copy(episodes = state.episodes.map { item -> changes.users[item.id]?.let { item.copy(userData = it) } ?: item })
                }
                if (changes.revision > 0 && original.type == "Series") reload()
            }
        }
    }

    fun reload() {
        job?.cancel()
        seasonJob?.cancel()
        seasonGeneration++
        job = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            when (val result = session.call(account) { item(account, original.id) }) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message) }
                is ClientResult.Success -> {
                    val item = result.value
                    mutableState.update { it.copy(item = item) }
                    if (item.type == "Series") {
                        val next = session.call(account) { nextUp(account, item.id) }
                        val seasons = session.call(account) { seasons(account, item.id) }
                        mutableState.update {
                            it.copy(
                                nextUp = (next as? ClientResult.Success)?.value?.items.orEmpty(),
                                seasons = (seasons as? ClientResult.Success)?.value?.items.orEmpty(),
                                error = listOf(next, seasons).filterIsInstance<ClientResult.Failure>().firstOrNull()?.error?.message
                            )
                        }
                        val selected = state.value.seasons.firstOrNull { it.id == state.value.selectedSeasonId }
                            ?: state.value.seasons.firstOrNull()
                        if (selected !=
                            null
                        ) {
                            loadSeason(selected, preserveError = true)
                        } else {
                            mutableState.update { it.copy(loading = false) }
                        }
                    } else if (item.type == "Season") {
                        season(item)
                    } else {
                        mutableState.update { it.copy(loading = false) }
                    }
                }
            }
        }
    }

    fun season(season: MediaItem) = loadSeason(season, preserveError = false)

    private fun loadSeason(season: MediaItem, preserveError: Boolean) {
        seasonJob?.cancel()
        val generation = ++seasonGeneration
        mutableState.update {
            it.copy(selectedSeasonId = season.id, episodes = emptyList(), loading = true, error = if (preserveError) it.error else null)
        }
        seasonJob = viewModelScope.launch {
            val seriesId = if (original.type == "Series") original.id else season.seriesId
            if (seriesId == null) {
                mutableState.update { it.copy(loading = false, error = "This season has no series information.") }
                return@launch
            }
            val result = session.call(account) { episodes(account, seriesId, season.id) }
            if (generation != seasonGeneration) return@launch
            when (result) {
                is ClientResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error.message) }
                is ClientResult.Success -> mutableState.update { it.copy(loading = false, episodes = result.value.items) }
            }
        }
    }

    fun played() = mutate(true)
    fun favorite() = mutate(false)

    private fun mutate(watched: Boolean) {
        if (action?.isActive == true || state.value.loading) return
        val item = state.value.item ?: return
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
