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
    val mergeWatching: Boolean = false,
    val hiddenSeries: Set<String> = emptySet(),
    val landing: String = "home",
    val libraries: Map<String, LibraryDisplay> = emptyMap()
)

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
        val combined = (resume + next.filterNot { it.seriesId in resumedSeries }).distinctBy { it.id }
        listOf(HomeRow("watching", "Continue watching and next up", combined, wide = true)) +
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
    val studio: String? = null
) {
    val active: Boolean get() = this != LibraryFilters()
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
