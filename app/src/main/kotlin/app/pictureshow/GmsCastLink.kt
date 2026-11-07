/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.images.WebImage
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val LOG_TAG = "PictureshowCast"

/** Often enough for a clock on screen. Reports keep their own, slower pace. */
private const val PROGRESS_PERIOD_MS = 1_000L

/** Resolved here so no other file names the SDK's constant. */
private val receiverApplicationId: String
    get() = CastReceiverConfig.applicationId ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

/** Read by the SDK by class name from the manifest, and only once [CastContext] is requested. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(receiverApplicationId)
        // Kept for a session the SDK can bring back while the process lives. After a process death it didn't (2 of 2 on a real
        // receiver), and the viewer's Reconnect is the way back (CAST-21); whatever comes back, the host decides if it's ours.
        .setResumeSavedSession(true)
        .setEnableReconnectionService(false)
        .setStopReceiverApplicationWhenEndingSession(false)
        // Media3's session stays the only media session; the SDK adds no notification of its own.
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** ok, missing or update-required, without ever letting a Play Services failure reach the caller. */
internal fun castServicesState(context: Context): String = runCatching {
    when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
        ConnectionResult.SUCCESS -> "ok"

        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
        ConnectionResult.SERVICE_UPDATING,
        ConnectionResult.SERVICE_DISABLED -> "update-required"

        else -> "missing"
    }
}.getOrDefault("missing")

/**
 * The adapter over the SDK's session, router and media client. We use RemoteMediaClient directly instead of Media3's
 * RemoteCastPlayer because the handoff needs the receiver's own player state, idle reason, media session id, current
 * item and active tracks, which a Player interface hides.
 */
internal class GmsCastLink(private val context: Context, private val mainExecutor: Executor) : CastLink {
    private val mutableRoutes = MutableStateFlow<List<CastRoute>>(emptyList())
    override val routes: StateFlow<List<CastRoute>> = mutableRoutes.asStateFlow()
    private var listener: CastLinkListener? = null
    private var castContext: CastContext? = null
    private var discovering = false
    private var watched: RemoteMediaClient? = null
    private val router by lazy { MediaRouter.getInstance(context) }

