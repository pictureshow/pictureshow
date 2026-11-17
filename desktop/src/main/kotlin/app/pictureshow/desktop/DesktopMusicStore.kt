/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.imageUrl
import app.pictureshow.core.music.albumArtistLine
import app.pictureshow.core.music.trackArtistLine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a queue row, the mini player and Now playing show. A saved queue carries it, so a restored row needs no server. */
@Serializable
data class DesktopMusicTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artUrl: String? = null,
    val durationTicks: Long? = null
)

internal fun Account.desktopMusicTrack(item: MediaItem): DesktopMusicTrack = DesktopMusicTrack(
    id = item.id,
    title = item.name,
    artist = trackArtistLine(item) ?: albumArtistLine(item).ifBlank { null },
    album = item.album,
    albumId = item.albumId,
    artUrl = imageUrl(server, item, 512),
    durationTicks = item.runTimeTicks
)

@Serializable
internal data class DesktopMusicSnapshot(
    val queue: QueueState,
    val context: String? = null,
    val contextName: String? = null,
    val sequence: Long = 0,
    val tracks: List<DesktopMusicTrack> = emptyList()
)

@Serializable
internal data class DesktopMusicPosition(val entry: String?, val ticks: Long)

/**
 * One file per server and viewer (MUS-08), named by a hash of the account key so no name, server address or token is on
 * disk in a file name. Each write replaces the file whole through [atomicPrivateWrite].
 */
internal class DesktopMusicStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun name(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

    private fun queueFile(account: Account) = File(directory, name(account.key) + ".json")
    private fun positionFile(account: Account) = File(directory, name(account.key) + ".position")
    private val volumeFile get() = File(directory, "volume")

    suspend fun load(account: Account): DesktopMusicSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val saved = json.decodeFromString<DesktopMusicSnapshot>(queueFile(account).readText())
            val position = runCatching { json.decodeFromString<DesktopMusicPosition>(positionFile(account).readText()) }.getOrNull()
            if (position != null && position.entry == saved.queue.current) saved.copy(queue = saved.queue.copy(positionTicks = position.ticks)) else saved
        }.getOrNull()
    }

    suspend fun save(account: Account, snapshot: DesktopMusicSnapshot): Boolean = withContext(Dispatchers.IO) {
        runCatching { atomicPrivateWrite(queueFile(account), json.encodeToString(DesktopMusicSnapshot.serializer(), snapshot).toByteArray()) }.isSuccess
    }

    /** Written every few seconds, so it's separate from the queue, which can be two thousand rows. */
    suspend fun savePosition(account: Account, position: DesktopMusicPosition) {
        withContext(Dispatchers.IO) {
            runCatching { atomicPrivateWrite(positionFile(account), json.encodeToString(DesktopMusicPosition.serializer(), position).toByteArray()) }
        }
    }

    suspend fun delete(account: Account) {
        withContext(Dispatchers.IO) { queueFile(account).delete(); positionFile(account).delete() }
    }

    /** Removes the saved queue of every viewer not in [kept] (signed out or removed), including one removed while the app was closed. */
    suspend fun retain(kept: List<Account>) {
        withContext(Dispatchers.IO) {
            val names = kept.map { name(it.key) }.toSet()
            directory.listFiles()?.filter { it.name.endsWith(".json") || it.name.endsWith(".position") }
                ?.filter { it.name.substringBefore('.') !in names }?.forEach { it.delete() }
        }
    }

    fun volume(): Int = runCatching { volumeFile.readText().trim().toInt().coerceIn(0, 100) }.getOrDefault(80)
    fun saveVolume(value: Int) { runCatching { atomicPrivateWrite(volumeFile, value.coerceIn(0, 100).toString().toByteArray()) } }
}

/** A failure can't tell us what's gone, so only a real answer removes a saved row. */
internal fun desktopAccessibleAfter(ids: Set<String>, pages: List<ClientResult<List<String>>>): Set<String> {
    if (pages.any { it !is ClientResult.Success }) return ids
    return pages.flatMap { (it as ClientResult.Success).value }.toSet()
}
