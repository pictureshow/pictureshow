/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePolicy
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflinePresetChoice
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.committedBytes
import app.pictureshow.core.offline.offlinePresets
import app.pictureshow.core.offline.offlineSelect
import kotlin.math.roundToInt

/**
 * Compatible video is offered only where the contract proved it. Kept here, in one place, so a failed line can disable a
 * preset honestly (OFF-02); audio presets arrive with music.
 */
internal val OFFLINE_QUALIFIED_PRESETS = setOf(OfflinePreset.video720, OfflinePreset.video1080)

internal enum class OfflineAction { Play, Pause, Resume, Retry, Redownload, AllowCellular, Delete }

internal data class OfflineRow(
    val entry: OfflineEntry,
    val title: String,
    val subtitle: String?,
    val status: String,
    val fraction: Float?,
    val actions: List<OfflineAction>,
    val notDownloaded: List<String>,
    /** The same tracks in plain words, for the row. Null when every track came along. */
    val notDownloadedSummary: String? = null,
    /** Working with no total to measure against: motion, not a number (OFF-03b). */
    val indeterminate: Boolean = false,
    val statusIsError: Boolean = false
)

internal data class OfflineSections(
    val ready: List<OfflineRow>,
    val active: List<OfflineRow>,
    val failed: List<OfflineRow>,
    val usedBytes: Long,
    val capBytes: Long?
) {
    val isEmpty: Boolean get() = ready.isEmpty() && active.isEmpty() && failed.isEmpty()

    /** Top to bottom on both form factors: what needs the viewer comes first. */
    val ordered: List<Pair<String, List<OfflineRow>>>
        get() = listOf("Needs attention" to failed, "In progress" to active, "Ready to watch" to ready).filter { it.second.isNotEmpty() }
}

internal const val OFFLINE_NEEDS_PASSWORD = "needs-password"
internal const val OFFLINE_PASSWORD_STATUS = "Waiting for your password"
internal const val OFFLINE_PASSWORD_REASON =
    "This server has Quick Connect off. Downloads need your password once, to get their own sign-in. It isn't saved."
internal const val OFFLINE_PASSWORD_FAILED = "Could not confirm your password. Check it and your connection, then try again."
internal const val OFFLINE_RECOVERABLE_TEXT =
    "This list couldn't be read. Nothing was deleted. If a newer version of Pictureshow saved it, updating the app brings it back."
internal const val OFFLINE_UNREACHABLE_TEXT = "Could not reach the server. Your downloads still play."

private fun OfflineJob.waitingForPassword() = phase == OfflinePhase.failed && failure == OFFLINE_NEEDS_PASSWORD

/** The engine's flag and a job's failure can disagree (a failed password attempt clears one, a restart the other); either one asks. */
internal fun OfflineLibrary.passwordNeeded(): Boolean = needsPassword || manifest.entries.any { it.job.waitingForPassword() }

/** Ready downloads this viewer can watch right now; an unreadable list promises nothing. */
internal fun OfflineLibrary?.hasReadyDownloads(): Boolean =
    this != null && !recoverable && manifest.entries.any { it.job.phase == OfflinePhase.ready }

/** Home offers saved films in place of a bare error only when the server is out of reach and nothing else is on the page. */
internal fun offlineHomeOffersDownloads(state: CatalogHomeState, library: OfflineLibrary?): Boolean =
    state.unreachable && state.rows.none { it.items.isNotEmpty() } && library.hasReadyDownloads()

internal sealed interface OfflineFirstFocus {
    data object Password : OfflineFirstFocus

    data class Conflict(val itemId: String) : OfflineFirstFocus

    data class Row(val entryId: String, val action: OfflineAction) : OfflineFirstFocus

    data object Storage : OfflineFirstFocus
}

/** What OK does on arrival. Never a step that spends metered data, and never Delete while something else is on offer. */
private fun OfflineRow.primaryAction(): OfflineAction? =
    actions.firstOrNull { it != OfflineAction.AllowCellular && it != OfflineAction.Delete } ?: actions.firstOrNull()

/** A notice about the viewer's place comes before the list: it's the first thing on the page, and its OK is the answer. */
internal fun offlineFirstFocus(
    passwordNeeded: Boolean,
    sections: OfflineSections,
    conflicts: Collection<String> = emptyList()
): OfflineFirstFocus {
    if (passwordNeeded) return OfflineFirstFocus.Password
    conflicts.firstOrNull()?.let { return OfflineFirstFocus.Conflict(it) }
    val row = sections.ordered.flatMap { it.second }.firstOrNull { it.primaryAction() != null } ?: return OfflineFirstFocus.Storage
    return OfflineFirstFocus.Row(row.entry.id, requireNotNull(row.primaryAction()))
}

