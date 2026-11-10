/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** The editor presents only rows that can actually appear in the current watching mode. */
fun editableHomeKeys(keys: List<String>, preferences: HomePreferences): List<String> {
    val inactive = if (preferences.mergeWatching) setOf("resume", "nextup") else setOf("watching")
    return keys.distinct().filterNot { it in inactive }
        .sortedBy { preferences.order.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
}

/** Reorder the visible rows while retaining dormant mode rows in the saved order. */
fun moveHomeRow(order: List<String>, visible: List<String>, key: String, delta: Int): List<String> {
    val active = visible.distinct().toMutableList()
    val from = active.indexOf(key)
    if (from < 0) return order
    val to = (from + delta).coerceIn(active.indices)
    if (from == to) return order
    active.add(to, active.removeAt(from))
    val complete = (order + visible).distinct()
    val moved = active.iterator()
    return complete.map { if (it in visible) moved.next() else it }
}

/** Each independent constraint counts once, including a width range's two wire parameters. */
fun LibraryFilters.constraintKeys(): List<String> = buildList {
    if (!genre.isNullOrBlank()) add("genre")
    if (year != null) add("year")
    if (!officialRating.isNullOrBlank()) add("rating")
    if (unwatched) add("unwatched")
    if (favorite) add("favorite")
    if (resolution != VideoResolution.ANY) add("resolution")
    if (!personId.isNullOrBlank()) add("person")
    if (!studio.isNullOrBlank()) add("studio")
}

fun LibraryFilters.withoutConstraint(key: String): LibraryFilters = when (key) {
    "genre" -> copy(genre = null)
    "year" -> copy(year = null)
    "rating" -> copy(officialRating = null)
    "unwatched" -> copy(unwatched = false)
    "favorite" -> copy(favorite = false)
    "resolution" -> copy(resolution = VideoResolution.ANY)
    "person" -> copy(personId = null)
    "studio" -> copy(studio = null)
    else -> this
}

fun filteredEmptyLabel(collectionType: String?): String = "No ${when (collectionType) {
    "movies" -> "movies"
    "tvshows" -> "shows"
    "music" -> "music"
    else -> "titles"
}} match these filters"
