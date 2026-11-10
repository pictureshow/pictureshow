/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.LibraryFilters
import app.pictureshow.core.MediaItem
import app.pictureshow.core.constraintKeys
import app.pictureshow.core.filteredEmptyLabel
import java.text.NumberFormat
import java.util.Locale

internal fun communityRatingLabel(rating: Double, locale: Locale = Locale.getDefault()): String {
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }
    return "★ ${formatter.format(rating)}"
}

internal fun itemCountLabel(total: Int): String = if (total == 1) "1 item" else "$total items"

internal fun emptyGridLabel(parent: MediaItem, filters: LibraryFilters = LibraryFilters()): String = if (filters.active) {
    filteredEmptyLabel(parent.collectionType)
} else {
    when (parent.type) {
        "CollectionFolder", "UserView" -> "This library is empty."
        "BoxSet" -> "This collection is empty."
        "Playlist" -> "This playlist is empty."
        "Folder" -> "This folder is empty."
        else -> "Nothing here yet."
    }
}

internal fun filterChipLabel(filters: LibraryFilters): String = filters.constraintKeys().size.let {
    if (it ==
        0
    ) {
        "Filters"
    } else {
        "Filters ($it)"
    }
}

internal fun filterConstraintLabel(filters: LibraryFilters, key: String): String = when (key) {
    "genre" -> filters.genre ?: "Genre"
    "year" -> filters.year.toString()
    "rating" -> filters.officialRating ?: "Rating"
    "unwatched" -> "Unwatched"
    "favorite" -> "Favorites"
    "resolution" -> filters.resolution.label
    "person" -> "Person"
    "studio" -> filters.studio ?: "Studio"
    else -> "Filter"
}
