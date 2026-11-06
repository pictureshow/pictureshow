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
private const val OFFLINE_NOTIFICATION = 4801
private const val OFFLINE_JOB = 4802
private const val OFFLINE_CHANNEL_NAME = "Downloads"

internal const val OFFLINE_EXTRA_TITLES = "titles"
internal const val OFFLINE_FOREGROUND_SETTLE_MS = 400L
internal const val OFFLINE_JOB_TRANSFER_MS = 2 * 60_000L
internal const val OFFLINE_JOB_WAITING_MS = 15 * 60_000L
private const val OFFLINE_JOB_HOLD_LOOK_MS = 2_000L

private fun offlineNotification(context: Context, titles: List<String>): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager?.createNotificationChannel(NotificationChannel(OFFLINE_CHANNEL, OFFLINE_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
    }
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
    return NotificationCompat.Builder(context, OFFLINE_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(titles.firstOrNull() ?: OFFLINE_CHANNEL_NAME)
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
        foreground.serviceStarted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground.serviceGone()
        super.onDestroy()
    }

    /** Android 15 limits this service type's time. Bytes wait; the scheduled job picks them up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.foregroundTimedOut()
        stopSelf()
    }
}

/**
 * Restarts unfinished work after a kill or reboot, with no Google services involved. While the service can't be
 * foreground (Android refuses a start from the background), this job's own running time carries the bytes.
 */
class OfflineJobService : JobService() {
    private val engine: OfflineEngine by inject()
    private val foreground: AndroidOfflineForeground by inject()
    private var holding: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        foreground.jobStarted()
        holding = CoroutineScope(Dispatchers.Main.immediate).launch {
            engine.start().join()
            delay(OFFLINE_FOREGROUND_SETTLE_MS * 3)
            while (foreground.transferringUnderJobOnly()) delay(OFFLINE_JOB_HOLD_LOOK_MS)
            jobFinished(params, false)
            foreground.jobEnded()
        }
        return true
    }

    /** The system's time for this run is up. Book the next one, since the booked one is this one. */
    override fun onStopJob(params: JobParameters?): Boolean {
        holding?.cancel()
        foreground.jobEnded()
        return false
    }
}

internal fun offlineScheduleJob(context: Context, delayMs: Long) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    runCatching {
        scheduler.schedule(
            JobInfo.Builder(OFFLINE_JOB, ComponentName(context, OfflineJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMs)
                .setPersisted(true)
                .build()
        )
    }
}

/**
 * Keeps one restart booked for as long as work is unfinished. A job that may run at once runs at once, ends, and is
 * gone from the system's store, so it would be missing at the kill or reboot it was for: this one is booked for later
 * and booked again each time it runs. Scheduling the same id ends a running job, so nothing is booked until it ends.
 * Main thread only.
 */
internal class OfflineRestartBooking(private val book: (Long) -> Unit, private val cancel: () -> Unit) {
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

    fun ended() {
        running = false
        settle()
    }

    private fun settle() {
        if (running) return
        val want = when {
            !unfinished -> null
            transferring -> OFFLINE_JOB_TRANSFER_MS
            else -> OFFLINE_JOB_WAITING_MS
        }
        if (want == booked) return
        if (want == null) cancel() else book(want)
        booked = want
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

internal class AndroidOfflineForeground(private val context: Context) : OfflineForeground {
    private val main = Handler(Looper.getMainLooper())
    private var latest = emptyList<String>()
    private var posted = false
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
        gate.want(titles)
        restart.transferring(titles.isNotEmpty())
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
        main.post { restart.unfinished(any) }
    }

    fun jobStarted() = restart.started()

    fun jobEnded() = restart.ended()

    fun transferringUnderJobOnly() = synchronized(this) { latest.isNotEmpty() } && !gate.serviceUp

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
