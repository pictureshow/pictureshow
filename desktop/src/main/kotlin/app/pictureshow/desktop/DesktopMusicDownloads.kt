/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.music.albumArtistLine
import app.pictureshow.core.music.trackIsAvailable
import app.pictureshow.core.offline.OfflineCollection
import app.pictureshow.core.offline.OfflineCollectionKind
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.collectionFor
import app.pictureshow.core.offline.collectionPlayOrder
import app.pictureshow.core.offline.collectionProgress
import app.pictureshow.core.offline.offlineEntryId
import app.pictureshow.core.offline.offlineSelect
import app.pictureshow.core.offline.refusal
import app.pictureshow.core.offline.roomBytes
import app.pictureshow.core.playback.PlayDecision
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MUS-10 on desktop: albums and playlists download whole through the same engine, manifest, budget and outbox as video. A single
 * track downloads with its album or playlist, never alone (offline.md); an artist has no fixed track list, so no Download (MUS-02).
 */
internal fun desktopMusicDownloadKind(state: DesktopMusicDetailState): OfflineCollectionKind? = when {
    state.isAlbum -> OfflineCollectionKind.album
    state.isPlaylist -> OfflineCollectionKind.playlist
    else -> null
}

/** What an album or playlist page says about its download, in the Downloads page's words. */
internal data class DesktopMusicDownloadStatus(val text: String, val fraction: Float?, val error: Boolean, val ready: Boolean)

private fun tracks(n: Int) = if (n == 1) "1 track" else "$n tracks"

/** Null when nothing of [collectionId] is on this computer; a removal under way counts as gone. */
internal fun desktopMusicDownloadStatus(library: OfflineLibrary?, collectionId: String, network: OfflineNetwork): DesktopMusicDownloadStatus? {
    val manifest = library?.manifest ?: return null
    val progress = manifest.collectionProgress(collectionId) ?: return null
    val stored = progress.tracks - progress.absent
    val counted = if (progress.absent > 0) "${progress.ready} of ${tracks(progress.tracks)}" else tracks(progress.ready)
    val own = manifest.collections.firstOrNull { it.id == collectionId }?.let { collection ->
        val ids = collection.itemIds.map { offlineEntryId(it, collection.preset) }.toSet()
        manifest.entries.filter { it.id in ids }
    }.orEmpty()
    fun words(entry: OfflineEntry?) = entry?.let { offlineStatus(it.job, library.progress[it.id], manifest.wifiOnly, network, 0, false) }.orEmpty()
    val fraction = (progress.ready.toFloat() / stored.coerceAtLeast(1)).coerceIn(0f, 1f)
    val done = "${progress.ready} of ${tracks(stored)}"
    return when (progress.phase) {
        "ready" -> DesktopMusicDownloadStatus("Downloaded · $counted · ${offlineBytes(progress.readyBytes)}", null, false, true)
        "waiting" -> {
            val waiting = own.firstOrNull { it.job.phase == OfflinePhase.waitingNetwork || it.job.phase == OfflinePhase.waitingStorage }
            DesktopMusicDownloadStatus("$done downloaded. ${words(waiting)}", fraction, false, false)
        }
        "paused" -> {
            val byCap = own.any { it.job.phase == OfflinePhase.paused && it.job.pausedByCap }
            DesktopMusicDownloadStatus("Download paused" + (if (byCap) ": over your storage limit" else "") + " · $done", fraction, false, false)
        }
        "failed" -> {
            val failed = own.firstOrNull { it.job.phase == OfflinePhase.failed || it.job.phase == OfflinePhase.missing }
            DesktopMusicDownloadStatus("Download stopped · $done. ${words(failed)}", fraction, true, false)
        }
        else -> DesktopMusicDownloadStatus("Downloading · $done", fraction, false, false)
    }
}

/** A question the page is asking about a download, or what it has to say. Every question opens on Cancel. */
internal sealed interface DesktopMusicDownloadAsk {
    data object Checking : DesktopMusicDownloadAsk
    data class Confirm(val text: String, val items: List<DesktopOfflineItem>) : DesktopMusicDownloadAsk
    data class Notice(val text: String) : DesktopMusicDownloadAsk
    /** Remove (ready) or cancel (still coming) the whole album or playlist. */
    data class Remove(val text: String, val confirm: String) : DesktopMusicDownloadAsk
}

/**
 * What Download asks, from the server's answer: refused rights, nothing downloadable, too big for the limit, or the question with the
 * count, size and what's left out. Original quality only: converted audio hasn't passed its checks (OFF-02), so it isn't offered.
 */
