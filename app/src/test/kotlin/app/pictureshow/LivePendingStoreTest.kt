/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.livetv.LivePendingWrites
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LivePendingStoreTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun clear() {
        context.getSharedPreferences("live-pending", 0).edit().clear().commit()
    }

    @Test fun unresolvedCreateSurvivesNewClientAndIsIsolatedByViewerScope() = runBlocking {
        val first: LivePendingWrites = LivePendingStore(context)
        first.add("server:viewer-a:program")
        val restarted: LivePendingWrites = LivePendingStore(context)
        assertTrue(restarted.contains("server:viewer-a:program"))
        assertFalse(restarted.contains("server:viewer-b:program"))
        assertTrue(context.getSharedPreferences("live-pending", 0).all.keys.none { "viewer" in it || "server" in it })
        restarted.remove("server:viewer-a:program")
        assertFalse(first.contains("server:viewer-a:program"))
    }
}
