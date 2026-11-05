/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

const val OFFLINE_MANIFEST_SCHEMA = 1

/** Suffix of a resource's in-progress file: `<entry dir>/<resource path>.part`. */
const val OFFLINE_PART_SUFFIX = ".part"

@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
@Serializable
enum class OfflinePreset { original, video720, video1080, audio128, audio256 }

/** audio arrives with music; no music UI here. */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
@Serializable
enum class OfflineMediaKind { movie, episode, audio }

@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
@Serializable
enum class OfflineResourceRole { media, audio, subtitle, artwork }

/** path is relative, under the entry. */
@Serializable
data class OfflineResource(
    val id: String,
    val role: OfflineResourceRole,
    val path: String,
    val required: Boolean = true,
    val serverStreamIndex: Int? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
    val complete: Boolean = false,
    val validator: OfflineValidator? = null,
    val mimeType: String? = null
)

/** type: "audio"|"subtitle". resourceId null = embedded in media. */
@Serializable
data class OfflineTrack(
    val serverIndex: Int,
    val type: String,
    val label: String,
    val language: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val codec: String? = null,
    val resourceId: String? = null,
    val available: Boolean = true
)

@Serializable
data class OfflineLabels(
    val title: String,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val album: String? = null,
    val artist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val overview: String? = null
)

@Serializable
data class OfflineEntry(
    val id: String,
    val itemId: String,
    val kind: OfflineMediaKind,
    val sourceId: String? = null,
    val sourceETag: String? = null,
    val container: String? = null,
    val preset: OfflinePreset,
    val runtimeTicks: Long? = null,
    val labels: OfflineLabels,
    val resources: List<OfflineResource> = emptyList(),
    val tracks: List<OfflineTrack> = emptyList(),
    val job: OfflineJob = OfflineJob(),
    val createdSequence: Long = 0,
    val warning: String? = null,
    val pinned: Boolean = true
)

/** pendingByteCleanup holds entry ids whose bytes still have to go. */
@Serializable
data class OfflineManifest(
    val schema: Int = OFFLINE_MANIFEST_SCHEMA,
    val entries: List<OfflineEntry> = emptyList(),
    val nextSequence: Long = 1,
    val budget: OfflineBudget = OfflineBudget(),
    val wifiOnly: Boolean = true,
    val autoCleanWatched: Boolean = false,
    val pendingByteCleanup: List<String> = emptyList()
)

data class OfflineManifestResult(
    val manifest: OfflineManifest,
    val ok: Boolean = true,
    val reason: String? = null,
    val deletePaths: List<String> = emptyList(),
    val changedEntries: List<String> = emptyList()
)

/** "<itemId>:<preset>", stable. Its directory name is [offlineEntryDir]. */
fun offlineEntryId(itemId: String, preset: OfflinePreset): String = "$itemId:${preset.name}"

/** The entry's directory name: every character outside [A-Za-z0-9_.-] becomes "_"; an all-dots name can't climb out of the root. */
fun offlineEntryDir(entryId: String): String {
    val name = entryId.map { if (it.isLetterOrDigit() && it.code < 128 || it in "_.-") it else '_' }.joinToString("")
    return if (name.isEmpty() || name.all { it == '.' }) "_".repeat(name.length.coerceAtLeast(1)) else name
}

/** Path safety: relative, no "..", no leading "/", no backslash, no NUL. Everything the app deletes passes this first. */
fun offlineSafePath(path: String): Boolean {
    if (path.isEmpty() || path.startsWith("/") || '\\' in path || '\u0000' in path) return false
    if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) return false
    return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

private fun OfflineResource.neededForReady() = required || role == OfflineResourceRole.media

/** Why Ready may not commit, in a fixed order. Empty list = may commit. */
fun offlineReadyBlockers(entry: OfflineEntry): List<String> {
    val blockers = mutableListOf<String>()
    if (entry.job.phase != OfflinePhase.verifying) blockers += "job-phase"
    if (entry.resources.none { it.role == OfflineResourceRole.media }) blockers += "no-media"
    entry.resources.filter { it.neededForReady() }.forEach { resource ->
        when {
            !resource.complete -> blockers += "incomplete:${resource.id}"
            resource.bytes == null || resource.sha256 == null -> blockers += "unverified:${resource.id}"
        }
    }
    entry.resources.filter { !offlineSafePath(it.path) }.forEach { blockers += "unsafe:${it.id}" }
    if (entry.labels.title.isBlank()) blockers += "no-title"
    return blockers
}

/** The one durable step to Ready, through reduceOfflineJob `committed`. Blocked or stale: unchanged, with the reason (blockers comma-joined). */
fun OfflineManifest.commitReady(entryId: String, attempt: Long): OfflineManifestResult {
    val entry = entries.firstOrNull { it.id == entryId } ?: return OfflineManifestResult(this, ok = false, reason = "no-entry")
    val blockers = offlineReadyBlockers(entry)
    if (blockers.isNotEmpty()) return OfflineManifestResult(this, ok = false, reason = blockers.joinToString(","))
    val reduced = reduceOfflineJob(entry.job, OfflineJobEvent("committed", attempt = attempt))
    val job = reduced.job
    if (reduced.ignored != null || job == null) return OfflineManifestResult(this, ok = false, reason = reduced.ignored)
    return OfflineManifestResult(
        copy(entries = entries.map { if (it.id == entryId) it.copy(job = job) else it }),
        changedEntries = listOf(entryId)
    )
}

