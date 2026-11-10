/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

private const val OFFLINE_CHANNEL = "downloads"
internal const val OFFLINE_NOTIFICATION = 4801
internal const val OFFLINE_JOB = 4802
private const val OFFLINE_CHANNEL_NAME = "Downloads"

internal const val OFFLINE_EXTRA_TITLES = "titles"
internal const val OFFLINE_FOREGROUND_SETTLE_MS = 400L
internal const val OFFLINE_JOB_TRANSFER_MS = 2 * 60_000L
internal const val OFFLINE_JOB_WAITING_MS = 15 * 60_000L
internal const val OFFLINE_RECOVERY_RETRY_MS = 60_000L
private const val OFFLINE_JOB_HOLD_LOOK_MS = 2_000L

/** Another viewer's download counts but has no name here: see [offlineForegroundTitles]. */
internal fun offlineNotificationTitle(titles: List<String>): String = titles.firstOrNull { it.isNotEmpty() } ?: OFFLINE_CHANNEL_NAME

internal fun offlineNotification(context: Context, titles: List<String>): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager?.createNotificationChannel(NotificationChannel(OFFLINE_CHANNEL, OFFLINE_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
    }
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
    return NotificationCompat.Builder(context, OFFLINE_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(offlineNotificationTitle(titles))
        .setContentText(if (titles.size <= 1) "1 download in progress" else "${titles.size} downloads in progress")
        .setOngoing(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) })
        .build()
}

/** Bytes moving on screen-off and while the viewer is elsewhere. Started only while a transfer runs. */
class OfflineTransferService : Service() {
    private val engine: OfflineEngine by inject()
    private val foreground: AndroidOfflineForeground by inject()
    private var waking: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Every start the app asked for is answered with startForeground, even when the work has since ended: Android
     * ends the app if this service stops before it has gone foreground. Only then may the gate stop it.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val titles = intent?.getStringArrayListExtra(OFFLINE_EXTRA_TITLES).orEmpty()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, OFFLINE_NOTIFICATION, offlineNotification(this, titles), type)
        } catch (e: RuntimeException) {
            // Android 15's time for this type is used up. Bytes carry on under the job.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
            foreground.serviceRefused()
            stopSelf()
            return START_NOT_STICKY
        }
        // A sticky restart has no Activity to start the engine. Answer Android first, then load off Main.
        waking?.cancel()
        waking = CoroutineScope(Dispatchers.Main.immediate).launch {
            engine.wake()
            foreground.serviceStarted()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        waking?.cancel()
        foreground.serviceGone()
        super.onDestroy()
    }

    /** Android 15 limits this service type's time. Bytes wait; the next booked run of the job picks them up, through wake(). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.foregroundTimedOut()
        stopSelf()
    }
}

/** The OS owns the lifetime of the transfer, including process death and persisted reboot recovery. */
class OfflineJobService : JobService() {
    private val engine: OfflineEngine by inject()
    private val foreground: AndroidOfflineForeground by inject()
    private var holding: Job? = null
    private var networkOwner: Any? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val userInitiated = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        foreground.jobStarted(userInitiated)
        if (userInitiated) showTransferNotification(params)
        val owner = Any()
        networkOwner = owner
        holding = CoroutineScope(Dispatchers.Main.immediate).launch {
            engine.useJobNetwork(owner, if (Build.VERSION.SDK_INT >= 28) params.network else null)
            engine.wake()
            delay(OFFLINE_FOREGROUND_SETTLE_MS * 3)
            while (foreground.transferringUnderJobOnly() || (userInitiated && foreground.transferring())) {
                if (userInitiated) showTransferNotification(params)
                delay(OFFLINE_JOB_HOLD_LOOK_MS)
            }
            // Preserve the OS's accepted user request while waiting for network/storage. Never recreate it
            // from the background, where a new UIDT would be refused. Backoff prevents an idle retry loop.
            val retry = userInitiated && foreground.hasUnfinished
            jobFinished(params, retry)
            engine.releaseJobNetwork(owner, suspendTransfers = false)
            networkOwner = null
            foreground.jobEnded(retry)
        }
        return true
    }

    private fun showTransferNotification(params: JobParameters) {
        if (Build.VERSION.SDK_INT >= 34) {
            setNotification(
                params,
                OFFLINE_NOTIFICATION,
                offlineNotification(this, foreground.titles()),
                JOB_END_NOTIFICATION_POLICY_REMOVE
            )
        }
    }

    @androidx.annotation.RequiresApi(28)
    override fun onNetworkChanged(params: JobParameters) {
        val owner = networkOwner ?: return
        val job = holding ?: return
        CoroutineScope(Dispatchers.Main.immediate + job).launch {
            engine.useJobNetwork(owner, params.network)
            engine.wake()
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        holding?.cancel()
        // A stopped job no longer licenses background network IO. Keep the partial and let the system retry.
        networkOwner?.let { engine.releaseJobNetwork(it, suspendTransfers = foreground.transferringUnderJobOnly()) }
        networkOwner = null
        foreground.jobEnded(true)
        return true
    }
}

