/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.control.RemoteEffect
import app.pictureshow.core.control.RemoteInbound
import app.pictureshow.core.control.RemoteQueueEvent
import app.pictureshow.core.control.RemoteQueueState
import app.pictureshow.core.control.RemoteReceiveEvent
import app.pictureshow.core.control.RemoteReceiveState
import app.pictureshow.core.control.RemoteViewing
import app.pictureshow.core.control.parseRemoteMessage
import app.pictureshow.core.control.reduceRemoteQueue
import app.pictureshow.core.control.reduceRemoteReceive
import app.pictureshow.core.control.remoteAdvertisedCommands
import app.pictureshow.core.control.remoteRelayEnforcesRights
import app.pictureshow.core.control.remoteSocketUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal interface RemoteSocket {
    fun send(text: String): Boolean
    fun close()
}

internal interface RemoteSocketEvents {
    fun opened()
    fun message(text: String)
    fun closed()
}

internal fun interface RemoteSocketFactory {
    fun open(url: String, authorization: String, events: RemoteSocketEvents): RemoteSocket
}

/** The token goes in the header: 12.4 refuses it in the query (measured), and URLs end up in logs. */
internal class OkHttpRemoteSocketFactory(private val client: OkHttpClient = OkHttpClient()) : RemoteSocketFactory {
    override fun open(url: String, authorization: String, events: RemoteSocketEvents): RemoteSocket {
        val request = Request.Builder().url(
            url.replaceFirst("ws", "http", ignoreCase = true)
        ).header("Authorization", authorization).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = events.opened()
                override fun onMessage(webSocket: WebSocket, text: String) = events.message(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    events.closed()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.closed()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.closed()
            }
        )
        return object : RemoteSocket {
            override fun send(text: String) = socket.send(text)
            override fun close() {
                socket.cancel()
            }
        }
    }
}

/** What a mounted player can do. Absent when nothing is playing, so a stray command has nothing to act on. */
/**
 * What was queued from a phone comes next, so the end of a title waits for it instead of exiting. A queued title that
 * cannot be opened holds nothing: the title ends as it would have.
 */
internal fun remoteQueueHoldsEnd(upcoming: String?, resolved: MediaItem?, resolving: Boolean): Boolean =
    upcoming != null && (resolved != null || resolving)

internal interface RemotePlayerBridge {
    fun pause()
    fun unpause()
    fun report()
    fun seekTo(positionMs: Long)
    fun stop()

    /** Null asks for the player's own next episode, the same one its Next control would open. */
    fun next(item: String?)
    fun audio(index: Int)
    fun subtitle(index: Int)
}

/** The target's queue lives in memory for this signed-in process; a restart forgets it with the viewing. */
internal class RemoteQueue(private val server: String, private val viewer: String) {
    private var q = RemoteQueueState(QueueState(server, viewer))

    private fun apply(event: RemoteQueueEvent) {
        q = reduceRemoteQueue(q, event)
    }

    val current: String? get() = q.current

    val upcoming: String? get() = q.upNext.firstOrNull()

    fun open(items: List<String>) = apply(RemoteQueueEvent("open", items))

    fun add(watching: String, items: List<String>, next: Boolean) =
        apply(RemoteQueueEvent(if (next) "add-next" else "add-last", items, watching))

    fun previous(): String? {
        apply(RemoteQueueEvent("previous"))
        return current
    }

    fun observed(item: String?) {
        if (item != null) apply(RemoteQueueEvent("observed", item = item))
    }

    fun clear() = apply(RemoteQueueEvent("clear"))
}

/**
 * The one lifecycle-aware receiver: a socket while this activity is started and signed in, one reducer in front of
 * every command, and nothing kept across a restart. All calls and socket events run on [scope]'s single thread.
 */
