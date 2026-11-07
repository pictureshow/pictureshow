/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The limit itself, at one second a hang, so proving it costs the suite a few seconds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PerTestTimeLimitTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(60)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun limited(body: () -> Unit) = PerTestTimeLimit(1).apply(
        object : Statement() {
            override fun evaluate() = body()
        },
        Description.createTestDescription("Fixture", "hangs")
    ).evaluate()

    private fun assertRanOver(thrown: AssertionError) = assertEquals("hangs(Fixture) ran over its 1 s limit", thrown.message)

    @Test fun composeThatNeverIdlesFailsByNameAtTheLimit() {
        var spinning = true
        try {
            assertRanOver(
                assertFailsWith {
                    limited {
                        compose.setContent {
                            LaunchedEffect(Unit) {
                                while (spinning) withFrameNanos { }
                            }
                        }
                        compose.waitForIdle()
                    }
                }
            )
        } finally {
            spinning = false
        }
    }

    // Twenty seconds rather than forever, so a limit that stops working fails this test and doesn't hang it.
    @Test fun aBlockedThreadFailsByNameAtTheLimit() = assertRanOver(assertFailsWith { limited { Thread.sleep(20_000) } })

    @Test fun theNextTestRunsCleanAfterAHang() {
        assertRanOver(assertFailsWith { limited { Thread.sleep(20_000) } })
        assertFalse(Thread.currentThread().isInterrupted)
        var ran = false
        limited { ran = true }
        assertTrue(ran)
        compose.waitForIdle()
    }

    @Test fun aTestsOwnFailureKeepsItsType() {
        assertEquals("own", assertFailsWith<IllegalStateException> { limited { error("own") } }.message)
    }
}
