/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable

@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
@Serializable
enum class OfflineCollectionKind { album, playlist }

/**
 * An album or playlist as it was when the viewer downloaded it (MUS-10): its tracks in the order the page showed, a song a playlist
 * holds twice listed twice. Each track is stored once, as its own entry, so an album and a playlist that share a song share its file.
 */
@Serializable
data class OfflineCollection(
    val id: String,
    val kind: OfflineCollectionKind,
    val title: String,
    val artist: String? = null,
    val itemIds: List<String> = emptyList(),
    val preset: OfflinePreset = OfflinePreset.original,
    val createdSequence: Long = 0
)

/** Adds a collection, or replaces the one with its id: downloading an album again takes a new snapshot of its order. */
fun OfflineManifest.withCollection(collection: OfflineCollection): OfflineManifest =
    copy(collections = collections.filter { it.id != collection.id } + collection)

private fun OfflineCollection.entryIds(): Set<String> = itemIds.map { offlineEntryId(it, preset) }.toSet()

/**
 * The entries a collection's removal deletes: its tracks', except any another collection still holds. Only entries that exist and
 * aren't already being removed.
 */
fun OfflineManifest.collectionRemoval(collectionId: String): List<String> {
    val target = collections.firstOrNull { it.id == collectionId } ?: return emptyList()
    val kept = collections.filter { it.id != collectionId }.flatMap { it.entryIds() }.toSet()
    val gone = target.entryIds() - kept
    return entries.filter { it.id in gone && it.job.phase != OfflinePhase.deleting }.map { it.id }
}

/** A collection none of whose tracks has an entry left (all removed) is gone too. */
fun OfflineManifest.prunedCollections(): OfflineManifest {
    val present = entries.filter { it.job.phase != OfflinePhase.deleting }.map { it.id }.toSet()
    val kept = collections.filter { collection -> collection.entryIds().any { it in present } }
    return if (kept.size == collections.size) this else copy(collections = kept)
}

/**
 * How far a collection's download has got, counted per stored track (a duplicate counts once). [absent] counts tracks with no
 * entry: removed one by one from Downloads, or never stored because the server refused them.
 */
data class OfflineCollectionProgress(
    val tracks: Int,
    val ready: Int,
    val transferring: Int,
    val waiting: Int,
    val paused: Int,
    val failed: Int,
    val absent: Int,
    val readyBytes: Long
) {
    /** "ready", "failed", "paused", "waiting" or "downloading", in that precedence once nothing is left to fetch. */
    val phase: String get() = when {
        ready == tracks - absent && ready > 0 -> "ready"
        transferring > 0 -> "downloading"
        waiting > 0 -> "waiting"
        paused > 0 -> "paused"
        failed > 0 -> "failed"
        else -> "downloading"
    }
}

fun OfflineManifest.collectionProgress(collectionId: String): OfflineCollectionProgress? {
    val collection = collections.firstOrNull { it.id == collectionId } ?: return null
    val byId = entries.filter { it.job.phase != OfflinePhase.deleting }.associateBy { it.id }
    val own = collection.itemIds.distinct().map { byId[offlineEntryId(it, collection.preset)] }
    val present = own.filterNotNull()
    if (present.isEmpty()) return null
    fun count(vararg phases: OfflinePhase) = present.count { it.job.phase in phases }
    return OfflineCollectionProgress(
        tracks = own.size,
        ready = count(OfflinePhase.ready),
        transferring = count(OfflinePhase.queued, OfflinePhase.preparing, OfflinePhase.transferring, OfflinePhase.verifying),
        waiting = count(OfflinePhase.waitingNetwork, OfflinePhase.waitingStorage),
        paused = count(OfflinePhase.paused),
        failed = count(OfflinePhase.failed, OfflinePhase.missing),
        absent = own.size - present.size,
        readyBytes = present.filter { it.job.phase == OfflinePhase.ready }.sumOf { it.committedBytes() }
    )
}

/** What plays offline: the collection's ready tracks in the snapshot's order, a duplicate kept where it stood. */
fun OfflineManifest.collectionPlayOrder(collectionId: String): List<String> {
    val collection = collections.firstOrNull { it.id == collectionId } ?: return emptyList()
    val ready = entries.filter { it.job.phase == OfflinePhase.ready }.map { it.id }.toSet()
    return collection.itemIds.filter { offlineEntryId(it, collection.preset) in ready }
}

/** The collection a downloaded track plays inside from Downloads: the newest one holding it, or none. */
fun OfflineManifest.collectionFor(itemId: String): OfflineCollection? =
    collections.filter { itemId in it.itemIds }.maxByOrNull { it.createdSequence }

/**
 * Whether a download of [bytes] (known sizes plus estimates) can start inside the limit, before anything is queued (OFF-06): null when
 * it fits or no limit is set yet (each chunk is checked against the limit the viewer picks), "cap" when it can't fit in what the
 * limit leaves after what's stored and reserved. Free disk is checked per chunk, as for video.
 */
fun OfflineBudget.refusal(bytes: Long): String? {
    val cap = capBytes ?: return null
    return if (bytes > cap - usedBytes - reservations.values.sum()) "cap" else null
}

/** What the limit leaves, never negative; null with no limit. */
fun OfflineBudget.roomBytes(): Long? = capBytes?.let { (it - usedBytes - reservations.values.sum()).coerceAtLeast(0) }