/** The row that takes a deleted row's place, else the one above it, else the top of the page. Rows with nothing to press don't count. */
internal fun offlineFocusAfterDelete(sections: OfflineSections, deleted: String): OfflineFirstFocus {
    val rows = sections.ordered.flatMap { it.second }
    val at = rows.indexOfFirst { it.entry.id == deleted }
    val row = rows.drop(at + 1).firstOrNull { it.primaryAction() != null }
        ?: rows.take(at.coerceAtLeast(0)).lastOrNull { it.primaryAction() != null }
        ?: return OfflineFirstFocus.Storage
    return OfflineFirstFocus.Row(row.entry.id, requireNotNull(row.primaryAction()))
}

/** Where a row sits among the list's section headings and rows, which is how the list finds one that isn't on screen yet. */
internal fun offlineRowPosition(sections: OfflineSections, entryId: String): Int? {
    var position = 0
    sections.ordered.forEach { (_, rows) ->
        position++
        val at = rows.indexOfFirst { it.entry.id == entryId }
        if (at >= 0) return position + at
        position += rows.size
    }
    return null
}

/** Waiting for storage has its answer one screen away. */
internal fun offlineOffersStorage(job: OfflineJob): Boolean = job.phase == OfflinePhase.waitingStorage

/** The current limit is always a choice, even one no step offers, so what's set is visible and there's a way back to it. */
internal fun offlineCapChoices(steps: List<Long>, current: Long?): List<Long> = (steps + listOfNotNull(current)).distinct().sorted()

private fun joinLanguages(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

private fun OfflineTrack.languageName(): String? = language?.let(::trackLanguage)?.takeIf { !it.equals(language, ignoreCase = true) }

private fun trackGroup(tracks: List<OfflineTrack>, words: String, oneTrack: String): String? {
    if (tracks.isEmpty()) return null
    val names = tracks.mapNotNull { it.languageName() }.distinct()
    val unnamed = tracks.count { it.languageName() == null }
    return when {
        names.isEmpty() -> "$unnamed $oneTrack track" + if (unnamed == 1) "" else "s"
        unnamed > 0 -> joinLanguages(names + "other") + " $words"
        else -> joinLanguages(names) + " $words"
    }
}

/** "Not downloaded: Spanish audio; English, Spanish and French subtitles". Each language once, in words a viewer knows. */
internal fun offlineNotDownloadedSummary(tracks: List<OfflineTrack>): String? {
    val missing = tracks.filter { !it.available }
    val groups = listOfNotNull(
        trackGroup(missing.filter { it.type == "audio" }, "audio", "audio"),
        trackGroup(missing.filter { it.type != "audio" }, "subtitles", "subtitle")
    )
    return groups.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "Not downloaded: ")
}

internal fun offlineBytes(bytes: Long): String {
    val kib = 1024.0
    return when {
        bytes >= kib * kib * kib -> "%.1f GB".format(bytes / (kib * kib * kib))
        bytes >= kib * kib -> "${(bytes / (kib * kib)).roundToInt()} MB"
        bytes >= kib -> "${(bytes / kib).roundToInt()} KB"
        else -> "$bytes bytes"
    }
}

internal fun offlinePresetLabel(preset: OfflinePreset): String = when (preset) {
    OfflinePreset.original -> "Original"
    OfflinePreset.video720 -> "Compatible, up to 720p"
    OfflinePreset.video1080 -> "Compatible, up to 1080p"
    OfflinePreset.audio128 -> "Compatible audio, 128 kbps"
    OfflinePreset.audio256 -> "Compatible audio, 256 kbps"
}

internal fun offlinePresetUnavailable(reason: String?): String = when (reason) {
    "denied" -> "Your account can't download this."
    "no-conversion-right" -> "Your account isn't allowed to use conversion."
    "not-qualified" -> "Conversion hasn't passed its checks on this server version."
    "live" -> "Live streams have no end to download."
    "wrong-media" -> "Not available for this kind of media."
    else -> "Not available."
}

private fun failureText(reason: String?): String = when (reason) {
    OFFLINE_NEEDS_PASSWORD -> OFFLINE_PASSWORD_STATUS
    "denied", "unauthorized" -> "The server no longer lets this account download it."
    "not-indexable", "no-body" -> "The server's copy couldn't be saved in a playable form."
    "length", "hash", "truncated", "overrun" -> "The file arrived damaged and couldn't be verified."
    "source-changed" -> "The file changed on the server during the download."
    "wrong-viewer" -> "The server answered for a different account."
    "revocation-pending" -> "Still cleaning up an old sign-in. Try again when the server is reachable."
    "network" -> "The connection kept dropping."
    "internal" -> "Something went wrong with this download. Try again."
    "gone" -> "The server no longer has this file."
    "short" -> "The server's conversion ended early. Try again, or choose another quality."
    else -> "The download stopped."
}

