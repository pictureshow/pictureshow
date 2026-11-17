/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Albums and playlists download whole; an artist is a moving target with no fixed list to keep, so it has no Download (MUS-02). */
internal fun musicDownloadKind(state: MusicDetailState): OfflineCollectionKind? = when {
    state.isAlbum -> OfflineCollectionKind.album
    state.isPlaylist -> OfflineCollectionKind.playlist
    else -> null
}

/** What an album or playlist page says about its download, in the Downloads screen's words. */
internal data class MusicDownloadStatus(
    val text: String,
    /** Tracks done of tracks to fetch, while there's something left to fetch. */
    val fraction: Float?,
    val error: Boolean,
    val ready: Boolean
)

private fun tracks(n: Int) = if (n == 1) "1 track" else "$n tracks"

/** Null when nothing of [collectionId] is on this device; a removal under way counts as gone. */
internal fun musicDownloadStatus(
    library: OfflineLibrary?,
    collectionId: String,
    network: OfflineNetwork,
    tv: Boolean
): MusicDownloadStatus? {
    val manifest = library?.manifest ?: return null
    val progress = manifest.collectionProgress(collectionId) ?: return null
    val stored = progress.tracks - progress.absent
    val counted = if (progress.absent > 0) "${progress.ready} of ${tracks(progress.tracks)}" else tracks(progress.ready)
    val own = manifest.collections.firstOrNull { it.id == collectionId }?.let { collection ->
        val ids = collection.itemIds.map { offlineEntryId(it, collection.preset) }.toSet()
        manifest.entries.filter { it.id in ids }
    }.orEmpty()
    fun words(entry: OfflineEntry?) = entry?.let {
        offlineStatus(it.job, library.progress[it.id], manifest.wifiOnly, network, 0, tv)
    }
    val fraction = (progress.ready.toFloat() / stored.coerceAtLeast(1)).coerceIn(0f, 1f)
    val done = "${progress.ready} of ${tracks(stored)}"
    return when (progress.phase) {
        "ready" -> MusicDownloadStatus("Downloaded · $counted · ${offlineBytes(progress.readyBytes)}", null, false, true)
        "waiting" -> {
            val waiting = own.firstOrNull { it.job.phase == OfflinePhase.waitingNetwork || it.job.phase == OfflinePhase.waitingStorage }
            MusicDownloadStatus("$done downloaded. ${words(waiting)}", fraction, false, false)
        }
        "paused" -> {
            val byCap = own.any { it.job.phase == OfflinePhase.paused && it.job.pausedByCap }
            MusicDownloadStatus("Download paused" + (if (byCap) ": over your storage limit" else "") + " · $done", fraction, false, false)
        }
        "failed" -> {
            val failed = own.firstOrNull { it.job.phase == OfflinePhase.failed || it.job.phase == OfflinePhase.missing }
            MusicDownloadStatus("Download stopped · $done. ${words(failed)}", fraction, true, false)
        }
        else -> MusicDownloadStatus("Downloading · $done", fraction, false, false)
    }
}

/** A question the page is asking about a download, or what it has to say. Every question opens on Cancel. */
internal sealed interface MusicDownloadAsk {
    data object Checking : MusicDownloadAsk

    data class Confirm(val text: String, val items: List<OfflineApiItem>) : MusicDownloadAsk

    data class Notice(val text: String) : MusicDownloadAsk

    /** Remove (ready) or cancel (still coming) the whole album or playlist. */
    data class Remove(val text: String, val confirm: String) : MusicDownloadAsk
}

/** One page's download: asks, starts and removes. TV and touch share it, so they can't drift on what gets downloaded. */
internal class MusicDownloadController(
    private val account: Account,
    private val engine: OfflineEngine,
    private val api: suspend () -> OfflineApi,
    private val scope: CoroutineScope
) {
    var ask by mutableStateOf<MusicDownloadAsk?>(null)
    private var kind: OfflineCollectionKind? = null
    private var name: String = ""
    private var id: String = ""
    private var artist: String? = null
    private var order: List<String> = emptyList()

    /** Reads the viewer's rights and every track's size, then asks with the count and size, or says why it can't (OFF-01, OFF-06). */
    fun request(state: MusicDetailState) {
        val collectionKind = musicDownloadKind(state) ?: return
        if (ask == MusicDownloadAsk.Checking) return
        kind = collectionKind
        name = state.item.name
        id = state.item.id
        artist = albumArtistLine(state.item).ifBlank { null }
        order = state.tracks.filter(::trackIsAvailable).map(MediaItem::id)
        ask = MusicDownloadAsk.Checking
        scope.launch {
            ask = try {
                val client = api()
                val policy = client.policy(account)
                val items = client.items(account, order)
                musicDownloadQuestion(policy.enableDownloads && policy.enablePlayback, items, engine.libraries.value[account.key], name)
            } catch (e: IOException) {
                MusicDownloadAsk.Notice("Can't reach the server. Downloads need it to start; what's already saved still plays.")
            }
        }
    }

    fun start(items: List<OfflineApiItem>, onStarted: () -> Unit = {}) {
        val collectionKind = kind ?: return
        ask = null
        val wanted = offlineSelect(items.map(OfflineApiItem::candidate), OfflinePreset.original).itemIds.toSet()
        val collection = OfflineCollection(id, collectionKind, name, artist, order.filter { it in wanted }, OfflinePreset.original)
        scope.launch {
            val started = engine.enqueueCollection(account, collection, items.filter { it.id in wanted }, cellular = false)
            ask = if (started) {
                onStarted()
                null
            } else {
                MusicDownloadAsk.Notice(
                    "Downloads can't start until the download list on this device can be read. Open Downloads to see why."
                )
            }
        }
    }

    fun askRemove(state: MusicDetailState, ready: Boolean) {
        id = state.item.id
        ask = if (ready) {
            MusicDownloadAsk.Remove("Remove ${state.item.name} from this device? You can still stream it from your server.", "Remove")
        } else {
            MusicDownloadAsk.Remove("Stop downloading ${state.item.name} and remove what's saved so far?", "Stop download")
        }
    }

    fun remove() {
        ask = null
        val target = id
        scope.launch { engine.deleteCollection(account.key, target) }
    }

    fun dismiss() {
        ask = null
    }
}