private fun OfflineEntry.reconciled(inventory: Map<String, Long>): OfflineEntry {
    val dir = offlineEntryDir(id)
    fun onDisk(resource: OfflineResource): Boolean {
        val size = (if (offlineSafePath(resource.path)) inventory["$dir/${resource.path}"] else null) ?: return false
        return resource.bytes == null || resource.bytes == size
    }
    val nextJob = when {
        job.phase == OfflinePhase.ready && resources.any { it.neededForReady() && !onDisk(it) } ->
            reduceOfflineJob(job, OfflineJobEvent("missing")).job
        job.phase in setOf(OfflinePhase.preparing, OfflinePhase.transferring, OfflinePhase.verifying) ->
            reduceOfflineJob(job, OfflineJobEvent("process-restart")).job
        else -> job
    } ?: job
    return copy(job = nextJob, resources = resources.map { if (it.complete && !onDisk(it)) it.copy(complete = false) else it })
}

/**
 * Startup and upgrade reconcile. inventory: path relative to the account's offline root ("<offlineEntryDir>/<resource path>") -> size.
 * - Ready whose required file is missing or the wrong size -> missing. A running phase -> process-restart. A resource no longer intact on
 *   disk -> complete=false; a `.part` alone counts as not intact.
 * - Everything under a non-Ready entry's directory stays; under a Ready entry only its resources and `.part` files stay.
 * - A file no entry owns -> deletePaths, sorted, only safe paths. A file directly in the root isn't in any entry's shape: it is neither
 *   listed nor counted, so the caller's own files there are safe.
 * - budget.usedBytes becomes the sum of the kept entries' files and reservations clear: no attempt survives a restart.
 * - A pendingByteCleanup id with no files left drops off the list.
 */
fun OfflineManifest.reconcile(inventory: Map<String, Long>): OfflineManifestResult {
    val nextEntries = entries.map { it.reconciled(inventory) }
    val byDir = nextEntries.associateBy { offlineEntryDir(it.id) }
    val deletePaths = mutableListOf<String>()
    var kept = 0L
    for ((path, size) in inventory) {
        val slash = path.indexOf('/')
        if (slash < 0 || !offlineSafePath(path)) continue
        val entry = byDir[path.substring(0, slash)]
        val rest = path.substring(slash + 1)
        val owned = entry != null &&
            (entry.job.phase != OfflinePhase.ready || rest.endsWith(OFFLINE_PART_SUFFIX) || entry.resources.any { it.path == rest })
        if (owned) kept += size else deletePaths += path
    }
    val remainingDirs = inventory.keys.map { it.substringBefore('/', "") }.toSet()
    return OfflineManifestResult(
        copy(
            entries = nextEntries,
            budget = budget.copy(usedBytes = kept, reservations = emptyMap()),
            pendingByteCleanup = pendingByteCleanup.filter { offlineEntryDir(it) in remainingDirs }
        ),
        deletePaths = deletePaths.sorted(),
        changedEntries = entries.indices.filter { entries[it] != nextEntries[it] }.map { entries[it].id }
    )
}

private val MANIFEST_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Decode any schema. Current passes; a missing `schema` counts as 0 and is upgraded (schema 0 had no `pendingByteCleanup` and no
 * `autoCleanWatched`); a schema newer than this client returns null, and the caller keeps the file and shows a recoverable state.
 * Corrupt JSON: null. Never throws.
 */
fun offlineDecodeManifest(text: String): OfflineManifest? = try {
    val root = MANIFEST_JSON.parseToJsonElement(text) as? JsonObject
    val schema = when (val value = root?.get("schema")) {
        null -> if (root == null) null else 0
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) null else value.intOrNull
        else -> null
    }
    if (root == null || schema == null || schema < 0 || schema > OFFLINE_MANIFEST_SCHEMA) {
        null
    } else {
        MANIFEST_JSON.decodeFromJsonElement<OfflineManifest>(root).copy(schema = OFFLINE_MANIFEST_SCHEMA)
    }
} catch (_: IllegalArgumentException) {
    null
}

fun offlineEncodeManifest(manifest: OfflineManifest): String = MANIFEST_JSON.encodeToString(OfflineManifest.serializer(), manifest)

/** What a viewer's track switch does offline: "local" (embedded or its resource is complete) or "reconnect". Off (null) is always local. */
fun OfflineEntry.trackSwitch(type: String, serverIndex: Int?): String {
    if (serverIndex == null) return "local"
    val track = tracks.firstOrNull { it.type == type && it.serverIndex == serverIndex } ?: return "reconnect"
    if (!track.available) return "reconnect"
    val resourceId = track.resourceId ?: return "local"
    return if (resources.any { it.id == resourceId && it.complete }) "local" else "reconnect"
}

/** Auto-clean preview: exact titles and bytes; empty when no entry qualifies. Pinned entries appear only because the viewer asked via it. */
data class OfflineCleanPreview(val entryIds: List<String>, val titles: List<String>, val bytes: Long)

fun OfflineManifest.watchedPreview(played: Set<String>): OfflineCleanPreview {
    val qualified = entries.filter { it.job.phase == OfflinePhase.ready && it.itemId in played }
    return OfflineCleanPreview(qualified.map { it.id }, qualified.map { it.labels.title }, qualified.sumOf { it.committedBytes() })
}

/** Sum of the resources' known bytes. */
fun OfflineEntry.committedBytes(): Long = resources.sumOf { it.bytes ?: 0L }
