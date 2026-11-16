/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class HomePinKind { COLLECTION, PLAYLIST, FAVORITES, GENRE, STUDIO }

/** Container pins store an id, never a cached title or cached contents. */
@Serializable
data class HomePin(val kind: HomePinKind, val value: String = "") {
    val key: String get() = "pin:${kind.name}:$value"
    fun query(): BrowseQuery = BrowseQuery(
        parentId = if (kind in listOf(HomePinKind.COLLECTION, HomePinKind.PLAYLIST)) value else "",
        recursive = kind !in listOf(HomePinKind.COLLECTION, HomePinKind.PLAYLIST),
        includeItemTypes = if (kind in listOf(HomePinKind.COLLECTION, HomePinKind.PLAYLIST)) null else "Movie,Series",
        limit = 24,
        filters = LibraryFilters(
            favorite = kind == HomePinKind.FAVORITES,
            genre = value.takeIf { kind == HomePinKind.GENRE },
            studio = value.takeIf { kind == HomePinKind.STUDIO }
        )
    )
}

@Serializable
data class LibraryDisplay(val sortBy: String = "SortName", val descending: Boolean = false, val list: Boolean = false)

@Serializable
data class HomePreferences(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val pins: List<HomePin> = emptyList(),
    /** HOME-12: one "Continue watching" row by default (operator 2026-11-16); Settings turns Next up back into its own row. */
    val mergeWatching: Boolean = true,
    val hiddenSeries: Set<String> = emptySet(),
    val landing: String = "home",
    val libraries: Map<String, LibraryDisplay> = emptyMap()
)

/** Null waits or preserves an already consumed landing; "home" consumes a safe fallback. */
fun homeLanding(ready: Boolean, applied: Boolean, selection: String, libraryIds: Set<String>): String? {
    if (!ready || applied) return null
    return when {
        selection == "libraries" -> selection
        selection.startsWith("library:") && selection.removePrefix("library:") in libraryIds -> selection
        else -> "home"
    }
}

/** Empty or unavailable saved pins remain removable without redisplaying their saved metadata. */
fun homeEditorPinLabel(pin: HomePin, rows: List<HomeRow>): String =
    rows.firstOrNull { it.key == pin.key && it.items.isNotEmpty() }?.title ?: "Pinned ${pin.kind.name.lowercase()}"

/** Unknown fields and other clients' custom values survive every save. */
fun homePreferencesDocument(before: JsonObject, preferences: HomePreferences): JsonObject {
    val custom = (before["CustomPrefs"] as? JsonObject).orEmpty()
    return JsonObject(
        before + mapOf(
            "CustomPrefs" to JsonObject(custom + ("pictureshow.home.v1" to JsonPrimitive(Json.encodeToString(preferences)))),
            "Client" to JsonPrimitive("pictureshow")
        )
    )
}

fun readHomePreferences(document: JsonObject): HomePreferences? = try {
    val value = document["CustomPrefs"]?.jsonObject?.get("pictureshow.home.v1")?.jsonPrimitive?.contentOrNull
    value?.let { Json { ignoreUnknownKeys = true }.decodeFromString<HomePreferences>(it) }
} catch (_: IllegalArgumentException) {
    null
}

/** Resume wins by item and series; hiding Next Up never removes a resumable item or marks it played. */
fun customizedHome(rows: List<HomeRow>, preferences: HomePreferences): List<HomeRow> {
    val resume = rows.firstOrNull { it.key == "resume" }?.items.orEmpty()
    val next = rows.firstOrNull { it.key == "nextup" }?.items.orEmpty().filterNot { it.seriesId in preferences.hiddenSeries }
    val prepared = if (preferences.mergeWatching) {
        val resumedSeries = resume.mapNotNull { it.seriesId }.toSet()
        val candidates = next.filterNot { it.seriesId in resumedSeries || it.id in resume.map(MediaItem::id) }
        // Next up also lists an episode the viewer has started (beyond the resume read's limit, or saved after it). It
        // is still a resume: it joins the resumed group, uncaptioned, so its card reads like the others with progress.
        val (resumable, upcoming) = candidates.partition(MediaItem::isResumable)
        val combined = (resume + resumable + upcoming).distinctBy { it.id }
        val captions = upcoming.associate { it.id to nextCaption(it) }
        listOf(HomeRow("watching", "Continue watching", combined, wide = true, captions = captions)) +
            rows.filterNot { it.key in listOf("resume", "nextup") }
    } else {
        rows.map { if (it.key == "nextup") it.copy(items = next) else it }
    }
    val priority = preferences.order.distinct().withIndex().associate { it.value to it.index }
    return prepared.filter { it.key !in preferences.hidden && it.items.isNotEmpty() }
        .sortedBy { priority[it.key] ?: Int.MAX_VALUE }
}

