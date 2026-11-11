/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

internal const val OFFLINE_RECOVERY_A = 4803
internal const val OFFLINE_RECOVERY_B = 4804
private val recoveryIds = setOf(OFFLINE_RECOVERY_A, OFFLINE_RECOVERY_B)
private const val RECOVERY_BOOT = "pictureshow.offline.boot"
private const val RECOVERY_ISSUED = "pictureshow.offline.issued"

/** An accepted user request can survive a lost UIDT at boot, but never a later explicit stop. */
internal data class OfflineRecoveryPermit(val boot: Int, val issuedAt: Long) {
    fun allowsBoot(currentBoot: Int, lastUserStop: Long): Boolean =
        boot >= 0 && currentBoot > boot && issuedAt > 0 && lastUserStop < issuedAt

    fun extras() = PersistableBundle().apply {
        putInt(RECOVERY_BOOT, boot)
        putLong(RECOVERY_ISSUED, issuedAt)
    }
}

internal fun offlineNewRecoveryPermit(context: Context) = OfflineRecoveryPermit(
    if (Build.VERSION.SDK_INT >= 24) Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) else -1,
    System.currentTimeMillis()
)

internal fun offlineStoredRecoveryPermit(extras: PersistableBundle?): OfflineRecoveryPermit? = extras?.let {
    if (!it.containsKey(RECOVERY_BOOT) || !it.containsKey(RECOVERY_ISSUED)) {
        null
    } else {
        OfflineRecoveryPermit(it.getInt(RECOVERY_BOOT), it.getLong(RECOVERY_ISSUED))
    }
}

internal fun offlineUserStoppedAfter(context: Context, permit: OfflineRecoveryPermit): Boolean {
    if (Build.VERSION.SDK_INT < 30) return false
    val manager = context.getSystemService(ActivityManager::class.java) ?: return true
    return runCatching {
        manager.getHistoricalProcessExitReasons(context.packageName, 0, 0).any {
            it.reason == ApplicationExitInfo.REASON_USER_REQUESTED && it.timestamp >= permit.issuedAt
        }
    }.getOrDefault(true)
}

internal fun offlineRecoveryPermit(context: Context, viewerReturned: Boolean = false): OfflineRecoveryPermit? {
    if (Build.VERSION.SDK_INT < 34) return null
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return null
    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    val primary = scheduler.getPendingJob(OFFLINE_JOB)?.takeIf { it.isUserInitiated }
    if (primary != null && viewerReturned) return offlineNewRecoveryPermit(context)
    val permit = (
        listOfNotNull(offlineStoredRecoveryPermit(primary?.extras)) + recoveryIds.mapNotNull { id ->
            offlineStoredRecoveryPermit(scheduler.getPendingJob(id)?.extras)
        }
        ).maxByOrNull(OfflineRecoveryPermit::issuedAt) ?: return null
    // API35 can persist a retry delay, then reject that UIDT when rebuilding JobInfo at boot.
    // A later user stop still wins, including when a non-running UIDT record survived Stop.
    if (offlineUserStoppedAfter(context, permit)) return null
    return permit.takeIf { primary != null || it.allowsBoot(boot, 0) }
}

/** One transfer owner; a timer firing alongside it only maintains the next persisted timer. Main only. */
internal class OfflineRecoveryGate {
    var owner: Int? = null
        private set
    var primaryRunning = false
    var timerKnown = false
    var viewerReturned = false

    fun claim(id: Int, serviceUp: Boolean): Boolean {
        if (primaryRunning || serviceUp || owner != null) return false
        owner = id
        return true
    }

    fun owns(id: Int, serviceUp: Boolean): Boolean = owner == id && !primaryRunning && !serviceUp

    fun release(id: Int) {
        if (owner == id) owner = null
    }
}

internal fun offlineRecoveryNextId(current: Int, owner: Int?): Int {
    val other = if (current == OFFLINE_RECOVERY_A) OFFLINE_RECOVERY_B else OFFLINE_RECOVERY_A
    return if (other == owner) current else other
}

