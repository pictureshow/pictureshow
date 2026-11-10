/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scheduler get() = app.getSystemService(JobScheduler::class.java)

    @Test
    fun `a rejected watchdog booking stays owed and retries later without spinning`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        var calls = 0
        val foreground = AndroidOfflineForeground(app) { owner, refreshUnknown ->
            calls++
            if (calls == 1) false else offlineBookRecovery(app, owner = owner, refreshUnknown = refreshUnknown)
        }
        foreground.jobStarted(true)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals(1, calls)
        assertEquals(null, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
        ShadowLooper.idleMainLooper(58, TimeUnit.SECONDS)
        assertEquals(1, calls)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals(2, calls)
        assertTrue(scheduler.allPendingJobs.any { it.id == OFFLINE_RECOVERY_A && it.isPersisted })
    }

    @Test
    fun `ending work cancels a rejected watchdog booking retry`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        var calls = 0
        val foreground = AndroidOfflineForeground(app) { _, _ ->
            calls++
            false
        }
        foreground.jobStarted(true)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        foreground.unfinished(false)
        ShadowLooper.idleMainLooper(2, TimeUnit.MINUTES)
        assertEquals(1, calls)
        assertEquals(null, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
    }

    @Test
    fun `a running UIDT does not suppress its independent future recovery timer`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        val primary = scheduler.getPendingJob(OFFLINE_JOB)
        val foreground = AndroidOfflineForeground(app)
        foreground.jobStarted(true)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(scheduler.allPendingJobs.any { it.id == OFFLINE_RECOVERY_A && it.isPersisted })
        assertSame(primary, scheduler.getPendingJob(OFFLINE_JOB))
        foreground.unfinished(false)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertEquals(null, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
    }

    @Test
    fun `a fired timer replaces a stale retry record with a fresh future timer`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        offlineBookRecovery(app)
        val stale = scheduler.getPendingJob(OFFLINE_RECOVERY_A)
        assertTrue(offlineBookRecovery(app, OFFLINE_RECOVERY_B))
        val future = scheduler.getPendingJob(OFFLINE_RECOVERY_A)!!
        assertNotSame(stale, future)
        assertEquals(OFFLINE_JOB_TRANSFER_MS, future.minLatencyMillis)
        assertEquals(OFFLINE_JOB_TRANSFER_MS * 2, future.maxExecutionDelayMillis)
    }

    @Test
    fun `a fresh process refreshes an unknown timer once while later live updates keep its deadline`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        offlineBookRecovery(app)
        val unknown = scheduler.getPendingJob(OFFLINE_RECOVERY_A)
        val foreground = AndroidOfflineForeground(app)
        foreground.jobStarted(true)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        val known = scheduler.getPendingJob(OFFLINE_RECOVERY_A)
        assertNotSame(unknown, known)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertSame(known, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
    }

    @Test
    fun `accepted UIDT has an independent persisted timer with a batching deadline`() {
        assertTrue(offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS))
        val primary = scheduler.getPendingJob(4802)
        assertTrue(offlineBookRecovery(app))
        val timer = scheduler.getPendingJob(OFFLINE_RECOVERY_A)!!
        assertFalse(timer.isUserInitiated)
        assertTrue(timer.isPersisted)
        assertEquals(OFFLINE_JOB_TRANSFER_MS, timer.minLatencyMillis)
        assertEquals(OFFLINE_JOB_TRANSFER_MS * 2, timer.maxExecutionDelayMillis)
        assertFalse(timer.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(timer.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertSame(primary, scheduler.getPendingJob(4802))
        assertTrue(offlineBookRecovery(app))
        assertSame(timer, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
    }

    @Test
    fun `starting recovery leaves another future timer without replacing its transfer owner`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        offlineBookRecovery(app)
        val owner = scheduler.getPendingJob(OFFLINE_RECOVERY_A)
        assertTrue(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
        assertTrue(scheduler.getPendingJob(OFFLINE_RECOVERY_B)!!.isPersisted)
        assertSame(owner, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
        assertEquals(OFFLINE_RECOVERY_B, offlineRecoveryNextId(OFFLINE_RECOVERY_B, OFFLINE_RECOVERY_A))
        assertTrue(offlineBookRecovery(app, OFFLINE_RECOVERY_B, OFFLINE_RECOVERY_A))
        assertSame(owner, scheduler.getPendingJob(OFFLINE_RECOVERY_A))
    }

    @Test
    fun `removing user visible work prevents the timer from resurrecting it`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        offlineBookRecovery(app)
        scheduler.cancel(4802)
        assertFalse(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `finishing or canceling the queue removes both recovery timers`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        offlineBookRecovery(app)
        offlineBookRecovery(app, OFFLINE_RECOVERY_A)
        offlineCancelRecovery(app)
        assertEquals(listOf(4802), scheduler.allPendingJobs.map { it.id })
    }

    @Test
    fun `recovery has one owner and yields to primary execution or a foreground service`() {
        val gate = OfflineRecoveryGate()
        gate.primaryRunning = true
        assertFalse(gate.claim(OFFLINE_RECOVERY_A, false))
        gate.primaryRunning = false
        assertFalse(gate.claim(OFFLINE_RECOVERY_A, true))
        assertTrue(gate.claim(OFFLINE_RECOVERY_A, false))
        assertFalse(gate.claim(OFFLINE_RECOVERY_B, false))
        gate.release(OFFLINE_RECOVERY_B)
        assertTrue(gate.owns(OFFLINE_RECOVERY_A, false))
        gate.primaryRunning = true
        assertFalse(gate.owns(OFFLINE_RECOVERY_A, false))
        gate.release(OFFLINE_RECOVERY_A)
        assertEquals(null, gate.owner)
    }

    @Test
    @Config(sdk = [23, 30, 33])
    fun `legacy sticky service and timed primary do not gain modern watchdog jobs`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        assertFalse(offlineBookRecovery(app))
        assertEquals(listOf(4802), scheduler.allPendingJobs.map { it.id })
    }
}
