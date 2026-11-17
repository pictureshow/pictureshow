/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.offline.OfflineConflict
import app.pictureshow.core.offline.OfflineDetailSource
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineResourceRole
import app.pictureshow.core.offline.OfflineServerReach
import app.pictureshow.core.offline.offlineDetailPlaySource
import app.pictureshow.core.offline.offlineDetailStartTicks
import app.pictureshow.core.offline.offlineNextEpisode
import app.pictureshow.core.playback.PlayDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A destructive action waiting for its explicit yes; every confirmation opens on Cancel. */
internal sealed interface OfflineConfirm {
    data class One(val row: OfflineRow) : OfflineConfirm
    data object All : OfflineConfirm
}

/** One download request being chosen: what it covers, which quality and which tracks (OFF-01/02). */
internal data class DesktopOfflinePreview(
    val itemId: String,
    val title: String,
    val state: OfflinePreviewState = OfflinePreviewState.Loading,
    val preset: OfflinePreset = OfflinePreset.original,
    val episodes: Set<String> = emptySet(),
    val audio: Set<Int> = emptySet(),
    val subtitles: Set<Int> = emptySet(),
    val started: Boolean = false
) {
    val ready: OfflinePreview? get() = (state as? OfflinePreviewState.Ready)?.preview
    fun sourceTracks(type: String): List<DesktopOfflineStream> =
        ready?.items?.firstOrNull()?.sources?.firstOrNull()?.streams.orEmpty().filter { it.type == type }
    fun selectedItems(): List<DesktopOfflineItem> = ready?.items.orEmpty().filter { it.id in episodes }
    val canStart: Boolean get() = !started && selectedItems().isNotEmpty() &&
        ready?.choices?.firstOrNull { it.preset == preset }?.available == true
}

internal data class DesktopOfflineState(
    val accountKey: String? = null,
    val library: OfflineLibrary = OfflineLibrary(),
    val network: OfflineNetwork = OfflineNetwork.unmetered,
    val conflicts: Map<String, OfflineConflict> = emptyMap(),
    /** Downloads this viewer has watched, from this computer's own record first. */
    val watched: Set<String> = emptySet(),
    val preview: DesktopOfflinePreview? = null,
    val confirm: OfflineConfirm? = null,
    val passwordFailed: Boolean = false,
    /** The last server answer at start or on a page load: out of reach (no answer at all, not a refusal). */
    val serverUnreachable: Boolean = false,
    /** After a local viewing: where its progress went (P-11). */
    val notice: String? = null
) {
    val sections: OfflineSections get() = offlineSections(library, network)
    val passwordNeeded: Boolean get() = library.passwordNeeded()
    val hasReady: Boolean get() = library.hasReadyDownloads()

    /** Every download of [itemId], whatever its quality, for the title page. */
    fun entriesFor(itemId: String): List<OfflineEntry> = library.manifest.entries.filter { it.itemId == itemId }
    fun readyFor(itemId: String): OfflineEntry? = entriesFor(itemId).firstOrNull { it.readyMedia() != null }
}

/** A local viewing as the controller runs it: its decision, where it starts, and the progress store it writes to. */
internal class DesktopLocalPlay(
    val account: Account,
    val entry: OfflineEntry,
    val decision: PlayDecision,
    val startSeconds: Double,
    val item: MediaItem,
    internal val store: OfflineProgressStore,
    val sequence: Long
)