internal class RemoteTargetHost(
    private val scope: CoroutineScope,
    private val sockets: RemoteSocketFactory,
    private val register: suspend (Account, List<String>) -> Int,
    private val authorization: suspend (Account) -> String,
    private val nowMs: () -> Long,
    private val trace: (String) -> Unit = {}
) {
    private val commands = remoteAdvertisedCommands(fixedVolume = true)
    private var account: Account? = null
    private var policy = "same-viewer"
    private var foreground = false
    private var state: RemoteReceiveState? = null
    private var generation = 0L
    private var queue: RemoteQueue? = null
    private var loop: Job? = null
    private var registration: Job? = null
    private var withdrawal: Job? = null
    private var withdrawnFor: String? = null
    private var socket: RemoteSocket? = null
    private var playerToken: Any? = null
    private var player: RemotePlayerBridge? = null
    private var viewing: RemoteViewing? = null
    private var textSink: ((String) -> Unit)? = null
    private val mutableOpens = MutableSharedFlow<RemoteEffect>(extraBufferCapacity = 8)
    private val mutableNavigation = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Bumps whenever the queue changes, so a mounted player can look its next title up again. */
    var queueRevision by mutableLongStateOf(0L)
        private set

    val opens = mutableOpens.asSharedFlow()
    val navigation = mutableNavigation.asSharedFlow()
    val upcoming: String? get() = queue?.upcoming

    fun bind(next: Account?, nextPolicy: String) {
        val usable = next?.takeUnless { it.expired }
        val changed = usable?.key != account?.key || usable?.accessToken != account?.accessToken
        account = usable
        if (nextPolicy != policy) {
            policy = nextPolicy
            state = state?.let { reduceRemoteReceive(it, RemoteReceiveEvent("policy", policy = policy, advertised = advertised())) }
        }
        if (changed) restart() else reconcile()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        reconcile()
    }

    private fun advertised() = if (policy == "off") emptyList() else commands

    private fun restart() {
        stop()
        // Monotonic across accounts, so a title still resolving for the previous viewer can never look current.
        generation = (state?.generation ?: generation) + 1
        state = null
        queue = null
        queueRevision++
        reconcile()
    }

    private fun stop() {
        loop?.cancel()
        loop = null
        socket?.close()
        socket = null
        state = state?.let { reduceRemoteReceive(it, RemoteReceiveEvent("disconnect")) }
    }

    private fun reconcile() {
        val current = account
        val wanted = foreground && current != null && policy != "off"
        if (!wanted) {
            if (loop != null) stop()
            if (foreground && current != null && policy == "off" && withdrawnFor != current.key) withdraw(current)
            return
        }
        withdrawnFor = null
        if (loop == null) {
            val pending = withdrawal
            loop = scope.launch {
                pending?.join()
                connect(requireNotNull(current))
            }
        }
    }

    /**
     * Closing the socket stops commands and, once the server sees the close, ends the listing (measured on 10.10, 10.11
     * and 12.4). A proxy or a dead network can hide that close, so say so directly: a server that hears this drops the
     * target at once, even with the socket still open (also measured). Waits for a registration already on the wire, so
     * the withdrawal is the last word the server hears.
     */
    private fun withdraw(target: Account) {
        withdrawnFor = target.key
        val prior = registration
        withdrawal = scope.launch {
            prior?.join()
            val status = register(target, emptyList())
            if (status !in 200..299) {
                trace("remote capabilities status=$status")
                // Offline: the next bind or return to the foreground tries again.
                if (withdrawnFor == target.key) withdrawnFor = null
            }
        }
    }

    private suspend fun connect(target: Account) {
        val url = remoteSocketUrl(target.server.url) ?: return
        var failures = 0
        while (scope.isActive) {
            val done = CompletableDeferred<Unit>()
            var live = true
            val before = state
            state = if (before == null) {
                RemoteReceiveState(
                    target.server.id,
                    target.user.id,
                    generation = generation,
                    policy = policy,
                    advertised = advertised(),
                    relayEnforcesRights = remoteRelayEnforcesRights(target.server.version),
                    viewing = viewing
                )
            } else {
                reduceRemoteReceive(before, RemoteReceiveEvent("reconnect"))
            }
            var heartbeat: Job? = null
            var ticker: Job? = null
            lateinit var opened: RemoteSocket
            fun beat(seconds: Int) {
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    while (isActive) {
                        delay(seconds * 400L)
                        opened.send("""{"MessageType":"KeepAlive"}""")
                    }
                }
            }
            val header = authorization(target)
            opened = sockets.open(
                url,
                header,
                object : RemoteSocketEvents {
                    override fun opened() {
                        scope.launch {
                            if (!live) return@launch
                            failures = 0
                            trace("remote socket-open")
                            beat(DEFAULT_KEEPALIVE_SECONDS)
                            ticker = scope.launch {
                                while (isActive) {
                                    delay(1_000)
                                    reduce(RemoteReceiveEvent("tick", nowMs = nowMs()))
                                }
                            }
                            // Capabilities follow every socket: a new connection has none until told.
                            registration = scope.launch {
                                val status = register(target, advertised())
                                if (status !in 200..299) trace("remote capabilities status=$status")
                            }
                        }
                    }
                    override fun message(text: String) {
                        scope.launch {
                            if (!live) return@launch
                            val inbound = parseRemoteMessage(text)
                            inbound.keepAliveSeconds?.let(::beat)
                            receive(inbound)
                        }
                    }
                    override fun closed() {
                        scope.launch {
                            live = false
                            done.complete(Unit)
                        }
                    }
                }
            )
            socket = opened
            try {
                done.await()
            } finally {
                live = false
                heartbeat?.cancel()
                ticker?.cancel()
            }
            state = state?.let { reduceRemoteReceive(it, RemoteReceiveEvent("disconnect")) }
            trace("remote socket-closed")
            delay(backoffMs(failures++))
        }
    }

    private fun receive(inbound: RemoteInbound) = reduce(RemoteReceiveEvent("message", inbound, nowMs()))

    private fun reduce(event: RemoteReceiveEvent) {
        val before = state ?: return
        val after = reduceRemoteReceive(before, event)
        state = after
        after.rejection?.let { trace("remote refused reason=$it") }
        after.effects.forEach(::apply)
    }

    private fun apply(effect: RemoteEffect) {
        val bridge = player
        when (effect.op) {
            "open" -> {
                val index = effect.index ?: 0
                queueFor().open(effect.items.drop(index))
                queueRevision++
                mutableOpens.tryEmit(effect)
            }
            "queue-next", "queue-add" -> {
                // The reducer also accepts these during preparation; the requested title is the current entry then,
                // even while the title being replaced is still reporting.
                val preparing = queue?.current.takeIf { state?.opening == true }
                val watching = preparing ?: viewing?.item ?: queue?.current ?: return
                queueFor().add(watching, effect.items, effect.op == "queue-next")
                queueRevision++
            }
            "pause" -> bridge?.pause()
            "unpause" -> bridge?.unpause()
            "report" -> bridge?.report()
            "seek" -> bridge?.seekTo(requireNotNull(effect.ticks) / 10_000)
            "stop" -> bridge?.stop()
            "next" -> bridge?.next(queue?.upcoming)
            "previous" -> if (bridge != null) previous(bridge)
            "audio" -> bridge?.audio(requireNotNull(effect.index))
            "subtitle" -> bridge?.subtitle(requireNotNull(effect.index))
            "nav" -> mutableNavigation.tryEmit(requireNotNull(effect.name))
            "text" -> textSink?.invoke(requireNotNull(effect.text))
        }
    }

    /** The usual convention: past three seconds Previous restarts this title; earlier it goes back one. */
    private fun previous(bridge: RemotePlayerBridge) {
        val here = viewing
        val prior = if ((here?.ticks ?: 0) > RESTART_TICKS) null else queue?.previous()
        if (prior == null || prior == here?.item) bridge.seekTo(0) else bridge.next(prior)
        queueRevision++
    }

    private fun queueFor(): RemoteQueue {
        val target = requireNotNull(state)
        return queue ?: RemoteQueue(target.server, target.viewer).also { queue = it }
    }

    /** False means the viewer or connection changed while the title resolved: do not open it. */
    fun resolved(generation: Long, ok: Boolean): Boolean {
        val current = state ?: return false
        val after = reduceRemoteReceive(current, RemoteReceiveEvent("resolved", generation = generation, ok = ok))
        state = after
        if (after.rejection == "stale-generation") {
            trace("remote refused reason=stale-generation")
            return false
        }
        if (!ok) {
            queue?.clear()
            queueRevision++
        }
        return ok
    }

    fun setPlayer(token: Any, bridge: RemotePlayerBridge) {
        playerToken = token
        player = bridge
    }

    fun clearPlayer(token: Any) {
        if (playerToken !== token) return
        playerToken = null
        player = null
        report(null)
    }

    fun report(next: RemoteViewing?) {
        if (next == viewing) return
        val itemChanged = next?.item != viewing?.item
        viewing = next
        if (itemChanged && next != null) {
            queue?.observed(next.item)
            queueRevision++
        }
        reduce(RemoteReceiveEvent("viewing", nowMs = nowMs(), viewing = next))
    }

    fun setSearchField(sink: ((String) -> Unit)?) {
        textSink = sink
        val current = state ?: return
        state = reduceRemoteReceive(current, RemoteReceiveEvent("field", field = if (sink != null) "search" else "none"))
    }

    internal companion object {
        const val DEFAULT_KEEPALIVE_SECONDS = 50
        private const val RESTART_TICKS = 30_000_000L

        fun backoffMs(failures: Int): Long = (1_000L shl failures.coerceIn(0, 5)).coerceAtMost(30_000)
    }
}
