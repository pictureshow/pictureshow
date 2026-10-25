/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PlaybackFdDiagnosticsTest {
    @Test
    fun inspectionFailureIsContainedOffThreadAndReopenDoesNotSampleAgain() {
        val callingThread = Thread.currentThread()
        val captures = AtomicInteger()
        val writes = AtomicInteger()
        val completed = CountDownLatch(1)
        var line = ""
        var workerThread: Thread? = null
        val diagnostic = PlaybackFdDiagnostics(
            capture = {
                workerThread = Thread.currentThread()
                captures.incrementAndGet()
                throw IllegalStateException("private target details")
            },
            write = {
                line = it
                writes.incrementAndGet()
                completed.countDown()
            }
        )
        diagnostic.opened { false }
        diagnostic.opened { false }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, captures.get())
        assertEquals(1, writes.get())
        assertTrue(workerThread !== callingThread)
        assertTrue(line.contains("available=false"))
        assertFalse(line.contains("private"))
    }
}
