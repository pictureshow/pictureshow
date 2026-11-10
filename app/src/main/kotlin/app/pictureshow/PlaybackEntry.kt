/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import kotlinx.coroutines.CancellationException

internal const val PLAY_ACTION = "app.pictureshow.action.PLAY"
internal const val ENTRY_UNAVAILABLE = "This item is unavailable for the current account."
internal const val ENTRY_INVALID = "Could not open this playback request."

internal data class PlaybackEntry(val itemId: String, val positionMs: Long? = null)

internal sealed interface ParsedPlaybackEntry {
    data object Ignore : ParsedPlaybackEntry
    data object Invalid : ParsedPlaybackEntry
    data class Play(val entry: PlaybackEntry) : ParsedPlaybackEntry
}

/** External input supplies an item identity, never a stream URL, server or credential. */
internal fun parsePlaybackEntry(intent: Intent): ParsedPlaybackEntry = try {
    when (intent.action) {
        PLAY_ACTION -> {
            val extras = intent.extras

            @Suppress("DEPRECATION")
            val id = extras?.get("itemId")

            @Suppress("DEPRECATION")
            val position = extras?.get("positionMs")
            if (intent.data != null ||
                extras == null ||
                extras.keySet().any { it !in setOf("itemId", "positionMs") } ||
                id !is String ||
                (extras.containsKey("positionMs") && position !is Long)
            ) {
                ParsedPlaybackEntry.Invalid
            } else {
                validatedEntry(id, position as Long?)
            }
        }
        Intent.ACTION_VIEW -> parsePlaybackUri(intent.data)
        else -> ParsedPlaybackEntry.Ignore
    }
} catch (_: RuntimeException) {
    // Includes malformed parcelables and invalid URI encodings. Nothing supplied by another app is logged.
    ParsedPlaybackEntry.Invalid
}

private fun parsePlaybackUri(uri: Uri?): ParsedPlaybackEntry {
    if (uri?.scheme != "pictureshow") return ParsedPlaybackEntry.Ignore
    if (!uri.isHierarchical ||
        uri.host != "play" ||
        uri.encodedAuthority != "play" ||
        uri.fragment != null ||
        uri.pathSegments.size != 1 ||
        uri.queryParameterNames.any { it != "positionMs" }
    ) {
        return ParsedPlaybackEntry.Invalid
    }
    val positions = uri.getQueryParameters("positionMs")
    if (positions.size > 1) return ParsedPlaybackEntry.Invalid
    val position = positions.singleOrNull()?.let {
        if (!it.matches(Regex("[0-9]+"))) return ParsedPlaybackEntry.Invalid
        it.toLongOrNull() ?: return ParsedPlaybackEntry.Invalid
    }
    return validatedEntry(uri.pathSegments.single(), position)
}

private fun validatedEntry(id: String, positionMs: Long?): ParsedPlaybackEntry =
    if (!id.matches(Regex("[a-fA-F0-9]{32}")) || positionMs != null && positionMs !in 0..Long.MAX_VALUE / 10_000) {
        ParsedPlaybackEntry.Invalid
    } else {
        ParsedPlaybackEntry.Play(PlaybackEntry(id.lowercase(), positionMs))
    }

internal sealed interface ResolvedPlaybackEntry {
    data object NormalStartup : ResolvedPlaybackEntry
    data object Unavailable : ResolvedPlaybackEntry
    data class Open(val item: MediaItem, val positionMs: Long, val syncOpen: SyncOpen? = null) : ResolvedPlaybackEntry
}

private const val SYNC_OPEN_GENERATION = "sync.open.generation"
private const val SYNC_OPEN_ENTRY = "sync.open.entry"
private const val SYNC_OPEN_ITEM = "sync.open.item"
private const val SYNC_OPEN_TICKS = "sync.open.ticks"

/** Internal destination state preserves the server entry without exposing a new external route parameter. */
internal fun SavedStateHandle.saveSyncOpen(opening: SyncOpen?) {
    if (opening == null) {
        for (key in listOf(SYNC_OPEN_GENERATION, SYNC_OPEN_ENTRY, SYNC_OPEN_ITEM, SYNC_OPEN_TICKS)) remove<Any>(key)
        return
    }
    this[SYNC_OPEN_GENERATION] = opening.generation
    this[SYNC_OPEN_ENTRY] = opening.entryId
    this[SYNC_OPEN_ITEM] = opening.itemId
    this[SYNC_OPEN_TICKS] = opening.ticks
}

internal fun SavedStateHandle.syncOpen(): SyncOpen? {
    val generation = (get<Any>(SYNC_OPEN_GENERATION) as? Long)?.takeIf { it >= 0 } ?: return null
    val entry = (get<Any>(SYNC_OPEN_ENTRY) as? String)?.takeIf { it.isNotBlank() } ?: return null
    val item = (get<Any>(SYNC_OPEN_ITEM) as? String)?.takeIf { it.isNotBlank() } ?: return null
    val ticks = (get<Any>(SYNC_OPEN_TICKS) as? Long)?.takeIf { it >= 0 } ?: return null
    return SyncOpen(generation, entry, item, ticks)
}

/** Resolve with the selected account's authenticated catalog before constructing any player destination. */
internal suspend fun resolvePlaybackEntry(entry: PlaybackEntry, account: Account?, catalog: CatalogSession): ResolvedPlaybackEntry {
    if (account == null || account.expired) return ResolvedPlaybackEntry.NormalStartup
    val result = try {
        catalog.call(account) { item(account, entry.itemId) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return ResolvedPlaybackEntry.Unavailable
    }
    val item = (result as? ClientResult.Success)?.value ?: return ResolvedPlaybackEntry.Unavailable
    if (!item.id.equals(entry.itemId, ignoreCase = true) || item.type !in setOf("Movie", "Episode") || item.isFolder) {
        return ResolvedPlaybackEntry.Unavailable
    }
    return ResolvedPlaybackEntry.Open(
        item,
        app.pictureshow.core.resumeStartPositionMs(item.userData.playbackPositionTicks, explicitPositionMs = entry.positionMs)
    )
}
