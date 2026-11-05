/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineValidator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTransferTest {
    @get:Rule val folder = TemporaryFolder()

    private class Ledger(var allowance: Long = Long.MAX_VALUE) : OfflineLedger {
        var reserved = 0L
        var committed = 0L
        var discarded = 0L
        val commits = mutableListOf<Long>()

        override suspend fun reserve(bytes: Long): Boolean {
            if (reserved + bytes > allowance) return false
            reserved += bytes
            return true
        }

        override suspend fun commit(bytes: Long) {
            check(committed + bytes <= reserved) { "wrote past the reservation" }
            committed += bytes
            commits += bytes
        }

        override suspend fun discard(bytes: Long) {
            discarded += bytes
        }

        override suspend fun progress(received: Long, total: Long?) = Unit
    }

    private val whole = ByteArray(700_000) { (it % 251).toByte() }
    private val stored = OfflineValidator(lastModified = "Mon", totalBytes = whole.size.toLong())

    private fun answer(status: Int, body: InputStream?, length: Long?, vararg headers: Pair<String, String>) = OfflineHttp { _, _, _ ->
        val map = headers.toMap()
        OfflineHttpResponse(status, { map[it] }, body, length) {}
    }

    private fun run(
        http: OfflineHttp,
        ledger: Ledger,
        target: File,
        validator: OfflineValidator? = null,
        fmp4: Boolean = false
    ): OfflineTransferResult = runBlocking {
        OfflineResourceTransfer(http, ledger).run(target, "http://x/", emptyMap(), validator, null, fmp4)
    }

    @Test
    fun `a whole body lands whole, with every byte cleared first`() {
        val target = File(folder.root, "a/media.part")
        val ledger = Ledger()
        val result = run(answer(200, ByteArrayInputStream(whole), whole.size.toLong(), "Last-Modified" to "Mon", "Accept-Ranges" to "bytes"), ledger, target)
        assertTrue(result is OfflineTransferResult.Complete)
        assertArrayEquals(whole, target.readBytes())
        assertEquals(whole.size.toLong(), ledger.committed)
    }

    @Test
    fun `a matching 206 appends at the exact offset`() {
        val target = folder.newFile()
        target.writeBytes(whole.copyOf(300_000))
        val ledger = Ledger()
        var asked: String? = null
        val http = OfflineHttp { _, _, range ->
            asked = range
            val headers = mapOf("Content-Range" to "bytes 300000-699999/700000", "Last-Modified" to "Mon")
            OfflineHttpResponse(206, { headers[it] }, ByteArrayInputStream(whole.copyOfRange(300_000, whole.size)), 400_000) {}
        }
        assertTrue(run(http, ledger, target, stored) is OfflineTransferResult.Complete)
        assertEquals("bytes=300000-", asked)
        assertArrayEquals(whole, target.readBytes())
    }

    @Test
    fun `a 200 on resume starts over instead of appending`() {
        val target = folder.newFile()
        target.writeBytes(ByteArray(300_000) { 9 })
        val ledger = Ledger()
        val result = run(answer(200, ByteArrayInputStream(whole), whole.size.toLong(), "Last-Modified" to "Mon"), ledger, target, stored)
        assertTrue(result is OfflineTransferResult.Complete)
        assertArrayEquals(whole, target.readBytes())
        assertEquals(300_000L, ledger.discarded)
    }

    @Test
    fun `a changed validator never appends`() {
        val target = folder.newFile()
        val partial = ByteArray(300_000) { 9 }
        target.writeBytes(partial)
        val http = answer(206, ByteArrayInputStream(whole), 400_000, "Content-Range" to "bytes 300000-699999/700000", "Last-Modified" to "Tue")
        val result = run(http, Ledger(), target, stored)
        assertFalse(result is OfflineTransferResult.Complete)
        assertTrue("partial was appended to", target.length() <= partial.size)
    }

    @Test
    fun `a wrong offset never appends`() {
        val target = folder.newFile()
        target.writeBytes(ByteArray(300_000) { 9 })
        val http = answer(206, ByteArrayInputStream(whole), 400_000, "Content-Range" to "bytes 299000-699999/700000", "Last-Modified" to "Mon")
        assertFalse(run(http, Ledger(), target, stored) is OfflineTransferResult.Complete)
        assertTrue(target.length() <= 300_000)
    }

    @Test
    fun `a truncated body keeps what arrived and asks to retry`() {
        val target = folder.newFile()
        val short = ByteArrayInputStream(whole.copyOf(100_000))
        val result = run(answer(200, short, whole.size.toLong(), "Last-Modified" to "Mon"), Ledger(), target)
        assertTrue(result is OfflineTransferResult.Interrupted)
        assertEquals(100_000L, target.length())
    }

    @Test
    fun `a dropped connection is interrupted, keeping its bytes`() {
        val target = folder.newFile()
        val body = object : InputStream() {
            var left = 300_000
            override fun read(): Int = throw IOException("drop")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw IOException("drop")
                val n = minOf(len, left)
                left -= n
                return n
            }
        }
        val result = run(answer(200, body, whole.size.toLong(), "Last-Modified" to "Mon"), Ledger(), target)
        assertTrue(result is OfflineTransferResult.Interrupted)
        assertTrue(target.length() in 1..300_000)
    }

    @Test
    fun `known length past the budget writes nothing at all`() {
        val target = File(folder.root, "b/media.part")
        val ledger = Ledger(allowance = whole.size - 1L)
        val result = run(answer(200, ByteArrayInputStream(whole), whole.size.toLong(), "Last-Modified" to "Mon"), ledger, target)
        assertEquals(OfflineTransferResult.NoBudget, result)
        assertEquals(0L, ledger.committed)
        assertEquals(0L, target.length())
    }

    @Test
    fun `unknown length stops at the increment it can't get`() {
        val target = folder.newFile()
        val huge = OFFLINE_UNKNOWN_RESERVE_BYTES.toInt() + 100
        val ledger = Ledger(allowance = OFFLINE_UNKNOWN_RESERVE_BYTES)
        val result = run(answer(200, ByteArrayInputStream(ByteArray(huge)), null), ledger, target)
        assertEquals(OfflineTransferResult.NoBudget, result)
        assertTrue(ledger.committed <= OFFLINE_UNKNOWN_RESERVE_BYTES)
        assertEquals(ledger.committed, target.length())
    }

    @Test
    fun `a converted partial is discarded, never resumed`() {
        val target = folder.newFile()
        target.writeBytes(ByteArray(1000) { 3 })
        val ledger = Ledger()
        var asked: String? = "unset"
        val source = Fmp4Builder.file()
        val http = OfflineHttp { _, _, range ->
            asked = range
            OfflineHttpResponse(200, { null }, ByteArrayInputStream(source), null) {}
        }
        val result = run(http, ledger, target, null, fmp4 = true)
        assertTrue(result is OfflineTransferResult.Complete)
        assertNull(asked)
        assertEquals(1000L, ledger.discarded)
        assertEquals(source.size + app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong(), target.length())
        assertEquals("the spacer's bytes count against the ledger", target.length(), ledger.committed)
    }

    @Test
    fun `an unauthorized answer fails as itself`() {
        val result = run(answer(401, null, null), Ledger(), folder.newFile())
        assertEquals(OfflineTransferResult.Failed("unauthorized"), result)
    }
}
