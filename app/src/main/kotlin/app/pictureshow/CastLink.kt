/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlinx.coroutines.flow.StateFlow

/** A Cast device found on the network. The id is opaque and is never shown; no address reaches this type. */
internal data class CastRoute(val id: String, val name: String, val detail: String? = null)

/** One sidecar subtitle. Its URL holds the token, so this never goes through a default toString. */
internal class CastLoadTrack(val id: Long, val name: String, val language: String?, val url: String) {
    override fun toString() = "CastLoadTrack(id=$id)"
}

internal class CastLoadItem(
    val url: String,
    val contentType: String,
    val title: String,
    val imageUrl: String?,
    val durationMs: Long?,
    val live: Boolean,
    val tracks: List<CastLoadTrack>,
    /** What ties this media to its viewing: generation and identifiers only, never a credential. */
    val customData: Map<String, String>
) {
    override fun toString() = "CastLoadItem(title=$title, tracks=${tracks.size})"
}

internal class CastLoadRequest(val items: List<CastLoadItem>, val startIndex: Int, val startMs: Long, val autoplay: Boolean)

/** What the receiver said, with nothing added. mediaSession < 0 means it holds no media. */
internal data class CastStatus(
    val mediaSession: Long = -1,
    val playerState: String = "idle",
    val idleReason: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val live: Boolean = false,
    val seekableStartMs: Long? = null,
    val seekableEndMs: Long? = null,
    val contentUrl: String? = null,
    val title: String? = null,
    val customData: Map<String, String> = emptyMap(),
    val activeTracks: List<Int> = emptyList(),
    val queue: List<String> = emptyList(),
    val commands: List<String> = emptyList()
) {
    override fun toString() =
        "CastStatus(mediaSession=$mediaSession, playerState=$playerState, idleReason=$idleReason, positionMs=$positionMs)"
}

internal interface CastLinkListener {
    fun onConnecting()

    /** The session is up, whether it was just started or resumed. */
    fun onConnected(deviceName: String?)
    fun onSuspended()

    /** The session is gone. */
    fun onEnded()

    /** A connect never succeeded, so there was never a session to lose. */
    fun onConnectFailed()
    fun onStatus(status: CastStatus)

    /** The receiver's clock moved on between statuses, which say nothing while it simply plays. */
    fun onProgress(positionMs: Long)
}

/**
 * The session the SDK last spoke about. Ending an old session is slow, so its end can arrive once a newer one is under way,
 * and that end says nothing about the newer one.
 */
internal class NewestSession<T : Any> {
    private var newest: T? = null
    fun note(session: T) {
        newest = session
    }

    fun isSuperseded(session: T) = newest.let { it != null && it !== session }
}

/**
 * The SDK raises several callbacks for one status. One empty status is asked about again, and a second is the end of the
 * viewing, so the same one twice over mustn't count as a second answer. A reply to that question always counts.
 */
internal fun repeatsAnEmptyStatus(status: CastStatus, last: CastStatus?) = status.mediaSession < 0 && status == last

/** Everything the app asks of the Cast SDK. [GmsCastLink] is the only implementation and the only file with SDK types. */
internal interface CastLink {
    val routes: StateFlow<List<CastRoute>>
    fun setListener(listener: CastLinkListener?)
    fun startDiscovery()
    fun stopDiscovery()

    /** False if the route is gone, so nothing will ever answer. */
    fun select(routeId: String): Boolean

    /** The route currently joined, from this side of the link: a receiver's status can't say this. */
    fun joinedRoute(): String?
    fun endSession(stopReceiver: Boolean)
    fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit)
    fun play(onResult: (Boolean) -> Unit)
    fun pause(onResult: (Boolean) -> Unit)
    fun seek(positionMs: Long, onResult: (Boolean) -> Unit)
    fun stop(onResult: (Boolean) -> Unit)
    fun setActiveTracks(ids: List<Long>, onResult: (Boolean) -> Unit)
    fun requestStatus()

    /** Where the receiver is by now, worked out from its last status and the time since. Null with no media to ask about. */
    fun positionMs(): Long?
}

/** The swap to a registered receiver later is this one value. */
internal object CastReceiverConfig {
    /** CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID, which [GmsCastLink] resolves. Null means that default. */
    val applicationId: String? = null
}

/** The local player during a handoff. Absent when the viewer casts from a detail screen with nothing playing. */
internal interface CastLocalOutput {
    /** Pauses and returns where it really stopped, in milliseconds. It returns only once the pause has taken effect. */
    suspend fun pauseAndReadPositionMs(): Long

    /** The receiver has it: end local playback and its server report. */
    fun release()

    /** The handoff failed and the viewer chose to carry on here. */
    fun resume()
}
