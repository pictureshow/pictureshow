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
    val notDownloaded: List<String>
)

internal data class OfflineSections(
    val ready: List<OfflineRow>,
    val active: List<OfflineRow>,
    val failed: List<OfflineRow>,
    val usedBytes: Long,
    val capBytes: Long?
) {
    val isEmpty: Boolean get() = ready.isEmpty() && active.isEmpty() && failed.isEmpty()
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
    "needs-password" -> "This server has Quick Connect off. Enter your password to download."
    "denied", "unauthorized" -> "The server no longer lets this account download it."
    "not-indexable", "no-body" -> "The server's copy couldn't be saved in a playable form."
    "length", "hash", "truncated", "overrun" -> "The file arrived damaged and couldn't be verified."
    "source-changed" -> "The file changed on the server during the download."
    "wrong-viewer" -> "The server answered for a different account."
    "revocation-pending" -> "Still cleaning up an old sign-in. Try again when the server is reachable."
    "network" -> "The connection kept dropping."
    null -> "The download stopped."
    else -> "The download stopped ($reason)."
}

internal fun offlineStatus(
    job: OfflineJob,
    progress: OfflineLiveProgress?,
    wifiOnly: Boolean,
    network: OfflineNetwork,
    committed: Long
): String = when (job.phase) {
    OfflinePhase.queued -> "Queued"
    OfflinePhase.waitingNetwork -> when {
        network == OfflineNetwork.metered && wifiOnly && !job.cellularAllowed -> "Waiting for Wi-Fi"
        else -> "Waiting for a connection"
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

internal fun offlineActions(job: OfflineJob, wifiOnly: Boolean): List<OfflineAction> = when (job.phase) {
    OfflinePhase.ready -> listOf(OfflineAction.Play, OfflineAction.Delete)
    OfflinePhase.paused -> listOf(OfflineAction.Resume, OfflineAction.Delete)
    OfflinePhase.failed -> listOf(OfflineAction.Retry, OfflineAction.Delete)
    OfflinePhase.missing -> listOf(OfflineAction.Redownload, OfflineAction.Delete)
    OfflinePhase.waitingNetwork -> listOfNotNull(
        OfflineAction.AllowCellular.takeIf { wifiOnly && !job.cellularAllowed },
        OfflineAction.Pause,
        OfflineAction.Delete
    )
    OfflinePhase.deleting -> emptyList()
    else -> listOf(OfflineAction.Pause, OfflineAction.Delete)
}

internal fun offlineSubtitle(entry: OfflineEntry): String? {
    val labels = entry.labels
    val place = when {
        labels.seasonNumber != null && labels.episodeNumber != null -> "S${labels.seasonNumber} E${labels.episodeNumber}"
        else -> labels.year?.toString()
    }
    return listOfNotNull(labels.seriesName, place, offlinePresetLabel(entry.preset)).joinToString(" · ").ifEmpty { null }
}

internal fun offlineSections(library: OfflineLibrary, network: OfflineNetwork): OfflineSections {
    val manifest: OfflineManifest = library.manifest
    val rows = manifest.entries.sortedBy { it.createdSequence }.map { entry ->
        val live = library.progress[entry.id]
        OfflineRow(
            entry,
            entry.labels.title,
            offlineSubtitle(entry),
            offlineStatus(entry.job, live, manifest.wifiOnly, network, entry.committedBytes()),
            live?.total?.takeIf { it > 0 && entry.job.phase == OfflinePhase.transferring }?.let { (live.received.toFloat() / it).coerceIn(0f, 1f) },
            offlineActions(entry.job, manifest.wifiOnly),
            entry.tracks.filter { !it.available }.map { "${it.label} (${it.type})" }
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
            selection.unknownCount > 0 -> "$countText. At least ${offlineBytes(selection.knownBytes)}, ${selection.unknownCount} of unknown size."
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
internal suspend fun offlineLoadPreview(api: OfflineApi, account: Account, itemId: String, seriesId: String?, seasonId: String?, title: String): OfflinePreviewState = try {
    val policy = api.policy(account)
    val items = if (seriesId != null && seasonId != null) api.seasonEpisodes(account, seriesId, seasonId) else listOf(api.item(account, itemId))
    if (items.isEmpty()) {
        OfflinePreviewState.Unavailable("Nothing here to download.")
    } else {
        OfflinePreviewState.Ready(OfflinePreview(title, items, offlinePreviewChoices(policy, items), policy))
    }
} catch (e: java.io.IOException) {
    OfflinePreviewState.Unavailable("Can't reach the server. Downloads need it to start; you can still watch what's already saved.")
}
