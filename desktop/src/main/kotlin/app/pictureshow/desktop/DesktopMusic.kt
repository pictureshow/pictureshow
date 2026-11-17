/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

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
import app.pictureshow.core.music.MUSIC_UNREACHABLE_TEXT
import app.pictureshow.core.music.MusicOutput
import app.pictureshow.core.music.MusicPlayRequest
import app.pictureshow.core.music.MusicReportEffect
import app.pictureshow.core.music.MusicReportEvent
import app.pictureshow.core.music.MusicReportState
import app.pictureshow.core.music.MusicRequest
import app.pictureshow.core.music.MusicService
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
import app.pictureshow.core.music.musicUnreachable
import app.pictureshow.core.music.musicUpcoming
import app.pictureshow.core.music.musicWindow
import app.pictureshow.core.music.planWindow
import app.pictureshow.core.music.reduceMusicReport
import app.pictureshow.core.music.reduceMusicSession
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import app.pictureshow.core.playback.Ticks
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A queue row as the viewer sees it: the entry's identity and what to show for it. */
data class DesktopMusicRow(val entry: String, val track: DesktopMusicTrack)

/** A play a page asked for. [owner] is the account key the tracks were read for; a play that outlives it is dropped. */
data class DesktopMusicPlay(
    val owner: String,
    val tracks: List<DesktopMusicTrack>,
    val start: Int? = null,
    /** "Type:id" of the album, playlist, artist or genre; null for a single track with no context. */
    val context: String? = null,
    val contextName: String? = null,
    val shuffle: Boolean = false
)

/** Everything the music pages, the mini player and Now playing draw from. */
data class DesktopMusicUi(
    val account: Account? = null,
    val queue: QueueState? = null,
    val context: String? = null,
    val contextName: String? = null,
    val tracks: Map<String, DesktopMusicTrack> = emptyMap(),
    /** Audio is coming out, or is meant to be. False when restored or stopped: the queue is here, ready to play. */
    val outputActive: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val volume: Int = 80,
    val output: MusicOutput? = null,
    val message: String? = null,
    /** A play that would replace another context's queue, waiting on the viewer's answer. */
    val prompt: DesktopMusicPlay? = null,
    val canRetrySave: Boolean = false
) {
    val current: DesktopMusicRow? get() = queue?.current?.let(::row)
    val upNext: List<DesktopMusicRow> get() = queue?.let { musicUpcoming(it).mapNotNull(::row) }.orEmpty()
    val played: List<DesktopMusicRow> get() = queue?.let { q -> q.order.take(q.order.indexOf(q.current).coerceAtLeast(0)).mapNotNull(::row) }.orEmpty()
    /** The mini player shows while there is something to play, restored or playing. */
    val visible: Boolean get() = current != null
    val repeat: String get() = queue?.repeat ?: "off"
    val shuffled: Boolean get() = queue?.shuffled == true

    private fun row(entry: String): DesktopMusicRow? {
        val media = queue?.entries?.firstOrNull { it.id == entry }?.media ?: return null
        return DesktopMusicRow(entry, tracks[media] ?: DesktopMusicTrack(media, ""))
    }
}

/**
 * Downloaded tracks (MUS-10), owned by the downloads stream. A ready file plays as a direct-play decision whose url is a
 * `file:` URI or a plain path; its listening is written down there and synced later, never sent live.
 */
interface DesktopMusicLocal {
    fun decision(account: Account, media: String): PlayDecision?
    fun downloaded(account: Account): Set<String>
    suspend fun report(account: Account, media: String, serial: Long, report: String, ticks: Long, runtimeTicks: Long?)
}

/** The server side of the music host, narrow so tests can answer from a fake Jellyfin. */
interface DesktopMusicGateway {
    suspend fun negotiate(account: Account, media: String, ceiling: Int, decoders: Set<AudioCodec>): ClientResult<PlayOutcome>
    suspend fun start(account: Account, info: PlaybackProgressInfo)
    suspend fun progress(account: Account, info: PlaybackProgressInfo)
    suspend fun stopped(account: Account, info: PlaybackStopInfo)
    /** The ids of [media] this viewer can still open, or a failure. */
    suspend fun check(account: Account, media: List<String>): ClientResult<List<String>>
}

internal class ClientDesktopMusicGateway(private val playback: PlaybackClient, private val music: MusicService) : DesktopMusicGateway {
    // MUS-06: the music profile and the quality ceiling, through the audio route of PlaybackInfo.
    override suspend fun negotiate(account: Account, media: String, ceiling: Int, decoders: Set<AudioCodec>) =
        playback.prepareFor(account, media, musicDeviceProfile(decoders, ceiling), ceiling, PlaybackRequest(audio = true))

