/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class OfflineRestartBookingTest {
    private val log = mutableListOf<String>()
    private val booking = OfflineRestartBooking(
        book = { log += "book:$it" },
        cancel = { log += "cancel" }
    )

    @Test
    fun `unfinished work keeps a restart booked for later, sooner while bytes move`() {
        booking.unfinished(true)
        assertEquals(listOf("book:$OFFLINE_JOB_WAITING_MS"), log)
        booking.transferring(true)
        assertEquals("book:$OFFLINE_JOB_TRANSFER_MS", log.last())
        booking.transferring(false)
        assertEquals("book:$OFFLINE_JOB_WAITING_MS", log.last())
    }

    @Test
    fun `the same answer again books nothing new, which would push the restart back forever`() {
        booking.unfinished(true)
        booking.unfinished(true)
        booking.transferring(false)
        assertEquals(1, log.size)
    }

    @Test
    fun `nothing unfinished means nothing stays booked`() {
        booking.unfinished(true)
        booking.unfinished(false)
        assertEquals("cancel", log.last())
        booking.transferring(true)
        assertEquals(2, log.size)
    }

    @Test
    fun `a running job is never replaced, since scheduling its id would end it, and the next is booked as it ends`() {
        booking.unfinished(true)
        log.clear()
        booking.started()
        booking.transferring(true)
        booking.unfinished(false)
        booking.unfinished(true)
        assertEquals(emptyList<String>(), log)
        booking.ended()
        assertEquals(listOf("book:$OFFLINE_JOB_TRANSFER_MS"), log)
    }

    @Test
    fun `a job that ran is gone, so the same work books it again`() {
        booking.unfinished(true)
        booking.started()
        booking.ended()
        assertEquals(listOf("book:$OFFLINE_JOB_WAITING_MS", "book:$OFFLINE_JOB_WAITING_MS"), log)
    }

    @Test
    fun `the booked job survives a reboot and can't run the moment it is booked`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        offlineScheduleJob(app, OFFLINE_JOB_TRANSFER_MS)
        val job = app.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        assertTrue(job.isPersisted)
        assertEquals(OFFLINE_JOB_TRANSFER_MS, job.minLatencyMillis)
    }
}
