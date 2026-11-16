/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.normalizeSearch
import app.pictureshow.core.seerr.SeerrConfig
import app.pictureshow.core.seerr.seerrSearchOffers
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** This account's Seerr connection, or null: Requests appears in Home, the phone bar and search only while it is set. */
@Composable
internal fun rememberSeerrConnection(key: String?): SeerrConfig? {
    val store: SeerrStore = koinInject()
    val connections by store.connections.collectAsStateWithLifecycle(emptyMap())
    return key?.let { connections[it] }
}

internal class SeerrOffers(val rows: List<HomeRow>, val artwork: (MediaItem) -> String?)

private val NoSeerrOffers = SeerrOffers(emptyList()) { null }

/**
 * Search's "Not in your library" row: Seerr's movies and series for the same words, minus anything the library
 * search found or Seerr says is available. Absent when the account isn't connected or Seerr found nothing.
 */
@Composable
internal fun rememberSeerrOffers(account: Account, search: SearchState): SeerrOffers {
    if (rememberSeerrConnection(account.key) == null) return NoSeerrOffers
    val vm: SeerrViewModel = koinViewModel(key = "seerr-search:${account.key}", parameters = { parametersOf(account) })
    val seerr by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(search.query, seerr.connected) { if (seerr.connected) vm.search(search.query) }
    val term = normalizeSearch(search.query)
    val library = search.rows.flatMap { it.items }.map { it.name to it.productionYear }
    val offers = remember(seerr.results, library) { seerrSearchOffers(seerr.results, library) }
    if (seerr.searched != term || offers.isEmpty()) return NoSeerrOffers
    val movie = stringResource(R.string.seerr_movie)
    val series = stringResource(R.string.seerr_series)
    val items = offers.map(::seerrCardItem)
    val captions = offers.zip(items).associate { (title, item) ->
        item.id to listOf(title.year, if (title.mediaType == "tv") series else movie).filter(String::isNotBlank).joinToString(" · ")
    }
    val posters = offers.zip(items).associate { (title, item) -> item.id to title.poster.orEmpty() }
    val row = HomeRow(SEERR_SEARCH_ROW, stringResource(R.string.seerr_not_in_library), items, captions = captions)
    // Library rows keep the server's art (null); a Seerr card without a poster gets none rather than a server request.
    return SeerrOffers(listOf(row)) { posters[it.id] }
}

internal const val SEERR_SEARCH_ROW = "seerr"
