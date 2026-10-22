/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.HomeRow
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.SearchKind
import app.pictureshow.core.SearchService
import app.pictureshow.core.normalizeSearch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Order is independent of completion order; failed and empty types never occupy a row. */
fun assembleSearch(results: Map<SearchKind, ClientResult<ItemPage>>): List<HomeRow> = SearchKind.entries.mapNotNull { kind ->
    val items = (results[kind] as? ClientResult.Success)?.value?.items.orEmpty().take(24)
    items.takeIf { it.isNotEmpty() }?.let { HomeRow(kind.name, kind.title, it, wide = kind == SearchKind.EPISODES) }
}

data class SearchState(
    val query: String = "",
    val rows: List<HomeRow> = emptyList(),
    val loading: Boolean = false,
    val completed: Boolean = false,
    val allFailed: Boolean = false,
    val recent: List<String> = emptyList(),
    val storageError: String? = null
)

class SearchViewModel(private val account: Account, private val session: CatalogSession, private val accounts: AccountStore) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            accounts.snapshots.collect { snapshot ->
                mutableState.update { it.copy(recent = snapshot.recentSearches[account.key].orEmpty()) }
            }
        }
    }

    fun query(value: String, immediate: Boolean = false) {
        val bounded = value.take(app.pictureshow.core.SEARCH_QUERY_LIMIT)
        val term = normalizeSearch(bounded)
        if (bounded == state.value.query && !immediate) return
        val epoch = ++generation
        job?.cancel()
        mutableState.update {
            it.copy(query = bounded, rows = emptyList(), loading = term.length >= 2, completed = false, allFailed = false)
        }
        if (term.length < 2) return
        job = viewModelScope.launch {
            if (!immediate) delay(300)
            val results = coroutineScope {
                SearchKind.entries.map { kind ->
                    async {
                        kind to session.call(account) { (this as SearchService).search(account, term, kind) }
                    }
                }.awaitAll().toMap()
            }
            // Also guards a transport that completes non-cancellably after a newer query or a clear.
            if (epoch == generation) {
                mutableState.update {
                    it.copy(
                        rows = assembleSearch(results),
                        loading = false,
                        completed = true,
                        allFailed = results.values.all { result ->
                            result is ClientResult.Failure
                        }
                    )
                }
            }
        }
    }

    fun retry() = query(state.value.query, immediate = true)

    fun submit() = rememberQuery()

    fun opened() = rememberQuery()

    private fun rememberQuery() {
        val term = normalizeSearch(state.value.query)
        if (term.length < 2) return
        viewModelScope.launch {
            try {
                accounts.recordSearch(account.key, term)
                mutableState.update { it.copy(storageError = null) }
            } catch (_: Exception) {
                mutableState.update { it.copy(storageError = "Could not save recent searches.") }
            }
        }
    }

    fun clearRecent() {
        viewModelScope.launch {
            try {
                accounts.clearSearches(account.key)
                mutableState.update { it.copy(storageError = null) }
            } catch (_: Exception) {
                mutableState.update { it.copy(storageError = "Could not clear recent searches. Try again.") }
            }
        }
    }
}

data class PersonState(val grid: CatalogGridState, val person: MediaItem)

class PersonViewModel(private val account: Account, person: MediaItem, private val session: CatalogSession) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonState(CatalogGridState(account, person), person))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var offset = 0
    private var ended = false

    init {
        viewModelScope.launch {
            val result = session.call(account) { item(account, person.id) }
            if (result is ClientResult.Success) mutableState.update { it.copy(person = result.value) }
        }
        load()
    }

    fun nearEnd(index: Int) {
        if (index >= state.value.grid.items.size - 12 && state.value.grid.error == null) load()
    }

    fun retry() = load()

    private fun load() {
        if (job?.isActive == true || ended) return
        mutableState.update { it.copy(grid = it.grid.copy(loading = true, error = null)) }
        job = viewModelScope.launch {
            when (val result = session.call(account) { (this as SearchService).personItems(account, state.value.person.id, offset) }) {
                is ClientResult.Failure -> mutableState.update {
                    it.copy(grid = it.grid.copy(loading = false, error = result.error.message))
                }
                is ClientResult.Success -> {
                    offset += result.value.items.size
                    ended = result.value.items.isEmpty() || offset >= result.value.totalRecordCount
                    mutableState.update {
                        it.copy(
                            grid = it.grid.copy(
                                loading = false,
                                items = (it.grid.items + result.value.items).distinctBy(MediaItem::id),
                                total = result.value.totalRecordCount
                            )
                        )
                    }
                }
            }
        }
    }
}
