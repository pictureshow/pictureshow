/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** The visible row state complements the eye icon and reader description on every platform. */
fun homeRowEditHint(shown: Boolean, moving: Boolean, tv: Boolean): String? = buildList {
    if (!shown) add("Hidden")
    if (moving) add(if (tv) "Moving · Up/Down · OK to drop" else "Moving · Release to drop")
}.joinToString(" · ").ifBlank { null }

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

/** Already authorized current choices are visible on the first unsearched page only. */
fun namedPickerChoices(current: List<MediaItem>, page: List<MediaItem>, term: String, offset: Int): List<MediaItem> =
    (if (term.isBlank() && offset == 0) current + page else page).distinctBy(MediaItem::id)

/** One returned removed series can be restored by its switch; bulk restore also reaches absent series. */
fun offerRestoreAllSeries(hidden: Set<String>, available: Set<String>): Boolean = hidden.size > 1 || hidden.any { it !in available }

fun nextUpSeriesHint(shown: Boolean): String = if (shown) "Shown in Next up" else "Removed from Next up"

fun homePinPickerSupport(pinned: Boolean, count: Int?): String? =
    listOfNotNull(if (pinned) "On your home" else null, count?.let(::titleCountLabel)).joinToString(" · ").ifBlank { null }

/** A confirmed home reset keeps independent library display choices. */
fun resetHomeLayout(preferences: HomePreferences, confirmed: Boolean): HomePreferences =
    if (confirmed) HomePreferences(libraries = preferences.libraries) else preferences

fun titleCountLabel(count: Int): String = if (count == 1) "1 title" else "$count titles"

fun VideoResolution.summary(): String = when (this) {
    VideoResolution.ANY -> "Any"
    VideoResolution.SD -> "SD"
    VideoResolution.HD -> "HD"
    VideoResolution.FULL_HD -> "Full HD"
    VideoResolution.UHD -> "4K and above"
}

/** A missing selected person never removes the remaining focus escape routes. */
fun namedPickerFocusTarget(hasChoices: Boolean, hasAny: Boolean, hasSelection: Boolean, editing: Boolean = false): String = when {
    editing -> "search-field"
    !hasChoices && !hasAny -> "search"
    hasAny && (!hasSelection || !hasChoices) -> "any"
    else -> "choice"
}

/** Announce the direction once; visual arrows remain decorative. */
fun sortDirectionLabel(descending: Boolean): String = if (descending) "Descending" else "Ascending"

/** Initial preferences must resolve before an editor can expose or mutate a default layout. */
fun canOpenHomeEditor(preferencesReady: Boolean): Boolean = preferencesReady

/** Commands have no selection state; unselected radio choices still have one. */
fun hasControlSelection(selected: Boolean?): Boolean = selected != null
