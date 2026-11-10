/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.control.CastReceiverFormats
import app.pictureshow.core.control.CastSenderVpn
import app.pictureshow.core.control.CastStoredSessions
import app.pictureshow.core.control.CastViewedItem
import app.pictureshow.core.control.CastViewingRecord
import app.pictureshow.core.control.castDeviceProfile
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import io.ktor.client.HttpClient
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Counts how often the app reaches for Cast, so a test can prove the TV interface never does. */
internal object CastRuntime {
    @Volatile var hostBuilds = 0

    @Volatile var linkBuilds = 0
}

/** Android 17's local-network permission applies from target 37. Below that, the platform grants it through INTERNET. */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_TARGET = 37
private const val PREFERENCES = "cast-sender"
private val CastStorageJson = Json { ignoreUnknownKeys = true }

internal fun castSenderHost(context: Context, http: HttpClient, identity: DeviceIdentityStore): CastSenderHost {
    CastRuntime.hostBuilds += 1
    return CastSenderHost(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        AndroidCastEnvironment(context.applicationContext),
        ClientCastGateway(http, identity, deviceDisplayName(context)),
        SystemClock::elapsedRealtime
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun castStorageDispatcher() = Dispatchers.IO.limitedParallelism(1)

/**
 * Writes run one at a time on [storage], in the order they were asked for, so an older one can never land after a newer
 * one. A commit waits on the disk, which is why none of them happens on the caller's thread.
 */
internal class AndroidCastEnvironment(private val context: Context, private val storage: CoroutineDispatcher = castStorageDispatcher()) :
    CastEnvironment {
    private val stored by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val writes = CoroutineScope(SupervisorJob() + storage)

    override val tvInterface: Boolean get() = usesTvInterface(context)

    /** The Cast SDK's floor is the app's own minimum, so every install qualifies. */
    override val osSupported: Boolean get() = true

    /** Only reached once the viewer has opened the chooser or a viewing may be live. */
    override fun services(): String = castServicesState(context)

    override fun localNetworkAllowed(): Boolean {
        val target = context.applicationInfo.targetSdkVersion
        if (target < LOCAL_NETWORK_TARGET || Build.VERSION.SDK_INT < LOCAL_NETWORK_TARGET) return true
        return context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    override fun senderVpn(): CastSenderVpn = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return CastSenderVpn()
        // A default route says nothing about one server; only a specific range says this address goes through the VPN.
        CastSenderVpn(
            active = true,
            ranges = manager.getLinkProperties(network)?.routes.orEmpty()
                .map { it.destination }
                .filter { it.prefixLength > 0 }
                .mapNotNull { prefix -> prefix.address.hostAddress?.let { "$it/${prefix.prefixLength}" } }
        )
    }.getOrDefault(CastSenderVpn())

    override fun link(): CastLink {
        CastRuntime.linkBuilds += 1
        val main = Handler(Looper.getMainLooper())
        return GmsCastLink(context) { main.post(it) }
    }

    override var enabled: Boolean
        get() = stored.getBoolean("enabled", true)
        set(value) = stored.edit { putBoolean("enabled", value) }

    override var mayBeLive: Boolean
        get() = stored.getBoolean("may-be-live", false)
        set(value) = stored.edit { putBoolean("may-be-live", value) }

    /** The same app-private storage as the signed-in account's own token, and excluded from backup the same way. */
    override val sessions: CastStoredSessions
        get() = stored.getString("sessions", null)
            ?.let { runCatching { CastStorageJson.decodeFromString(CastStoredSessions.serializer(), it) }.getOrNull() }
            ?: CastStoredSessions()

    override suspend fun saveSessions(value: CastStoredSessions) = withContext(storage) {
        stored.edit(commit = true) { putString("sessions", CastStorageJson.encodeToString(CastStoredSessions.serializer(), value)) }
    }

    /** Readers see the latest answer at once, though the disk catches up on [storage]. */
    private var viewingNow: Lazy<CastViewingRecord?> = lazy {
        stored.getString("viewing", null)
            ?.let { runCatching { CastStorageJson.decodeFromString(CastViewingRecord.serializer(), it) }.getOrNull() }
    }

    override val viewing: CastViewingRecord? get() = viewingNow.value

    override suspend fun saveViewing(record: CastViewingRecord) {
        viewingNow = lazyOf(record)
        withContext(storage) {
            stored.edit(commit = true) { putString("viewing", CastStorageJson.encodeToString(CastViewingRecord.serializer(), record)) }
        }
    }

    /** Losing this write to a process death is safe: a reattach finds the session gone and lets go of the record. */
    override fun clearViewing() {
        viewingNow = lazyOf(null)
        writes.launch { stored.edit(commit = true) { remove("viewing") } }
    }
}

private const val CAST_DEVICE_ID_LENGTH = 32
private val REVOKE_FINAL_STATUSES = setOf(403, 404)

internal class ClientCastGateway(private val http: HttpClient, private val identity: DeviceIdentityStore, private val name: String) :
    CastMediaGateway {
    private val liveLock = Mutex()
    private val liveSources = mutableMapOf<Account, MutableList<CastLiveSource>>()
    private val retired = mutableSetOf<Account>()
    private val restoredSources = mutableMapOf<Account, List<CastViewedItem>>()
    private val closedSources = mutableMapOf<Account, MutableSet<String>>()
    private val activeReports = mutableMapOf<Account, CastReport>()

    private class CastLiveSource(val decision: PlayDecision)

    private suspend fun clientIdentity() = ClientIdentity(name, identity.getOrCreate(), BuildConfig.VERSION_NAME)

    private suspend fun client() = PlaybackClient(http, clientIdentity())

    override suspend fun sessionDevice(account: Account, route: String): String {
        val seed = listOf(identity.getOrCreate(), route, account.server.id, account.user.id).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return "cast-" + digest.joinToString("") { "%02x".format(it) }.take(CAST_DEVICE_ID_LENGTH)
    }

    /** The server issues it under its own device, on the viewer's approval; no password is kept or asked for. */
    override suspend fun provisionSession(account: Account, deviceId: String): CastSessionIssue {
        val server = JellyfinClient(http, clientIdentity())
        val enabled = server.quickConnectEnabled(account.server)
        if ((enabled as? ClientResult.Success)?.value == false) return CastSessionIssue.QuickConnectOff
        if (enabled is ClientResult.Failure) return CastSessionIssue.Failed
        val issued = server.renewDevice(account, deviceId) as? ClientResult.Success ?: return CastSessionIssue.Failed
        return CastSessionIssue.Issued(issued.value)
    }

    override suspend fun restoreLiveSources(session: Account, items: List<CastViewedItem>) = liveLock.withLock {
        restoredSources[session] = items
    }

    override suspend fun takeClosedLiveSources(session: Account): Set<String> = liveLock.withLock {
        closedSources.remove(session).orEmpty()
    }

    /**
     * 401 says it's already gone. 403 and 404 are the server's final word that it won't do this: asking again changes nothing,
     * and waiting would refuse every cast to that receiver until sign-out. If a token did outlive that, the next login
     * under this device id replaces it (observed on the server). Anything else may be the network, so it stays pending.
     */
    override suspend fun revokeSession(session: Account): Boolean = liveLock.withLock {
        retired += session
        activeReports.remove(session)?.let { stopReport(session, it) }
        // Keep the credential and ownership pending when Close fails, so reconnect can retry it before Logout.
        if (!closeOwned(session)) return@withLock false
        val restored = restoredSources[session].orEmpty()
        for (source in restored) {
            val id = source.ownedLiveStreamId ?: continue
            if (client().closeLiveStream(session, id) !is ClientResult.Success) return@withLock false
        }
        restoredSources.remove(session)
        when (val result = JellyfinClient(http, clientIdentity()).logout(session)) {
            is ClientResult.Success -> true
            is ClientResult.Failure -> result.error.kind == ErrorKind.INVALID_CREDENTIALS || result.error.status in REVOKE_FINAL_STATUSES
        }
    }

    override suspend fun prepare(account: Account, itemId: String, request: CastPlayRequest, startMs: Long): ClientResult<PlayOutcome> =
        liveLock.withLock {
            // Revoke waits for negotiation to finish, closes its result, then invalidates the temporary credential.
            // It must not log out while Open is in flight: the resulting source would have no credential left to close it.
            if (account in retired) {
                return@withLock ClientResult.Failure(ClientError(ErrorKind.EXPIRED, "This Cast viewing has ended."))
            }
            val result = client().prepareFor(
                account,
                itemId,
                castDeviceProfile(),
                CastReceiverFormats.MAX_BITRATE,
                PlaybackRequest(
                    startPosition = startMs.milliseconds,
                    mediaSourceId = request.mediaSourceId,
                    audioStreamIndex = request.audioStreamIndex,
                    subtitleStreamIndex = request.subtitleStreamIndex
                )
            )
            val decision = ((result as? ClientResult.Success)?.value as? PlayOutcome.Play)?.decision
            if (decision?.isLive == true) liveSources.getOrPut(account) { mutableListOf() } += CastLiveSource(decision)
            result
        }

    private fun CastReport.withLiveSource(account: Account): CastReport {
        val source = liveSources[account]?.lastOrNull {
            it.decision.itemId == itemId && it.decision.playSessionId == playSessionId && it.decision.mediaSourceId == mediaSourceId
        }?.decision
        return copy(
            liveStreamId = liveStreamId ?: source?.mediaSource?.liveStreamId,
            ownedLiveStreamId = ownedLiveStreamId ?: source?.ownedLiveStreamId
        )
    }

    private suspend fun closeOwned(account: Account, report: CastReport? = null): Boolean {
        val sources = liveSources[account].orEmpty().filter {
            report == null ||
                (
                    it.decision.itemId == report.itemId &&
                        it.decision.playSessionId == report.playSessionId &&
                        it.decision.mediaSourceId == report.mediaSourceId
                    )
        }
        var closed = true
        for (source in sources) {
            val id = source.decision.ownedLiveStreamId
            if (id == null || client().closeLiveStream(account, id) is ClientResult.Success) {
                liveSources[account]?.remove(source)
                if (id != null) closedSources.getOrPut(account) { mutableSetOf() } += id
            } else {
                closed = false
            }
        }
        // A reattached viewing carries ownership from app-private storage after process death.
        if (sources.isEmpty() && report?.ownedLiveStreamId != null) {
            closed = client().closeLiveStream(account, report.ownedLiveStreamId) is ClientResult.Success
            if (closed) closedSources.getOrPut(account) { mutableSetOf() } += report.ownedLiveStreamId
        }
        if (liveSources[account]?.isEmpty() == true) liveSources.remove(account)
        return closed
    }

    private fun CastReport.progress() = PlaybackProgressInfo(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        playMethod = method,
        positionTicks = positionTicks,
        isPaused = paused,
        liveStreamId = liveStreamId
    )

    private suspend fun beginReport(account: Account, report: CastReport): CastReport {
        val current = report.withLiveSource(account)
        val previous = activeReports[account]
        if (previous != null &&
            previous.liveStreamId != null &&
            (
                previous.itemId != current.itemId ||
                    previous.playSessionId != current.playSessionId ||
                    previous.mediaSourceId != current.mediaSourceId
                )
        ) {
            stopReport(account, previous)
            closeOwned(account, previous)
        }
        activeReports[account] = current
        return current
    }

    override suspend fun reportStart(account: Account, report: CastReport) = liveLock.withLock {
        if (account !in retired) client().reportStart(account, beginReport(account, report).progress())
        Unit
    }

    override suspend fun reportProgress(account: Account, report: CastReport) = liveLock.withLock {
        if (account !in retired) client().reportProgress(account, beginReport(account, report).progress())
        Unit
    }

    private suspend fun stopReport(account: Account, report: CastReport) {
        client().reportStopped(
            account,
            PlaybackStopInfo(
                itemId = report.itemId,
                mediaSourceId = report.mediaSourceId,
                playSessionId = report.playSessionId,
                positionTicks = report.positionTicks,
                liveStreamId = report.liveStreamId
            )
        )
    }

    override suspend fun reportStopped(account: Account, report: CastReport) = liveLock.withLock {
        val current = report.withLiveSource(account)
        stopReport(account, current)
        closeOwned(account, current)
        activeReports.remove(account)
        Unit
    }

    override suspend fun stopEncoding(account: Account, playSessionId: String) {
        client().stopEncoding(account, playSessionId)
    }
}
