/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopNativeOwnerTest {
    @Test fun normalOwnerDestroysBeforeReleasingPlayerExactlyOnceOnItsOwnThread() {
        val events = CopyOnWriteArrayList<String>()
        val closeCalls = AtomicInteger()
        val release = DesktopPlayerRelease { events.add("player:${Thread.currentThread().name}"); closeCalls.incrementAndGet() }
        val owner = DesktopNativeOwner(
            create = { events.add("create:${Thread.currentThread().name}"); "context" },
            frames = { events.add("frames:${Thread.currentThread().name}") },
            finish = { events.add("finish:${Thread.currentThread().name}") },
            destroy = { events.add("destroy:${Thread.currentThread().name}") }, player = release
        )
        val worker = Thread(owner, "native-owner-test").apply { start() }
        owner.completed.get(5, TimeUnit.SECONDS); worker.join()
        release.release(); release.release()
        assertEquals(1, closeCalls.get())
        assertEquals(listOf("create", "frames", "finish", "destroy", "player"), events.map { it.substringBefore(':') })
        assertTrue(events.all { it.substringAfter(':') == "native-owner-test" })
    }
    @Test fun callerTimeoutStillLeavesLateNativeTeardownAndReleaseWithOriginalOwner() {
        val allowFrameToReturn = CountDownLatch(1)
        val enteredFrame = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val release = DesktopPlayerRelease { events.add("player") }
        val owner = DesktopNativeOwner(
            create = { "context" },
            frames = { enteredFrame.countDown(); check(allowFrameToReturn.await(5, TimeUnit.SECONDS)); events.add("frame-return") },
            finish = { events.add("finish") }, destroy = { events.add("destroy") }, player = release
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(owner)
            assertTrue(enteredFrame.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { owner.completed.get(20, TimeUnit.MILLISECONDS) }
            // shutdown retains the already-running owner. No new teardown task is required.
            executor.shutdown()
            assertTrue(events.isEmpty())
            allowFrameToReturn.countDown()
            owner.completed.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("frame-return", "finish", "destroy", "player"), events.toList())
            release.release(); assertEquals(1, events.count { it == "player" })
        } finally { allowFrameToReturn.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun failedDrainStillDestroysContextBeforeReleasingPlayer() {
        val events = mutableListOf<String>()
        val owner = DesktopNativeOwner(create = { "context" }, frames = {},
            finish = { events.add("finish"); error("GPU drain failed") },
            destroy = { events.add("destroy") }, player = DesktopPlayerRelease { events.add("player") })
        owner.run()
        assertFailsWith<ExecutionException> { owner.completed.get() }
        assertEquals(listOf("finish", "destroy", "player"), events)
    }
    @Test fun failedDestructionNeverReleasesPlayerUnderAnUnconfirmedContext() {
        val closeCalls = AtomicInteger()
        val owner = DesktopNativeOwner(create = { "context" }, frames = {}, finish = {},
            destroy = { error("native destruction unconfirmed") }, player = DesktopPlayerRelease { closeCalls.incrementAndGet() })
        owner.run()
        assertFailsWith<ExecutionException> { owner.completed.get() }
        assertEquals(0, closeCalls.get())
    }
    @Test fun failedCreationReleasesPlayerWithoutTryingToDestroyAnAbsentContext() {
        val events = mutableListOf<String>()
        val owner = DesktopNativeOwner<String>(create = { error("no native window") }, frames = { error("must not frame") },
            finish = { error("must not finish") }, destroy = { error("must not destroy") }, player = DesktopPlayerRelease { events.add("player") })
        owner.run()
        assertFailsWith<ExecutionException> { owner.created.get() }
        assertFailsWith<ExecutionException> { owner.completed.get() }
        assertEquals(listOf("player"), events)
    }
}
