/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineReconnectFacts
import app.pictureshow.core.offline.OfflineServerSource
import app.pictureshow.core.offline.OfflineServerTrack
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.offline.offlineHandoffSelection
import app.pictureshow.core.offline.offlineReconnectDecision
import app.pictureshow.core.offline.offlineTrackOnServer
import app.pictureshow.core.offline.undownloadedTracks
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlaybackHttpOutcome
import app.pictureshow.core.playback.PlaybackStopInfo
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What the offer is showing. Playback keeps running through Checking and Offer; it holds only through Connecting. */
internal sealed interface OfflineReconnectState {
    val track: OfflineTrack? get() = null

    data object Idle : OfflineReconnectState
    data class Checking(override val track: OfflineTrack) : OfflineReconnectState
    data class Offer(override val track: OfflineTrack, val metered: Boolean) : OfflineReconnectState

    /** outcome is one of core's refusals: no-connection, server-unreachable, signed-out, not-allowed, track-gone. */
    data class Refused(override val track: OfflineTrack, val outcome: String) : OfflineReconnectState
    data class Connecting(override val track: OfflineTrack) : OfflineReconnectState

    /** Terminal: the session is closing and the online player is next. */
    data class Leaving(override val track: OfflineTrack) : OfflineReconnectState
}

internal fun offlineReconnectMessage(state: OfflineReconnectState): String {
    val label = state.track?.label.orEmpty()
    return when (state) {
        OfflineReconnectState.Idle -> ""
        is OfflineReconnectState.Checking -> "Checking the server for $label…"
        is OfflineReconnectState.Offer ->
            "$label isn't in this download. You can stream it from the server from this point, or stay on the download." +
                if (state.metered) " Streaming uses your mobile data. The Wi-Fi only setting is for downloads." else ""
        is OfflineReconnectState.Refused -> when (state.outcome) {
            "no-connection" -> "There's no connection, so $label can't play right now. You're still on the download."
            "signed-out" -> "Sign in to this server again to stream $label. You're still on the download."
            "not-allowed" -> "This account isn't allowed to stream from the server. You're still on the download."
            "track-gone" -> "$label is no longer on the server. You're still on the download."
            else -> "The server isn't answering, so $label can't play right now. You're still on the download."
        }
        is OfflineReconnectState.Connecting, is OfflineReconnectState.Leaving -> "Saving your place on the server…"
    }
}

internal fun interface OfflineReconnectProbe {
    suspend fun check(track: OfflineTrack): OfflineReconnectFacts
}

/** The local player as the handoff needs it. close ends the local session, then runs next. */
internal interface OfflineReconnectPlayer {
    val paused: Boolean
    val positionMs: Long
    val audioIndex: Int?
    val subtitleIndex: Int?
    fun pause()
    fun resume()
    fun close(next: () -> Unit)
}

internal interface OfflineReconnectHandoff {
    /** Local progress stops here and reaches the server, or the answer is Failed and local viewing can go on. */
    suspend fun settle(positionTicks: Long): OfflineSettleResult

    fun reopen()
}

/** Where the online player must pick up. */
internal data class OfflineStreamStart(val positionMs: Long, val audioIndex: Int?, val subtitleIndex: Int?)

/**
 * The offer's whole life (OFF-05). Order on accept matters: hold playback, stop local reports and write the last position, reach the server,
 * and only then close and move. Any failure on the way reopens local viewing where it was, with nothing lost.
 */