/**
 * What Download asks, from the server's answer: refused rights, nothing downloadable, too big for the limit, or the question with the
 * count, size and what's left out. Original quality: compatible audio hasn't passed its checks (OFF-02), so it isn't offered.
 */
internal fun musicDownloadQuestion(
    allowed: Boolean,
    items: List<OfflineApiItem>,
    library: OfflineLibrary?,
    name: String
): MusicDownloadAsk {
    if (!allowed) return MusicDownloadAsk.Notice("Your account can't download from this server.")
    val selection = offlineSelect(items.map(OfflineApiItem::candidate), OfflinePreset.original)
    if (selection.itemIds.isEmpty()) return MusicDownloadAsk.Notice("None of these tracks can be downloaded.")
    val stored = library?.manifest?.entries.orEmpty().map { it.id }.toSet()
    // What's already here costs nothing more.
    val fresh = items.filter { it.id in selection.itemIds && offlineEntryId(it.id, OfflinePreset.original) !in stored }
    val needed = offlineSelect(fresh.map(OfflineApiItem::candidate), OfflinePreset.original).knownBytes
    val budget = library?.manifest?.budget
    if (budget?.refusal(needed) != null) {
        return MusicDownloadAsk.Notice(
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
    return MusicDownloadAsk.Confirm("Download ${tracks(count)} ($size) in their original quality?$skipped", items)
}

/** Everything a music page does with its download. Null on pages that can't download, or with no engine. */
internal class MusicDownload(val status: MusicDownloadStatus?, val controller: MusicDownloadController, val openDownloads: () -> Unit) {
    /** The page's More list: Download, or Remove/Stop and the way to Downloads. */
    fun actions(state: MusicDetailState): List<MusicPageAction> = buildList {
        val status = status
        if (status == null) {
            add(
                MusicPageAction(
                    "Download",
                    "music:download",
                    state.tracks.any(::trackIsAvailable) && state.tracksComplete && !state.busy
                ) { controller.request(state) }
            )
        } else {
            val label = if (status.ready) "Remove download" else "Stop download"
            add(MusicPageAction(label, "music:download-remove", true) { controller.askRemove(state, status.ready) })
            add(MusicPageAction("Open Downloads", "music:downloads", true, openDownloads))
        }
    }
}

@Composable
internal fun rememberMusicDownload(state: MusicDetailState, tv: Boolean, openDownloads: () -> Unit): MusicDownload? {
    if (musicDownloadKind(state) == null) return null
    val engine = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<OfflineEngine>() } ?: return null
    val identity = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<DeviceIdentityStore>() } ?: return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraries by engine.libraries.collectAsStateWithLifecycle()
    var network by remember { mutableStateOf(measuredNetwork(context)) }
    LaunchedEffect(Unit) {
        engine.start()
        while (true) {
            network = measuredNetwork(context)
            delay(2_000)
        }
    }
    val controller = remember(state.account.key, state.item.id) {
        MusicDownloadController(state.account, engine, { offlineApiFor(context, identity) }, scope)
    }
    return MusicDownload(musicDownloadStatus(libraries[state.account.key], state.item.id, network, tv), controller, openDownloads)
}

/** A downloaded track as the queue shows it, with no server: art from the saved file. */
internal fun OfflineEntry.musicTrack(art: (OfflineEntry) -> java.io.File?): MusicTrack = MusicTrack(
    id = itemId,
    title = labels.title,
    artist = labels.artist,
    album = labels.album,
    artUrl = art(this)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() },
    durationTicks = runtimeTicks
)

/**
 * Downloads' Play on a track: the album or playlist it came with, ready tracks in their saved order, starting at this one (MUS-10).
 * A track no collection holds plays alone. Null when it isn't a ready track.
 */
