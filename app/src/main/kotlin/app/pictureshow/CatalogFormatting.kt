/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.MediaItem
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

internal fun emptyGridLabel(parent: MediaItem): String = when (parent.type) {
    "CollectionFolder" -> "This library is empty."
    "BoxSet" -> "This collection is empty."
    "Playlist" -> "This playlist is empty."
    "Folder" -> "This folder is empty."
    else -> "Nothing here yet."
}