    private val selector: MediaRouteSelector
        get() = castContext?.mergedSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(receiverApplicationId))
            .build()

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    private val newest = NewestSession<CastSession>()
    private var lastStatus: CastStatus? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishStatus()
        override fun onQueueStatusUpdated() = publishStatus()
    }

    private val progress = RemoteMediaClient.ProgressListener { progressMs, _ ->
        if (watched?.hasMediaSession() == true) listener?.onProgress(progressMs)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            newest.note(session)
            listener?.onConnecting()
        }
        override fun onSessionStarted(session: CastSession, id: String) = connected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            if (!newest.isSuperseded(session)) connectFailed("start", error)
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) {
            if (!newest.isSuperseded(session)) ended()
        }
        override fun onSessionResuming(session: CastSession, id: String) {
            newest.note(session)
            listener?.onConnecting()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(LOG_TAG, "Cast session resume failed: $error (${CastStatusCodes.getStatusCodeString(error)})")
            ended()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            listener?.onSuspended()
        }
    }

    init {
        CastContext.getSharedInstance(context, mainExecutor)
            .addOnSuccessListener(mainExecutor) { shared ->
                castContext = shared
                shared.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
                shared.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::connected)
                if (discovering) addRouterCallback()
            }
            .addOnFailureListener(mainExecutor) {
                Log.w(LOG_TAG, "Cast framework did not start: ${it.javaClass.simpleName}")
                watch(null)
                listener?.onConnectFailed()
            }
    }

    private fun connected(session: CastSession) {
        newest.note(session)
        watch(session.remoteMediaClient)
        listener?.onConnected(session.castDevice?.friendlyName)
    }

    private fun ended() {
        watch(null)
        listener?.onEnded()
    }

    private fun connectFailed(step: String, error: Int) {
        Log.w(LOG_TAG, "Cast session $step failed: $error (${CastStatusCodes.getStatusCodeString(error)})")
        watch(null)
        listener?.onConnectFailed()
    }

    private fun watch(client: RemoteMediaClient?) {
        if (client === watched) return
        lastStatus = null
        watched?.unregisterCallback(mediaCallback)
        watched?.removeProgressListener(progress)
        watched = client
        client?.registerCallback(mediaCallback)
        client?.addProgressListener(progress, PROGRESS_PERIOD_MS)
    }

    private fun publishRoutes() {
        val active = selector
        mutableRoutes.value = router.routes
            .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(active) }
            .map { CastRoute(it.id, it.name, it.description) }
    }

    private fun addRouterCallback() {
        router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        publishRoutes()
    }

    private fun publishStatus(answer: Boolean = false) {
        val client = watched ?: return
        val status = client.mediaStatus?.let { status(it, client.approximateStreamPosition) } ?: CastStatus()
        if (!answer && repeatsAnEmptyStatus(status, lastStatus)) return
        lastStatus = status
        listener?.onStatus(status)
    }

    private fun status(status: MediaStatus, positionMs: Long): CastStatus {
        val media = status.mediaInfo
        // The SDK exposes this only through its own JSON. A status without it still counts as having media.
        val session = runCatching { status.toJson().optLong("mediaSessionId", 0) }.getOrDefault(0).coerceAtLeast(0)
        val live = media?.streamType == MediaInfo.STREAM_TYPE_LIVE
        val range = status.liveSeekableRange
        return CastStatus(
            mediaSession = if (media == null) -1 else session,
            playerState = when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> "playing"
                MediaStatus.PLAYER_STATE_PAUSED -> "paused"
                MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
                MediaStatus.PLAYER_STATE_LOADING -> "loading"
                MediaStatus.PLAYER_STATE_IDLE -> "idle"
                else -> "buffering"
            },
            idleReason = when (status.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> "finished"
                MediaStatus.IDLE_REASON_CANCELED -> "cancelled"
                MediaStatus.IDLE_REASON_INTERRUPTED -> "interrupted"
                MediaStatus.IDLE_REASON_ERROR -> "error"
                else -> null
            },
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = media?.streamDuration?.takeIf { it > 0 && !live },
            live = live,
            seekableStartMs = range?.startTime,
            seekableEndMs = range?.endTime,
            contentUrl = media?.contentId,
            title = media?.metadata?.getString(MediaMetadata.KEY_TITLE),
            customData = strings(media?.customData ?: status.customData),
            activeTracks = status.activeTrackIds?.map(Long::toInt).orEmpty(),
            queue = status.queueItems.mapNotNull { strings(it.customData)["entry"] ?: strings(it.media?.customData)["entry"] },
            commands = buildList {
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_PAUSE)) {
                    add("pause")
                    add("play")
                }
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK)) add("seek")
                add("stop")
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_SET_VOLUME)) add("volume")
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_EDIT_TRACKS)) add("tracks")
            }
        )
    }

    private fun strings(json: JSONObject?): Map<String, String> {
        val source = json ?: return emptyMap()
        return source.keys().asSequence().associateWith { source.optString(it) }
    }

    override fun setListener(listener: CastLinkListener?) {
        this.listener = listener
        // A session the SDK already resumed is reported again for whoever is listening now.
        castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let(::connected)
    }

    override fun startDiscovery() {
        discovering = true
        if (castContext != null) addRouterCallback()
    }

    override fun stopDiscovery() {
        discovering = false
        router.removeCallback(routerCallback)
        mutableRoutes.value = emptyList()
    }

    override fun joinedRoute(): String? = router.selectedRoute.id

    override fun select(routeId: String): Boolean {
        val route = router.routes.firstOrNull { it.id == routeId } ?: return false
        router.selectRoute(route)
        return true
    }

    override fun launchedReceiver() = castContext?.sessionManager?.currentCastSession?.applicationConnectionResult?.wasLaunched == true

    override fun endSession(stopReceiver: Boolean) {
        castContext?.sessionManager?.endCurrentSession(stopReceiver)
    }

    private fun PendingResult<RemoteMediaClient.MediaChannelResult>?.answer(onResult: (Boolean) -> Unit) {
        if (this == null) onResult(false) else setResultCallback { onResult(it.status.isSuccess) }
    }

    override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) {
        val client = watched ?: return onResult(false)
        val builder = MediaLoadRequestData.Builder()
            .setAutoplay(request.autoplay)
            .setCurrentTime(request.startMs)
        if (request.items.size == 1) {
            builder.setMediaInfo(mediaInfo(request.items.single()))
        } else {
            builder.setQueueData(
                MediaQueueData.Builder()
                    .setItems(
                        request.items.map {
                            MediaQueueItem.Builder(mediaInfo(it))
                                .setCustomData(JSONObject(it.customData))
                                .setAutoplay(true)
                                .build()
                        }
                    )
                    .setStartIndex(request.startIndex)
                    .setStartTime(request.startMs)
                    .build()
            )
        }
        client.load(builder.build()).answer(onResult)
    }

    private fun mediaInfo(item: CastLoadItem): MediaInfo {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        metadata.putString(MediaMetadata.KEY_TITLE, item.title)
        item.imageUrl?.let { metadata.addImage(WebImage(it.toUri())) }
        val builder = MediaInfo.Builder(item.url)
            .setContentType(item.contentType)
            .setStreamType(if (item.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .setCustomData(JSONObject(item.customData))
            .setMediaTracks(
                item.tracks.map { track ->
                    MediaTrack.Builder(track.id, MediaTrack.TYPE_TEXT)
                        .setContentId(track.url)
                        .setContentType("text/vtt")
                        .setName(track.name)
                        .setLanguage(track.language)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .build()
                }
            )
        item.durationMs?.let(builder::setStreamDuration)
        return builder.build()
    }

    override fun play(onResult: (Boolean) -> Unit) = watched?.play().answer(onResult)

    override fun pause(onResult: (Boolean) -> Unit) = watched?.pause().answer(onResult)

    override fun seek(positionMs: Long, onResult: (Boolean) -> Unit) =
        watched?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()).answer(onResult)

    override fun stop(onResult: (Boolean) -> Unit) = watched?.stop().answer(onResult)

    override fun setActiveTracks(ids: List<Long>, onResult: (Boolean) -> Unit) =
        watched?.setActiveMediaTracks(ids.toLongArray()).answer(onResult)

    override fun positionMs(): Long? = watched?.takeIf { it.hasMediaSession() }?.approximateStreamPosition

    override fun requestStatus() {
        val client = watched ?: return
        client.requestStatus().setResultCallback { result ->
            if (result.status.isSuccess && client === watched) publishStatus(answer = true)
        }
    }
}
