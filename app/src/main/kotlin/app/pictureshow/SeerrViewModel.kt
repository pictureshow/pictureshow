/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.normalizeSearch
import app.pictureshow.core.seerr.SeerrClient
import app.pictureshow.core.seerr.SeerrConfig
import app.pictureshow.core.seerr.SeerrError
import app.pictureshow.core.seerr.SeerrFailure
import app.pictureshow.core.seerr.SeerrRequest
import app.pictureshow.core.seerr.SeerrStatus
import app.pictureshow.core.seerr.SeerrTitle
import app.pictureshow.core.seerr.seerrMovieRequestable
import app.pictureshow.core.seerr.seerrSelectableSeasons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SeerrRequestRow(val request: SeerrRequest, val title: SeerrTitle?)

internal data class SeerrState(
    /** Null until read; then [connected] says whether this account has Seerr at all. */
    val config: SeerrConfig? = null,
    val ready: Boolean = false,
    val trending: List<SeerrTitle> = emptyList(),
    val trendingLoading: Boolean = false,
    val trendingError: SeerrError? = null,
    val query: String = "",
    val results: List<SeerrTitle> = emptyList(),
    val searching: Boolean = false,
    val searched: String? = null,
    val searchError: SeerrError? = null,
    val requests: List<SeerrRequestRow> = emptyList(),
    val requestsLoading: Boolean = false,
    val requestsError: SeerrError? = null,
    val title: SeerrTitle? = null,
    val titleLoading: Boolean = false,
    val titleError: SeerrError? = null,
    val chosen: Set<Int> = emptySet(),
    val sending: Boolean = false,
    val sendError: SeerrError? = null,
    val sent: Boolean = false,
    val connecting: Boolean = false,
    val connectError: SeerrError? = null
) {
    val connected: Boolean get() = config != null
}

