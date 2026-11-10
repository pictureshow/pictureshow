/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.util.AtomicFile
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.imageUrl
import app.pictureshow.core.music.albumArtistLine
import app.pictureshow.core.music.trackArtistLine
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a queue row and the OS controls show. A saved queue carries it, so a restored row needs no server. */
@Serializable
internal data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artUrl: String? = null,
    val durationTicks: Long? = null
)

internal fun Account.musicTrack(item: MediaItem): MusicTrack = MusicTrack(
    id = item.id,
    title = item.name,
    artist = trackArtistLine(item) ?: albumArtistLine(item).ifBlank { null },
    album = item.album,
    albumId = item.albumId,
    artUrl = imageUrl(server, item, 512),
    durationTicks = item.runTimeTicks
)

@Serializable
internal data class MusicSnapshot(
    val queue: QueueState,
    val context: String? = null,
    val contextName: String? = null,
    val sequence: Long = 0,
    val tracks: List<MusicTrack> = emptyList()
)

@Serializable
internal data class MusicPosition(val entry: String?, val ticks: Long)

/** One file per server and viewer, so nobody restores another viewer's queue. Each write replaces the file whole. */
internal class MusicStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val directory = File(context.applicationContext.filesDir, "music")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun name(account: Account): String = MessageDigest.getInstance("SHA-256")
        .digest(account.key.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

    private fun queueFile(account: Account) = AtomicFile(File(directory, name(account) + ".json"))

    private fun positionFile(account: Account) = AtomicFile(File(directory, name(account) + ".position"))

    private fun write(file: AtomicFile, text: String) {
        directory.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    suspend fun load(account: Account): MusicSnapshot? = withContext(io) {
        runCatching {
            val saved = json.decodeFromString<MusicSnapshot>(queueFile(account).readFully().decodeToString())
            val position = runCatching {
                json.decodeFromString<MusicPosition>(positionFile(account).readFully().decodeToString())
            }.getOrNull()
            if (position != null && position.entry == saved.queue.current) {
                saved.copy(queue = saved.queue.copy(positionTicks = position.ticks))
            } else {
                saved
            }
        }.getOrNull()
    }

    suspend fun save(account: Account, snapshot: MusicSnapshot): Boolean = withContext(io) {
        runCatching { write(queueFile(account), json.encodeToString(snapshot)) }
            .onFailure { android.util.Log.w("MusicStore", "Couldn't save the queue", it) }
            .isSuccess
    }

    /** Written every few seconds, so it's separate from the queue, which can be two thousand rows. */
    suspend fun savePosition(account: Account, position: MusicPosition) {
        withContext(io) { runCatching { write(positionFile(account), json.encodeToString(position)) } }
    }

    suspend fun delete(account: Account) {
        withContext(io) {
            queueFile(account).delete()
            positionFile(account).delete()
        }
    }

    /** Removes the saved queue of every viewer not in [kept], including one removed while this app wasn't running. */
    suspend fun retain(kept: List<Account>) {
        withContext(io) {
            val names = kept.map(::name).toSet()
            directory.listFiles()?.filter { it.name.substringBefore('.') !in names }?.forEach { it.delete() }
        }
    }
}

/** Failure can't tell us what's gone, so only a real answer removes a saved row. */
internal fun accessibleAfter(ids: Set<String>, pages: List<ClientResult<List<String>>>): Set<String> {
    if (pages.any { it !is ClientResult.Success }) return ids
    return pages.flatMap { (it as ClientResult.Success).value }.toSet()
}
