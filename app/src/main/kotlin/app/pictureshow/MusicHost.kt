/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.QueueEntry
import app.pictureshow.core.control.QueueEvent
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.control.QueueStoreEvent
import app.pictureshow.core.control.QueueStoreState
import app.pictureshow.core.control.reduceQueue
import app.pictureshow.core.control.reduceQueueStore
import app.pictureshow.core.music.MUSIC_QUEUE_LIMIT
import app.pictureshow.core.music.MusicOutput
import app.pictureshow.core.music.MusicPlayRequest
import app.pictureshow.core.music.MusicReportEffect
import app.pictureshow.core.music.MusicReportEvent
import app.pictureshow.core.music.MusicReportState
import app.pictureshow.core.music.MusicRequest
import app.pictureshow.core.music.MusicSessionEvent
import app.pictureshow.core.music.MusicSessionState
import app.pictureshow.core.music.musicBitrateCeiling
import app.pictureshow.core.music.musicDeviceProfile
import app.pictureshow.core.music.musicEntries
import app.pictureshow.core.music.musicIdBatches
import app.pictureshow.core.music.musicOutputFacts
import app.pictureshow.core.music.musicOutputRepeats
import app.pictureshow.core.music.musicPermutation
import app.pictureshow.core.music.musicPlayDecision
import app.pictureshow.core.music.musicUpcoming
import app.pictureshow.core.music.musicWindow
import app.pictureshow.core.music.planWindow
import app.pictureshow.core.music.reduceMusicReport
import app.pictureshow.core.music.reduceMusicSession
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackPreferences
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import app.pictureshow.core.playback.Ticks
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A row of what the listener sees: the entry's identity and what to show for it. */
internal data class MusicRow(val entry: String, val track: MusicTrack)

/** A choice only the listener can make before a play replaces something. */
internal data class MusicPrompt(val kind: String, val play: MusicPlay, val destination: String? = null)

internal data class MusicPlay(
    /** The account these tracks were read for. A play that outlives its account is dropped, not put in the next one's queue. */
    val owner: String,
    val tracks: List<MusicTrack>,
    val start: Int?,
    val context: String?,
    val contextName: String?,
    val shuffle: Boolean
)

internal data class MusicUi(
    val account: Account? = null,
    val queue: QueueState? = null,
    val context: String? = null,
    val contextName: String? = null,
    val tracks: Map<String, MusicTrack> = emptyMap(),
    /** Audio is coming out, or is meant to be. False when restored or stopped: the queue is here, ready to play. */
    val outputActive: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val output: MusicOutput? = null,
    val message: String? = null,
    val prompt: MusicPrompt? = null,
    val canRetrySave: Boolean = false
) {
    val current: MusicRow? get() = queue?.current?.let(::row)
    val paused: Boolean get() = !isPlaying
    val upNext: List<MusicRow> get() = queue?.let { musicUpcoming(it).mapNotNull(::row) }.orEmpty()
    val played: List<MusicRow> get() = queue?.let { q ->
        q.order.take(q.order.indexOf(q.current).coerceAtLeast(0)).mapNotNull(::row)
    }.orEmpty()
    val visible: Boolean get() = current != null

    private fun row(entry: String): MusicRow? {
        val media = queue?.entries?.firstOrNull { it.id == entry }?.media ?: return null
        return MusicRow(entry, tracks[media] ?: MusicTrack(media, ""))
    }
}

internal data class PreparedTrack(val decision: PlayDecision, val entry: String, val media: String)

/** The server side of the host, narrow so tests can answer with a real file on a mock server. */
internal interface MusicGateway {
    suspend fun negotiate(account: Account, media: String, ceiling: Int): ClientResult<PlayOutcome>

    suspend fun start(account: Account, info: PlaybackProgressInfo)

    suspend fun progress(account: Account, info: PlaybackProgressInfo)

    suspend fun stopped(account: Account, info: PlaybackStopInfo)

    /** The saved IDs this viewer can still open, or a failure. */
    suspend fun check(account: Account, media: List<String>): ClientResult<List<String>>

    /** The rows themselves, for a list a remote names by ID. */
    suspend fun items(account: Account, media: List<String>): ClientResult<List<MediaItem>>
}