/**
 * OFF-01..12 on desktop: the feature controller the window reads. It owns the downloads engine, the current viewer's library
 * view, the download dialog, and the local side of a viewing (progress into the outbox, OFF-07; server-wins sync, OFF-08).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopOffline(
    internal val engine: DesktopOfflineEngine,
    private val api: DesktopOfflineApi,
    private val scope: CoroutineScope,
    private val accounts: Flow<Account?>,
    private val currentAccount: () -> Account?
) {
    private val mutable = MutableStateFlow(DesktopOfflineState())
    val state: StateFlow<DesktopOfflineState> = mutable.asStateFlow()
    private var started: Job? = null
    private var previewJob: Job? = null

    /** Loads every viewer's library and starts the worker; work that was running resumes (OFF-03). Idempotent. */
    fun start(): Job = started ?: engine.start().also { job ->
        started = job
        scope.launch {
            accounts.distinctUntilChanged { a, b -> a?.key == b?.key && a?.accessToken == b?.accessToken }.flatMapLatest { account ->
                if (account == null) flowOf(Triple<Account?, Map<String, OfflineLibrary>, Map<String, OfflineConflict>>(null, emptyMap(), emptyMap()))
                else combine(engine.libraries, engine.progressStore(account).conflicts) { libraries, conflicts -> Triple(account, libraries, conflicts) }
            }.collectLatest { (account, libraries, conflicts) ->
                val library = account?.let { libraries[it.key] } ?: OfflineLibrary()
                val watched = account?.let { a -> runCatching { engine.progressStore(a).watched(library.manifest.entries.map { it.itemId }) }.getOrNull() }
                mutable.update {
                    val switched = it.accountKey != account?.key
                    it.copy(accountKey = account?.key, library = library, conflicts = conflicts, network = engine.network(),
                        watched = watched ?: it.watched, preview = if (switched) null else it.preview, confirm = if (switched) null else it.confirm)
                }
            }
        }
    }

    /** Waits for the first load; a cold start offline asks this before choosing its first page. */
    suspend fun readyFor(account: Account): Boolean {
        start().join()
        val library = engine.ready()[account.key] ?: return false
        return library.recoverable || library.manifest.entries.any { it.readyMedia() != null }
    }

    fun serverReach(unreachable: Boolean) = mutable.update { it.copy(serverUnreachable = unreachable) }
    fun clearNotice() = mutable.update { it.copy(notice = null) }

    /** A visit to Downloads looks again without being asked: files removed by hand, space freed, a server back. */
    fun refresh() {
        scope.launch { engine.refresh(); engine.networkChanged() }
    }

    // The download dialog (OFF-01/02)

    fun openPreview(item: MediaItem) {
        val account = currentAccount() ?: return
        previewJob?.cancel()
        mutable.update { it.copy(preview = DesktopOfflinePreview(item.id, item.name)) }
        previewJob = scope.launch {
            val next = offlineLoadPreview(api, account, item.id, item.type, item.seriesId, item.name)
            mutable.update { state ->
                val preview = state.preview?.takeIf { it.itemId == item.id } ?: return@update state
                if (next !is OfflinePreviewState.Ready) return@update state.copy(preview = preview.copy(state = next))
                val first = next.preview.items.first().sources.firstOrNull()?.streams.orEmpty()
                val audio = (first.firstOrNull { it.type == "Audio" && it.isDefault } ?: first.firstOrNull { it.type == "Audio" })?.index
                state.copy(preview = preview.copy(state = next, episodes = next.preview.items.map(DesktopOfflineItem::id).toSet(),
                    audio = setOfNotNull(audio), preset = next.preview.choices.firstOrNull { it.available }?.preset ?: OfflinePreset.original))
            }
        }
    }

    fun closePreview() { previewJob?.cancel(); mutable.update { it.copy(preview = null) } }

    private fun editPreview(change: (DesktopOfflinePreview) -> DesktopOfflinePreview) =
        mutable.update { s -> s.preview?.takeIf { !it.started }?.let { s.copy(preview = change(it)) } ?: s }

    fun choosePreset(preset: OfflinePreset) = editPreview { p ->
        if (p.ready?.choices?.firstOrNull { it.preset == preset }?.available == true) p.copy(preset = preset) else p
    }
    fun toggleEpisode(id: String) = editPreview { p -> p.copy(episodes = if (id in p.episodes) p.episodes - id else p.episodes + id) }
    fun toggleAudio(index: Int) = editPreview { p -> p.copy(audio = desktopToggleAudio(p.audio, index)) }
    fun toggleSubtitle(index: Int) = editPreview { p -> p.copy(subtitles = desktopToggleSubtitle(p.subtitles, index)) }

    /** Queues the chosen items. Each episode keeps its own track indexes: a season's files needn't agree on which is which. */
    fun startPreview() {
        val account = currentAccount() ?: return
        val preview = mutable.value.preview ?: return
        if (!preview.canStart) return
        mutable.update { it.copy(preview = preview.copy(started = true)) }
        scope.launch {
            val base = preview.sourceTracks("Audio").filter { it.index in preview.audio }.map { it.language }
            val baseSubs = preview.sourceTracks("Subtitle").filter { it.index in preview.subtitles }.map { it.language to it.title }
            preview.selectedItems().forEach { item ->
                val streams = item.sources.firstOrNull()?.streams.orEmpty()
                val audio = if (preview.selectedItems().size > 1) {
                    streams.filter { it.type == "Audio" && it.language in base }.map { it.index }.ifEmpty { preview.audio.toList() }
                } else preview.audio.toList()
                val subtitles = if (preview.selectedItems().size > 1) {
                    streams.filter { s -> s.type == "Subtitle" && baseSubs.any { it.first == s.language } }.map { it.index }.ifEmpty { preview.subtitles.toList() }
                } else preview.subtitles.toList()
                engine.enqueue(account, item, DesktopOfflineChoice(item.id, preview.preset, audio, subtitles, cellular = true))
            }
            mutable.update { s -> if (s.preview?.itemId == preview.itemId) s.copy(preview = null) else s }
        }
    }

    // Downloads list actions (OFF-03/04)

    fun act(row: OfflineRow, action: OfflineAction, play: (OfflineEntry) -> Unit) {
        val key = currentAccount()?.key ?: return
        val id = row.entry.id
        when (action) {
            OfflineAction.Play -> play(row.entry)
            OfflineAction.Delete -> mutable.update { it.copy(confirm = OfflineConfirm.One(row)) }
            OfflineAction.Pause -> scope.launch { engine.pause(key, id) }
            OfflineAction.Resume -> scope.launch { engine.resume(key, id) }
            OfflineAction.Retry -> scope.launch { engine.retry(key, id) }
            OfflineAction.Redownload -> scope.launch { engine.redownload(key, id) }
            OfflineAction.AllowCellular -> scope.launch { engine.setCellular(key, id, true); engine.networkChanged() }
            OfflineAction.Tracks -> Unit
        }
    }

    fun confirmDeleteAll() = mutable.update { it.copy(confirm = OfflineConfirm.All) }
    fun cancelConfirm() = mutable.update { it.copy(confirm = null) }

    fun confirmed() {
        val key = currentAccount()?.key ?: return
        val pending = mutable.value.confirm
        val all = mutable.value.library.manifest.entries.map(OfflineEntry::id)
        mutable.update { it.copy(confirm = null) }
        scope.launch {
            when (pending) {
                is OfflineConfirm.One -> pending.row.collection?.let { engine.deleteCollection(key, it.id) } ?: engine.delete(key, pending.row.entry.id)
                OfflineConfirm.All -> engine.deleteMany(key, all)
                null -> Unit
            }
        }
    }

    fun delete(entryId: String) { val key = currentAccount()?.key ?: return; scope.launch { engine.delete(key, entryId) } }

    fun setCap(bytes: Long) { val key = currentAccount()?.key ?: return; scope.launch { engine.setCap(key, bytes) } }

    fun dismissConflict(itemId: String) { val account = currentAccount() ?: return; scope.launch { engine.progressStore(account).dismissConflict(itemId) } }

    fun providePassword(password: String) {
        val account = currentAccount() ?: return
        scope.launch {
            val ok = engine.providePassword(account, password)
            mutable.update { it.copy(passwordFailed = !ok) }
        }
    }

    fun capChoices(): List<Long> = offlineCapChoices(offlineCapSteps(engine.quota(currentAccount()?.key)), mutable.value.library.manifest.budget.capBytes)

    // A local viewing (OFF-05/07/11)

    /**
     * Opens a ready download for the player. It starts where the viewer left it: an unsent local place first, then a fresh server
     * read when [server] has one, then this computer's last-known place (offlineDetailStartTicks).
     */
    suspend fun prepareLocal(account: Account, entry: OfflineEntry, fromStart: Boolean, server: ItemUserData? = null): DesktopLocalPlay? {
        val current = engine.libraries.value[account.key]?.manifest?.entries?.firstOrNull { it.id == entry.id } ?: return null
        val path = current.readyMedia() ?: return null
        val media = engine.resourceFile(account.key, current, current.resources.first { it.role == OfflineResourceRole.media })
        if (!media.isFile) {
            engine.refresh()
            return null
        }
        val store = engine.progressStore(account)
        val (outbox, lastKnown) = store.place(current.itemId)
        val serverData = server?.let { app.pictureshow.core.offline.OfflineUserData(it.playbackPositionTicks, it.played) }
        val ticks = offlineDetailStartTicks(fromStart, outbox, lastKnown, serverData)
        val decision = desktopLocalDecision(current, media) { engine.files.resource(account.key, current.id, it) }
        val item = current.playerItem(ItemUserData(playbackPositionTicks = ticks))
        return DesktopLocalPlay(account, current, decision, ticks / 10_000_000.0, item, store, store.newSequence())
    }

    /** Progress, durable on this computer before anything is sent (OFF-07). Never fails the viewing. */
    suspend fun localProgress(local: DesktopLocalPlay, positionSeconds: Double) {
        runCatching { local.store.viewing(local.entry.itemId, local.sequence, (positionSeconds * 10_000_000).toLong().coerceAtLeast(0)) }
    }

    suspend fun localCompleted(local: DesktopLocalPlay) {
        runCatching { local.store.completed(local.entry.itemId, local.sequence) }
    }

    /** The viewing closed: settle now if the server answers, quickly; otherwise say where the progress is (P-11). */
    fun localEnded(local: DesktopLocalPlay) {
        engine.viewings.ended(local.account, local.entry.itemId) { result ->
            if (result == OfflineSettleResult.Failed) mutable.update { it.copy(notice = OFFLINE_PROGRESS_KEPT) }
        }
    }

    /** The next downloaded episode, by the server's answer when it gave one, otherwise by the downloads' own order. */
    fun localNext(account: Account, local: DesktopLocalPlay, serverAnswered: Boolean, serverNext: String?): MediaItem? {
        val entries = engine.libraries.value[account.key]?.manifest?.entries.orEmpty()
        return offlineNextEpisode(entries, local.entry, serverAnswered, serverNext)?.playerItem()
    }

    fun entryForNext(account: Account, itemId: String): OfflineEntry? =
        engine.libraries.value[account.key]?.manifest?.entries?.firstOrNull { it.itemId == itemId && it.readyMedia() != null }

    /** Where a title page's Play goes: its ready download, unless the viewer chose to stream (offlineDetailPlaySource). */
    fun detailSource(account: Account, itemId: String, explicitStream: Boolean = false): OfflineDetailSource {
        val ready = engine.libraries.value[account.key]?.manifest?.entries.orEmpty().any { it.itemId == itemId && it.readyMedia() != null }
        val reach = if (mutable.value.serverUnreachable) OfflineServerReach.unreachable else OfflineServerReach.unknown
        return offlineDetailPlaySource(ready, reach, explicitStream)
    }

    /** An online viewing of a downloaded title ended: what the server now holds becomes this computer's last-known place. */
    fun onlineViewingEnded(account: Account, itemId: String) {
        if (engine.libraries.value[account.key]?.manifest?.entries.orEmpty().none { it.itemId == itemId }) return
        engine.onlineViewingEnded(account, itemId) { api.userData(account, itemId) }
    }
}

internal fun desktopToggleAudio(audio: Set<Int>, index: Int): Set<Int> = when {
    index in audio -> if (audio.size > 1) audio - index else audio
    audio.size >= OFFLINE_MAX_CONVERTED_AUDIO -> audio
    else -> audio + index
}

internal fun desktopToggleSubtitle(subtitles: Set<Int>, index: Int): Set<Int> = when {
    index in subtitles -> subtitles - index
    subtitles.size >= OFFLINE_MAX_CONVERTED_SUBTITLES -> subtitles
    else -> subtitles + index
}

/** Unused phases a title page never shows as its own state. */
internal fun OfflineEntry.detailVisible(): Boolean = job.phase != OfflinePhase.deleting
