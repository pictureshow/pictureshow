/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import android.provider.Settings
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
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
        val foreground = AndroidOfflineForeground(app) { owner, refreshUnknown, viewerReturned ->
            calls++
            if (calls ==
                1
            ) {
                false
            } else {
                offlineBookRecovery(app, owner = owner, refreshUnknown = refreshUnknown, viewerReturned = viewerReturned)
            }
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
        val foreground = AndroidOfflineForeground(app) { _, _, _ ->
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
    fun `boot can recover a persisted timer when Android drops a delayed UIDT during restore`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        assertTrue(offlineBookRecovery(app))
        scheduler.cancel(OFFLINE_JOB)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertTrue(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
        assertTrue(scheduler.getPendingJob(OFFLINE_RECOVERY_B)!!.isPersisted)
        assertEquals(null, scheduler.getPendingJob(OFFLINE_JOB))
        val foreground = AndroidOfflineForeground(app)
        foreground.unfinished(true)
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(scheduler.allPendingJobs.any { it.id == OFFLINE_RECOVERY_A || it.id == OFFLINE_RECOVERY_B })
    }

    @Test
    fun `a boot authorization never overrides a subsequent explicit user stop`() {
        val permit = OfflineRecoveryPermit(7, 1_000)
        assertTrue(permit.allowsBoot(8, 999))
        assertTrue(permit.allowsBoot(9, 0))
        assertFalse(permit.allowsBoot(7, 0))
        assertFalse(permit.allowsBoot(8, 1_000))
        assertFalse(permit.allowsBoot(8, 1_001))
        assertFalse(OfflineRecoveryPermit(7, 0).allowsBoot(8, 0))
    }

    private fun userStopped(at: Long) {
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
                .setTimestamp(at)
                .setPid(123)
                .build()
        )
    }

    @Test
    fun `Task Manager Stop blocks recovery even if a pending UIDT survived it`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        assertTrue(offlineBookRecovery(app))
        val permit = offlineStoredRecoveryPermit(scheduler.getPendingJob(OFFLINE_JOB)!!.extras)!!
        userStopped(permit.issuedAt)
        assertFalse(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
        assertEquals(null, scheduler.getPendingJob(OFFLINE_RECOVERY_B))
        assertTrue(scheduler.getPendingJob(OFFLINE_JOB)!!.isUserInitiated)
    }

    @Test
    fun `Task Manager Stop also wins over a later boot with a lost primary`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        assertTrue(offlineBookRecovery(app))
        val permit = offlineRecoveryPermit(app)!!
        userStopped(permit.issuedAt + 1)
        scheduler.cancel(OFFLINE_JOB)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertFalse(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `a stop preceding the accepted user request does not block boot recovery`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        val permit = offlineRecoveryPermit(app)!!
        userStopped(permit.issuedAt - 1)
        assertTrue(offlineBookRecovery(app))
        scheduler.cancel(OFFLINE_JOB)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertTrue(offlineBookRecovery(app, OFFLINE_RECOVERY_A))
    }

    @Test
    fun `only a viewer return can renew authorization after Task Manager Stop`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        val permit = offlineRecoveryPermit(app)!!
        userStopped(permit.issuedAt)
        assertEquals(null, offlineRecoveryPermit(app))
        assertTrue(offlineRecoveryPermit(app, viewerReturned = true) != null)
        // Use main's bounded deadline with the actual permit clock and keep background denial explicit.
        val wallClockDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (offlineNewRecoveryPermit(app).issuedAt <= permit.issuedAt && System.nanoTime() < wallClockDeadline) {
            Thread.sleep(1)
        }
        assertTrue(
            "Permit wall clock did not advance beyond the original permit within one second",
            offlineNewRecoveryPermit(app).issuedAt > permit.issuedAt
        )
        assertEquals(null, offlineRecoveryPermit(app))

        assertTrue(offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS, viewerReturned = true))
        val renewed = offlineStoredRecoveryPermit(scheduler.getPendingJob(OFFLINE_JOB)!!.extras)!!
        assertTrue(renewed.issuedAt > permit.issuedAt)
        assertFalse(offlineUserStoppedAfter(app, renewed))
    }

    @Test
    @Config(sdk = [33])
    fun `legacy timer retains its authorization until a viewer returns`() {
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        val original = offlineStoredRecoveryPermit(scheduler.getPendingJob(OFFLINE_JOB)!!.extras)!!
        userStopped(original.issuedAt)
        offlineScheduleJob(app, OFFLINE_JOB_WAITING_MS)
        val background = offlineStoredRecoveryPermit(scheduler.getPendingJob(OFFLINE_JOB)!!.extras)!!
        assertEquals(original, background)
        assertTrue(offlineUserStoppedAfter(app, background))
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
        assertTrue(offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS))
        assertFalse(offlineBookRecovery(app))
        assertEquals(listOf(4802), scheduler.allPendingJobs.map { it.id })
    }
}
