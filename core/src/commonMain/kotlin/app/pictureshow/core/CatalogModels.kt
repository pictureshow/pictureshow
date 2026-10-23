/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null
)

@Serializable
data class MediaItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "Untitled",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("Chapters") val chapters: List<Chapter> = emptyList(),
    @SerialName("Trickplay") val trickplay: Map<String, Map<String, TrickplayInfo>> = emptyMap(),
    @SerialName("UserData") val userData: ItemUserData = ItemUserData()
)

/** Counts are nullable: older/partial responses without cardinality do not establish emptiness. */
val MediaItem.isEmptyContainer: Boolean
    get() {
        val container = isFolder || type in setOf("BoxSet", "Playlist", "Folder", "CollectionFolder", "Series", "Season")
        if (!container || childCount?.let { it > 0 } == true || recursiveItemCount?.let { it > 0 } == true) return false
        return childCount == 0 || recursiveItemCount == 0
    }

/** Jellyfin marks an empty folder played because all of its zero children are played. */
val MediaItem.isWatched: Boolean get() = userData.played && !isEmptyContainer

@Serializable
data class ItemPage(
    @SerialName("Items") val items: List<MediaItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0
)

data class BrowseQuery(
    val parentId: String,
    val includeItemTypes: String? = null,
    val recursive: Boolean = false,
    val sortBy: String = "SortName",
    val descending: Boolean = false,
    val startIndex: Int = 0,
    val limit: Int = 60
)

data class HomeRow(val key: String, val title: String, val items: List<MediaItem>, val library: Boolean = false, val wide: Boolean = false)

/** Server view order is meaningful; unavailable or empty sections do not remove other rows. */
fun assembleHome(
    views: List<MediaItem>,
    resume: List<MediaItem>,
    nextUp: List<MediaItem>,
    latest: Map<String, List<MediaItem>>
): List<HomeRow> = buildList {
    if (resume.isNotEmpty()) add(HomeRow("resume", "Continue watching", resume, wide = true))
    if (nextUp.isNotEmpty()) add(HomeRow("nextup", "Next up", nextUp, wide = true))
    if (views.isNotEmpty()) add(HomeRow("libraries", "Libraries", views, library = true))
    views.filter { it.collectionType in listOf("movies", "tvshows") }.forEach { view ->
        val items = latest[view.id].orEmpty()
        if (items.isNotEmpty()) add(HomeRow("latest:${view.id}", "Latest in ${view.name}", items))
    }
}

fun resumeLabel(ticks: Long): String {
    val seconds = ticks.coerceAtLeast(0) / 10_000_000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    val time = if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder" else "$minutes:$remainder"
    return "Resume from $time"
}

/** Unknown or implausible totals are omitted without changing the server's playback timeline. */
fun displayRuntimeMillis(milliseconds: Long): Long? = milliseconds.takeIf { it in 1..86_400_000L }

/** A display safety ceiling, not a correction to the server's playback timeline. */
fun runtimeLabel(ticks: Long?): String {
    // A generous 24-hour display ceiling rejects overflow-derived DVD runtimes. Neither chapters
    // (start positions) nor an EOF playhead after a seek establishes a replacement total. Omit it.
    if (ticks == null || ticks !in 1..864_000_000_000L) return ""
    val minutes = (ticks / 600_000_000).coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

fun episodeLabel(item: MediaItem): String {
    val season = item.parentIndexNumber
    val episode = item.indexNumber
    return when {
        season == 0 && episode != null -> "Special E$episode"
        season == 0 -> "Special"
        season != null && episode != null -> "S$season:E$episode"
        season != null -> "S$season"
        episode != null -> "E$episode"
        else -> "Episode"
    }
}

/** Fraction in 0..1, with a tick-based fallback when the server omits PlayedPercentage. */
fun progress(item: MediaItem): Float {
    val percentage = item.userData.playedPercentage
    if (percentage != null && percentage.isFinite()) return (percentage / 100).coerceIn(0.0, 1.0).toFloat()
    val duration = item.runTimeTicks ?: return 0f
    if (duration <= 0) return 0f
    return (item.userData.playbackPositionTicks.toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
}
