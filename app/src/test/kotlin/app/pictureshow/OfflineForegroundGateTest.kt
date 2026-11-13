/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class OfflineForegroundGateTest {
    private val log = mutableListOf<String>()
    private var allowed = true
    private val gate = OfflineForegroundGate(
        start = { titles ->
            if (allowed) log += "start:${titles.joinToString()}"
            allowed
        },
        stop = { log += "stop" }
    )

    @Test
    fun `a stop waits until the service has answered its start`() {
        gate.want(listOf("A"))
        gate.want(emptyList())
        assertEquals(listOf("start:A"), log)
        gate.started()
        assertEquals(listOf("start:A", "stop"), log)
    }

    @Test
    fun `new titles during a start wait for the answer and then show`() {
        gate.want(listOf("A"))
        gate.want(listOf("A", "B"))
        assertEquals(listOf("start:A"), log)
        gate.started()
        assertEquals(listOf("start:A", "start:A, B"), log)
    }

    @Test
    fun `the same titles don't start the service again`() {
        gate.want(listOf("A"))
        gate.started()
        gate.want(listOf("A"))
        assertEquals(listOf("start:A"), log)
    }

    @Test
    fun `a sticky recreation with no unfinished work stops its actual service`() {
        gate.started()
        assertEquals(listOf("stop"), log)
    }

    @Test
    fun `nothing to stop when nothing started`() {
        gate.want(emptyList())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a start Android refuses isn't waited for, and the next change tries again`() {
        allowed = false
        gate.want(listOf("A"))
        gate.want(emptyList())
        assertEquals(emptyList<String>(), log)
        allowed = true
        gate.want(listOf("B"))
        assertEquals(listOf("start:B"), log)
    }

    @Test
    fun `a service refused by Android or timed out is started afresh, not stopped`() {
        gate.want(listOf("A"))
        gate.refused()
        gate.want(emptyList())
        assertEquals(listOf("start:A"), log)
        gate.want(listOf("A"))
        gate.started()
        gate.gone()
        log.clear()
        gate.want(listOf("A"))
        assertEquals(listOf("start:A"), log)
    }

    @Test
    fun `a flurry of updates reaches Android as one start of where it ended up`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val foreground = AndroidOfflineForeground(app)
        repeat(100) { n ->
            foreground.update(if (n % 2 == 0) listOf("A") else emptyList())
        }
        foreground.update(listOf("A", "B"))
        val shadow = shadowOf(app)
        assertNull(shadow.nextStartedService)
        ShadowLooper.idleMainLooper(OFFLINE_FOREGROUND_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        val started: Intent? = shadow.nextStartedService
        assertNotNull(started)
        assertEquals(listOf("A", "B"), started!!.getStringArrayListExtra(OFFLINE_EXTRA_TITLES))
        assertNull(shadow.nextStartedService)
    }
}