internal class ClientMusicGateway(
    private val client: suspend (Account) -> PlaybackClient,
    private val music: app.pictureshow.core.music.MusicService,
    private val decoders: () -> Set<AudioCodec>
) : MusicGateway {
    override suspend fun negotiate(account: Account, media: String, ceiling: Int) = client(account).prepareFor(
        account,
        media,
        musicDeviceProfile(decoders(), ceiling),
        ceiling,
        PlaybackRequest(audio = true)
    )

    override suspend fun start(account: Account, info: PlaybackProgressInfo) {
        client(account).reportStart(account, info)
    }

    override suspend fun progress(account: Account, info: PlaybackProgressInfo) {
        client(account).reportProgress(account, info)
    }

    override suspend fun stopped(account: Account, info: PlaybackStopInfo) {
        client(account).reportStopped(account, info)
    }

    override suspend fun check(account: Account, media: List<String>): ClientResult<List<String>> =
        when (val rows = read(account, media, emptyMap())) {
            is ClientResult.Failure -> rows
            is ClientResult.Success -> ClientResult.Success(rows.value.map(MediaItem::id))
        }

    override suspend fun items(account: Account, media: List<String>): ClientResult<List<MediaItem>> =
        read(account, media, mapOf("fields" to "MediaSources"))

    /** Ids go in batches, so a long queue never makes a request line the server refuses; the first failure ends it. */
    private suspend fun read(account: Account, media: List<String>, extra: Map<String, String>): ClientResult<List<MediaItem>> {
        val rows = mutableListOf<MediaItem>()
        for (batch in musicIdBatches(media)) {
            val parameters = mapOf("ids" to batch.joinToString(","), "limit" to batch.size.toString()) + extra
            when (val page = music.music(account, MusicRequest("Items", parameters))) {
                is ClientResult.Failure -> return page
                is ClientResult.Success -> rows += page.value.items
            }
        }
        return ClientResult.Success(rows)
    }
}

private const val POSITION_SAVE_MS = 5_000L
private const val PROGRESS_REPORT_MS = 10_000L

/**
 * The one music player: the reducer owns the queue, this file owns the output, and every change takes the same road.
 * An edit is reduced, saved, published, and only then reaches the player, so what's on disk is never behind what's heard.
 */