/** Returns acceptance, so a refused schedule is never remembered as a durable booking. */
internal fun offlineScheduleJob(context: Context, delayMs: Long): Boolean {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
    val component = ComponentName(context, OfflineJobService::class.java)
    if (Build.VERSION.SDK_INT >= 34) {
        // Do not replace an already accepted UIDT (including its system retry/backoff after a kill).
        if (scheduler.getPendingJob(OFFLINE_JOB)?.isUserInitiated == true) return true
        val accepted = runCatching {
            scheduler.schedule(
                JobInfo.Builder(OFFLINE_JOB, component)
                    .setUserInitiated(true)
                    // Builder defaults exclude VPNs. A private server may only be reachable through
                    // the viewer's VPN, so allow that route just as JobInfo's standard requests do.
                    .setRequiredNetwork(
                        NetworkRequest.Builder()
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            .build()
                    )
                    .setPersisted(true)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
            ) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
        if (accepted) return true
    }
    // API 23–33, or a new background request the OS refused. Deadline also wakes a boot recovery
    // that is otherwise merely READY. Doze, force-stop and system health can still defer execution.
    return runCatching {
        scheduler.schedule(
            JobInfo.Builder(OFFLINE_JOB, component)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs * 2)
                .setPersisted(true)
                .build()
        ) == JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)
}

/**
 * Keeps one restart booked for as long as work is unfinished. A job that may run at once runs at once, ends, and is
 * gone from the system's store, so it would be missing at the kill or reboot it was for: this one is booked for later
 * and booked again each time it runs. Scheduling the same id ends a running job, so nothing is booked until it ends.
 * Main thread only.
 */
internal class OfflineRestartBooking(private val book: (Long) -> Boolean, private val cancel: () -> Unit) {
    private var transferring = false
    private var unfinished = false
    private var booked: Long? = null
    private var running = false

    fun transferring(any: Boolean) {
        transferring = any
        settle()
    }

    fun unfinished(any: Boolean) {
        unfinished = any
        settle()
    }

    fun started() {
        running = true
        booked = null
    }

    fun ended(systemRetry: Boolean = false) {
        running = false
        if (systemRetry) booked = wantedDelay()
        settle()
    }

    private fun wantedDelay(): Long? = when {
        !unfinished -> null
        transferring -> OFFLINE_JOB_TRANSFER_MS
        else -> OFFLINE_JOB_WAITING_MS
    }

    private fun settle() {
        if (running) return
        val want = wantedDelay()
        if (want == booked) return
        if (want == null) {
            cancel()
            booked = null
        } else if (book(want)) {
            booked = want
        }
    }
}

/**
 * Decides when the service starts and stops. One rule keeps the app alive: never stop while a start is unanswered.
 * Main thread only.
 */
internal class OfflineForegroundGate(private val start: (List<String>) -> Boolean, private val stop: () -> Unit) {
    private var wanted = emptyList<String>()
    private var shown = emptyList<String>()
    private var unanswered = 0
    private var alive = false

    val serviceUp get() = alive

    fun want(titles: List<String>) {
        wanted = titles
        settle()
    }

