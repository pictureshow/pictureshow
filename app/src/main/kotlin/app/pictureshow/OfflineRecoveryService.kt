/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

internal const val OFFLINE_RECOVERY_A = 4803
internal const val OFFLINE_RECOVERY_B = 4804
private val recoveryIds = setOf(OFFLINE_RECOVERY_A, OFFLINE_RECOVERY_B)

/** One transfer owner; a timer firing alongside it only maintains the next persisted timer. Main only. */
internal class OfflineRecoveryGate {
    var owner: Int? = null
        private set
    var primaryRunning = false
    var timerKnown = false

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
internal fun offlineBookRecovery(context: Context, current: Int? = null, owner: Int? = null, refreshUnknown: Boolean = false): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
    // Task Manager Stop removes user-visible work. A watchdog must not create a replacement request.
    if (scheduler.getPendingJob(OFFLINE_JOB)?.isUserInitiated != true) {
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
        if (!offlineBookRecovery(this, params.jobId, recovery.owner)) {
            recovery.timerKnown = false
            if (getSystemService(JobScheduler::class.java)?.getPendingJob(OFFLINE_JOB)?.isUserInitiated == true) {
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
