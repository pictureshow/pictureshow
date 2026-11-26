/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** How a TV detail action appears in the action bar (CAT-05/06). */
enum class DetailActionPresentation(val wireName: String) {
    /** Prominent filled pill (Play / Resume / Play again / Play S1:E1). */
    PRIMARY("primary"),

    /** Secondary translucent pill right beside the primary (Play from start). */
    SECONDARY_PILL("secondary-pill"),

    /** Compact round icon button with a caption underneath. */
    ICON("icon")
}

/** One slot in a TV detail page's action row, in focus traversal order. */
data class DetailActionSlot(val target: String, val presentation: DetailActionPresentation)

/**
 * CAT-05/06: which actions a TV detail, series or season page shows, in traversal order, and how each renders.
 *
 * One prominent Play/Resume pill leads; Play from start sits right beside it only when the resolved playable title has
 * saved progress (or during a playback-return handoff so pending->fresh never shifts nodes), never for missing episodes
 * (CAT-21) or after a natural-end finish (PLAY-17). The remaining actions form a compact row of round icon buttons with
 * captions in a fixed order: Favorite, optional Download, optional Find subtitles (playable leaf items only), optional
 * Trailer (Movie and Series only), and Mark watched last — never beside Play.
 */
fun detailActionSlots(
    itemType: String,
    isMissing: Boolean = false,
    hasProgress: Boolean = false,
    playsAgain: Boolean = false,
    playbackReturn: Boolean = false,
    canDownload: Boolean = false,
    canFindSubtitles: Boolean = false,
    hasTrailer: Boolean = false,
    pending: Boolean = false
): List<DetailActionSlot> {
    if (itemType !in setOf("Movie", "Episode", "Video", "Series", "Season")) return emptyList()
    return buildList {
        add(DetailActionSlot("primary", DetailActionPresentation.PRIMARY))
        if (!isMissing && !playsAgain && (hasProgress || playbackReturn)) {
            add(DetailActionSlot("from-start", DetailActionPresentation.SECONDARY_PILL))
        }
        add(DetailActionSlot("favorite", DetailActionPresentation.ICON))
        if (canDownload && !isMissing && !pending) {
            add(DetailActionSlot("download", DetailActionPresentation.ICON))
        }
        if (canFindSubtitles && !isMissing && !pending && itemType in setOf("Movie", "Episode", "Video")) {
            add(DetailActionSlot("subtitles", DetailActionPresentation.ICON))
        }
        if (hasTrailer && !isMissing && !pending && itemType in setOf("Movie", "Series")) {
            add(DetailActionSlot("trailer", DetailActionPresentation.ICON))
        }
        // CAT-05: Mark watched alters watch/resume state, so it always sits last, away from Play.
        add(DetailActionSlot("played", DetailActionPresentation.ICON))
    }
}
