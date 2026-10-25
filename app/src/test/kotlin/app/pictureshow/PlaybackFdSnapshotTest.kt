/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PlaybackFdSnapshotTest {
    @Test
    fun importedBuffersAndAllocatorNodesStayDistinctFromOrdinaryPipesAndAnonInodes() {
        val targets = listOf(
            "anon_inode:dmabuf", "/dmabuf:frame", "/dev/dma_heap/system",
            "/dev/ion", "/dev/gralloc", "/dev/mali0", "/dev/dri/renderD128",
            "/dev/goldfish_address_space", "/dev/goldfish_pipe",
            "socket:[123]", "pipe:[456]", "anon_inode:[eventfd]", "/private/account/cache"
        )
        withEntries(targets.size) { directory ->
            val snapshot = PlaybackFdSnapshot.capture(directory) { targets[File(it).name.toInt()] }
            assertEquals(
                mapOf("dmabuf" to 3, "ion" to 1, "gralloc" to 5, "sockets" to 1, "pipes" to 1, "anon_inode" to 1, "other" to 1),
                snapshot.counts
            )
            val line = snapshot.fields()
            assertTrue(line.contains("total=13"))
            targets.forEach { assertFalse(line.contains(it), "FD target escaped into diagnostic: $line") }
            assertFalse(line.contains("123"))
            assertFalse(line.contains("456"))
        }
    }

    @Test
    fun concurrentCloseIsCountedAndDeniedEnumerationIsExplicit() {
        withEntries(3) { directory ->
            val snapshot = PlaybackFdSnapshot.capture(directory) {
                if (File(it).name == "1") throw java.io.IOException("private descriptor details")
                "socket:[789]"
            }
            assertEquals(2, snapshot.counts?.get("sockets"))
            assertEquals(1, snapshot.counts?.get("other"))
            assertEquals(1, snapshot.unreadable)
            assertTrue(snapshot.fields().contains("total=3"))
            assertFalse(snapshot.fields().contains("private"))
            assertEquals("available=false", PlaybackFdSnapshot.capture(File(directory, "missing")).fields())
        }
    }

    @Test
    fun missingReadlinkTargetIsCountedWithoutEscapingTheDiagnosticWorker() {
        withEntries(3) { directory ->
            val snapshot = PlaybackFdSnapshot.capture(directory) { null }
            assertEquals(3, snapshot.unreadable)
            assertEquals(3, snapshot.counts?.get("other"))
            assertTrue(snapshot.fields().contains("total=3"))
        }
    }

    private fun withEntries(count: Int, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("fd-snapshot").toFile()
        try {
            repeat(count) { File(directory, it.toString()).createNewFile() }
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