/** One screen's view of the account's Seerr connection: hub, title, connect, or search's "Not in your library". */
internal class SeerrViewModel(private val account: Account, private val store: SeerrStore) : ViewModel() {
    private val mutable = MutableStateFlow(SeerrState())
    val state: StateFlow<SeerrState> = mutable.asStateFlow()
    private var client: SeerrClient? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            store.connection(account.key).collect { config ->
                if (config != mutable.value.config) client = null
                mutable.update { it.copy(config = config, ready = true) }
            }
        }
    }

    private suspend fun client(): SeerrClient =
        client ?: (store.client(account.key) ?: throw SeerrFailure(SeerrError.EXPIRED)).also { client = it }

    private suspend fun <T> attempt(block: suspend (SeerrClient) -> T): Result<T> = try {
        Result.success(block(client()))
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: SeerrFailure) {
        Result.failure(failure)
    } catch (_: IllegalArgumentException) {
        Result.failure(SeerrFailure(SeerrError.SERVER))
    }

    private fun Throwable.reason(): SeerrError = (this as? SeerrFailure)?.reason ?: SeerrError.SERVER

    fun loadTrending(force: Boolean = false) {
        val now = mutable.value
        if (now.trendingLoading || (!force && now.trending.isNotEmpty())) return
        mutable.update { it.copy(trendingLoading = true, trendingError = null) }
        viewModelScope.launch {
            // Two pages: about 40 titles, enough for a movies row and a series row.
            val result = attempt { seerr -> (1..2).flatMap { seerr.trending(it).results } }
            mutable.update {
                it.copy(
                    trendingLoading = false,
                    trending =
                    result.getOrNull()?.filter { title -> title.mediaType in setOf("movie", "tv") }?.distinctBy { t ->
                        t.mediaType to
                            t.id
                    }
                        ?: it.trending,
                    trendingError = result.exceptionOrNull()?.reason()
                )
            }
        }
    }

    /** Debounced like the library search; under 2 characters clears the results. */
    fun search(query: String, immediate: Boolean = false) {
        mutable.update { it.copy(query = query) }
        searchJob?.cancel()
        val term = normalizeSearch(query)
        if (term.length < 2 || mutable.value.config == null) {
            mutable.update { it.copy(results = emptyList(), searching = false, searched = null, searchError = null) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(350)
            mutable.update { it.copy(searching = true, searchError = null) }
            val result = attempt { it.search(term).results.filter { title -> title.mediaType in setOf("movie", "tv") } }
            mutable.update {
                it.copy(
                    searching = false,
                    searched = term,
                    results = result.getOrNull() ?: emptyList(),
                    searchError = result.exceptionOrNull()?.reason()
                )
            }
        }
    }

    fun loadRequests() {
        if (mutable.value.requestsLoading) return
        mutable.update { it.copy(requestsLoading = true, requestsError = null) }
        viewModelScope.launch {
            val result = attempt { seerr ->
                val requests = seerr.requests(take = 30).results
                // A request carries only TMDB ids; each row's title and poster come from the title's own page.
                val titles = requests.map { request ->
                    async {
                        try {
                            seerr.detail(if (request.type == "tv") "tv" else "movie", request.media.tmdbId)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
                requests.zip(titles) { request, title -> SeerrRequestRow(request, title) }
            }
            mutable.update {
                it.copy(
                    requestsLoading = false,
                    requests = result.getOrNull() ?: it.requests,
                    requestsError = result.exceptionOrNull()?.reason()
                )
            }
        }
    }

    fun openTitle(type: String, id: Int) {
        if (type !in setOf("movie", "tv") || id <= 0) {
            mutable.update { it.copy(titleError = SeerrError.SERVER) }
            return
        }
        mutable.update {
            // "Requested" belongs to the title it was sent for; reopening that title after the request keeps it.
            val same = it.title?.let { now -> now.mediaType == type && now.id == id } == true
            it.copy(titleLoading = true, titleError = null, sendError = null, sent = it.sent && same)
        }
        viewModelScope.launch {
            val result = attempt { it.detail(type, id) }
            mutable.update { now ->
                val title = result.getOrNull()
                now.copy(
                    titleLoading = false,
                    title = title ?: now.title,
                    titleError = result.exceptionOrNull()?.reason(),
                    // A series starts with every season that can still be requested chosen, so one press asks for all.
                    chosen = if (title != null && !now.sent) seerrSelectableSeasons(title).toSet() else now.chosen
                )
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

    fun request() {
        val now = mutable.value
        val title = now.title ?: return
        if (now.sending) return
        val seasons = if (title.mediaType == "tv") seerrSelectableSeasons(title).filter { it in now.chosen } else emptyList()
        if (title.mediaType == "tv" && seasons.isEmpty()) return
        if (title.mediaType == "movie" && !seerrMovieRequestable(title)) return
        mutable.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            val result = attempt { it.request(title, seasons) }
            mutable.update { it.copy(sending = false, sent = result.isSuccess, sendError = result.exceptionOrNull()?.reason()) }
            // The page then shows the new state: requested seasons stop being selectable.
            if (result.isSuccess || result.exceptionOrNull()?.reason() == SeerrError.ALREADY_REQUESTED) {
                openTitle(title.mediaType, title.id)
            }
        }
    }

    fun connect(url: String, apiKey: String?, username: String, password: String) {
        if (mutable.value.connecting) return
        mutable.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val error = try {
                store.connect(account, url, apiKey, username, password)
                null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: SeerrFailure) {
                failure.reason
            }
            client = null
            mutable.update { it.copy(connecting = false, connectError = error) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            store.disconnect(account.key)
            client = null
            mutable.update { SeerrState(ready = true) }
        }
    }
}

@StringRes
internal fun seerrErrorText(error: SeerrError): Int = when (error) {
    SeerrError.UNREACHABLE -> R.string.seerr_error_unreachable
    SeerrError.PASSWORD -> R.string.seerr_error_password
    SeerrError.API_KEY -> R.string.seerr_error_api_key
    SeerrError.EXPIRED -> R.string.seerr_error_expired
    SeerrError.PERMISSION -> R.string.seerr_error_permission
    SeerrError.QUOTA -> R.string.seerr_error_quota
    SeerrError.ALREADY_REQUESTED -> R.string.seerr_error_already
    SeerrError.BLOCKED -> R.string.seerr_error_blocked
    SeerrError.NOT_LINKED -> R.string.seerr_error_not_linked
    SeerrError.SERVER -> R.string.seerr_error_server
    SeerrError.ADDRESS -> R.string.seerr_error_address
    SeerrError.STORAGE -> R.string.seerr_error_storage
}

@StringRes
internal fun seerrStatusText(status: SeerrStatus): Int = when (status) {
    SeerrStatus.UNKNOWN, SeerrStatus.DELETED -> R.string.seerr_status_none
    SeerrStatus.PENDING -> R.string.seerr_status_pending
    SeerrStatus.APPROVED -> R.string.seerr_status_approved
    SeerrStatus.PROCESSING -> R.string.seerr_status_processing
    SeerrStatus.PARTIAL -> R.string.seerr_status_partial
    SeerrStatus.AVAILABLE, SeerrStatus.COMPLETED -> R.string.seerr_status_available
    SeerrStatus.DECLINED -> R.string.seerr_status_declined
    SeerrStatus.FAILED -> R.string.seerr_status_failed
    SeerrStatus.BLOCKED -> R.string.seerr_status_blocked
}

/** A Seerr title as a catalog card's item, so Seerr rows reuse the library's cards; its id never reaches Jellyfin. */
internal fun seerrCardItem(title: SeerrTitle): MediaItem = MediaItem(
    id = "$SEERR_ITEM_PREFIX${title.mediaType}-${title.id}",
    name = title.label,
    type = if (title.mediaType == "tv") "Series" else "Movie",
    productionYear = title.year.toIntOrNull(),
    overview = title.overview
)

internal const val SEERR_ITEM_PREFIX = "seerr-"
internal const val SEERR_ROUTE = "seerr"
internal const val SEERR_CONNECT_ROUTE = "seerr-connect"
internal const val SEERR_TITLE_ROUTE = "seerr-title?type={type}&id={id}"

internal fun seerrTitleRoute(type: String, id: Int) = "seerr-title?type=$type&id=$id"

/** The Seerr title route for a card made by [seerrCardItem], or null for a library item. */
internal fun seerrTitleRoute(item: MediaItem): String? {
    if (!item.id.startsWith(SEERR_ITEM_PREFIX)) return null
    val rest = item.id.removePrefix(SEERR_ITEM_PREFIX)
    val type = rest.substringBefore('-')
    val id = rest.substringAfter('-').toIntOrNull() ?: return null
    return if (type in setOf("movie", "tv")) seerrTitleRoute(type, id) else null
}

/** The status line under a Seerr card: year, then what Seerr says about it. */
internal fun seerrCardLine(title: SeerrTitle, status: String): String =
    listOf(title.year, status).filter(String::isNotBlank).joinToString(" · ")
