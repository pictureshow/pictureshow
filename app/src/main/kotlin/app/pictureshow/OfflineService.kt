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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.koin.android.ext.android.inject

private const val OFFLINE_CHANNEL = "downloads"
private const val OFFLINE_NOTIFICATION = 4801
private const val OFFLINE_JOB = 4802
private const val OFFLINE_CHANNEL_NAME = "Downloads"

internal const val OFFLINE_EXTRA_TITLES = "titles"

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
        .setContentText(if (titles.size == 1) "1 download in progress" else "${titles.size} downloads in progress")
        .setOngoing(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) })
        .build()
}

/** Bytes moving on screen-off and while the viewer is elsewhere. Started only while a transfer runs. */
class OfflineTransferService : Service() {
    private val engine: OfflineEngine by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val titles = intent?.getStringArrayListExtra(OFFLINE_EXTRA_TITLES).orEmpty()
        if (titles.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, OFFLINE_NOTIFICATION, offlineNotification(this, titles), type)
        return START_NOT_STICKY
    }

    /** Android 15 limits this service type's time. Bytes wait; the scheduled job picks them up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.foregroundTimedOut()
        offlineScheduleJob(this)
        stopSelf()
    }
}

/** Restarts unfinished work after a kill or reboot, with no Google services involved. */
class OfflineJobService : JobService() {
    private val engine: OfflineEngine by inject()

    override fun onStartJob(params: JobParameters?): Boolean {
        engine.start()
        engine.networkChanged()
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

internal fun offlineScheduleJob(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    runCatching {
        scheduler.schedule(
            JobInfo.Builder(OFFLINE_JOB, ComponentName(context, OfflineJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
        )
    }
}

internal class AndroidOfflineForeground(private val context: Context) : OfflineForeground {
    private var last: List<String>? = null

    @Synchronized
    override fun update(activeTitles: List<String>) {
        if (activeTitles == last) return
        val wasEmpty = last.isNullOrEmpty()
        last = activeTitles
        val intent = Intent(context, OfflineTransferService::class.java)
        if (activeTitles.isEmpty()) {
            if (!wasEmpty) context.stopService(intent)
            return
        }
        offlineScheduleJob(context)
        intent.putStringArrayListExtra(OFFLINE_EXTRA_TITLES, ArrayList(activeTitles))
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: RuntimeException) {
            // Started from the background (reboot job, for one). Work carries on under the job until the app next opens.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
        }
    }
}