/** A READY UIDT retry can have no timer after a disconnected host. Keep a separate timed request. */
internal fun offlineBookRecovery(
    context: Context,
    current: Int? = null,
    owner: Int? = null,
    refreshUnknown: Boolean = false,
    viewerReturned: Boolean = false
): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
    val permit = offlineRecoveryPermit(context, viewerReturned)
    if (permit == null) {
        offlineCancelRecovery(context)
        return false
    }
    val id = if (current == null) {
        if (!refreshUnknown && recoveryIds.any { it != owner && scheduler.getPendingJob(it) != null }) return true
        if (owner == OFFLINE_RECOVERY_A) OFFLINE_RECOVERY_B else OFFLINE_RECOVERY_A
    } else {
        offlineRecoveryNextId(current, owner)
    }
    // JobInfo survives a crash even when the retry has lost its actual timing constraint.
    // A fired timer always books a fresh successor; only repeated live-process updates preserve a known timer.
    // The selection above never replaces the job holding transfer IO.
    return runCatching {
        scheduler.schedule(
            JobInfo.Builder(id, ComponentName(context, OfflineRecoveryJobService::class.java))
                .setExtras(permit.extras())
                .setPersisted(true)
                .setMinimumLatency(OFFLINE_JOB_TRANSFER_MS)
                .setOverrideDeadline(OFFLINE_JOB_TRANSFER_MS * 2)
                .setRequiredNetwork(
                    NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build()
                )
                .build()
        ) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
}

internal fun offlineCancelRecovery(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    recoveryIds.forEach(scheduler::cancel)
}

/**
 * Supplemental ordinary job, subject to ordinary quotas. It can own IO when UIDT hasn't dispatched;
 * it never depends on opening an Activity or being permitted a new background UIDT/FGS launch.
 */
@androidx.annotation.RequiresApi(34)
class OfflineRecoveryJobService : JobService() {
    private val engine: OfflineEngine by inject()
    private val foreground: AndroidOfflineForeground by inject()
    private var holding: Job? = null
    private var networkOwner: Any? = null
    private var ownerId: Int? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val recovery = foreground.recovery
        // Book before waking the engine: another process death must leave a future timer intact.
        if (!offlineBookRecovery(this, params.jobId, recovery.owner, viewerReturned = recovery.viewerReturned)) {
            recovery.timerKnown = false
            if (offlineRecoveryPermit(this, recovery.viewerReturned) != null) {
                // A failed timer booking is not completion. Request a timed system retry too.
                jobFinished(params, true)
                return true
            }
            return false
        }
        recovery.timerKnown = true
        if (!recovery.claim(params.jobId, foreground.serviceUp())) return false
        ownerId = params.jobId
        val owner = Any()
        networkOwner = owner
        holding = CoroutineScope(Dispatchers.Main.immediate).launch {
            engine.useJobNetwork(owner, params.network)
            engine.wake()
            delay(OFFLINE_FOREGROUND_SETTLE_MS * 3)
            while (recovery.owns(params.jobId, foreground.serviceUp()) && foreground.transferring()) {
                setNotification(
                    params,
                    OFFLINE_NOTIFICATION,
                    offlineNotification(this@OfflineRecoveryJobService, foreground.titles()),
                    JOB_END_NOTIFICATION_POLICY_REMOVE
                )
                delay(2_000)
            }
            val suspendTransfers = recovery.owns(params.jobId, foreground.serviceUp())
            engine.releaseJobNetwork(owner, suspendTransfers).join()
            networkOwner = null
            recovery.release(params.jobId)
            ownerId = null
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // A coordinator timer may replace itself while the other id holds IO. That has no owner to stop.
        if (ownerId != params.jobId) return false
        holding?.cancel()
        networkOwner?.let { engine.releaseJobNetwork(it, foreground.recovery.owns(params.jobId, foreground.serviceUp())) }
        networkOwner = null
        foreground.recovery.release(params.jobId)
        ownerId = null
        // The separate future timer remains booked, including after this job's process is killed.
        return false
    }

    override fun onNetworkChanged(params: JobParameters) {
        val owner = networkOwner ?: return
        val job = holding ?: return
        CoroutineScope(Dispatchers.Main.immediate + job).launch {
            if (foreground.recovery.owns(params.jobId, foreground.serviceUp())) {
                engine.useJobNetwork(owner, params.network)
                engine.wake()
            }
        }
    }
}