    override suspend fun start(account: Account, info: PlaybackProgressInfo) { playback.reportStart(account, info) }
    override suspend fun progress(account: Account, info: PlaybackProgressInfo) { playback.reportProgress(account, info) }
    override suspend fun stopped(account: Account, info: PlaybackStopInfo) { playback.reportStopped(account, info) }

    override suspend fun check(account: Account, media: List<String>): ClientResult<List<String>> {
        val ids = mutableListOf<String>()
        for (batch in musicIdBatches(media)) {
            val request = MusicRequest("Items", mapOf("ids" to batch.joinToString(","), "limit" to batch.size.toString()))
            when (val page = music.music(account, request)) {
                is ClientResult.Failure -> return page
                is ClientResult.Success -> ids += page.value.items.map(MediaItem::id)
            }
        }
        return ClientResult.Success(ids)
    }
}

private class Prepared(val decision: PlayDecision, val entry: String, val media: String, val local: Boolean)

/**
 * The one music player on desktop (MUS-03, MUS-04, MUS-07, MUS-08). The core reducer owns the queue and this class owns
 * the output, a separate audio-only engine that keeps playing while the viewer browses. Every change takes one road: it
 * is reduced, saved, published, and only then reaches the output, so what's on disk is never behind what's heard.
 *
 * Every step runs in order on one channel, so the output is only ever touched from one place at a time. A step asked
 * for on behalf of one viewer runs for that viewer or not at all.
 */
