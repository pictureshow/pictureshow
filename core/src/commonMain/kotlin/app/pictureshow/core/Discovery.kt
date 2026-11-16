/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.StillWatching
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A trailer the server knows by address only (usually YouTube), from the item's RemoteTrailers. */
@Serializable
data class RemoteTrailer(@SerialName("Url") val url: String? = null, @SerialName("Name") val name: String? = null)

/**
 * One entry in a title's Trailers row (CAT-30). A local trailer is a library video the player plays; a remote one is
 * an address another app opens. [youTubeId] is set when the address is a YouTube video, so a platform can open its app.
 */
data class TrailerChoice(
    val key: String,
    val title: String,
    val item: MediaItem? = null,
    val url: String? = null,
    val youTubeId: String? = null
)

/** Which discovery reads a detail page makes for an item type (CAT-30..32). */
data class DetailDiscoveryReads(val trailers: Boolean, val extras: Boolean, val similar: Boolean) {
    val any: Boolean get() = trailers || extras || similar
}

fun detailDiscoveryReads(type: String?): DetailDiscoveryReads = when (type) {
    "Movie", "Series" -> DetailDiscoveryReads(trailers = true, extras = true, similar = true)
    "Episode" -> DetailDiscoveryReads(trailers = false, extras = true, similar = false)
    else -> DetailDiscoveryReads(trailers = false, extras = false, similar = false)
}

/**
 * The 11-character id of a YouTube video address, or null. Accepts watch, short-link, embed, shorts and mobile forms
 * over http or https; anything else (playlists, channels, other hosts) is not a video id.
 */
fun youTubeVideoId(url: String): String? {
    val parsed = runCatching { Url(url.trim()) }.getOrNull() ?: return null
    if (parsed.protocol.name !in setOf("http", "https")) return null
    val host = parsed.host.lowercase().removePrefix("www.").removePrefix("m.")
    val segments = parsed.segments.filter(String::isNotEmpty)
    val id = when (host) {
        "youtu.be" -> segments.firstOrNull()
        "youtube.com", "youtube-nocookie.com", "music.youtube.com" -> when (segments.firstOrNull()) {
            "watch" -> parsed.parameters["v"]
            "embed", "shorts", "v", "live" -> segments.getOrNull(1)
            else -> null
        }
        else -> null
    }
    return id?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
}

/**
 * CAT-30: the Trailers row. Local trailers first, in the server's order, then remote ones with an http(s) address.
 * A remote trailer that repeats an earlier address (or the same YouTube video under another form) is dropped. Untitled
 * entries are numbered "Trailer", "Trailer 2", … across the whole row.
 */
fun trailerChoices(local: List<MediaItem>, remote: List<RemoteTrailer>): List<TrailerChoice> {
    val choices = mutableListOf<TrailerChoice>()
    var untitled = 0
    fun fallbackTitle(): String = (++untitled).let { if (it == 1) "Trailer" else "Trailer $it" }
    local.filterNot { it.isMissing }.distinctBy { it.id }.forEach { item ->
        val title = item.name.takeIf { it.isNotBlank() && it != "Untitled" } ?: fallbackTitle()
        choices += TrailerChoice("local:${item.id}", title, item = item)
    }
    val seen = mutableSetOf<String>()
    remote.forEach { trailer ->
        val url = trailer.url?.trim().orEmpty()
        val parsed = runCatching { Url(url) }.getOrNull()
        if (url.isEmpty() || parsed == null || parsed.protocol.name !in setOf("http", "https") || parsed.host.isBlank()) return@forEach
        val youTube = youTubeVideoId(url)
        if (!seen.add(youTube?.let { "youtube:$it" } ?: url)) return@forEach
        val title = trailer.name?.trim()?.takeIf(String::isNotEmpty) ?: fallbackTitle()
        choices += TrailerChoice("remote:${youTube ?: url}", title, url = url, youTubeId = youTube)
    }
    return choices
}

private val EXTRA_ORDER = listOf("BehindTheScenes", "Featurette", "Interview", "DeletedScene", "Scene", "Short", "Clip", "Unknown")