internal class OfflineReconnect(
    private val entry: OfflineEntry,
    private val scope: CoroutineScope,
    private val network: () -> String,
    private val probe: OfflineReconnectProbe,
    private val handoff: OfflineReconnectHandoff,
    private val onStream: (OfflineStreamStart) -> Unit
) {
    var state by mutableStateOf<OfflineReconnectState>(OfflineReconnectState.Idle)
        private set
    private var job: Job? = null
    private var holding: OfflineReconnectPlayer? = null
    private var resumeAfterHold = false

    /** A row marked as needing a connection. No network is answered without asking anyone. */
    fun pick(type: MediaStreamType, index: Int) {
        if (state != OfflineReconnectState.Idle) return
        val name = if (type == MediaStreamType.Audio) "audio" else "subtitle"
        val track = entry.undownloadedTracks(name).firstOrNull { it.serverIndex == index } ?: return
        val first = offlineReconnectDecision("reconnect", network(), OfflineReconnectFacts())
        if (first.outcome != "checking") {
            state = OfflineReconnectState.Refused(track, first.outcome)
            return
        }
        state = OfflineReconnectState.Checking(track)
        job = scope.launch {
            val facts = try {
                probe.check(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OfflineReconnectFacts("unreachable")
            }
            val decision = offlineReconnectDecision("reconnect", network(), facts)
            state = when (decision.outcome) {
                "offer" -> OfflineReconnectState.Offer(track, decision.metered)
                "checking" -> OfflineReconnectState.Refused(track, "server-unreachable")
                else -> OfflineReconnectState.Refused(track, decision.outcome)
            }
        }
    }

    fun accept(player: OfflineReconnectPlayer) {
        val track = (state as? OfflineReconnectState.Offer)?.track ?: return
        resumeAfterHold = !player.paused
        player.pause()
        val positionMs = player.positionMs
        holding = player
        state = OfflineReconnectState.Connecting(track)
        job = scope.launch {
            val settled = try {
                handoff.settle(positionMs * TICKS_PER_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OfflineSettleResult.Failed
            }
            if (settled == OfflineSettleResult.Failed) {
                release(resume = true)
                state = OfflineReconnectState.Refused(track, "server-unreachable")
            } else {
                holding = null
                state = OfflineReconnectState.Leaving(track)
                val selection = offlineHandoffSelection(track.type, track.serverIndex, player.audioIndex, player.subtitleIndex)
                player.close { onStream(OfflineStreamStart(positionMs, selection.audio, selection.subtitle)) }
            }
        }
    }

    /** Every way of saying no: nothing changes. resume is false when the app is going away and playback must not restart. */
    fun stay(resume: Boolean = true) {
        if (state is OfflineReconnectState.Leaving || state == OfflineReconnectState.Idle) return
        job?.cancel()
        release(resume)
        state = OfflineReconnectState.Idle
    }

    private fun release(resume: Boolean) {
        val player = holding ?: return
        holding = null
        handoff.reopen()
        if (resume && resumeAfterHold) player.resume()
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}

/** Real answers to the offer's questions. A body that isn't the server's (a captive portal) is a server that isn't answering. */
internal class OfflineApiReconnectProbe(private val api: OfflineApi, private val account: Account, private val entry: OfflineEntry) :
    OfflineReconnectProbe {
    override suspend fun check(track: OfflineTrack): OfflineReconnectFacts {
        if (account.expired) return OfflineReconnectFacts("signed-out")
        return try {
            if (!api.sameViewer(account)) return OfflineReconnectFacts("signed-out")
            if (!api.policy(account).enablePlayback) return OfflineReconnectFacts("ok", playbackAllowed = false)
            val sources = api.item(account, entry.itemId).sources.map { source ->
                OfflineServerSource(source.id, source.streams.map { OfflineServerTrack(it.index, it.type, it.language) })
            }
            OfflineReconnectFacts("ok", true, offlineTrackOnServer(track, entry.sourceId, sources))
        } catch (e: OfflineApiException) {
            when (e.status) {
                401, 403 -> OfflineReconnectFacts("signed-out")
                404 -> OfflineReconnectFacts("ok", true, false)
                else -> OfflineReconnectFacts("unreachable")
            }
        } catch (_: IOException) {
            OfflineReconnectFacts("unreachable")
        } catch (_: IllegalArgumentException) {
            OfflineReconnectFacts("unreachable")
        }
    }
}

/**
 * The self-conflict trap lives here: a report landing after the last position reached the server would make a new record based on what the
 * server held before it, and the next sync would see this device's own write as someone else's. Hence the seal comes first.
 */
internal class OfflineStoreHandoff(
    private val gateway: OfflinePlaybackGateway,
    private val store: OfflineProgressStore,
    private val itemId: String,
    private val remote: OfflineProgressRemote
) : OfflineReconnectHandoff {
    override suspend fun settle(positionTicks: Long): OfflineSettleResult {
        gateway.seal(positionTicks)
        val result = store.settle(itemId, remote)
        // The server kept other progress, and the online session that starts now replaces it: a "server's progress was kept" note would be untrue.
        if (result == OfflineSettleResult.Conflict) store.dismissConflict(itemId)
        return result
    }

    override fun reopen() = gateway.reopen()
}

/** Asks the server questions with an answer inside a bound the viewer can sit through. OkHttp's own limit also ends a blocked call. */
internal val offlineReconnectHttp: OkHttpClient by lazy {
    PlaybackMedia.createPlaybackHttpClient().newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
}

/** Provided by the route that follows a handoff, and applied only to the item that was handed off; a following episode starts normally. */
internal class OfflineHandoffStart(val itemId: String, val tracks: PlaybackStartTracks, val gateway: (PlaybackGateway) -> PlaybackGateway)

internal val LocalOfflineHandoff = staticCompositionLocalOf<OfflineHandoffStart?> { null }

/**
 * Online viewing normally leaves the device's last-known state behind. After a handoff that would make the next local viewing resume from before
 * this session, and see the session's own progress as a conflict. A failure here only means the device knows less: the next sync reads first.
 */
internal class OfflineOnlineViewingGateway(private val delegate: PlaybackGateway, private val refresh: suspend () -> Unit) :
    PlaybackGateway by delegate {
    private suspend fun learn() {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
    }

    override suspend fun stopped(info: PlaybackStopInfo) = delegate.stopped(info).also { learn() }

    override suspend fun stopped(info: PlaybackStopInfo, observe: (PlaybackHttpOutcome) -> Unit) =
        delegate.stopped(info, observe).also { learn() }

    override suspend fun played() = delegate.played().also { learn() }
}
