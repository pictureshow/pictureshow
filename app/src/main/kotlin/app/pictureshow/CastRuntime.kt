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
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.control.CastReceiverFormats
import app.pictureshow.core.control.CastSenderVpn
import app.pictureshow.core.control.CastStoredSessions
import app.pictureshow.core.control.CastViewingRecord
import app.pictureshow.core.control.castDeviceProfile
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import io.ktor.client.HttpClient
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

internal class AndroidCastEnvironment(private val context: Context) : CastEnvironment {
    private val stored by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

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
    override var sessions: CastStoredSessions
        get() = stored.getString("sessions", null)
            ?.let { runCatching { CastStorageJson.decodeFromString(CastStoredSessions.serializer(), it) }.getOrNull() }
            ?: CastStoredSessions()
        set(value) {
            stored.edit(commit = true) { putString("sessions", CastStorageJson.encodeToString(CastStoredSessions.serializer(), value)) }
        }

    override var viewing: CastViewingRecord?
        get() = stored.getString("viewing", null)
            ?.let { runCatching { CastStorageJson.decodeFromString(CastViewingRecord.serializer(), it) }.getOrNull() }
        set(value) {
            stored.edit(commit = true) {
                if (value == null) {
                    remove("viewing")
                } else {
                    putString("viewing", CastStorageJson.encodeToString(CastViewingRecord.serializer(), value))
                }
            }
        }
}

private const val CAST_DEVICE_ID_LENGTH = 32

private class ClientCastGateway(private val http: HttpClient, private val identity: DeviceIdentityStore, private val name: String) :
    CastMediaGateway {
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

    override suspend fun revokeSession(session: Account): Boolean =
        when (val result = JellyfinClient(http, clientIdentity()).logout(session)) {
            is ClientResult.Success -> true
            is ClientResult.Failure -> result.error.kind == ErrorKind.INVALID_CREDENTIALS
        }

    override suspend fun prepare(account: Account, itemId: String, request: CastPlayRequest, startMs: Long): ClientResult<PlayOutcome> =
        client().prepareFor(
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

    private fun CastReport.progress() = PlaybackProgressInfo(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        playMethod = method,
        positionTicks = positionTicks,
        isPaused = paused
    )

    override suspend fun reportStart(account: Account, report: CastReport) {
        client().reportStart(account, report.progress())
    }

    override suspend fun reportProgress(account: Account, report: CastReport) {
        client().reportProgress(account, report.progress())
    }

    override suspend fun reportStopped(account: Account, report: CastReport) {
        client().reportStopped(
            account,
            PlaybackStopInfo(
                itemId = report.itemId,
                mediaSourceId = report.mediaSourceId,
                playSessionId = report.playSessionId,
                positionTicks = report.positionTicks
            )
        )
    }

    override suspend fun stopEncoding(account: Account, playSessionId: String) {
        client().stopEncoding(account, playSessionId)
    }
}