internal fun offlineMusicPlay(
    account: Account,
    library: OfflineLibrary?,
    entry: OfflineEntry,
    /** The album or playlist row it was played from; otherwise the newest one holding the track. */
    collectionId: String? = null,
    art: (OfflineEntry) -> java.io.File?
): MusicPlay? {
    if (entry.kind != OfflineMediaKind.audio) return null
    val manifest = library?.manifest ?: return null
    val ready = manifest.entries.filter { it.kind == OfflineMediaKind.audio && it.job.phase == OfflinePhase.ready }
    val collection = collectionId?.let { id -> manifest.collections.firstOrNull { it.id == id && entry.itemId in it.itemIds } }
        ?: manifest.collectionFor(entry.itemId)
    val byItem = ready.filter { it.preset == (collection?.preset ?: entry.preset) }.associateBy { it.itemId }
    val order = collection?.let { manifest.collectionPlayOrder(it.id) } ?: listOf(entry.itemId)
    val tracks = order.mapNotNull { byItem[it]?.musicTrack(art) }
    val start = order.indexOf(entry.itemId).takeIf { it >= 0 } ?: return null
    if (tracks.size != order.size) return null
    val context = collection?.let { (if (it.kind == OfflineCollectionKind.album) "MusicAlbum:" else "Playlist:") + it.id }
    return MusicPlay(account.key, tracks, start, context, collection?.title ?: entry.labels.title, shuffle = false)
}

/** The saved artwork of an entry, when it has one. */
internal fun OfflineEngine.artFile(accountKey: String, entry: OfflineEntry): java.io.File? =
    entry.resources.firstOrNull { it.role == OfflineResourceRole.artwork && it.complete }?.let { resourceFile(accountKey, entry, it) }

/** For Downloads: plays a music row through the music player and opens Now playing; anything else isn't handled here. */
internal fun offlinePlayMusic(
    context: Context,
    account: Account,
    entry: OfflineEntry,
    collectionId: String?,
    engine: OfflineEngine,
    host: MusicHost
): Boolean {
    val library = engine.libraries.value[account.key]
    val play = offlineMusicPlay(account, library, entry, collectionId) { engine.artFile(account.key, it) } ?: return false
    host.request(play)
    return true
}

/** The ready download of a track, if this viewer has one: any preset, Original first. */
internal fun OfflineLibrary.readyTrack(media: String): OfflineEntry? = manifest.entries
    .filter { it.itemId == media && it.kind == OfflineMediaKind.audio && it.job.phase == OfflinePhase.ready }
    .minByOrNull { if (it.preset == OfflinePreset.original) 0 else 1 }

/** MusicHost's view of the download engine: files to play and the outbox their listens go to. */
internal class EngineMusicLocal(private val engine: OfflineEngine) : MusicLocal {
    private val sequences = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    override fun decision(account: Account, media: String): app.pictureshow.core.playback.PlayDecision? {
        val entry = engine.libraries.value[account.key]?.readyTrack(media) ?: return null
        val resource = entry.resources.firstOrNull { it.role == OfflineResourceRole.media && it.complete } ?: return null
        val file = engine.resourceFile(account.key, entry, resource).takeIf { it.isFile } ?: return null
        return app.pictureshow.core.playback.PlayDecision(
            entry.itemId,
            offlineSourceInfo(entry, file.length()) { id ->
                entry.resources.firstOrNull { it.id == id }?.let { engine.resourceFile(account.key, entry, it) }
            },
            app.pictureshow.core.playback.PlayMethod.DirectPlay,
            Uri.fromFile(file).toString(),
            emptyMap(),
            null
        )
    }

    override val changes: kotlinx.coroutines.flow.Flow<Any?> get() = engine.libraries

    override fun art(account: Account, media: String): String? = engine.libraries.value[account.key]?.readyTrack(media)
        ?.let { engine.artFile(account.key, it) }
        ?.takeIf { it.isFile }
        ?.let { Uri.fromFile(it).toString() }

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
                if (musicListenFinished(ticks, runtimeTicks)) store.completed(media, sequence) else store.viewing(media, sequence, ticks)
                engine.syncSoon()
            }
        }
    }
}

/** The host reports a natural end at the track's length; a second short still counts, as rounding lands either side. */
internal fun musicListenFinished(ticks: Long, runtimeTicks: Long?): Boolean =
    runtimeTicks != null && runtimeTicks > 0 && ticks >= runtimeTicks - 10_000_000L

/** Downloads' Play for a music row: true when it went to the music player. Without one (tests, no Koin), nothing is music. */
@Composable
internal fun rememberOfflineMusicPlay(account: Account): (OfflineEntry, String?) -> Boolean {
    val context = LocalContext.current
    val engine = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<OfflineEngine>() }
    val host = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<MusicHost>() }
    return remember(account.key, engine, host) {
        { entry, collection -> engine != null && host != null && offlinePlayMusic(context, account, entry, collection, engine, host) }
    }
}