    fun started() {
        // A sticky recreation did not pass through start(), so adopt the real service too.
        alive = true
        if (unanswered > 0) unanswered--
        settle()
    }

    fun refused() {
        if (unanswered > 0) unanswered--
        if (unanswered == 0) alive = false
    }

    fun gone() {
        if (unanswered == 0) alive = false
    }

    private fun settle() {
        if (unanswered > 0) return
        if (wanted.isEmpty()) {
            if (alive) {
                alive = false
                stop()
            }
        } else if ((!alive || wanted != shown) && start(wanted)) {
            unanswered++
            alive = true
            shown = wanted
        }
    }
}

internal class AndroidOfflineForeground(
    private val context: Context,
    private val bookRecovery: (Int?) -> Boolean = { owner -> offlineBookRecovery(context, owner = owner) }
) : OfflineForeground {
    val recovery = OfflineRecoveryGate()
    private val main = Handler(Looper.getMainLooper())
    private val recoveryRetry = Runnable { maintainRecovery() }
    private var latest = emptyList<String>()
    private var posted = false
    private var userInitiatedRunning = false
    var hasUnfinished = false
        private set
    private val gate = OfflineForegroundGate(
        start = ::startService,
        stop = { context.stopService(Intent(context, OfflineTransferService::class.java)) }
    )
    private val restart = OfflineRestartBooking(
        book = { delayMs -> offlineScheduleJob(context, delayMs) },
        cancel = { context.getSystemService(JobScheduler::class.java)?.cancel(OFFLINE_JOB) }
    )
    private val apply = Runnable {
        val titles = synchronized(this) {
            posted = false
            latest
        }
        restart.transferring(titles.isNotEmpty())
        // UIDT owns both notification and execution; a parallel FGS would add a six-hour limit.
        gate.want(if (userInitiatedRunning || userInitiatedBooked()) emptyList() else titles)
    }

    /** Called from the engine at every phase change. A flurry settles into one look at where things ended up. */
    override fun update(activeTitles: List<String>) {
        synchronized(this) {
            latest = activeTitles
            if (posted) return
            posted = true
        }
        main.postDelayed(apply, OFFLINE_FOREGROUND_SETTLE_MS)
    }

    override fun unfinished(any: Boolean) {
        main.post {
            hasUnfinished = any
            restart.unfinished(any)
            maintainRecovery()
        }
    }

    fun jobStarted(userInitiated: Boolean = false) {
        recovery.primaryRunning = true
        userInitiatedRunning = userInitiated
        restart.started()
        if (userInitiated) gate.want(emptyList())
        if (hasUnfinished) maintainRecovery()
    }

    fun jobEnded(systemRetry: Boolean = false) {
        recovery.primaryRunning = false
        userInitiatedRunning = false
        restart.ended(systemRetry)
        if (hasUnfinished) maintainRecovery()
    }

    fun titles(): List<String> = synchronized(this) { latest }

    fun transferring() = titles().isNotEmpty()

    fun transferringUnderJobOnly() = transferring() && !gate.serviceUp

    fun serviceUp() = gate.serviceUp

    private fun userInitiatedBooked(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(OFFLINE_JOB)?.isUserInitiated == true

    private fun maintainRecovery() {
        main.removeCallbacks(recoveryRetry)
        if (!hasUnfinished || !userInitiatedBooked()) {
            offlineCancelRecovery(context)
        } else if (!bookRecovery(recovery.owner)) {
            // A rejected booking remains owed. Retry once later, without spinning or moving an accepted deadline.
            main.postDelayed(recoveryRetry, OFFLINE_RECOVERY_RETRY_MS)
        }
    }

    fun serviceStarted() = gate.started()

    fun serviceRefused() = gate.refused()

    fun serviceGone() = gate.gone()

    private fun startService(titles: List<String>): Boolean {
        val intent = Intent(context, OfflineTransferService::class.java)
            .putStringArrayListExtra(OFFLINE_EXTRA_TITLES, ArrayList(titles))
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: RuntimeException) {
            // Started from the background (reboot job, for one). Work carries on under the job until the app next opens.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
            false
        }
    }
}