internal class MusicHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val accounts: AccountStore,
    private val store: MusicStore,
    private val gateway: MusicGateway,
    private val preferences: suspend () -> PlaybackPreferences,
    private val remoteAttached: () -> String?,
    private val remoteTarget: () -> RemoteTargetHost?,
    private val newPlayer: (Context) -> ExoPlayer = ::createMusicPlayer,
    private val startService: (MusicHost) -> Unit = ::connectMusicService,
    private val seed: () -> Long = { Random.nextLong(Long.MAX_VALUE) },
    private val metered: () -> Boolean = {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }
) {
    /** The Cast device in use. The touch interface sets this, because a TV must never load the sender. */
    var castAttached: () -> String? = { null }

    private val mutable = MutableStateFlow(MusicUi())
    val state = mutable.asStateFlow()

    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var account: Account? = null
    private var queue = QueueStoreState(QueueState("", ""))
    private var session = MusicSessionState()
    private var reports = MusicReportState()
    private var playContext: String? = null
    private var contextName: String? = null
    private var sequence = 0L
    private var tracks: Map<String, MusicTrack> = emptyMap()
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var controller: ListenableFuture<MediaController>? = null
    private val prepared = HashMap<String, PreparedTrack>()
    private val viewings = HashMap<Long, PreparedTrack>()
    private var preparing: Job? = null
    private var ticker: Job? = null
    private var ceiling = 0
    private var endedHandled = false
    private var binding = 0L
    private var restoring: Job? = null

    /** A step belongs to the account that was bound when it was asked for; once another is bound it no longer runs. */
    private fun enqueue(job: suspend () -> Unit) {
        val asked = binding
        jobs.trySend { if (asked == binding) job() }
    }

    /**
     * A step a screen asked for on behalf of one account runs for that account or not at all. A screen can be ahead of this
     * host: it may already show the account the viewer just switched to, so that switch is made here first rather than the
     * step being dropped for arriving before it.
     */
    private fun enqueueFor(owner: String, job: suspend () -> Unit) {
        jobs.trySend {
            if (owner != account?.key && accounts.snapshots.value.currentAccount?.key == owner) bindLatest()
            if (owner == account?.key) job()
        }
    }

    // Accounts

    /** Always the latest accounts, so a switch a step already made is not undone by an older one still in line. */
    private suspend fun bindLatest() {
        val snapshot = accounts.snapshots.value
        bind(snapshot.currentAccount, snapshot.accounts.map(Account::key).toSet())
        // Any viewer no longer saved here, current or not, takes their saved queue with them.
        store.retain(snapshot.accounts)
    }

    private suspend fun bind(next: Account?, known: Set<String>) {
        val previous = account
        if (previous != null && previous.key != next?.key) {
            if (session.intent != "stopped") applySession("sign-out")
            teardown()
            if (previous.key !in known) store.delete(previous)
        }
        if (previous?.key == next?.key) {
            account = next
            if (previous != null && next != null && previous.accessToken != next.accessToken) renewed()
            return
        }
        account = next
        binding++
        restoring?.cancel()
        reports = MusicReportState()
        session = MusicSessionState()
        playContext = null
        contextName = null
        sequence = 0
        tracks = emptyMap()
        queue = QueueStoreState(QueueState(next?.server?.id ?: "", next?.user?.id ?: ""))
        publish { MusicUi(account = next, queue = next?.let { queue.committed }) }
        if (next != null) {
            val bound = binding
            val empty = queue
            // The server check is off the step road, so Play and Stop never wait on it.
            restoring = scope.launch { restore(next, bound, empty) }
        }
    }

    /**
     * The same viewer with a new token, after a sign-in again or a device upgrade: nothing opened with the old one is used
     * again. What's playing is reopened where it is, and what was prepared is asked for again.
     */
    private suspend fun renewed() {
        preparing?.cancel()
        prepared.clear()
        val running = player ?: return
        val at = currentTicks()
        // Stopped first, so the empty player is idle rather than ended, which would read as the track finishing.
        running.stop()
        running.clearMediaItems()
        if (queue.committed.current != null) {
            commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = at) }
        }
        sync()
    }

    /**
     * A saved queue comes back paused, without what the server no longer lets this viewer open (QUE-07, MUS-08). It lands
     * only on the queue it was read for: not after a switch, and not over something the viewer started meanwhile.
     */
    private suspend fun restore(account: Account, bound: Long, empty: QueueStoreState) {
        val saved = store.load(account) ?: return
        if (saved.queue.server != account.server.id || saved.queue.viewer != account.user.id) return
        val media = saved.queue.entries.map(QueueEntry::media).distinct()
        val pages = mutableListOf<ClientResult<List<String>>>()
        for (batch in musicIdBatches(media)) {
            val page = gateway.check(account, batch)
            pages += page
            // One failure already means every saved track stays, so the rest needn't be asked.
            if (page !is ClientResult.Success) break
        }
        val accessible = accessibleAfter(media.toSet(), pages)
        jobs.trySend {
            if (binding == bound && this.account?.key == account.key && queue === empty) applyRestore(account, saved, accessible)
        }
    }

    private suspend fun applyRestore(account: Account, saved: MusicSnapshot, accessible: Set<String>) {
        val ids = saved.queue.entries.filter { it.media in accessible }.map(QueueEntry::id)
        val restored = reduceQueueStore(queue, QueueStoreEvent("restore", saved = saved.queue, accessible = ids))
        if (restored.effect != "persist") return
        queue = restored
        tracks = saved.tracks.associateBy(MusicTrack::id)
        playContext = saved.context
        contextName = saved.contextName
        sequence = saved.sequence
        val removed = restored.removed.size
        commitPending(account)
        if (removed >
            0
        ) {
            publish { it.copy(message = "$removed saved ${if (removed == 1) "track is" else "tracks are"} no longer available.") }
        }
    }

    // What the screens and the session call.

    fun request(play: MusicPlay) = enqueueFor(play.owner) {
        val q = queue.committed
        val decision = musicPlayDecision(
            MusicPlayRequest(
                context = play.context,
                queueEntries = q.entries.size,
                queueContext = playContext,
                remoteAttached = remoteAttached() != null,
                castAttached = castAttached() != null
            )
        )
        when (decision) {
            "play" -> start(play)
            "jump" -> jumpOrStart(play)
            "confirm-replace" -> publish { it.copy(prompt = MusicPrompt("replace", play)) }
            else -> publish { it.copy(prompt = MusicPrompt("destination", play, remoteAttached() ?: castAttached())) }
        }
    }

    fun confirm() = enqueue {
        val prompt = mutable.value.prompt ?: return@enqueue
        publish { it.copy(prompt = null) }
        val q = queue.committed
        if (prompt.kind == "destination" && q.entries.isNotEmpty() && prompt.play.context != playContext) {
            // Choosing this device is one question; replacing its own queue is still another.
            publish { it.copy(prompt = MusicPrompt("replace", prompt.play)) }
        } else {
            start(prompt.play)
        }
    }

    /** The chooser's "This device": the question was already answered, so nothing is asked again. */
    fun playHere(play: MusicPlay) = enqueueFor(play.owner) { start(play) }

    /**
     * A remote's Play on this device, when what it named is music: true means it's handled here, since the video route
     * can't open an audio item. Anything else, or a failed read, goes on to the video path as before.
     */
    suspend fun openFromRemote(account: Account, ids: List<String>, index: Int): Boolean {
        val rows = (gateway.items(account, ids) as? ClientResult.Success)?.value?.associateBy(MediaItem::id) ?: return false
        val chosen = ids.getOrNull(index)?.let(rows::get)
        if (chosen?.type != "Audio") return false
        val tracks = ids.mapNotNull(rows::get).filter { it.type == "Audio" }
        val play =
            MusicPlay(
                account.key,
                tracks.map(account::musicTrack),
                tracks.indexOf(chosen),
                "remote:${ids.joinToString(",").hashCode()}",
                null,
                false
            )
        enqueueFor(account.key) { start(play) }
        return true
    }

    fun dismissPrompt() = enqueue { publish { it.copy(prompt = null) } }

    fun dismissMessage() = enqueue { publish { it.copy(message = null) } }

    private suspend fun jumpOrStart(play: MusicPlay) {
        val q = queue.committed
        val media = play.start?.let(play.tracks::getOrNull)?.id
        val entry = q.entries.firstOrNull { it.media == media }?.id
        if (play.shuffle || entry == null) return start(play)
        activate()
        commit { QueueEvent("jump", revision = it.revision, generation = it.generation, entry = entry) }
    }

    private suspend fun start(play: MusicPlay) {
        if (play.owner != account?.key) return
        val limited = play.tracks.take(MUSIC_QUEUE_LIMIT)
        if (limited.isEmpty()) return
        val entries = musicEntries(limited.map(MusicTrack::id), sequence)
        sequence += entries.size
        tracks = (tracks + limited.associateBy(MusicTrack::id))
        playContext = play.context
        contextName = play.contextName
        val ids = entries.map(QueueEntry::id)
        val selected = play.start?.let(ids::getOrNull)
        val order = if (play.shuffle) {
            val shuffled = musicPermutation(ids, seed())
            // A chosen first track stays first, so Shuffle from a row plays that row.
            if (selected != null) listOf(selected) + shuffled.filter { it != selected } else shuffled
        } else {
            emptyList()
        }
        activate()
        commit {
            QueueEvent(
                "play-now",
                revision = it.revision,
                generation = it.generation,
                entries = entries,
                entry = selected,
                permutation = order
            )
        }
        if (limited.size < play.tracks.size) publish { it.copy(message = "Playing the first $MUSIC_QUEUE_LIMIT tracks.") }
    }

    private fun add(owner: Account, items: List<MusicTrack>, op: String) = enqueueFor(owner.key) {
        val limited = items.take((MUSIC_QUEUE_LIMIT - queue.committed.entries.size).coerceAtLeast(0))
        if (limited.isEmpty()) {
            if (items.isNotEmpty()) publish { it.copy(message = "The queue is full.") }
            return@enqueueFor
        }
        val entries = musicEntries(limited.map(MusicTrack::id), sequence)
        sequence += entries.size
        tracks = tracks + limited.associateBy(MusicTrack::id)
        val wasEmpty = queue.committed.current == null
        // The first thing added to an empty queue is ready to play, and adding never starts it by itself.
        val added = if (wasEmpty) {
            commit {
                QueueEvent("play-now", revision = it.revision, generation = it.generation, entries = entries)
            } &&
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
        } else {
            commit { QueueEvent(op, revision = it.revision, generation = it.generation, entries = entries) }
        }
        // A refused or unsaved edit has already said so; claiming it was added would hide that.
        if (!added) return@enqueueFor
        val count = limited.size
        publish { it.copy(message = if (count == 1) "Added ${limited[0].title} to the queue." else "Added $count tracks to the queue.") }
    }

    fun playNext(owner: Account, items: List<MusicTrack>) = add(owner, items, "play-next")

    fun append(owner: Account, items: List<MusicTrack>) = add(owner, items, "append")

    fun remove(entry: String) = enqueue {
        commit { QueueEvent("remove", revision = it.revision, generation = it.generation, entry = entry) }
        if (queue.committed.current == null) endOutput("stop-clear")
    }

    fun move(entry: String, index: Int) = enqueue {
        commit { QueueEvent("move", revision = it.revision, generation = it.generation, entry = entry, index = index) }
    }

    fun clearUpcoming() = enqueue {
        commit { QueueEvent("clear-upcoming", revision = it.revision, generation = it.generation) }
    }

    fun clear() = enqueue {
        applySession("queue-clear")
        commit { QueueEvent("clear-all", revision = it.revision, generation = it.generation) }
        teardown()
        playContext = null
        contextName = null
    }

    fun jump(entry: String, positionMs: Long = 0) = enqueue {
        activate()
        val jumped = commit { QueueEvent("jump", revision = it.revision, generation = it.generation, entry = entry) }
        if (jumped && positionMs > 0) {
            val ticks = positionMs * Ticks.PER_MILLISECOND
            commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = ticks) }
        }
    }

    fun play() = enqueue {
        if (queue.committed.current == null) return@enqueue
        applySession("viewer-play")
        val fromIdle = player == null
        activate()
        val existing = player
        commit { QueueEvent("play", revision = it.revision, generation = it.generation) }
        if (!fromIdle && existing?.playbackState == Player.STATE_IDLE) existing.prepare()
    }

    fun pause() = enqueue {
        applySession("viewer-pause")
        savePosition()
        commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
    }

    fun toggle() {
        if (mutable.value.isPlaying || (mutable.value.outputActive && mutable.value.queue?.paused == false)) pause() else play()
    }

    fun next() = enqueue {
        activateIfPlaying()
        commit { QueueEvent("next", revision = it.revision, generation = it.generation, completion = it.completion) }
    }

    fun previous() = enqueue {
        val at = player?.currentPosition
        if (at != null) {
            commit {
                QueueEvent("position", revision = it.revision, generation = it.generation, ticks = at * Ticks.PER_MILLISECOND)
            }
        }
        val before = queue.committed.current
        commit { QueueEvent("previous", revision = it.revision, generation = it.generation) }
        if (queue.committed.current == before) player?.seekTo(0)
    }

    fun seekTo(positionMs: Long) = enqueue {
        val target = positionMs.coerceAtLeast(0)
        val running = player
        if (running != null) {
            running.seekTo(target)
        } else {
            commit {
                QueueEvent("position", revision = it.revision, generation = it.generation, ticks = target * Ticks.PER_MILLISECOND)
            }
        }
    }

    fun cycleRepeat() = enqueue {
        val next = when (queue.committed.repeat) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        commit { QueueEvent("repeat", revision = it.revision, generation = it.generation, repeat = next) }
    }

    fun setRepeat(mode: String) = enqueue {
        commit { QueueEvent("repeat", revision = it.revision, generation = it.generation, repeat = mode) }
    }

    fun toggleShuffle() = setShuffle(!queue.committed.shuffled)

    fun setShuffle(on: Boolean) = enqueue {
        val q = queue.committed
        if (on == q.shuffled) return@enqueue
        if (on) {
            val upcoming = musicUpcoming(q).filter { it !in q.visited }
            commit {
                QueueEvent(
                    "shuffle",
                    revision = it.revision,
                    generation = it.generation,
                    permutation = musicPermutation(upcoming, seed())
                )
            }
        } else {
            commit { QueueEvent("unshuffle", revision = it.revision, generation = it.generation) }
        }
    }

    fun retrySave() = enqueue {
        val active = account ?: return@enqueue
        val retried = reduceQueueStore(queue, QueueStoreEvent("retry-persist"))
        if (retried.effect != "persist") return@enqueue
        queue = retried
        commitPending(active)
    }

    /** Stop means the sound, the notification and the session end. The queue stays, ready to play. */
    fun stop() = enqueue { endOutput("stop") }

    /** Called before a video opens. Twice is harmless: the second finds nothing to stop. */
    fun beforeVideo() = enqueue { endOutput("video-start") }

    /** What the session's button handler calls with the press's own stamp, so one press acts once by any route. */
    fun acceptKey(stamp: String): Boolean {
        session = reduceMusicSession(session, MusicSessionEvent("key", stamp))
        return session.effect == "accept"
    }

    fun positionMs(): Long = player?.currentPosition ?: (queue.committed.positionTicks / Ticks.PER_MILLISECOND)

    fun durationMs(): Long? {
        val known = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        if (known != null) return known
        val media = queue.committed.entries.firstOrNull { it.id == queue.committed.current }?.media
        return tracks[media]?.durationTicks?.let { it / Ticks.PER_MILLISECOND }?.takeIf { it > 0 }
    }

    // The queue's one road.

    /** The output exists from the first Play until Stop. */
    private fun activate() {
        if (player == null) {
            createOutput()
            startService(this)
        }
    }

    private fun activateIfPlaying() {
        if (!queue.committed.paused) activate()
    }

    private suspend fun commit(event: (QueueState) -> QueueEvent): Boolean {
        val active = account ?: return false
        val edit = event(queue.committed)
        val outcome = reduceQueue(queue.committed, edit).outcome
        val next = reduceQueueStore(queue, QueueStoreEvent("edit", edit = edit))
        if (next.effect != "persist") {
            android.util.Log.w("MusicHost", "${edit.op} refused: ${next.reason ?: outcome}")
            return false
        }
        queue = next
        if (!commitPending(active)) return false
        when (outcome) {
            // The queue ran out: Play starts its last track again rather than landing on its final second.
            "stopped" -> endOutput("stop", 0)
            "exhausted" -> {
                endOutput("stop")
                publish { it.copy(message = "None of the tracks left in this queue could play.") }
            }
            "skipped-unavailable" -> publish { it.copy(message = "Skipped a track that couldn't play.") }
        }
        return true
    }

    private suspend fun commitPending(active: Account): Boolean {
        val pending = queue.pending ?: return false
        val saved = store.save(active, snapshot(pending))
        val acknowledged = reduceQueueStore(queue, QueueStoreEvent(if (saved) "persisted" else "persist-failed", ticket = queue.ticket))
        queue = acknowledged
        if (!saved) {
            publish { it.copy(message = "Couldn't save the queue on this device, so that change didn't apply.", canRetrySave = true) }
            return false
        }
        publish { it.copy(canRetrySave = false) }
        sync()
        return true
    }

    private fun snapshot(q: QueueState): MusicSnapshot {
        val media = q.entries.map(QueueEntry::media).toSet()
        return MusicSnapshot(q, playContext, contextName, sequence, media.mapNotNull(tracks::get))
    }

    private inline fun publish(change: (MusicUi) -> MusicUi = { it }) {
        val running = player
        val q = queue.committed
        mutable.update {
            change(it).copy(
                account = account,
                queue = q.takeIf { account != null },
                context = playContext,
                contextName = contextName,
                tracks = tracks,
                outputActive = running != null,
                isPlaying = running?.isPlaying == true,
                buffering = running?.playbackState == Player.STATE_BUFFERING
            )
        }
    }

    // The output.

    private fun sync() {
        preparing?.cancel()
        val running = player ?: return
        running.repeatMode = if (musicOutputRepeats(queue.committed)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        running.playWhenReady = !queue.committed.paused
        preparing = scope.launch { prepareWindow() }
    }

    private suspend fun prepareWindow() {
        val active = account ?: return
        val q = queue.committed
        val target = musicWindow(q)
        if (target.isEmpty()) return
        val next = musicBitrateCeiling(preferences().musicQuality, metered())
        if (next != ceiling) {
            // A quality change applies from what hasn't been opened yet.
            prepared.keys.retainAll(setOfNotNull(player?.currentMediaItem?.mediaId))
            ceiling = next
        }
        val entries = q.entries.associateBy(QueueEntry::id)
        prepared.keys.retainAll(target.toSet())
        target.forEachIndexed { index, entry ->
            if (entry !in prepared) {
                val media = entries[entry]?.media ?: return
                val outcome = gateway.negotiate(active, media, ceiling)
                when {
                    outcome is ClientResult.Success && outcome.value is PlayOutcome.Play ->
                        prepared[entry] = PreparedTrack((outcome.value as PlayOutcome.Play).decision, entry, media)
                    index > 0 -> return applyWindow(active, target)
                    outcome is ClientResult.Failure -> return negotiationFailed(outcome.error)
                    else -> {
                        // The server has no way to give this device this file: skipping it is the honest move.
                        enqueue { failCurrent() }
                        return
                    }
                }
            }
            applyWindow(active, target)
        }
    }

    private fun negotiationFailed(error: ClientError) {
        when {
            error.kind == ErrorKind.EXPIRED -> enqueue { revoked() }
            error.status == 403 || error.status == 404 -> enqueue { failCurrent() }
            else -> enqueue {
                player?.playWhenReady = false
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
                publish { it.copy(message = "Can't reach the server. Your queue is saved: press Play to try again.") }
            }
        }
    }

    private suspend fun failCurrent() {
        commit { QueueEvent("error", revision = it.revision, generation = it.generation, completion = it.completion, music = true) }
    }

    /**
     * The server refused this account's token. As with video, the account is marked expired, so signing in again replaces the
     * token rather than adding a second viewer, and the same viewer's saved queue waits for them, paused.
     */
    private suspend fun revoked() {
        val active = account
        endOutput("revoked")
        if (active != null && active.accessToken.isNotEmpty()) accounts.expire(active.key, active.accessToken)
        publish { it.copy(message = "This account needs to sign in again. Your queue is saved for when it does.") }
    }

    private fun applyWindow(active: Account, target: List<String>) {
        val running = player ?: return
        val ready = target.takeWhile { it in prepared }
        if (ready.isEmpty()) return
        val held = (0 until running.mediaItemCount).map { running.getMediaItemAt(it).mediaId }
        val playing = running.currentMediaItem?.mediaId
        val plan = planWindow(held, playing, ready)
        if (plan.replace) {
            val start = if (queue.committed.positionTicks > 0) queue.committed.positionTicks / Ticks.PER_MILLISECOND else 0
            running.setMediaSources(plan.add.map { source(active, prepared.getValue(it)) }, 0, start)
            running.prepare()
        } else {
            if (plan.removeAfter > 0) running.removeMediaItems(held.size - plan.removeAfter, held.size)
            if (plan.removeBefore > 0) running.removeMediaItems(0, plan.removeBefore)
            if (plan.add.isNotEmpty()) running.addMediaSources(plan.add.map { source(active, prepared.getValue(it)) })
        }
        running.playWhenReady = !queue.committed.paused
        updateOutputLabel()
    }

    private fun source(active: Account, track: PreparedTrack) = DefaultMediaSourceFactory(context)
        .setDataSourceFactory(PlaybackMedia.httpFactory(active.server, track.decision))
        .createMediaSource(
            ExoItem.Builder()
                .setUri(track.decision.url)
                .setMediaId(track.entry)
                .setMimeType(if (track.decision.method == PlayMethod.Transcode) MimeTypes.APPLICATION_M3U8 else null)
                .setMediaMetadata(metadata(tracks[track.media]))
                .build()
        )

    private fun metadata(track: MusicTrack?) = MediaMetadata.Builder()
        .setTitle(track?.title)
        .setArtist(track?.artist)
        .setAlbumTitle(track?.album)
        .setArtworkUri(track?.artUrl?.let(Uri::parse))
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .build()

    private fun updateOutputLabel() {
        val entry = player?.currentMediaItem?.mediaId ?: return
        val track = prepared[entry] ?: return
        val facts = musicOutputFacts(
            track.decision.mediaSource,
            track.decision.method,
            currentRoute(),
            ceiling,
            track.decision.transcodeReasons
        )
        publish { it.copy(output = facts) }
    }

    private fun currentRoute(): String {
        if (castAttached() != null) return "cast"
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(AudioDeviceInfo::getType)
        return when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "bluetooth"
            types.any { it == AudioDeviceInfo.TYPE_USB_HEADSET || it == AudioDeviceInfo.TYPE_USB_DEVICE } -> "usb"
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "wired"
            else -> "device"
        }
    }

    private fun createOutput() {
        val created = newPlayer(context)
        player = created
        endedHandled = false
        created.addListener(listener)
        ticker = scope.launch { tick() }
        remoteTarget()?.setPlayer(this, remoteBridge)
        publish()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: ExoItem?, reason: Int) {
            val entry = mediaItem?.mediaId ?: return
            val previous = reports.viewing
            val leaving = tracks[previous?.media]?.durationTicks ?: 0
            val natural = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val track = prepared[entry]
            if (track != null) {
                report(MusicReportEvent("load", entry, track.media, ticks = lastTicks, natural = natural, durationTicks = leaving))
                reports.viewing?.let { viewings[it.serial] = track }
                if (player?.isPlaying == true) report(MusicReportEvent("playing", ticks = 0))
            }
            lastTicks = 0
            if (natural) {
                enqueue {
                    commit { QueueEvent("end", revision = it.revision, generation = it.generation, completion = it.completion) }
                }
            }
            updateOutputLabel()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
            if (reports.viewing == null) return
            val at = currentTicks()
            report(if (isPlaying) MusicReportEvent("playing", ticks = at) else MusicReportEvent("progress", ticks = at, paused = true))
            if (!isPlaying) scope.launch { savePosition() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
            if (playbackState == Player.STATE_ENDED && !endedHandled) {
                endedHandled = true
                enqueue {
                    endedHandled = false
                    commit { QueueEvent("end", revision = it.revision, generation = it.generation, completion = it.completion) }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            val cause = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focus-lost"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "noisy"
                else -> return
            }
            enqueue {
                applySession(cause)
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
            }
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            // The player itself holds and resumes for a transient loss; this records why, for the viewer-pause rule.
            enqueue { applySession(if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) "interruption-ended" else "interrupted") }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            when {
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 401 -> enqueue { revoked() }
                error.errorCode in NETWORK_ERRORS -> enqueue {
                    savePosition()
                    commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
                    publish { it.copy(message = "Lost the connection. Your place is saved: press Play to try again.") }
                }
                else -> enqueue {
                    report(MusicReportEvent("stop", ticks = currentTicks(), failed = true))
                    failCurrent()
                }
            }
        }
    }

    private var lastTicks = 0L

    private fun currentTicks() = (player?.currentPosition ?: 0) * Ticks.PER_MILLISECOND

    private suspend fun tick() {
        var sinceReport = 0L
        var sincePosition = 0L
        while (true) {
            delay(1_000)
            val running = player ?: return
            if (!running.isPlaying) continue
            lastTicks = currentTicks()
            sinceReport += 1_000
            sincePosition += 1_000
            if (sinceReport >= PROGRESS_REPORT_MS) {
                sinceReport = 0
                report(MusicReportEvent("progress", ticks = lastTicks))
            }
            if (sincePosition >= POSITION_SAVE_MS) {
                sincePosition = 0
                savePosition()
            }
            remoteBridge.report()
        }
    }

    private suspend fun savePosition() {
        val active = account ?: return
        val running = player ?: return
        store.savePosition(active, MusicPosition(running.currentMediaItem?.mediaId, currentTicks()))
    }

    private fun report(event: MusicReportEvent) {
        val active = account ?: return
        reports = reduceMusicReport(reports, event)
        reports.effects.forEach { effect -> send(active, effect) }
    }

    private fun send(active: Account, effect: MusicReportEffect) {
        val track = viewings[effect.serial]
        val decision = track?.decision
        // Reports leave in the order they happened, on a queue of their own, so a slow server can't hold the player.
        reportJobs.trySend {
            if (decision != null) {
                when (effect.report) {
                    "start" -> gateway.start(active, progressInfo(decision, effect))
                    "progress" -> gateway.progress(active, progressInfo(decision, effect))
                    else -> gateway.stopped(
                        active,
                        PlaybackStopInfo(
                            itemId = effect.media,
                            mediaSourceId = decision.mediaSource.id,
                            playSessionId = decision.playSessionId,
                            positionTicks = effect.ticks,
                            failed = effect.failed
                        )
                    )
                }
            }
        }
        if (effect.report == "stopped") viewings.remove(effect.serial)
    }

    private val reportJobs = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { channel ->
        scope.launch {
            for (job in channel) {
                runCatching { job() }
            }
        }
    }

    private fun progressInfo(decision: PlayDecision, effect: MusicReportEffect) = PlaybackProgressInfo(
        itemId = effect.media,
        mediaSourceId = decision.mediaSource.id,
        playSessionId = decision.playSessionId,
        playMethod = decision.method,
        positionTicks = effect.ticks,
        isPaused = effect.paused
    )

    private fun applySession(op: String) {
        session = reduceMusicSession(session, MusicSessionEvent(op))
    }

    private suspend fun endOutput(cause: String, ticks: Long = currentTicks()) {
        applySession(cause)
        val active = account
        if (player != null && active != null) {
            store.savePosition(active, MusicPosition(queue.committed.current, ticks))
            commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
            commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = ticks) }
        }
        teardown()
    }

    private fun teardown() {
        preparing?.cancel()
        ticker?.cancel()
        val running = player ?: return publish()
        report(MusicReportEvent("stop", ticks = currentTicks()))
        player = null
        remoteTarget()?.clearPlayer(this)
        running.removeListener(listener)
        running.stop()
        running.clearMediaItems()
        mediaSession?.release()
        mediaSession = null
        controller?.let(MediaController::releaseFuture)
        controller = null
        running.release()
        context.stopService(Intent(context, MusicPlaybackService::class.java))
        prepared.clear()
        publish { it.copy(output = null) }
    }

    // The session service's side.

    internal fun openSession(service: MusicPlaybackService, build: (Player) -> MediaSession): MediaSession? {
        mediaSession?.let { return it }
        val running = player ?: return null
        val created = build(MusicSessionPlayer(running, this))
        mediaSession = created
        return created
    }

    internal fun closeSession(session: MediaSession) {
        if (mediaSession === session) mediaSession = null
    }

    internal fun attachController(future: ListenableFuture<MediaController>) {
        controller?.let(MediaController::releaseFuture)
        controller = future
    }

    internal fun appContext(): Context = context

    internal fun hasNext(): Boolean = queue.committed.let { q ->
        q.current != null && (musicUpcoming(q).isNotEmpty() || q.repeat == "all")
    }

    internal val remoteBridge = object : RemotePlayerBridge {
        override fun pause() = this@MusicHost.pause()

        override fun unpause() = this@MusicHost.play()

        override fun report() {
            val running = player ?: return
            val q = queue.committed
            val media = q.entries.firstOrNull { it.id == q.current }?.media ?: return
            remoteTarget()?.report(
                this@MusicHost,
                app.pictureshow.core.control.RemoteViewing(
                    media,
                    ready = running.playbackState == Player.STATE_READY,
                    durationTicks = (durationMs() ?: 0) * Ticks.PER_MILLISECOND,
                    ticks = currentTicks(),
                    paused = !running.playWhenReady,
                    canSeek = running.isCurrentMediaItemSeekable
                )
            )
        }

        override fun seekTo(positionMs: Long) = this@MusicHost.seekTo(positionMs)

        override fun stop() = this@MusicHost.stop()

        override fun next(item: String?) = this@MusicHost.next()

        override fun previous(): Boolean {
            this@MusicHost.previous()
            return true
        }

        override fun enqueue(items: List<String>, next: Boolean): Boolean {
            val active = account ?: return false
            scope.launch {
                // Only tracks join a music queue; the reply to the phone is the queue it reads back.
                val rows = (gateway.items(active, items) as? ClientResult.Success)?.value?.associateBy(MediaItem::id) ?: return@launch
                val tracks = items.mapNotNull(rows::get).filter { it.type == "Audio" }.map(active::musicTrack)
                if (next) playNext(active, tracks) else append(active, tracks)
            }
            return true
        }

        override fun audio(index: Int) = Unit

        override fun subtitle(index: Int) = Unit
    }

    // Last, so a step that runs at once finds every property above already built.
    init {
        scope.launch {
            for (job in jobs) {
                // One failed step mustn't end the only road the queue has.
                try {
                    job()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("MusicHost", "A music step failed", failure)
                }
            }
        }
        scope.launch {
            accounts.load()
            accounts.snapshots
                .distinctUntilChangedBy { Triple(it.currentAccount?.key, it.currentAccount?.accessToken, it.accounts.map(Account::key)) }
                .collect {
                    // The one step that always runs: it is what moves the queue to the next account.
                    jobs.trySend { bindLatest() }
                }
        }
    }
}

private val NETWORK_ERRORS = setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
)

/**
 * The music output. No video or text tracks: art inside a file is not worth a decoder, and background audio holds none
 * (MUS-11). The player owns focus, ducking and the headphone rule, and tells the host what it did.
 */
internal fun createMusicPlayer(context: Context): ExoPlayer {
    val player = ExoPlayer.Builder(context, PictureshowRenderersFactory(context, passthroughEnabled = false))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
    return player
}

/** Binding starts the service, and Media3 takes it to the foreground once something plays. */
private fun connectMusicService(host: MusicHost) {
    val context = host.appContext()
    host.attachController(
        MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync()
    )
}