@Serializable
enum class VideoResolution(val label: String, val minWidth: Int?, val maxWidth: Int?) {
    ANY("Any resolution", null, null),
    SD("SD (up to 719 px wide)", null, 719),
    HD("HD (720–1919 px wide)", 720, 1919),
    FULL_HD("Full HD (1920–3839 px wide)", 1920, 3839),
    UHD("4K and above (3840+ px wide)", 3840, null)
}

/** Different facets intersect. A facet has a single selected value; blank means no constraint. */
@Serializable
data class LibraryFilters(
    val genre: String? = null,
    val year: Int? = null,
    val officialRating: String? = null,
    val unwatched: Boolean = false,
    val favorite: Boolean = false,
    val resolution: VideoResolution = VideoResolution.ANY,
    val personId: String? = null,
    val studio: String? = null,
    val personName: String? = null
) {
    val active: Boolean get() = constraintKeys().isNotEmpty()
    fun parameters(): Map<String, String?> = mapOf(
        "genres" to genre?.takeIf { it.isNotBlank() },
        "years" to year?.toString(),
        "officialRatings" to officialRating?.takeIf { it.isNotBlank() },
        "isPlayed" to "false".takeIf { unwatched },
        "isFavorite" to "true".takeIf { favorite },
        "minWidth" to resolution.minWidth?.toString(),
        "maxWidth" to resolution.maxWidth?.toString(),
        "personIds" to personId?.takeIf { it.isNotBlank() },
        "studios" to studio?.takeIf { it.isNotBlank() }
    )
}

@Serializable
data class LibraryFacets(
    @kotlinx.serialization.SerialName("Genres") val genres: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("Years") val years: List<Int> = emptyList(),
    @kotlinx.serialization.SerialName("OfficialRatings") val ratings: List<String> = emptyList()
)

/** User configuration exclusions affect only their respective rows, never the separate library route. */
fun configuredHome(
    libraries: List<MediaItem>,
    resume: List<MediaItem>,
    next: List<MediaItem>,
    latest: Map<String, List<MediaItem>>,
    configuration: JsonObject?
): List<HomeRow> {
    fun excluded(key: String): Set<String> = (configuration?.get(key) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
    val hidePlayed = (configuration?.get("HidePlayedInLatest") as? JsonPrimitive)?.content == "true"
    return assembleHome(
        libraries,
        resume,
        next,
        latest.filterKeys { it !in excluded("LatestItemsExcludes") }.mapValues { (_, items) ->
            items.filterNot { hidePlayed && it.userData.played }
        }
    ).map { row -> if (row.library) row.copy(items = row.items.filterNot { it.id in excluded("MyMediaExcludes") }) else row }
}

/**
 * The server saved a resume point. Jellyfin's own Resume list uses the same test, a rewatch of a played item included;
 * HOME-12 keeps such an item a resume card.
 */
val MediaItem.isResumable: Boolean get() = userData.playbackPositionTicks > 0

/** A next episode in the merged row says so, "Next · S1:E2", so it isn't mistaken for one already started (HOME-12). */
fun nextCaption(item: MediaItem): String = if (item.type == "Episode") "Next · ${episodeLabel(item)}" else "Next"

private fun homeTitleKey(text: String?) = (text ?: "").trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * HOME-13: ids of Home cards whose title is also on Home from another library, so the card names its library (two
 * copies of a show in "Shows" and "Open Series", or a 4K and a 1080p library). An episode compares its series: two
 * series ids under one series name. Anything else compares type, name and year across different ids.
 */
fun homeDuplicateIds(rows: List<HomeRow>): Set<String> {
    val items = rows.filterNot { it.library }.flatMap { it.items }.distinctBy { it.id }
    val episodes = items.filter { it.type == "Episode" && it.seriesId != null && homeTitleKey(it.seriesName).isNotEmpty() }
        .groupBy { homeTitleKey(it.seriesName) }
        .values.filter { group -> group.mapNotNull { it.seriesId }.distinct().size > 1 }.flatten()
    val titles = items.filter { it.type != "Episode" && homeTitleKey(it.name).isNotEmpty() }
        .groupBy { Triple(it.type, homeTitleKey(it.name), it.productionYear) }
        .values.filter { group -> group.map { it.id }.distinct().size > 1 }.flatten()
    return (episodes + titles).map { it.id }.toSet()
}

/**
 * The line under a Home card's title. An episode reads "Series · Library · S1:E2", its library only when
 * [homeDuplicateIds] flagged it and the caption standing in for "S1:E2" in the merged row ("Next · S1:E2").
 * Anything else names only its library, or nothing.
 */
fun homeCardSubtitle(item: MediaItem, caption: String?, library: String?): String? = if (item.type == "Episode") {
    listOfNotNull(item.seriesName?.takeIf(String::isNotBlank), library, caption ?: episodeLabel(item)).joinToString(" · ")
} else {
    listOfNotNull(caption, library).joinToString(" · ").takeIf(String::isNotEmpty)
}
