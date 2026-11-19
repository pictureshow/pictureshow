/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.ServerDiscovery
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The app's launch path for Watch together and remote control, on the real classpath and the real Main dispatcher.
 * The shipped macOS app stopped at launch because nothing provided Dispatchers.Main (2026-11-19) while every host
 * test passed on test dispatchers; this one fails the same way the app did.
 */
class DesktopStartupTest {
    private object NoDiscovery : ServerDiscovery {
        override suspend fun discover(): List<DiscoveredServer> = emptyList()
    }

    @Test fun watchTogetherStartsAtLaunchOnTheMainDispatcherWithoutThrowing() {
        val thrown = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> thrown += e }
        try {
            DesktopController(Files.createTempDirectory("pictureshow-startup-").toFile(), discovery = NoDiscovery).use { c ->
                c.startTogether() // DesktopApplication's own call, with its default dispatcher
                assertNotNull(c.together)
                // Let the hosts' start (bind, sockets, poll loop) run on Main, then look for anything it threw.
                runBlocking { withTimeout(10_000) { repeat(5) { withContext(Dispatchers.Main) { yield() } } } }
                assertTrue(thrown.isEmpty(), "Watch together's start threw: $thrown")
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
