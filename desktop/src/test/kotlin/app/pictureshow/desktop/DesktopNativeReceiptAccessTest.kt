/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class DesktopNativeReceiptAccessTest {
    @Test fun pollingAndViewportCannotOverwriteBorrowedReceiptDuringCopy() {
        val access = DesktopNativeReceiptAccess()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val copy = CountDownLatch(1)
        val competing = CountDownLatch(1)
        var nativeStorage = "first"
        try {
            val polling = pool.submit<String?> { access.read {
                entered.countDown()
                check(copy.await(5, TimeUnit.SECONDS))
                nativeStorage
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val viewport = pool.submit<String?> {
                competing.countDown()
                access.read { nativeStorage = "second"; nativeStorage }
            }
            assertTrue(competing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { viewport.get(100, TimeUnit.MILLISECONDS) }
            copy.countDown()
            assertEquals("first", polling.get(5, TimeUnit.SECONDS))
            assertEquals("second", viewport.get(5, TimeUnit.SECONDS))
        } finally { copy.countDown(); pool.shutdownNow() }
    }

    @Test fun destructionDrainsReceiptCopyAndFutureReadsNeverTouchFreedStorage() {
        val access = DesktopNativeReceiptAccess()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val copy = CountDownLatch(1)
        val closing = CountDownLatch(1)
        try {
            val reader = pool.submit<String?> { access.read {
                entered.countDown()
                check(copy.await(5, TimeUnit.SECONDS))
                "copied"
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = pool.submit {
                closing.countDown()
                access.close { }
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { close.get(100, TimeUnit.MILLISECONDS) }
            copy.countDown()
            assertEquals("copied", reader.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertNull(access.read<String> { fail("Native receipt was read after destruction") })
            access.close { fail("Native surface destroyed twice") }
        } finally { copy.countDown(); pool.shutdownNow() }
    }
}