internal fun offlineStatus(
    job: OfflineJob,
    progress: OfflineLiveProgress?,
    wifiOnly: Boolean,
    network: OfflineNetwork,
    committed: Long,
    tv: Boolean = false
): String = when (job.phase) {
    OfflinePhase.queued -> "Queued"
    OfflinePhase.waitingNetwork -> when {
        network == OfflineNetwork.metered && wifiOnly && !job.cellularAllowed ->
            if (tv) "Waiting for Wi-Fi or Ethernet" else "Waiting for Wi-Fi"
        // Only what's true: a booked restart is best effort, so this promises nothing about a closed app.
        else -> "Waiting for a connection. It resumes once you're connected and Pictureshow is open."
    }
    OfflinePhase.waitingStorage -> "Waiting for storage. Free up space or raise your limit."
    OfflinePhase.preparing -> "Preparing"
    OfflinePhase.transferring -> when {
        progress == null -> "Starting"
        progress.total != null && progress.total > 0 ->
            "${(progress.received * 100 / progress.total).coerceIn(0, 100)}% of ${offlineBytes(progress.total)}"
        // No total: only what's true (OFF-03b).
        else -> "${offlineBytes(progress.received)} received. The server is converting; final size isn't known."
    }
    OfflinePhase.verifying -> "Checking the download"
    OfflinePhase.ready -> offlineBytes(committed)
    OfflinePhase.paused -> if (job.pausedByCap) "Paused: over your storage limit" else "Paused"
    OfflinePhase.failed -> failureText(job.failure)
    OfflinePhase.missing -> "The file is gone from this device."
    OfflinePhase.deleting -> "Removing"
}

internal fun offlineActions(job: OfflineJob, wifiOnly: Boolean, network: OfflineNetwork): List<OfflineAction> = when (job.phase) {
    OfflinePhase.ready -> listOf(OfflineAction.Play, OfflineAction.Delete)
    OfflinePhase.paused -> listOf(OfflineAction.Resume, OfflineAction.Delete)
    // Retry can't work without the password; the form above the list retries these itself.
    OfflinePhase.failed ->
        if (job.waitingForPassword()) listOf(OfflineAction.Delete) else listOf(OfflineAction.Retry, OfflineAction.Delete)
    OfflinePhase.missing -> listOf(OfflineAction.Redownload, OfflineAction.Delete)
    OfflinePhase.waitingNetwork -> listOfNotNull(
        // The override only helps on the connection it overrides for.
        OfflineAction.AllowCellular.takeIf { wifiOnly && !job.cellularAllowed && network == OfflineNetwork.metered },
        OfflineAction.Pause,
        OfflineAction.Delete
    )
    OfflinePhase.deleting -> emptyList()
    else -> listOf(OfflineAction.Pause, OfflineAction.Delete)
}

internal fun offlineIndeterminate(job: OfflineJob, progress: OfflineLiveProgress?): Boolean = when (job.phase) {
    OfflinePhase.preparing, OfflinePhase.verifying -> true
    OfflinePhase.transferring -> progress?.total?.takeIf { it > 0 } == null
    else -> false
}

/** Waiting for a password is a question, not a failure; the form asks it. */
internal fun offlineStatusIsError(job: OfflineJob): Boolean =
    (job.phase == OfflinePhase.failed && !job.waitingForPassword()) || job.phase == OfflinePhase.missing

internal fun offlineSubtitle(entry: OfflineEntry): String? {
    val labels = entry.labels
    val place = when {
        labels.seasonNumber != null && labels.episodeNumber != null -> "S${labels.seasonNumber} E${labels.episodeNumber}"
        else -> labels.year?.toString()
    }
    return listOfNotNull(labels.seriesName, place, offlinePresetLabel(entry.preset)).joinToString(" · ").ifEmpty { null }
}

