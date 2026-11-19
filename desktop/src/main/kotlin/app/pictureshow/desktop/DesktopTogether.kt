/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.RemoteClient
import app.pictureshow.core.control.RemoteEffect
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteViewing
import app.pictureshow.core.sync.SyncCloseReason
import app.pictureshow.core.sync.SyncClient
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.syncEnqueue
import app.pictureshow.core.sync.syncNewQueue
import app.pictureshow.core.sync.syncSourceEligible
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop video kinds a remote or a group may open; music goes to [DesktopMusic]. */
internal val DESKTOP_REMOTE_VIDEO_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Recording", "Trailer")

/**
 * What the shared hosts need from desktop playback. [DesktopController] implements it; tests use a fake. Every call
 * comes on the host thread; implementations hop to their own threads as they need.
 */
internal interface DesktopTogetherPlayback {
    /** The video viewing on screen now, or null. Its [DesktopTogetherVideo.token] changes with every new viewing. */
    fun video(): DesktopTogetherVideo?
    fun music(): DesktopMusic?

    /** Whether music can take a remote's tracks here (the music player exists). */
    fun hasMusic(): Boolean = music() != null

    /** Music starts a remote asks for. Callers check [DesktopTogether]'s `allowed` first, after any lookup. */
    fun playMusic(play: DesktopMusicPlay)
    fun queueMusic(owner: Account, tracks: List<DesktopMusicTrack>, next: Boolean)

    /** Open [itemId] in the player; [admit] runs on the host thread with whether it resolved, and false drops it. */
    fun open(itemId: String, startMs: Long?, admit: suspend (Boolean) -> Boolean)
    fun toggle()
    fun seek(positionMs: Long)
    fun stop()
    fun nextEpisode()
    fun audio(index: Int)
    fun subtitle(index: Int)

    /** Straight to the engine, never back through the group: the group's own corrections use these. */
    fun engineSeek(positionMs: Long)
    fun enginePlaying(playing: Boolean)
    fun engineSpeed(rate: Double): Boolean
}

/** One video viewing as the hosts see it. */
internal data class DesktopTogetherVideo(
    val token: Any,
    val itemId: String,
    val itemType: String,
    val positionMs: Long,
    val durationMs: Long,
    val ready: Boolean,
    val paused: Boolean,
    val live: Boolean,
    val failed: Boolean = false,
    val startMs: Long = 0,
    /** A downloaded copy playing from this computer: never a group's source (core syncSourceEligible). */
    val offline: Boolean = false
)

/**
 * Stream E: remote control both ways (REM-*) and Watch together (SYN-*), on the hosts ported from Android. One socket
 * serves both, as on Android: the remote target's socket carries the group's messages. All host state lives on one
 * thread ([hostDispatcher], Swing's in the app), the way Android keeps it on Main.
 */
