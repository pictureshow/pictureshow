/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.listen
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ListenerCleanupPlayerTest {
    @Test fun cancellingActualMedia3ListenerOnWorkerRemovesItOnApplicationLooperExactlyOnce() {
        val base = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder().build()
        }
        var removals = 0
        var additions = 0
        var removalLooper: Looper? = null
        val recorded = object : ForwardingPlayer(base) {
            override fun addListener(listener: Player.Listener) {
                additions++
                super.addListener(listener)
            }
            override fun removeListener(listener: Player.Listener) {
                removalLooper = Looper.myLooper()
                super.removeListener(listener)
                removals++
            }
        }
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failure.set(error) })
        val presented = ListenerCleanupPlayer(recorded)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { presented.listen {} }
        try {
            assertEquals(1, additions)
            val worker = Thread({ job.cancel() }, "presentation-canceller")
            worker.start()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(job.isCompleted)
            assertNull(failure.get(), "Worker cancellation must not remove a SimpleBasePlayer listener on the worker")
            assertEquals(0, removals, "Off-thread cleanup must be queued, rather than accessing the player there")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, removals)
            assertSame(base.applicationLooper, removalLooper)
            job.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, removals, "Repeated cancellation cannot remove the listener twice")
        } finally {
            scope.cancel()
            base.release()
        }
    }

    @Test fun unrelatedPlayerAccessStillRejectsTheWrongThread() {
        val base = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State = State.Builder().build()
        }
        val presented = ListenerCleanupPlayer(base)
        val failure = AtomicReference<Throwable?>()
        try {
            val worker = Thread({ failure.set(runCatching { presented.currentPosition }.exceptionOrNull()) }, "wrong-thread-position")
            worker.start()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
        } finally {
            base.release()
        }
    }
}