internal fun desktopMusicDownloadQuestion(allowed: Boolean, items: List<DesktopOfflineItem>, library: OfflineLibrary?, name: String): DesktopMusicDownloadAsk {
    if (!allowed) return DesktopMusicDownloadAsk.Notice("Your account can't download from this server.")
    val selection = offlineSelect(items.map(DesktopOfflineItem::candidate), OfflinePreset.original)
    if (selection.itemIds.isEmpty()) return DesktopMusicDownloadAsk.Notice("None of these tracks can be downloaded.")
    val stored = library?.manifest?.entries.orEmpty().map { it.id }.toSet()
    // What's already here costs nothing more.
    val fresh = items.filter { it.id in selection.itemIds && offlineEntryId(it.id, OfflinePreset.original) !in stored }
    val needed = offlineSelect(fresh.map(DesktopOfflineItem::candidate), OfflinePreset.original).knownBytes
    val budget = library?.manifest?.budget
    if (budget?.refusal(needed) != null) {
        return DesktopMusicDownloadAsk.Notice(
            "$name needs about ${offlineBytes(needed)}, and your download limit has ${offlineBytes(budget.roomBytes() ?: 0)} left. " +
                "Remove downloads or raise the limit in Downloads, then try again."
        )
    }
    val count = selection.itemIds.size
    val size = when {
        selection.unknownCount == count -> "size not known"
        selection.estimated -> "about ${offlineBytes(selection.knownBytes)}"
        else -> offlineBytes(selection.knownBytes)
    }
    val skipped = selection.skipped.size.takeIf { it > 0 }?.let { " ${tracks(it)} can't be downloaded and will stream as usual." }.orEmpty()
    return DesktopMusicDownloadAsk.Confirm("Download ${tracks(count)} ($size) in their original quality?$skipped", items)
}

/** One album or playlist page's download: asks, starts and removes. Owns its question as a StateFlow the page draws. */
internal class DesktopMusicDownloader(
    private val account: Account,
    private val engine: DesktopOfflineEngine,
    private val api: DesktopOfflineApi,
    private val scope: CoroutineScope
) {
    private val mutable = MutableStateFlow<DesktopMusicDownloadAsk?>(null)
    val ask: StateFlow<DesktopMusicDownloadAsk?> = mutable.asStateFlow()
    private var kind: OfflineCollectionKind? = null
    private var name = ""
    private var id = ""
    private var artist: String? = null
    private var order: List<String> = emptyList()

    /** Reads the viewer's rights and every track's size, then asks with the count and size, or says why it can't (OFF-01, OFF-06). */
    fun request(state: DesktopMusicDetailState) {
        val collectionKind = desktopMusicDownloadKind(state) ?: return
        if (mutable.value == DesktopMusicDownloadAsk.Checking) return
        kind = collectionKind
        name = state.item.name
        id = state.item.id
        artist = albumArtistLine(state.item).ifBlank { null }
        order = state.tracks.filter(::trackIsAvailable).map(MediaItem::id)
        mutable.value = DesktopMusicDownloadAsk.Checking
        scope.launch {
            mutable.value = try {
                val policy = api.policy(account)
                val items = api.items(account, order)
                desktopMusicDownloadQuestion(policy.enableDownloads && policy.enablePlayback, items, engine.libraries.value[account.key], name)
            } catch (e: IOException) {
                DesktopMusicDownloadAsk.Notice("Can't reach the server. Downloads need it to start; what's already saved still plays.")
            }
        }
    }

    fun start(items: List<DesktopOfflineItem>) {
        val collectionKind = kind ?: return
        mutable.value = null
        val wanted = offlineSelect(items.map(DesktopOfflineItem::candidate), OfflinePreset.original).itemIds.toSet()
        val collection = OfflineCollection(id, collectionKind, name, artist, order.filter { it in wanted }, OfflinePreset.original)
        scope.launch {
            // Desktop has no metered detection, so a music download is never held for "cellular".
            val started = engine.enqueueCollection(account, collection, items.filter { it.id in wanted }, cellular = true)
            if (!started) mutable.value = DesktopMusicDownloadAsk.Notice(
                "Downloads can't start until the download list on this computer can be read. Open Downloads to see why."
            )
        }
    }

    fun askRemove(state: DesktopMusicDetailState, ready: Boolean) {
        id = state.item.id
        mutable.value = if (ready) {
            DesktopMusicDownloadAsk.Remove("Remove ${state.item.name} from this computer? You can still stream it from your server.", "Remove")
        } else {
            DesktopMusicDownloadAsk.Remove("Stop downloading ${state.item.name} and remove what's saved so far?", "Stop download")
        }
    }

    fun remove() {
        mutable.value = null
        val target = id
        scope.launch { engine.deleteCollection(account.key, target) }
    }

    fun dismiss() { mutable.value = null }
}

