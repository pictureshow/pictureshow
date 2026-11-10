/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.Service
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.TextPersistence
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineLifetimeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())

    @After
    fun cleanup() {
        scope.cancel()
        stopKoin()
    }

    @Test
    fun `modern transfer is a persisted immediate user request without a public internet requirement`() {
        assertTrue(offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS))
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val job = scheduler.allPendingJobs.single()
        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertEquals(0L, job.minLatencyMillis)
        assertFalse(job.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(offlineScheduleJob(app, OFFLINE_JOB_WAITING_MS))
        assertSame(job, scheduler.allPendingJobs.single())
    }

    @Test
    fun `accepted modern transfer uses the job notification and never starts a dataSync service`() {
        val foreground = AndroidOfflineForeground(app)
        foreground.unfinished(true)
        foreground.update(listOf("Example"))
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.single().isUserInitiated)
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    @Config(sdk = [23, 30, 33])
    fun `legacy null restart requests sticky recreation and wakes the engine without an activity`() {
        var loads = 0
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? {
                loads++
                return null
            }
            override suspend fun write(text: String) = Unit
        })
        val foreground = AndroidOfflineForeground(app)
        val engine = OfflineEngine(app, accounts, { ClientIdentity("Example", "example", "test") }, foreground, scope = scope)
        startKoin {
            modules(
                module {
                    single { foreground }
                    single { engine }
                }
            )
        }
        val controller = Robolectric.buildService(OfflineTransferService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        // Wake is dispatched, not a synchronous startup disk/network read on Main.
        assertEquals(0, loads)
        (scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.test.TestDispatcher).scheduler.runCurrent()
        assertTrue(loads > 0)
        controller.destroy()
    }

    @Test
    fun `a schedule rejection is retried instead of remembered as a booking`() {
        var calls = 0
        val booking = OfflineRestartBooking(book = { ++calls > 1 }, cancel = {})
        booking.unfinished(true)
        booking.unfinished(true)
        booking.unfinished(true)
        assertEquals(2, calls)
    }

    @Test
    fun `system retry preserves accepted work without scheduling a new background request`() {
        var calls = 0
        val booking = OfflineRestartBooking(book = {
            calls++
            true
        }, cancel = {})
        booking.unfinished(true)
        booking.started()
        booking.transferring(true)
        booking.ended(systemRetry = true)
        assertEquals(1, calls)
    }
}
