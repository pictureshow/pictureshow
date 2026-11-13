/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** A single stable announcement, independent of artwork load, truncation and decorative badges. */
fun catalogAnnouncement(item: MediaItem): String = buildList {
    add(item.name)
    if (item.type == "Episode") {
        item.seriesName?.takeIf(String::isNotBlank)?.let(::add)
        item.parentIndexNumber?.let { add("Season $it") }
        item.indexNumber?.let { add("Episode $it") }
    }
    item.productionYear?.let { add(it.toString()) }
    if (item.isWatched) {
        add("Watched")
    } else if (!item.isEmptyContainer && item.userData.playbackPositionTicks > 0) {
        add(resumeLabel(item.userData.playbackPositionTicks))
    }
    item.userData.unplayedItemCount?.takeUnless { item.isEmptyContainer }?.let { add("$it unplayed") }
    if (item.userData.isFavorite) add("Favorite")
}.joinToString(", ")