/** The ready download of a track, if this viewer has one: any preset, Original first. */
internal fun OfflineLibrary.readyTrack(media: String): OfflineEntry? = manifest.entries
    .filter { it.itemId == media && it.kind == OfflineMediaKind.audio && it.job.phase == OfflinePhase.ready }
    .minByOrNull { if (it.preset == OfflinePreset.original) 0 else 1 }

/** The saved artwork of an entry (a track saves its album's cover), when it has one. */
internal fun DesktopOfflineEngine.artFile(key: String, entry: OfflineEntry): File? =
    entry.resources.firstOrNull { it.role == OfflineResourceRole.artwork && it.complete }?.let { resourceFile(key, entry, it) }?.takeIf { it.isFile }

/** A downloaded track as the queue shows it, with no server: art from the saved file. */
internal fun OfflineEntry.desktopMusicTrack(art: File?): DesktopMusicTrack = DesktopMusicTrack(
    id = itemId,
    title = labels.title,
    artist = labels.artist,
    album = labels.album,
    albumId = labels.albumId,
    artUrl = art?.toURI()?.toString(),
    durationTicks = runtimeTicks
)

/**
 * Downloads' Play on a music row: the album or playlist it came with, ready tracks in their saved order, starting at this one (MUS-10).
 * A track no collection holds plays alone. Null when it isn't a ready track or the collection has tracks not yet here.
 */
internal fun desktopOfflineMusicPlay(
    account: Account,
    library: OfflineLibrary?,
    entry: OfflineEntry,
    /** The album or playlist row it was played from; otherwise the newest one holding the track. */
    collectionId: String? = null,
    art: (OfflineEntry) -> File? = { null }
): DesktopMusicPlay? {
    if (entry.kind != OfflineMediaKind.audio) return null
    val manifest = library?.manifest ?: return null
    val ready = manifest.entries.filter { it.kind == OfflineMediaKind.audio && it.job.phase == OfflinePhase.ready }
    val collection = collectionId?.let { id -> manifest.collections.firstOrNull { it.id == id && entry.itemId in it.itemIds } }
        ?: manifest.collectionFor(entry.itemId)
    val byItem = ready.filter { it.preset == (collection?.preset ?: entry.preset) }.associateBy { it.itemId }
    val order = collection?.let { manifest.collectionPlayOrder(it.id) } ?: listOf(entry.itemId)
    val tracks = order.mapNotNull { byItem[it]?.let { e -> e.desktopMusicTrack(art(e)) } }
    val start = order.indexOf(entry.itemId).takeIf { it >= 0 } ?: return null
    if (tracks.size != order.size) return null
    val context = collection?.let { (if (it.kind == OfflineCollectionKind.album) "MusicAlbum:" else "Playlist:") + it.id }
    return DesktopMusicPlay(account.key, tracks, start, context, collection?.title ?: entry.labels.title, shuffle = false)
}

/** The host reports a natural end at the track's length; a second short still counts, as rounding lands either side. */
internal fun desktopMusicListenFinished(ticks: Long, runtimeTicks: Long?): Boolean =
    runtimeTicks != null && runtimeTicks > 0 && ticks >= runtimeTicks - 10_000_000L

/** The music player's view of the download engine: files to play and the outbox their listens go to (stream B's hook). */
internal class DesktopEngineMusicLocal(private val engine: DesktopOfflineEngine) : DesktopMusicLocal {
    private val sequences = ConcurrentHashMap<Long, Long>()

    override fun decision(account: Account, media: String): PlayDecision? {
        val entry = engine.libraries.value[account.key]?.readyTrack(media) ?: return null
        val resource = entry.resources.firstOrNull { it.role == OfflineResourceRole.media && it.complete } ?: return null
        val file = engine.resourceFile(account.key, entry, resource).takeIf { it.isFile } ?: return null
        return desktopLocalDecision(entry, file) { path -> engine.files.resource(account.key, entry.id, path) }
    }

    override fun downloaded(account: Account): Set<String> = engine.libraries.value[account.key]?.manifest?.entries.orEmpty()
        .filter { it.kind == OfflineMediaKind.audio && it.job.phase == OfflinePhase.ready }
        .map { it.itemId }
        .toSet()

    override suspend fun report(account: Account, media: String, serial: Long, report: String, ticks: Long, runtimeTicks: Long?) {
        val store = engine.progressStore(account)
        val sequence = sequences.getOrPut(serial) { store.newSequence() }
        when (report) {
            "start" -> Unit
            "progress" -> store.viewing(media, sequence, ticks)
            else -> {
                sequences.remove(serial)
                // A listen that reached the end counts as played; one cut short keeps its place, as video does.
                if (desktopMusicListenFinished(ticks, runtimeTicks)) store.completed(media, sequence) else store.viewing(media, sequence, ticks)
                engine.syncSoon()
            }
        }
    }
}