class DesktopMusic internal constructor(
    private val scope: CoroutineScope,
    private val accounts: AccountStore,
    private val store: DesktopMusicStore,
    private val gateway: DesktopMusicGateway,
    private val newOutput: (volume: Int) -> DesktopMusicOutput,
    private val quality: () -> String,
    private val log: (String) -> Unit = {},
    private val seed: () -> Long = { Random.nextLong(Long.MAX_VALUE) },
    private val tickMs: Long = 250,
    /** Desktop has no metered-network signal to read; Auto takes the unmetered ceiling (ASSUMED wired networks). */
    private val metered: () -> Boolean = { false }
) : AutoCloseable {
    /** Set by the downloads stream (MUS-10); null plays everything from the server. */
    @Volatile var local: DesktopMusicLocal? = null

    private val mutable = MutableStateFlow(DesktopMusicUi(volume = store.volume()))
    val state: StateFlow<DesktopMusicUi> = mutable.asStateFlow()

    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val reportJobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var account: Account? = null
    private var binding = 0L
    private var queue = QueueStoreState(QueueState("", ""))
    private var session = MusicSessionState()
    private var reports = MusicReportState()
    private var playContext: String? = null
    private var contextName: String? = null
    private var sequence = 0L
    private var tracks: Map<String, DesktopMusicTrack> = emptyMap()
    private var restoring: Job? = null
    private var accountsSeen = false

    // The output and what this host knows it holds, in the output's order.
    private var output: DesktopMusicOutput? = null
    private var decoders: Set<AudioCodec> = emptySet()
    private val held = mutableListOf<String>()
    private val prepared = mutableMapOf<String, Prepared>()
    private val viewings = mutableMapOf<Long, Prepared>()
    private var preparing: Job? = null
    private var syncToken = 0L
    private var ceiling = -1
    private var ticker: Job? = null
    private var pollQueued = false
    private var lastIndex = -1
    private var lastPosition = 0.0
    private var lastDuration = 0.0
    private var lastPlaying = false
    private var endedFor: String? = null
    private var missingPolls = 0
    private var sinceReportMs = 0L
    private var sincePositionMs = 0L
    @Volatile private var closed = false

    // What the pages and the shell call.

    /** Play [play]: now, inside the playing context, or after the viewer confirms replacing another context's queue. */
    fun request(play: DesktopMusicPlay) = enqueueFor(play.owner) {
        val decision = musicPlayDecision(MusicPlayRequest(play.context, queue.committed.entries.size, playContext))
        when (decision) {
            "play" -> start(play)
            "jump" -> jumpOrStart(play)
            else -> publish { it.copy(prompt = play) }
        }
    }

    fun confirm() = enqueue {
        val play = mutable.value.prompt ?: return@enqueue
        publish { it.copy(prompt = null) }
        start(play)
    }

    fun dismissPrompt() = enqueue { publish { it.copy(prompt = null) } }
    fun dismissMessage() = enqueue { publish { it.copy(message = null) } }

    fun playNext(owner: Account, items: List<DesktopMusicTrack>) = add(owner, items, "play-next")
    fun append(owner: Account, items: List<DesktopMusicTrack>) = add(owner, items, "append")

    fun remove(entry: String) = enqueue {
        commit { QueueEvent("remove", revision = it.revision, generation = it.generation, entry = entry) }
        if (queue.committed.current == null) endOutput("queue-clear")
    }

    fun move(entry: String, index: Int) = enqueue {
        commit { QueueEvent("move", revision = it.revision, generation = it.generation, entry = entry, index = index) }
    }

    fun clearUpcoming() = enqueue { commit { QueueEvent("clear-upcoming", revision = it.revision, generation = it.generation) } }

    fun clear() = enqueue {
        applySession("queue-clear")
        commit { QueueEvent("clear-all", revision = it.revision, generation = it.generation) }
        teardown()
        playContext = null
        contextName = null
        publish()
    }

    fun jump(entry: String) = enqueue {
        applySession("viewer-play")
        activate()
        commit { QueueEvent("jump", revision = it.revision, generation = it.generation, entry = entry) }
    }

    fun play() = enqueue {
        if (queue.committed.current == null) return@enqueue
        applySession("viewer-play")
        activate()
        commit { QueueEvent("play", revision = it.revision, generation = it.generation) }
    }

    fun pause() = enqueue {
        applySession("viewer-pause")
        savePosition()
        commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
    }

    fun toggle() {
        val ui = mutable.value
        if (ui.outputActive && ui.queue?.paused == false) pause() else play()
    }

    fun next() = enqueue {
        if (!queue.committed.paused) activate()
        positionCommit()
        commit { QueueEvent("next", revision = it.revision, generation = it.generation, completion = it.completion) }
    }

    fun previous() = enqueue {
        positionCommit()
        val before = queue.committed.current
        commit { QueueEvent("previous", revision = it.revision, generation = it.generation) }
        if (queue.committed.current == before) {
            output?.seek(0.0)
            lastPosition = 0.0
            publish { it.copy(positionMs = 0) }
        }
    }

    fun seekTo(positionMs: Long) = enqueue {
        val target = positionMs.coerceAtLeast(0)
        val running = output
        if (running != null && lastIndex >= 0) {
            running.seek(target / 1000.0)
            lastPosition = target / 1000.0
            publish { it.copy(positionMs = target) }
        } else {
            commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = target * Ticks.PER_MILLISECOND) }
        }
    }

    /** Off, then all, then this track, then off (MUS-03). */
    fun cycleRepeat() = enqueue {
        val next = when (queue.committed.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
        commit { QueueEvent("repeat", revision = it.revision, generation = it.generation, repeat = next) }
    }

    fun toggleShuffle() = enqueue { shuffle(!queue.committed.shuffled) }
    fun setShuffle(on: Boolean) = enqueue { shuffle(on) }

    fun setVolume(value: Int) = enqueue {
        val v = value.coerceIn(0, 100)
        output?.volume(v)
        store.saveVolume(v)
        publish { it.copy(volume = v) }
    }

    fun retrySave() = enqueue {
        val active = account ?: return@enqueue
        val retried = reduceQueueStore(queue, QueueStoreEvent("retry-persist"))
        if (retried.effect != "persist") return@enqueue
        queue = retried
        commitPending(active)
    }

    /** Stop: the sound ends and the position is saved. The queue stays, ready to play. */
    fun stop() = enqueue { endOutput("stop") }

    /** Called before a video opens (MUS-04). Music stops keeping its place and resumes only when the viewer presses Play. */
    fun beforeVideo() {
        val asked = binding
        jobs.trySend { if (asked == binding) endOutput("video-start") }
    }

    /** One press of a media key arrives once per route; the stamp lets a second route's copy of it be ignored (MUS-05). */
    fun acceptKey(stamp: String): Boolean {
        session = reduceMusicSession(session, MusicSessionEvent("key", stamp))
        return session.effect == "accept"
    }

    /** Waits until every step asked for before this call has run. For tests and an orderly close. */
    internal suspend fun idle() {
        val done = CompletableDeferred<Unit>()
        jobs.trySend { done.complete(Unit) }
        done.await()
    }

    // Steps.

    private fun enqueue(job: suspend () -> Unit) {
        val asked = binding
        jobs.trySend { if (asked == binding) job() }
    }

    /** A step a page asked for on one viewer's behalf. A page can be ahead of this host, so the switch is made here first. */
    private fun enqueueFor(owner: String, job: suspend () -> Unit) {
        jobs.trySend {
            if (owner != account?.key && accounts.snapshots.value.currentAccount?.key == owner) bindLatest()
            if (owner == account?.key) job()
        }
    }

    private suspend fun bindLatest() {
        val snapshot = accounts.snapshots.value
        bind(snapshot.currentAccount, snapshot.privateStateOwners.map(Account::key).toSet())
        // A viewer no longer saved here, or signed out (AUTH-10), takes their saved queue with them. The account store
        // publishes an empty snapshot before it has read its file, so nothing is pruned until it has shown an account:
        // pruning on that first, empty value would delete every saved queue at each launch.
        if (snapshot.accounts.isNotEmpty()) accountsSeen = true
        if (accountsSeen) store.retain(snapshot.privateStateOwners)
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
        mutable.value = DesktopMusicUi(account = next, queue = next?.let { queue.committed }, volume = mutable.value.volume)
        if (next != null) {
            val bound = binding
            val empty = queue
            // Off the step road, so Play and Stop never wait on the server check.
            restoring = scope.launch { restore(next, bound, empty) }
        }
    }

    /** The same viewer with a new token: what's playing is reopened where it is, with requests that carry the new one. */
    private suspend fun renewed() {
        preparing?.cancel()
        prepared.clear()
        val running = output ?: return
        val at = lastPosition
        running.replace(account ?: return, emptyList(), 0.0, true)
        held.clear()
        lastIndex = -1
        if (queue.committed.current != null) {
            commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = (at * 1000).toLong() * Ticks.PER_MILLISECOND) }
        }
        sync()
    }

    /** A saved queue comes back paused, without what the server no longer lets this viewer open (QUE-07, MUS-08). */
    private suspend fun restore(account: Account, bound: Long, empty: QueueStoreState) {
        val saved = store.load(account) ?: return log("music restore-none")
        if (saved.queue.server != account.server.id || saved.queue.viewer != account.user.id) return log("music restore-other-partition")
        val media = saved.queue.entries.map(QueueEntry::media).distinct()
        val pages = mutableListOf<ClientResult<List<String>>>()
        for (batch in musicIdBatches(media)) {
            val page = gateway.check(account, batch)
            pages += page
            if (page !is ClientResult.Success) break
        }
        val accessible = desktopAccessibleAfter(media.toSet(), pages)
        jobs.trySend {
            if (binding == bound && this.account?.key == account.key && queue === empty) applyRestore(account, saved, accessible)
        }
    }

    private suspend fun applyRestore(account: Account, saved: DesktopMusicSnapshot, accessible: Set<String>) {
        val ids = saved.queue.entries.filter { it.media in accessible }.map(QueueEntry::id)
        val restored = reduceQueueStore(queue, QueueStoreEvent("restore", saved = saved.queue, accessible = ids))
        if (restored.effect != "persist") return log("music restore-refused reason=${restored.reason}")
        queue = restored
        tracks = saved.tracks.associateBy(DesktopMusicTrack::id)
        playContext = saved.context
        contextName = saved.contextName
        sequence = saved.sequence
        val removed = restored.removed.size
        commitPending(account)
        if (removed > 0) publish { it.copy(message = "$removed saved ${if (removed == 1) "track is" else "tracks are"} no longer available.") }
    }

    private suspend fun jumpOrStart(play: DesktopMusicPlay) {
        val media = play.start?.let(play.tracks::getOrNull)?.id
        val entry = queue.committed.entries.firstOrNull { it.media == media }?.id
        if (play.shuffle || entry == null) return start(play)
        activate()
        applySession("viewer-play")
        commit { QueueEvent("jump", revision = it.revision, generation = it.generation, entry = entry) }
    }

    private suspend fun start(play: DesktopMusicPlay) {
        if (play.owner != account?.key) return
        val limited = play.tracks.take(MUSIC_QUEUE_LIMIT)
        if (limited.isEmpty()) return
        val entries = musicEntries(limited.map(DesktopMusicTrack::id), sequence)
        sequence += entries.size
        tracks = tracks + limited.associateBy(DesktopMusicTrack::id)
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
        applySession("viewer-play")
        activate()
        commit { QueueEvent("play-now", revision = it.revision, generation = it.generation, entries = entries, entry = selected, permutation = order) }
        if (limited.size < play.tracks.size) publish { it.copy(message = "Playing the first $MUSIC_QUEUE_LIMIT tracks.") }
    }

    private fun add(owner: Account, items: List<DesktopMusicTrack>, op: String) = enqueueFor(owner.key) {
        val limited = items.take((MUSIC_QUEUE_LIMIT - queue.committed.entries.size).coerceAtLeast(0))
        if (limited.isEmpty()) {
            if (items.isNotEmpty()) publish { it.copy(message = "The queue is full.") }
            return@enqueueFor
        }
        val entries = musicEntries(limited.map(DesktopMusicTrack::id), sequence)
        sequence += entries.size
        tracks = tracks + limited.associateBy(DesktopMusicTrack::id)
        // The first thing added to an empty queue is ready to play, and adding never starts it by itself.
        val added = if (queue.committed.current == null) {
            commit { QueueEvent("play-now", revision = it.revision, generation = it.generation, entries = entries) } &&
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
        } else {
            commit { QueueEvent(op, revision = it.revision, generation = it.generation, entries = entries) }
        }
        if (!added) return@enqueueFor
        val count = limited.size
        publish { it.copy(message = if (count == 1) "Added ${limited[0].title} to the queue." else "Added $count tracks to the queue.") }
    }

    private suspend fun shuffle(on: Boolean) {
        val q = queue.committed
        if (on == q.shuffled) return
        if (on) {
            val upcoming = musicUpcoming(q).filter { it !in q.visited }
            commit { QueueEvent("shuffle", revision = it.revision, generation = it.generation, permutation = musicPermutation(upcoming, seed())) }
        } else {
            commit { QueueEvent("unshuffle", revision = it.revision, generation = it.generation) }
        }
    }

    private suspend fun positionCommit() {
        if (output == null || lastIndex < 0) return
        val ticks = currentTicks()
        commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = ticks) }
    }

    // The queue's one road.

    /** The output exists from the first Play until Stop. */
    private fun activate() {
        if (output != null || closed) return
        val created = runCatching { newOutput(mutable.value.volume) }.getOrElse {
            log("music output-failed kind=${it::class.simpleName}")
            publish { ui -> ui.copy(message = "Music can't play on this computer: its audio engine didn't start.") }
            return
        }
        output = created
        decoders = runCatching { created.decoders() }.getOrDefault(emptySet())
        held.clear()
        lastIndex = -1
        lastPosition = 0.0
        lastDuration = 0.0
        lastPlaying = false
        endedFor = null
        missingPolls = 0
        sinceReportMs = 0
        sincePositionMs = 0
        log("music output-open")
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (!pollQueued) {
                    pollQueued = true
                    jobs.trySend { pollQueued = false; poll() }
                }
            }
        }
    }

    private suspend fun commit(event: (QueueState) -> QueueEvent): Boolean {
        val active = account ?: return false
        val edit = event(queue.committed)
        val outcome = reduceQueue(queue.committed, edit).outcome
        val next = reduceQueueStore(queue, QueueStoreEvent("edit", edit = edit))
        if (next.effect != "persist") {
            log("music edit-refused op=${edit.op} reason=${next.reason ?: outcome}")
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
        queue = reduceQueueStore(queue, QueueStoreEvent(if (saved) "persisted" else "persist-failed", ticket = queue.ticket))
        if (!saved) {
            publish { it.copy(message = "Couldn't save the queue on this computer, so that change didn't apply.", canRetrySave = true) }
            return false
        }
        publish { it.copy(canRetrySave = false) }
        sync()
        return true
    }

    private fun snapshot(q: QueueState): DesktopMusicSnapshot {
        val media = q.entries.map(QueueEntry::media).toSet()
        return DesktopMusicSnapshot(q, playContext, contextName, sequence, media.mapNotNull(tracks::get))
    }

    private fun publish(change: (DesktopMusicUi) -> DesktopMusicUi = { it }) {
        val running = output
        val q = queue.committed
        val media = q.entries.firstOrNull { it.id == q.current }?.media
        mutable.update {
            change(it).copy(
                account = account,
                queue = q.takeIf { account != null },
                context = playContext,
                contextName = contextName,
                tracks = tracks,
                outputActive = running != null,
                isPlaying = running != null && lastPlaying,
                durationMs = (lastDuration.takeIf { running != null && it > 0 }?.let { d -> (d * 1000).toLong() })
                    ?: tracks[media]?.durationTicks?.let { t -> t / Ticks.PER_MILLISECOND }?.takeIf { d -> d > 0 },
                positionMs = if (running != null && lastIndex >= 0) (lastPosition * 1000).toLong() else q.positionTicks / Ticks.PER_MILLISECOND
            )
        }
    }

    // The output.

    private fun sync() {
        preparing?.cancel()
        val running = output ?: return
        running.loopCurrent(musicOutputRepeats(queue.committed))
        running.paused(queue.committed.paused)
        val token = ++syncToken
        val active = account ?: return
        val q = queue.committed
        preparing = scope.launch { prepareWindow(active, q, token) }
    }

    /** Negotiates each entry of the window as it comes near (MUS-06), off the step road, then applies it on the road. */
    private suspend fun prepareWindow(active: Account, q: QueueState, token: Long) {
        val target = musicWindow(q)
        if (target.isEmpty()) return
        val nextCeiling = musicBitrateCeiling(quality(), metered())
        val entries = q.entries.associateBy(QueueEntry::id)
        val ready = mutableMapOf<String, Prepared>()
        val known = jobsRead { if (nextCeiling != ceiling) mapOf() else prepared.toMap() }
        for ((index, entry) in target.withIndex()) {
            val have = known[entry]
            if (have != null) { ready[entry] = have; continue }
            val media = entries[entry]?.media ?: break
            val file = local?.decision(active, media)
            if (file != null) { ready[entry] = Prepared(file, entry, media, local = true); continue }
            val outcome = gateway.negotiate(active, media, nextCeiling, decoders)
            when {
                outcome is ClientResult.Success && outcome.value is PlayOutcome.Play ->
                    ready[entry] = Prepared((outcome.value as PlayOutcome.Play).decision, entry, media, local = false)
                index > 0 -> break
                outcome is ClientResult.Failure -> { jobs.trySend { if (token == syncToken) negotiationFailed(outcome.error, entry) }; return }
                else -> { jobs.trySend { if (token == syncToken) failCurrent() }; return }
            }
        }
        jobs.trySend {
            if (token != syncToken || account?.key != active.key) return@trySend
            if (nextCeiling != ceiling) {
                // A quality change applies from what hasn't been opened yet.
                prepared.keys.retainAll(setOfNotNull(held.getOrNull(lastIndex)))
                ceiling = nextCeiling
            }
            prepared.keys.retainAll(target.toSet() + setOfNotNull(held.getOrNull(lastIndex)))
            ready.forEach { (k, v) -> prepared.putIfAbsent(k, v) }
            applyWindow(active, target)
        }
    }

    /** Reads host state on the step road from off it. */
    private suspend fun <T> jobsRead(read: () -> T): T {
        val answer = CompletableDeferred<T>()
        jobs.trySend { answer.complete(read()) }
        return answer.await()
    }

    private fun applyWindow(active: Account, target: List<String>) {
        val running = output ?: return
        val ready = target.takeWhile { it in prepared }
        if (ready.isEmpty()) return
        val playing = held.getOrNull(lastIndex)
        val plan = planWindow(held.toList(), playing, ready)
        if (plan.replace) {
            val start = queue.committed.positionTicks / Ticks.PER_MILLISECOND / 1000.0
            running.replace(active, plan.add.map { prepared.getValue(it).source() }, start, queue.committed.paused)
            held.clear()
            held += plan.add
            lastIndex = 0
            lastPosition = start
            lastDuration = 0.0
            endedFor = null
            missingPolls = 0
            loaded(plan.add.first(), natural = false)
        } else {
            if (plan.removeAfter > 0) {
                running.remove(held.size - plan.removeAfter, held.size)
                repeat(plan.removeAfter) { held.removeAt(held.size - 1) }
            }
            if (plan.removeBefore > 0) {
                running.remove(0, plan.removeBefore)
                repeat(plan.removeBefore) { held.removeAt(0) }
                lastIndex -= plan.removeBefore
            }
            if (plan.add.isNotEmpty()) {
                running.append(active, plan.add.map { prepared.getValue(it).source() })
                held += plan.add
            }
        }
        running.paused(queue.committed.paused)
        updateOutputLabel()
        publish()
    }

    private fun Prepared.source() = DesktopMusicSource(entry, decision, local)

    private fun updateOutputLabel() {
        val playing = prepared[held.getOrNull(lastIndex)] ?: return publish { it.copy(output = null) }
        val decision = playing.decision
        val facts = musicOutputFacts(decision.mediaSource, decision.method, "device", ceiling.takeIf { it > 0 } ?: 0, decision.transcodeReasons)
        publish { it.copy(output = facts) }
    }

    /** What the output started holding at the head: one viewing per time a track is heard. */
    private fun loaded(entry: String, natural: Boolean) {
        val track = prepared[entry] ?: return
        val leaving = reports.viewing?.media?.let(tracks::get)?.durationTicks ?: 0
        report(MusicReportEvent("load", entry, track.media, ticks = currentTicks(), natural = natural, durationTicks = leaving))
        reports.viewing?.let { viewings[it.serial] = track }
        if (lastPlaying) report(MusicReportEvent("playing", ticks = 0))
    }

    /**
     * The output is read, not heard from: each poll compares what it holds and plays with what it held and played last
     * time, and turns the difference into the events a player would send (a track change, an end, a loop, a failure).
     */
    private suspend fun poll() {
        val running = output ?: return
        val active = account ?: return
        val st = runCatching { running.state() }.getOrNull() ?: return
        val q = queue.committed
        if (st.index >= 0 && st.index < held.size) {
            missingPolls = 0
            if (lastIndex >= 0 && st.index > lastIndex) {
                // The output moved on by itself: the track before ended, or failed to open and was skipped.
                val failed = lastDuration <= 0.0 && lastPosition <= 0.0
                val leftAt = currentTicks()
                lastIndex = st.index
                lastPosition = st.positionSeconds
                lastDuration = st.durationSeconds
                endedFor = null
                if (failed) {
                    report(MusicReportEvent("stop", ticks = leftAt, failed = true))
                    loaded(held[st.index], natural = false)
                    failCurrent()
                } else {
                    loaded(held[st.index], natural = true)
                    commit { QueueEvent("end", revision = it.revision, generation = it.generation, completion = it.completion) }
                }
                return
            }
            if (st.index == lastIndex && lastDuration > 0 && lastPosition > lastDuration - 2.0 && st.positionSeconds < 1.5 && musicOutputRepeats(q)) {
                // Repeat One loops inside the output; each pass is a new hearing.
                lastPosition = st.positionSeconds
                loaded(held[st.index], natural = true)
                commit { QueueEvent("end", revision = it.revision, generation = it.generation, completion = it.completion) }
                return
            }
            lastIndex = st.index
        } else if (held.isNotEmpty() && !q.paused) {
            // The output holds nothing playable although it should: what it was asked to open didn't open.
            if (++missingPolls >= 4) {
                missingPolls = 0
                report(MusicReportEvent("stop", ticks = currentTicks(), failed = true))
                classifyFailure(active, held.getOrNull(lastIndex.coerceAtLeast(0)))
                return
            }
        }
        if (st.durationSeconds > 0) lastDuration = st.durationSeconds
        if (st.index >= 0) lastPosition = st.positionSeconds
        val playing = st.index >= 0 && !st.paused && !st.buffering && !st.ended
        if (playing != lastPlaying) {
            lastPlaying = playing
            if (reports.viewing != null) {
                report(if (playing) MusicReportEvent("playing", ticks = currentTicks()) else MusicReportEvent("progress", ticks = currentTicks(), paused = true))
            }
            if (!playing) savePosition()
        }
        if (st.ended && !q.paused && endedFor != q.current) {
            endedFor = q.current
            commit { QueueEvent("end", revision = it.revision, generation = it.generation, completion = it.completion) }
            return
        }
        if (playing) {
            sinceReportMs += tickMs
            sincePositionMs += tickMs
            if (sinceReportMs >= PROGRESS_REPORT_MS) { sinceReportMs = 0; report(MusicReportEvent("progress", ticks = currentTicks())) }
            if (sincePositionMs >= POSITION_SAVE_MS) { sincePositionMs = 0; savePosition() }
        }
        publish { it.copy(buffering = st.buffering) }
    }

    /** An open that failed says nothing about why, so the server is asked: gone, refused, or unreachable. */
    private suspend fun classifyFailure(active: Account, entry: String?) {
        val media = queue.committed.entries.firstOrNull { it.id == entry }?.media
        val answer = if (media != null && prepared[entry]?.local != true) gateway.check(active, listOf(media)) else null
        when {
            answer is ClientResult.Failure && answer.error.kind == ErrorKind.EXPIRED -> revoked()
            answer is ClientResult.Failure -> {
                savePosition()
                if (unreachable(entry)) return
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
                publish { it.copy(message = "Lost the connection. Your place is saved: press Play to try again.") }
            }
            else -> failCurrent()
        }
    }

    private suspend fun negotiationFailed(error: ClientError, entry: String) {
        when {
            error.kind == ErrorKind.EXPIRED -> revoked()
            error.status == 403 || error.status == 404 -> failCurrent()
            else -> unreachable(entry)
        }
    }

    /** The server can't be reached for the current track; a queue with downloads in it follows the offline rule (MUS-10). */
    private suspend fun unreachable(entry: String?): Boolean {
        val q = queue.committed
        if (entry != null && entry != q.current) return true
        val media = q.entries.firstOrNull { it.id == q.current }?.media
        val title = media?.let(tracks::get)?.title.orEmpty()
        val downloaded = account?.let { a -> local?.downloaded(a) }.orEmpty()
        val decision = musicUnreachable(q, downloaded, title)
        if (decision.step == "skip" && !q.paused) {
            commit { QueueEvent("error", revision = it.revision, generation = it.generation, completion = it.completion, music = true) }
            publish { it.copy(message = decision.message) }
        } else {
            output?.paused(true)
            commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
            publish { it.copy(message = if (decision.step == "skip") MUSIC_UNREACHABLE_TEXT else decision.message) }
        }
        return true
    }

    private suspend fun failCurrent() {
        commit { QueueEvent("error", revision = it.revision, generation = it.generation, completion = it.completion, music = true) }
    }

    /** The server refused this viewer's token: the account is marked expired and the queue waits for them, paused. */
    private suspend fun revoked() {
        val active = account
        endOutput("revoked")
        if (active != null && active.accessToken.isNotEmpty()) runCatching { accounts.expire(active.key, active.accessToken) }
        publish { it.copy(message = "This account needs to sign in again. Your queue is saved for when it does.") }
    }

    private fun currentTicks(): Long = (lastPosition * 1000).toLong() * Ticks.PER_MILLISECOND

    private suspend fun savePosition() {
        val active = account ?: return
        if (output == null) return
        store.savePosition(active, DesktopMusicPosition(held.getOrNull(lastIndex), currentTicks()))
    }

    private fun report(event: MusicReportEvent) {
        val active = account ?: return
        reports = reduceMusicReport(reports, event)
        reports.effects.forEach { send(active, it) }
    }

    private fun send(active: Account, effect: MusicReportEffect) {
        val track = viewings[effect.serial]
        val decision = track?.decision
        val files = local
        // Reports leave in order on their own road, so a slow server never holds the player.
        reportJobs.trySend {
            if (track != null && track.local) {
                files?.report(active, effect.media, effect.serial, effect.report, effect.ticks, decision?.mediaSource?.runTimeTicks)
            } else if (decision != null) {
                when (effect.report) {
                    "start" -> gateway.start(active, progressInfo(decision, effect))
                    "progress" -> gateway.progress(active, progressInfo(decision, effect))
                    else -> gateway.stopped(active, PlaybackStopInfo(
                        itemId = effect.media,
                        mediaSourceId = decision.mediaSource.id,
                        playSessionId = decision.playSessionId,
                        positionTicks = effect.ticks,
                        failed = effect.failed
                    ))
                }
            }
        }
        if (effect.report == "stopped") viewings.remove(effect.serial)
    }

    private fun progressInfo(decision: PlayDecision, effect: MusicReportEffect) = PlaybackProgressInfo(
        itemId = effect.media,
        mediaSourceId = decision.mediaSource.id,
        playSessionId = decision.playSessionId,
        playMethod = decision.method,
        positionTicks = effect.ticks,
        isPaused = effect.paused
    )

    private fun applySession(op: String) { session = reduceMusicSession(session, MusicSessionEvent(op)) }

    private suspend fun endOutput(cause: String, ticks: Long = currentTicks()) {
        applySession(cause)
        val active = account
        if (output != null && active != null) {
            store.savePosition(active, DesktopMusicPosition(queue.committed.current, ticks))
            if (queue.committed.current != null) {
                commit { QueueEvent("pause", revision = it.revision, generation = it.generation) }
                commit { QueueEvent("position", revision = it.revision, generation = it.generation, ticks = ticks) }
            }
        }
        teardown()
    }

    private fun teardown() {
        preparing?.cancel()
        ticker?.cancel()
        syncToken++
        val running = output ?: return publish()
        report(MusicReportEvent("stop", ticks = currentTicks()))
        output = null
        runCatching { running.close() }
        held.clear()
        prepared.clear()
        lastIndex = -1
        lastPlaying = false
        log("music output-close")
        publish { it.copy(output = null, buffering = false) }
    }

    /** At app exit: the step road may already be gone, so the engine is closed here (its calls are synchronized). */
    fun closeNow() {
        closed = true
        ticker?.cancel()
        preparing?.cancel()
        output?.let { runCatching { it.close() } }
        output = null
    }

    override fun close() {
        if (closed) return
        closed = true
        jobs.trySend { teardown() }
    }

    init {
        scope.launch {
            for (job in jobs) {
                // One failed step mustn't end the only road the queue has.
                try {
                    job()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log("music step-failed kind=${failure::class.simpleName}")
                }
            }
        }
        scope.launch { for (job in reportJobs) runCatching { job() } }
        scope.launch {
            accounts.snapshots
                .distinctUntilChangedBy { Triple(it.currentAccount?.key, it.currentAccount?.accessToken, it.privateStateOwners.map(Account::key)) }
                .collect { jobs.trySend { bindLatest() } }
        }
    }

    private companion object {
        const val POSITION_SAVE_MS = 5_000L
        const val PROGRESS_REPORT_MS = 10_000L
    }
}
