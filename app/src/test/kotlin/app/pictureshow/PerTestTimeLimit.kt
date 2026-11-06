/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Fails a test by name when it outlasts [seconds], so a scroll or focus loop in a mutant can't hold the build, and the
 * test classes after it still run.
 *
 * JUnit's Timeout moves the test to another thread, where Compose's rule can't run its looper. A hang here is the main
 * looper never going idle, and that looper runs on the test thread, so the failure goes in as a posted task and surfaces
 * from waitForIdle. An interrupt covers a test blocked outright. Code spinning with neither looper nor interrupt can't be
 * stopped from inside the JVM; the Test task's own timeout in app/build.gradle.kts is the backstop for that.
 */
class PerTestTimeLimit(private val seconds: Long) : TestRule {
    private class Expired : Error("time limit")

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val testThread = Thread.currentThread()
            val expired = AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    Thread.sleep(seconds * 1_000)
                    expired.set(true)
                    // Again and again: Compose's teardown idles the same looper and has to be got out of too.
                    while (true) {
                        Handler(Looper.getMainLooper()).post { throw Expired() }
                        testThread.interrupt()
                        Thread.sleep(2_000)
                    }
                } catch (_: InterruptedException) {
                    // The test finished.
                }
            }.apply {
                isDaemon = true
                start()
            }
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (thrown: Throwable) {
                failure = thrown
            } finally {
                watchdog.interrupt()
                watchdog.join()
                Thread.interrupted()
            }
            if (expired.get()) throw AssertionError("${description.displayName} ran over its $seconds s limit", failure)
            failure?.let { throw it }
        }
    }
}