internal class DesktopTogether(
    private val playback: DesktopTogetherPlayback,
    http: HttpClient,
    identity: ClientIdentity,
    internal val hostDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Whether the caller is already on [hostDispatcher]'s thread. */
    private val onHost: () -> Boolean = { javax.swing.SwingUtilities.isEventDispatchThread() },
    private val sockets: RemoteSocketFactory = OkHttpRemoteSocketFactory(),
    private val log: (String) -> Unit = {},
    private val admitted: (Account) -> Boolean = { true },
    /**
     * Whether a remote's command may still start something for [Account] now: it is the signed-in viewer and no PIN
     * holds it. Asked after every lookup, since the profile can lock or switch while one is in flight.
     */
    private val allowed: (Account) -> Boolean = { true },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    internal val scope = CoroutineScope(SupervisorJob() + hostDispatcher)
    private val remoteClient: (Account) -> RemoteClient = { RemoteClient(http, identity) }
    private val syncClient = SyncClient(http, identity)

    val remote = RemoteTargetHost(
        scope,
        sockets,
        register = { account, commands -> remoteClient(account).registerTarget(account, commands.isNotEmpty(), commands) },
        authorization = { account -> AuthorizationHeader.build(identity.forAccount(account), account.accessToken) },
        nowMs = nowMs,
        trace = log,
        admitted = admitted
    )

    val controller = RemoteControllerHost(
        scope,
        object : RemoteControllerGateway {
            override suspend fun targets(account: Account) = remoteClient(account).targets(account)
            override suspend fun send(account: Account, request: RemoteRequest) = remoteClient(account).send(account, request)
        },
        nowMs
    )

    private var titleLookup: suspend (Account, String) -> ClientResult<MediaItem> = { _, _ -> ClientResult.Success(MediaItem("", "", "")) }
    private var itemsLookup: suspend (Account, List<String>) -> List<MediaItem> = { _, _ -> emptyList() }

    val sync = SyncPlayHost(
        scope,
        object : SyncPlayGateway {
            override suspend fun groups(account: Account) = syncClient.groups(account)
            override suspend fun title(account: Account, itemId: String): ClientResult<String?> =
                when (val item = titleLookup(account, itemId)) {
                    is ClientResult.Success -> ClientResult.Success(item.value.name)
                    is ClientResult.Failure -> item
                }
            override suspend fun access(account: Account) = syncClient.access(account)
            override suspend fun time(account: Account) = syncClient.utcTime(account)
            override suspend fun send(account: Account, request: SyncRequest) = syncClient.send(account, request)
        }
    )

    private var account: Account? = null
    private var policy = "same-viewer"
    private var videoToken: Any? = null
    private var musicBound = false
    private val musicToken = Any()

    /** A title the group asked for, until its viewing appears and binds with it. */
    private var pendingSyncOpen: SyncOpen? = null

    /** Lookups through the controller's catalog client (wired by [DesktopController.startTogether]). */
    fun lookups(item: suspend (Account, String) -> ClientResult<MediaItem>, items: suspend (Account, List<String>) -> List<MediaItem>) {
        titleLookup = item
        itemsLookup = items
    }

    /** Starts serving: the controller's account and settings drive the hosts from here on. Call on the host thread. */
    fun start(accounts: kotlinx.coroutines.flow.Flow<Account?>, receiver: kotlinx.coroutines.flow.Flow<String>) {
        wire()
        remote.setForeground(true)
        controller.setForeground(true)
        scope.launch {
            combine(accounts, receiver) { a, p -> a to p }.distinctUntilChanged().collect { (a, p) -> bind(a, p) }
        }
        scope.launch { remote.opens.collect(::openFromRemote) }
        scope.launch { remote.navigation.collect { name -> log("remote navigation name=$name") } }
        scope.launch { sync.opens.collect(::openFromGroup) }
        // The player's state as the hosts see it: what a remote reads, and the bridge/renderer binding per viewing.
        // One failed look must not end the loop: it is also what unbinds a closed player, so the stay bar and the remote's
        // view of this device depend on it (macOS recheck: mpv refused a pause before it was initialized, the loop died,
        // and the bar never came back after Back).
        scope.launch {
            while (true) {
                try {
                    observe()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("together observe failed error=${e.javaClass.simpleName}")
                }
                kotlinx.coroutines.delay(OBSERVE_MS)
            }
        }
    }

    /** SyncPlayCoordinator's wiring on Android: one socket, the group's messages and queue requests through it. */
    private fun wire() {
        fun subscription(generation: Long, rotate: Boolean) {
            remote.syncMessages = { text -> sync.receive(text, generation) }
            if (rotate) remote.renewSyncSocket()
        }
        sync.membershipSocket = ::subscription
        subscription(sync.membership.generation, false)
        remote.syncQueueRequest = { effect -> groupQueueRequest(sync, effect) }
        remote.syncConnection = sync::socket
        sync.socket(remote.syncSocketReady)
    }

    fun bind(next: Account?, nextPolicy: String) {
        val switched = next?.key != account?.key
        account = next
        policy = nextPolicy
        if (switched) pendingSyncOpen = null
        sync.bind(next)
        remote.bind(next, nextPolicy)
        remote.requireSyncSocket(next?.expired == false)
        controller.bind(next)
    }

    /** The profile lock opened or closed. */
    fun lockChanged(locked: Boolean) {
        remote.lockChanged()
        if (locked) sync.background()
    }

    // The player's side: the desktop shell calls these from the poll and from the viewer's own controls.

    /** Called on the host thread whenever playback state changes (the controller's 500 ms poll and page changes). */
    fun observe() {
        val video = playback.video()
        if (video?.token !== videoToken) {
            videoToken?.let { old ->
                sync.clearRenderer(old)
                remote.clearPlayer(old)
            }
            videoToken = video?.token
            heldPlaying = null
            if (video != null) bindVideo(video)
        } else if (video != null) {
            retryHeldPlaying(video)
        }
        if (video != null) {
            remote.report(video.token, RemoteViewing(video.itemId, video.ready, video.durationMs * 10_000, video.positionMs * 10_000, video.paused, !video.live))
        }
        observeMusic(video == null)
    }

    private fun bindVideo(video: DesktopTogetherVideo) {
        remote.setPlayer(video.token, videoBridge)
        val opening = pendingSyncOpen?.takeIf { it.itemId == video.itemId }
        pendingSyncOpen = null
        val current = sync.membership.queue?.current
        val selection = if (opening == null && sync.participating && sync.membership.group != null && current?.itemId != video.itemId) {
            SyncLocalSelection(video.itemId, video.startMs * 10_000)
        } else {
            null
        }
        if (!sync.bindRenderer(video.token, renderer, selection, opening)) enginePlaying(false)
    }

    /**
     * The group's play/pause for a player that could not take it yet (mpv before its core is up refuses commands).
     * Retried on each look until the engine takes it or a newer choice replaces it.
     */
    private var heldPlaying: Boolean? = null

    private fun enginePlaying(playing: Boolean) {
        heldPlaying = if (runCatching { playback.enginePlaying(playing) }.isSuccess) null else playing
        if (heldPlaying != null) log("together engine not ready; holding playing=$playing")
    }

    private fun retryHeldPlaying(video: DesktopTogetherVideo) {
        val playing = heldPlaying ?: return
        if (playing != video.paused) {
            heldPlaying = null
            return
        }
        enginePlaying(playing)
    }

    private fun observeMusic(free: Boolean) {
        val music = playback.music()
        val active = free && music != null && music.state.value.outputActive
        if (active && !musicBound) remote.setPlayer(musicToken, musicBridge(requireNotNull(music)))
        if (!active && musicBound) remote.clearPlayer(musicToken)
        musicBound = active
        if (active) {
            val s = requireNotNull(music).state.value
            val media = s.current?.track?.id ?: return
            remote.report(musicToken, RemoteViewing(media, !s.buffering, (s.durationMs ?: 0) * 10_000, s.positionMs * 10_000, !s.isPlaying, s.durationMs != null))
        }
    }

    /** The viewer's own transport while in a group goes to the group; true means the group took it. */
    fun transport(command: String, positionMs: Long? = null): Boolean {
        if (!sync.participating) return false
        if (onHost()) return sync.transport(command, positionMs)
        scope.launch { sync.transport(command, positionMs) }
        return true
    }

    /** SYN-08: closing the player keeps or leaves membership by core's stay rules. */
    fun closing(reason: SyncCloseReason) {
        if (sync.participating) scope.launch { sync.closeViewing(reason) }
    }

    /** A genuine end while in a group: true when the group owns what happens next (no local autoplay). */
    fun ended(): Boolean {
        if (!sync.participating) return false
        scope.launch {
            val token = videoToken ?: return@launch
            sync.rendererEnded(token, renderer, renderer.sourceIdentity, sync.membership.generation, sync.membership.queue?.current?.playlistItemId)
        }
        return true
    }

    val participating: Boolean get() = sync.participating

    /** The group's view of this device's player, read from the engine through the controller. */
    private val renderer = object : SyncRenderer {
        override val sourceIdentity: Any get() = videoToken ?: this
        override fun snapshot(): SyncRendererState {
            val v = playback.video()
            if (v == null) return SyncRendererState("", 0, 0, ready = false, buffering = false, playing = false, seekable = false)
            val durationTicks = v.durationMs * 10_000
            return SyncRendererState(
                v.itemId,
                v.positionMs * 10_000,
                durationTicks,
                ready = v.ready && !v.failed,
                buffering = !v.ready && !v.failed,
                playing = v.ready && !v.paused,
                seekable = syncSourceEligible(durationTicks, !v.live, v.live || v.itemType == "TvChannel", v.live, offline = v.offline),
                rateSupported = true,
                playWhenReady = !v.paused
            )
        }

        override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double): Boolean {
            val v = playback.video() ?: return false
            var sought = false
            // Never throw into the host: an engine that isn't up yet refuses, and the group corrects it on a later tick.
            if (positionTicks != null && kotlin.math.abs(v.positionMs - positionTicks / 10_000) > 1) {
                sought = runCatching { playback.engineSeek(positionTicks / 10_000) }.isSuccess
            }
            runCatching { playback.engineSpeed(rate) }
            if (playing != null) {
                if (playing == v.paused) enginePlaying(playing) else heldPlaying = null
            }
            if (sought) {
                // mpv takes a seek as soon as it is asked; tell the group once this call has returned.
                val self = this
                val token = v.token
                val generation = sync.membership.generation
                val entry = sync.membership.queue?.current?.playlistItemId
                scope.launch { sync.rendererPositionApplied(token, self, token, generation, entry) }
            }
            return sought
        }
    }

    private val videoBridge = object : RemotePlayerBridge {
        override fun pause() { playback.video()?.takeIf { !it.paused }?.let { playback.toggle() } }
        override fun unpause() { playback.video()?.takeIf { it.paused }?.let { playback.toggle() } }
        override fun report() = observe()
        override fun seekTo(positionMs: Long) = playback.seek(positionMs)
        override fun stop() = playback.stop()
        override fun next(item: String?) {
            if (item == null) playback.nextEpisode() else playback.open(item, null) { ok -> ok }
        }
        override fun audio(index: Int) = playback.audio(index)
        override fun subtitle(index: Int) = playback.subtitle(index)
    }

    private fun musicBridge(music: DesktopMusic) = object : RemotePlayerBridge {
        override fun pause() = music.pause()
        override fun unpause() = music.play()
        override fun report() = observe()
        override fun seekTo(positionMs: Long) = music.seekTo(positionMs)
        override fun stop() = music.stop()
        override fun next(item: String?) = music.next()
        override fun previous(): Boolean {
            music.previous()
            return true
        }
        override fun enqueue(items: List<String>, next: Boolean): Boolean {
            val active = account ?: return false
            scope.launch {
                // Only tracks join a music queue, as on Android.
                val tracks = itemsLookup(active, items).filter { it.type == "Audio" }.map(active::desktopMusicTrack)
                if (tracks.isEmpty()) return@launch
                if (!stillAllowed(active)) {
                    log("together music queue refused")
                    return@launch
                }
                playback.queueMusic(active, tracks, next)
            }
            return true
        }
        override fun audio(index: Int) = Unit
        override fun subtitle(index: Int) = Unit
    }

    /** REM-02/03: a Play from another device. Tracks go to music; anything else opens in the player. */
    private suspend fun openFromRemote(effect: RemoteEffect) {
        val target = account?.takeUnless { it.expired }
        val index = effect.index ?: 0
        val first = effect.items.getOrNull(index)
        if (target == null || first == null) {
            remote.resolved(effect.generation, false)
            return
        }
        if (playback.hasMusic()) {
            val rows = itemsLookup(target, effect.items)
            val tracks = rows.filter { it.type == "Audio" }
            if (rows.firstOrNull { it.id == first }?.type == "Audio" && tracks.isNotEmpty()) {
                // The lookup took time: the profile may have locked or switched since the command arrived.
                val ok = stillAllowed(target)
                if (!remote.resolved(effect.generation, ok) || !ok) {
                    if (!ok) log("together music open refused")
                    return
                }
                val start = tracks.indexOfFirst { it.id == first }.coerceAtLeast(0)
                playback.playMusic(DesktopMusicPlay(target.key, tracks.map(target::desktopMusicTrack), start = start))
                return
            }
        }
        playback.open(first, effect.ticks?.div(10_000)) { ok -> remote.resolved(effect.generation, ok) }
    }

    /** The command's viewer is still the one signed in here, and no PIN holds them. */
    private fun stillAllowed(target: Account): Boolean = account?.key == target.key && !target.expired && allowed(target)

    /** SYN: the group's current entry opens here when this device follows the group. */
    private fun openFromGroup(request: SyncOpen) {
        pendingSyncOpen = request
        playback.open(request.itemId, request.ticks / 10_000) { ok ->
            val accepted = sync.resolved(request, ok)
            if (!accepted && pendingSyncOpen === request) pendingSyncOpen = null
            accepted
        }
    }

    fun close() = scope.cancel()

    internal companion object {
        const val OBSERVE_MS = 250L
    }
}

/** Android's SyncPlayCoordinator queue routing: what a remote queues while this device is in a group goes to the group. */
internal fun groupQueueRequest(sync: SyncPlayHost, effect: RemoteEffect): Boolean = when (effect.op) {
    "open" -> sync.queueRequest(syncNewQueue(effect.items, effect.index ?: 0, effect.ticks ?: 0))
    "queue-next", "queue-add" -> sync.queueRequest(syncEnqueue(effect.items, effect.op == "queue-next"))
    else -> false
}

/** Item rows for [ids] in one request, in the server's order (callers look them up by id). */
internal suspend fun desktopItemsById(client: app.pictureshow.core.CatalogService, account: Account, ids: List<String>): List<MediaItem> {
    if (ids.isEmpty()) return emptyList()
    val page = client.items(account, BrowseQuery(parentId = "", ids = ids.take(app.pictureshow.core.control.REMOTE_PLAY_ITEM_LIMIT), limit = ids.size.coerceAtMost(app.pictureshow.core.control.REMOTE_PLAY_ITEM_LIMIT)))
    return (page as? ClientResult.Success)?.value?.items.orEmpty()
}
