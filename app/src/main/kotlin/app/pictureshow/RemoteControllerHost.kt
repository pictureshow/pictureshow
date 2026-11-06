/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.control.RemoteCommand
import app.pictureshow.core.control.RemoteControllerEvent
import app.pictureshow.core.control.RemoteControllerState
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.RemoteWire
import app.pictureshow.core.control.reduceRemoteController
import app.pictureshow.core.control.remoteRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class RemoteControllerUi(
    val controller: RemoteControllerState? = null,
    val targets: List<RemoteTarget> = emptyList(),
    val listing: Boolean = false,
    val listFailed: Boolean = false
)

/** The controller's server access. It lists and sends only: this seam has no way to report playback. */
internal interface RemoteControllerGateway {
    suspend fun targets(account: Account): ClientResult<List<RemoteTarget>>
    suspend fun send(account: Account, request: RemoteRequest): Int
}

/**
 * One controller for the signed-in account. It outlives rotation and screens, polls only while the app is in the
 * foreground, and treats a return from the background as unknown until a fresh list arrives.
 */
internal class RemoteControllerHost(
    private val scope: CoroutineScope,
    private val gateway: RemoteControllerGateway,
    private val nowMs: () -> Long
) {
    private val mutable = MutableStateFlow(RemoteControllerUi())
    val state = mutable.asStateFlow()
    private var account: Account? = null
    private var foreground = false
    private var pickerOpen = false
    private var nextId = 1L
    private var poll: Job? = null

    private val controller get() = mutable.value.controller

    fun now() = nowMs()

    private fun reduce(event: RemoteControllerEvent) {
        val current = controller ?: return
        val after = reduceRemoteController(current, event)
        mutable.value = mutable.value.copy(controller = after)
    }

    fun bind(next: Account?) {
        val usable = next?.takeUnless { it.expired }
        if (usable?.key == account?.key && usable?.accessToken == account?.accessToken) return
        account = usable
        mutable.value = RemoteControllerUi(
            controller = usable?.let { RemoteControllerState(it.server.id, it.user.id, generation = (controller?.generation ?: 0) + 1) }
        )
        reconcile()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) reduce(RemoteControllerEvent("resume"))
        reconcile()
    }

    /** The picker's list is wanted even when nothing is attached. */
    fun setPicker(open: Boolean) {
        pickerOpen = open
        if (open) mutable.value = mutable.value.copy(listing = mutable.value.targets.isEmpty(), listFailed = false)
        reconcile()
    }

    private fun attached() = controller?.phase?.let { it !in listOf("detached", "signed-out") } == true

    private fun reconcile() {
        val wanted = foreground && account != null && (pickerOpen || attached())
        if (!wanted) {
            poll?.cancel()
            poll = null
        } else if (poll == null) {
            poll = scope.launch {
                while (isActive) {
                    refresh()
                    val pending = controller?.command?.pending?.result == "sent" || controller?.followUp?.result == "sent"
                    delay(if (pending) FAST_POLL_MS else POLL_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        val signedIn = account ?: return
        val generation = controller?.generation
        val result = gateway.targets(signedIn)
        // A reply that started before a detach or attach describes a world this controller has left.
        if (signedIn !== account || generation != controller?.generation) return
        when (result) {
            is ClientResult.Success -> {
                mutable.value = mutable.value.copy(targets = result.value, listing = false, listFailed = false)
                reduce(RemoteControllerEvent("refresh", targets = result.value, nowMs = nowMs()))
            }
            is ClientResult.Failure -> {
                mutable.value = mutable.value.copy(listing = false, listFailed = true)
                if (result.error.kind == ErrorKind.INVALID_CREDENTIALS) {
                    reduce(RemoteControllerEvent("signed-out"))
                } else {
                    // Without a list nothing can be shown as current, so controls wait for the next good one.
                    reduce(RemoteControllerEvent("resume"))
                }
            }
        }
    }

    fun attach(target: RemoteTarget) {
        reduce(RemoteControllerEvent("attach", target = target, nowMs = nowMs()))
        reconcile()
    }

    /** Leaves the TV exactly as it is. Stop is a different button. */
    fun detach() {
        reduce(RemoteControllerEvent("detach"))
        reconcile()
    }

    fun playNow(items: List<String>, ticks: Long? = null) {
        val item = items.firstOrNull() ?: return
        send("play-now", entry = item) { RemoteCommand("play-now", items, ticks) }
    }

    fun queue(items: List<String>, next: Boolean) = send("queue") { RemoteCommand(if (next) "play-next" else "add-to-queue", items) }

    fun pause() = send("pause") { RemoteCommand("pause") }

    fun play() = send("play") { RemoteCommand("play") }

    fun stop() = send("stop") { RemoteCommand("stop") }

    fun next() = send("next") { RemoteCommand("next") }

    fun previous() = send("previous") { RemoteCommand("previous") }

    fun seekTo(ticks: Long) = send("seek-absolute", entry = ticks.toString(), command = ::seekCommand)

    /** Repeated taps add up from the target of the last one, so two quick +30s reach +60s. */
    fun seekBy(deltaTicks: Long) = send("seek-relative", entry = deltaTicks.toString(), command = ::seekCommand)

    private fun seekCommand(after: RemoteControllerState) = RemoteCommand("seek", ticks = after.command?.pending?.ticks)

    fun audio(index: Int) = send("audio", index = index) { RemoteCommand("audio", index = index) }

    fun subtitle(index: Int) = send("subtitle", index = index) { RemoteCommand("subtitle", index = index) }

    fun navigate(name: String) {
        val target = controller?.target ?: return
        if (controller?.phase != "attached") return
        dispatchUnobserved(remoteRequest(target, RemoteCommand("general", name = name)))
    }

    /** False when the text cannot be sent; the screen keeps what the viewer typed. */
    fun text(value: String): Boolean {
        val target = controller?.target ?: return false
        if (controller?.phase != "attached") return false
        val wire = remoteRequest(target, RemoteCommand("text", text = value))
        if (wire.request == null) return false
        dispatchUnobserved(wire)
        return true
    }

    /** Navigation and text have nothing to observe, so the only thing to learn is that access or the target has gone. */
    private fun dispatchUnobserved(wire: RemoteWire) {
        val request = wire.request ?: return
        val signedIn = account ?: return
        val generation = controller?.generation
        scope.launch {
            val status = gateway.send(signedIn, request)
            if (signedIn === account && generation == controller?.generation) {
                reduce(RemoteControllerEvent("answer", status = status))
                reconcile()
            }
        }
    }

    private fun send(value: String, entry: String? = null, index: Int? = null, command: (RemoteControllerState) -> RemoteCommand) {
        val signedIn = account ?: return
        val id = nextId++
        reduce(RemoteControllerEvent("intent", id = id, nowMs = nowMs(), value = value, entry = entry, index = index))
        val after = controller ?: return
        if (after.effect != "dispatch") return
        val request = after.target?.let { remoteRequest(it, command(after)).request }
        if (request == null) {
            reduce(RemoteControllerEvent("sent", id = id, status = 0))
            return
        }
        val generation = after.generation
        scope.launch {
            val status = gateway.send(signedIn, request)
            if (signedIn === account && generation == controller?.generation) {
                reduce(RemoteControllerEvent("sent", id = id, status = status))
                reconcile()
            }
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val FAST_POLL_MS = 700L
    }
}

/** Plain words for a phase, or null while everything is in order. Nothing here names what a denied viewer cannot see. */
internal const val REMOTE_CHECKING = "Checking the TV…"
internal const val REMOTE_IDLE = "Nothing is playing on this TV."

/** A track type the TV hasn't reported: it stays silent about its own automatic choice until someone picks (PLAY-20). */
internal const val REMOTE_TRACK_UNREPORTED = "TV reports it once you pick"

internal fun remoteProblem(phase: String?): String? = when (phase) {
    "target-gone" -> "This TV isn't available right now. Open Pictureshow on the TV, then try again."
    "target-restarted" -> "This TV restarted. Reconnect to keep controlling it."
    "denied" -> "This account isn't allowed to control that device. The server's administrator can change this."
    "signed-out" -> "Your sign-in has ended. Sign in again to use the remote."
    else -> null
}

private const val STATUS_SHOWN_MS = 15_000L

/** Every wording of [remoteCommandStatus] and the slot's other lines: the screen reserves room for the longest. */
internal val REMOTE_STATUS_WORDINGS = listOf(
    "Sent to the TV.",
    "Sending to the TV…",
    "Sent. Check the queue on the TV.",
    "The TV didn't confirm that.",
    "That wasn't allowed.",
    REMOTE_CHECKING,
    REMOTE_IDLE
)

/**
 * What the screen may claim about the last command. A 204 only ever reads as sending, and an old outcome leaves the
 * screen rather than sitting under whatever has happened since.
 */
internal fun remoteCommandStatus(state: RemoteControllerState?, nowMs: Long): String? {
    if (state?.phase != "attached") return null
    val follow = state.followUp
    val pending = state.command?.pending
    val latest = if (follow != null && (pending == null || follow.id > pending.id)) {
        Triple(follow.op, follow.result, follow.sentAtMs)
    } else {
        pending?.let { Triple(it.operation, it.result, it.sentAtMs) }
    } ?: return null
    val (operation, result, sentAtMs) = latest
    if (nowMs - sentAtMs >= STATUS_SHOWN_MS) return null
    return when (result) {
        "sent" -> if (operation == "queue") "Sent to the TV." else "Sending to the TV…"
        "unknown" -> if (operation == "queue") {
            "Sent. Check the queue on the TV."
        } else {
            "The TV didn't confirm that."
        }
        "rejected" -> "That wasn't allowed."
        else -> null
    }
}