/** What the Extras row says under a special feature (CAT-31). Null for types that are not extras to watch. */
fun extraLabel(extraType: String?): String? = when (extraType ?: "Unknown") {
    "BehindTheScenes" -> "Behind the scenes"
    "Featurette" -> "Featurette"
    "Interview" -> "Interview"
    "DeletedScene" -> "Deleted scene"
    "Scene" -> "Scene"
    "Short" -> "Short"
    "Clip" -> "Clip"
    "Unknown" -> "Extra"
    else -> null
}

/**
 * CAT-31: the Extras row from the server's special features. Theme songs, theme videos, samples and trailers (which
 * have their own row) are left out, as are items the library has no file for. The rest is grouped by kind (behind the
 * scenes, featurettes, interviews, deleted scenes, scenes, shorts, clips, then other extras), keeping the server's
 * order inside each kind.
 */
fun specialFeatureExtras(items: List<MediaItem>): List<MediaItem> = items
    .filterNot { it.isMissing }
    .distinctBy { it.id }
    .filter { (it.extraType ?: "Unknown") in EXTRA_ORDER }
    .withIndex()
    .sortedWith(compareBy({ EXTRA_ORDER.indexOf(it.value.extraType ?: "Unknown") }, { it.index }))
    .map { it.value }

/**
 * CAT-32: "More like this". Drops the title itself, anything the library has no file for, repeats, and other versions
 * of the same title (the same name and year, as a library with a second copy lists it), then keeps at most [limit].
 */
fun moreLikeThis(self: MediaItem, similar: List<MediaItem>, limit: Int = 12): List<MediaItem> {
    fun sameTitle(other: MediaItem) = other.name.equals(self.name, ignoreCase = true) && other.productionYear == self.productionYear
    return similar.asSequence()
        .filter { it.id != self.id && !it.isMissing && !sameTitle(it) }
        .distinctBy { it.id }
        .distinctBy { it.name.lowercase() to it.productionYear }
        .take(limit.coerceAtLeast(0))
        .toList()
}

/**
 * PLAY-30: whether the player asks "Are you still watching?" instead of starting the next episode by itself.
 * [episodesInARow] counts the episode ending now plus each one that started by itself since the viewer last pressed a
 * button; [minutesSinceInput] is the time since that press.
 */
fun stillWatchingDue(policy: StillWatching, episodesInARow: Int, minutesSinceInput: Long): Boolean =
    policy != StillWatching.Off && (episodesInARow >= policy.episodes || minutesSinceInput >= policy.minutes)

/** A photo at a display size: the server scales the original down, never up, and keeps its aspect (PHOTO-01). */
fun photoImageUrl(server: Server, item: MediaItem, maxWidth: Int, maxHeight: Int): String? {
    val tag = item.imageTags["Primary"]?.takeIf(String::isNotBlank) ?: return null
    return URLBuilder("${server.url.trimEnd('/')}/Items/${item.id.encodeURLPathPart()}/Images/Primary").apply {
        parameters.append("tag", tag)
        parameters.append("maxWidth", maxWidth.coerceAtLeast(1).toString())
        parameters.append("maxHeight", maxHeight.coerceAtLeast(1).toString())
        parameters.append("quality", "90")
    }.buildString()
}

/**
 * PHOTO-02: the photo a viewer or slideshow moves to. Manual moves stop at the ends; a running slideshow wraps from
 * the last photo to the first. Null when there is nowhere to go.
 */
fun photoStep(index: Int, count: Int, forward: Boolean, slideshow: Boolean): Int? {
    if (count <= 0) return null
    val current = index.coerceIn(0, count - 1)
    val next = if (forward) current + 1 else current - 1
    return when {
        next in 0 until count -> next
        slideshow && forward && count > 1 -> 0
        else -> null
    }
}

/** Slideshow speeds a viewer can choose, in seconds a photo stays (PHOTO-02). */
val SLIDESHOW_SECONDS = listOf(3, 5, 10, 20)

/**
 * SAVER-01: which artwork the screensaver may show. Only titles with their own backdrop, nothing missing, no repeats,
 * and, with a ceiling, only titles whose rating is known to be at or under it ([ratingAge] maps a rating to its age).
 */
fun screensaverArt(items: List<MediaItem>, maxAge: Int?, ratingAge: (String) -> Int?): List<MediaItem> = items
    .filter { it.backdropImageTags.any(String::isNotBlank) && !it.isMissing }
    .filter { item ->
        maxAge == null || item.officialRating?.let(ratingAge)?.let { it <= maxAge } == true
    }
    .distinctBy { it.id }
