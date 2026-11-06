/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable

/** Tracks of one type this download can't play, in server order: the rows a local player marks as needing a connection. */
fun OfflineEntry.undownloadedTracks(type: String): List<OfflineTrack> =
    tracks.filter { it.type == type && trackSwitch(type, it.serverIndex) == "reconnect" }.sortedBy { it.serverIndex }

/** server: unknown|unreachable|signed-out|ok. A null answer hasn't arrived yet. */
data class OfflineReconnectFacts(val server: String = "unknown", val playbackAllowed: Boolean? = null, val trackOnServer: Boolean? = null)

/** outcome: local|no-connection|checking|server-unreachable|signed-out|not-allowed|track-gone|offer. */
data class OfflineReconnectDecision(val outcome: String, val metered: Boolean = false)

/**
 * Whether a picked track may be offered as a stream. The Wi-Fi-only setting governs downloads, not streaming, so it isn't an input: a metered
 * network still gets the offer and the viewer is told it uses their data.
 */
fun offlineReconnectDecision(trackSwitch: String, network: String, facts: OfflineReconnectFacts): OfflineReconnectDecision {
    val outcome = when {
        trackSwitch == "local" -> "local"
        network == "none" -> "no-connection"
        facts.server == "unreachable" -> "server-unreachable"
        facts.server == "signed-out" -> "signed-out"
        facts.server != "ok" -> "checking"
        facts.playbackAllowed == false -> "not-allowed"
        facts.trackOnServer == false -> "track-gone"
        facts.playbackAllowed == null || facts.trackOnServer == null -> "checking"
        else -> "offer"
    }
    return OfflineReconnectDecision(outcome, outcome == "offer" && network == "metered")
}

/** type is the server's, compared without case: "Audio", "Subtitle". */
@Serializable
data class OfflineServerTrack(val index: Int, val type: String, val language: String? = null)

@Serializable
data class OfflineServerSource(val id: String? = null, val tracks: List<OfflineServerTrack> = emptyList())

/**
 * A download's index means nothing on a file the server has since replaced. The source must still exist (the first when the download named
 * none), hold that index with that type, and agree on the language when both sides know it.
 */
fun offlineTrackOnServer(track: OfflineTrack, sourceId: String?, sources: List<OfflineServerSource>): Boolean {
    val source = (if (sourceId == null) sources.firstOrNull() else sources.firstOrNull { it.id == sourceId }) ?: return false
    return source.tracks.any { candidate ->
        candidate.index == track.serverIndex &&
            candidate.type.equals(track.type, ignoreCase = true) &&
            (candidate.language == null || track.language == null || candidate.language.equals(track.language, ignoreCase = true))
    }
}

/** Null asks for the server's default; -1 for subtitles is the viewer's explicit Off and must survive the move. */
data class OfflineHandoffSelection(val audio: Int?, val subtitle: Int?)

/** The picked track plus whatever else the viewer has now. Audio has no Off on a server, so audio off falls to the server's default. */
fun offlineHandoffSelection(type: String, index: Int, currentAudio: Int?, currentSubtitle: Int?): OfflineHandoffSelection =
    OfflineHandoffSelection(
        audio = if (type == "audio") index else currentAudio?.takeIf { it >= 0 },
        subtitle = if (type == "subtitle") index else currentSubtitle
    )

/** The ready download of [itemId], the same preset as [preset] when both exist. */
fun offlineReadyEntry(entries: List<OfflineEntry>, itemId: String, preset: OfflinePreset): OfflineEntry? = entries
    .filter { it.itemId == itemId && it.job.phase == OfflinePhase.ready }
    .minWithOrNull(compareBy({ it.preset != preset }, { it.createdSequence }))

/**
 * What follows [current] at the end of a local viewing. A server that answered knows the true order, so only a download of the episode it
 * named follows; no download of that episode means the viewing ends rather than skipping ahead. With no answer the manifest can vouch only
 * for the next number in the same season: it doesn't know how long a season is, so a season's end stops here.
 */
fun offlineNextEpisode(entries: List<OfflineEntry>, current: OfflineEntry, serverAnswered: Boolean, serverNext: String?): OfflineEntry? {
    if (current.kind != OfflineMediaKind.episode) return null
    if (serverAnswered) return serverNext?.let { offlineReadyEntry(entries, it, current.preset) }
    val series = current.labels.seriesId ?: return null
    val season = current.labels.seasonNumber ?: return null
    val episode = current.labels.episodeNumber ?: return null
    val next = entries.firstOrNull {
        it.kind == OfflineMediaKind.episode &&
            it.labels.seriesId == series &&
            it.labels.seasonNumber == season &&
            it.labels.episodeNumber == episode + 1 &&
            it.job.phase == OfflinePhase.ready
    } ?: return null
    return offlineReadyEntry(entries, next.itemId, current.preset)
}
