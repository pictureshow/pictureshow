/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart

/** One server image: whose it is, which kind (Backdrop, Logo, Thumb, Primary) and its cache tag. */
data class ImageRef(val itemId: String, val type: String, val tag: String)

/**
 * HOME-20: the art behind the focused title on TV Home and Detail. The backdrop is the title's own, then its parent's
 * (an episode shows its series' backdrop), then a wide Thumb, then an episode's own still. Posters are never stretched
 * behind the page: a title with none of those shows no backdrop and the page keeps its plain background.
 */
fun heroBackdrop(item: MediaItem): ImageRef? {
    item.backdropImageTags.firstOrNull()?.takeIf(String::isNotBlank)?.let { return ImageRef(item.id, "Backdrop", it) }
    val parent = item.parentBackdropItemId
    val parentTag = item.parentBackdropImageTags.firstOrNull()?.takeIf(String::isNotBlank)
    if (parent != null && parentTag != null) return ImageRef(parent, "Backdrop", parentTag)
    item.imageTags["Thumb"]?.takeIf(String::isNotBlank)?.let { return ImageRef(item.id, "Thumb", it) }
    val thumbParent = item.parentThumbItemId
    val thumbTag = item.parentThumbImageTag?.takeIf(String::isNotBlank)
    if (thumbParent != null && thumbTag != null) return ImageRef(thumbParent, "Thumb", thumbTag)
    if (item.type == "Episode") item.imageTags["Primary"]?.takeIf(String::isNotBlank)?.let { return ImageRef(item.id, "Primary", it) }
    return null
}

/** HOME-20: the title's logo (clear art), its own first, else its series' logo; null means the title is drawn as text. */
fun heroLogo(item: MediaItem): ImageRef? {
    item.imageTags["Logo"]?.takeIf(String::isNotBlank)?.let { return ImageRef(item.id, "Logo", it) }
    val parent = item.parentLogoItemId
    val tag = item.parentLogoImageTag?.takeIf(String::isNotBlank)
    return if (parent != null && tag != null) ImageRef(parent, "Logo", tag) else null
}

/** The server's BlurHash for [ref] when the item carried one (Jellyfin sends ImageBlurHashes by default). */
fun blurHashFor(item: MediaItem, ref: ImageRef): String? = item.imageBlurHashes[ref.type]?.get(ref.tag)?.takeIf(String::isNotBlank)

/** Image GET is public in the server API; no access token goes into the URL or image cache keys. */
fun imageRefUrl(server: Server, ref: ImageRef, maxWidth: Int, quality: Int = 90): String =
    URLBuilder("${server.url.trimEnd('/')}/Items/${ref.itemId.encodeURLPathPart()}/Images/${ref.type}").apply {
        parameters.append("tag", ref.tag)
        parameters.append("maxWidth", maxWidth.coerceAtLeast(1).toString())
        parameters.append("quality", quality.coerceIn(1, 100).toString())
    }.buildString()

/**
 * HOME-21: the facts line under the hero title, in order. An episode leads with its code ("S1 E1"), then year, runtime,
 * parental rating and community rating. [endsAtEpochMillis] is when the title would end if started now, from what is
 * left of it (runtime minus resume position); each platform formats it as a local clock time ("Ends at 10:45 PM").
 */
data class HeroFacts(
    val episodeCode: String?,
    val year: Int?,
    val runtime: String?,
    val officialRating: String?,
    val communityRating: Double?,
    val endsAtEpochMillis: Long?
)

fun heroFacts(item: MediaItem, nowEpochMillis: Long): HeroFacts {
    val episodeCode = if (item.type == "Episode") {
        val season = item.parentIndexNumber
        val episode = item.indexNumber
        when {
            season == 0 && episode != null -> "Special E$episode"
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> null
        }
    } else {
        null
    }
    val year = item.productionYear ?: item.premiereDate?.take(4)?.toIntOrNull()
    val runtime = runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank)
    val playable = item.type in setOf("Movie", "Episode", "Video", "MusicVideo") && !item.isMissing
    val ticks = item.runTimeTicks?.takeIf { it in 1..864_000_000_000L }
    val endsAt = if (playable && ticks != null) {
        val remaining = (ticks - item.userData.playbackPositionTicks.coerceIn(0, ticks)).coerceAtLeast(0)
        nowEpochMillis + remaining / 10_000
    } else {
        null
    }
    return HeroFacts(
        episodeCode = episodeCode,
        year = year,
        runtime = runtime,
        officialRating = item.officialRating?.takeIf(String::isNotBlank),
        communityRating = item.communityRating?.takeIf { it.isFinite() && it > 0.0 },
        endsAtEpochMillis = endsAt
    )
}

/**
 * HOME-22: what a hero says under its facts. An episode's own overview, else its series' line is not fetched, so
 * nothing; long text is cut by the screen (two lines on TV Home), not here. Links are left out ([summaryWithoutLinks]).
 * Blank and whitespace-only overviews, and overviews that are only links, say nothing.
 */
fun heroSummary(item: MediaItem): String? = item.overview?.let(::summaryWithoutLinks)

private val SUMMARY_LINK = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
private val WHITESPACE = Regex("\\s+")

/**
 * HOME-22 / CAT-05: an overview as a summary a viewer reads. Video descriptions (a YouTube library's) are often walls of
 * links ("Merch - https://…", "visit https://…"); a link can't be followed on a TV and a sentence built around one says
 * nothing without it, so each sentence (or line) holding a bare http(s):// or www. link is dropped whole, and the rest
 * is joined with whitespace collapsed. Null when nothing is left.
 */
fun summaryWithoutLinks(overview: String): String? = overview.lineSequence()
    .flatMap { line -> line.split(SENTENCE_END).asSequence() }
    .filterNot { SUMMARY_LINK.containsMatchIn(it) }
    .joinToString(" ")
    .replace(WHITESPACE, " ")
    .trim()
    .takeIf(String::isNotEmpty)