internal fun offlineSections(library: OfflineLibrary, network: OfflineNetwork, tv: Boolean = false): OfflineSections {
    val manifest: OfflineManifest = library.manifest
    val rows = manifest.entries.sortedBy { it.createdSequence }.map { entry ->
        val live = library.progress[entry.id]
        OfflineRow(
            entry,
            entry.labels.title,
            offlineSubtitle(entry),
            offlineStatus(entry.job, live, manifest.wifiOnly, network, entry.committedBytes(), tv),
            live?.total?.takeIf {
                it > 0 && entry.job.phase == OfflinePhase.transferring
            }?.let { (live.received.toFloat() / it).coerceIn(0f, 1f) },
            offlineActions(entry.job, manifest.wifiOnly, network),
            entry.tracks.filter { !it.available }.map { "${it.label} (${it.type})" },
            offlineNotDownloadedSummary(entry.tracks),
            offlineIndeterminate(entry.job, live),
            offlineStatusIsError(entry.job)
        )
    }
    val needsAttention = setOf(OfflinePhase.failed, OfflinePhase.missing)
    return OfflineSections(
        ready = rows.filter { it.entry.job.phase == OfflinePhase.ready },
        active = rows.filter { it.entry.job.phase != OfflinePhase.ready && it.entry.job.phase !in needsAttention },
        failed = rows.filter { it.entry.job.phase in needsAttention },
        usedBytes = manifest.budget.usedBytes,
        capBytes = manifest.budget.capBytes
    )
}

/** Cap steps, offered up to what the device can actually hold. */
internal fun offlineCapSteps(totalBytes: Long?): List<Long> {
    val gib = 1024L * 1024 * 1024
    val steps = listOf(2L, 5L, 10L, 20L, 50L, 100L, 200L).map { it * gib }
    return if (totalBytes == null) steps else steps.filter { it <= totalBytes }.ifEmpty { listOf(totalBytes / 2) }
}

/** The whole preview for one download request: scope, quality, tracks and size. */
internal data class OfflinePreview(
    val title: String,
    val items: List<OfflineApiItem>,
    val choices: List<OfflinePresetChoice>,
    val policy: OfflinePolicy
)

internal sealed interface OfflinePreviewState {
    data object Loading : OfflinePreviewState

    data class Unavailable(val message: String) : OfflinePreviewState

    data class Ready(val preview: OfflinePreview) : OfflinePreviewState
}

/** Size text: exact only when every item's size is exact. */
internal fun offlineSizeText(items: List<OfflineApiItem>, preset: OfflinePreset, estimate: (OfflineApiItem) -> Long?): String {
    val selection = offlineSelect(items.map(OfflineApiItem::candidate), preset)
    val count = selection.itemIds.size
    val countText = if (count == 1) "1 item" else "$count items"
    if (preset == OfflinePreset.original) {
        return when {
            selection.unknownCount == count -> "$countText. Size not known."
            selection.unknownCount > 0 -> "$countText. At least ${offlineBytes(
                selection.knownBytes
            )}, ${selection.unknownCount} of unknown size."
            else -> "$countText, ${offlineBytes(selection.knownBytes)}"
        }
    }
    val known = items.mapNotNull(estimate)
    return if (known.isEmpty()) "$countText. Size not known." else "$countText, about ${offlineBytes(known.sum())} (estimate)"
}

internal fun offlinePreviewChoices(policy: OfflinePolicy, items: List<OfflineApiItem>): List<OfflinePresetChoice> {
    val first = items.firstOrNull() ?: return emptyList()
    // A season is as downloadable as each of its items; a mixed season still lists what it can.
    val all = items.map { offlinePresets(policy, it.candidate(), OFFLINE_QUALIFIED_PRESETS) }
    return offlinePresets(policy, first.candidate(), OFFLINE_QUALIFIED_PRESETS).map { choice ->
        val each = all.mapNotNull { list -> list.firstOrNull { it.preset == choice.preset } }
        val available = each.any { it.available }
        choice.copy(
            available = available,
            reason = if (available) null else each.firstOrNull()?.reason,
            estimateBytes = each.mapNotNull { it.estimateBytes }.takeIf { it.isNotEmpty() }?.sum(),
            exactSize = each.all { it.exactSize }
        )
    }.filter { it.preset != OfflinePreset.audio128 && it.preset != OfflinePreset.audio256 }
}

/** Loads what a preview needs; the failure text is what the viewer sees. */
internal suspend fun offlineLoadPreview(
    api: OfflineApi,
    account: Account,
    itemId: String,
    seriesId: String?,
    seasonId: String?,
    title: String
): OfflinePreviewState = try {
    val policy = api.policy(account)
    val items = if (seriesId != null &&
        seasonId != null
    ) {
        api.seasonEpisodes(account, seriesId, seasonId)
    } else {
        listOf(api.item(account, itemId))
    }
    if (items.isEmpty()) {
        OfflinePreviewState.Unavailable("Nothing here to download.")
    } else {
        OfflinePreviewState.Ready(OfflinePreview(title, items, offlinePreviewChoices(policy, items), policy))
    }
} catch (e: java.io.IOException) {
    OfflinePreviewState.Unavailable("Can't reach the server. Downloads need it to start; you can still watch what's already saved.")
}
