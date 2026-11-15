/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** Re-entered catalog pages must reconcile server-owned user data, even when their layout is cached. */
enum class CatalogReturnRefresh { None, HomeRows, TitleAndChildren, LoadedCards, SearchResults }

fun catalogReturnRefresh(page: String): CatalogReturnRefresh = when (page) {
    "Home" -> CatalogReturnRefresh.HomeRows
    "Detail" -> CatalogReturnRefresh.TitleAndChildren
    "Library", "Person" -> CatalogReturnRefresh.LoadedCards
    "Search" -> CatalogReturnRefresh.SearchResults
    else -> CatalogReturnRefresh.None
}

/** Local continuity until the server responds. Only physical EOF establishes played locally; the server may
 * apply its own short-video thresholds on Stop. Preserve favorites and other server fields. */
fun playbackReturnData(previous: ItemUserData, positionTicks: Long, ended: Boolean): ItemUserData = previous.copy(
    playbackPositionTicks = if (ended) 0 else positionTicks.coerceAtLeast(0),
    played = previous.played || ended
)
