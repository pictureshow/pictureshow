/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Boxes small enough to read: a conversion's real shape, `ftyp moov (moof mdat)*`. */
internal object Fmp4Builder {
    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.encodeToByteArray()).put(body).array()
    }

    private fun full(version: Int, flags: Int, vararg ints: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4 + ints.size * 4).putInt((version shl 24) or flags)
        ints.forEach(buffer::putInt)
        return buffer.array()
    }

    fun ftyp() = box("ftyp", "isom".encodeToByteArray(), ByteArray(4))

    fun moov(timescale: Int = 1000): ByteArray {
        val tkhd = box("tkhd", full(0, 3, 0, 0, 1) + ByteArray(68))
        val mdhd = box("mdhd", full(0, 0, 0, 0, timescale, 0) + ByteArray(4))
        val hdlr = box("hdlr", full(0, 0, 0) + "vide".encodeToByteArray() + ByteArray(13))
        val trak = box("trak", tkhd, box("mdia", mdhd, hdlr))
        val trex = box("trex", full(0, 0, 1, 1, 0, 0, 0))
        return box("moov", trak, box("mvex", trex))
    }

    fun fragment(baseTime: Int, duration: Int, payload: Int = 64): ByteArray {
        val tfhd = box("tfhd", full(0, 0x020000, 1))
        val tfdt = box("tfdt", full(0, 0, baseTime))
        // One sample with an explicit duration.
        val trun = box("trun", full(0, 0x100, 1, duration))
        return box("moof", box("traf", tfhd, tfdt, trun)) + box("mdat", ByteArray(payload) { 7 })
    }

    fun file(fragments: Int = 3): ByteArray {
        var all = ftyp() + moov()
        repeat(fragments) { all += fragment(it * 2000, 2000) }
        return all
    }

    fun topLevel(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var at = 0
        while (at + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, at, 4).int
            out += String(bytes, at + 4, 4)
            at += size
        }
        return out
    }
}

class OfflineFmp4Test {
    @get:Rule val folder = TemporaryFolder()

    private fun spaced(source: ByteArray, chunk: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val spacer = Fmp4Spacer(out)
        var at = 0
        while (at < source.size) {
            val length = minOf(chunk, source.size - at)
            spacer.write(source.copyOfRange(at, at + length), length)
            at += length
        }
        assertEquals(OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong(), spacer.inserted)
        return out.toByteArray()
    }

    @Test
    fun `room for the index opens right before the first moof however the bytes arrive`() {
        val source = Fmp4Builder.file()
        listOf(1, 3, 7, 16, 4096).forEach { chunk ->
            val output = spaced(source, chunk)
            assertEquals(
                "chunk $chunk",
                listOf("ftyp", "moov", "free", "moof", "mdat", "moof", "mdat", "moof", "mdat"),
                Fmp4Builder.topLevel(output)
            )
            assertEquals(source.size + OFFLINE_INDEX_PLACEHOLDER_BYTES, output.size)
        }
    }

    @Test
    fun `the index replaces the placeholder in place and points at the first moof`() {
        val file = folder.newFile()
        file.writeBytes(spaced(Fmp4Builder.file(), 4096))
        val before = file.length()
        assertTrue(writeFmp4Index(file))
        assertEquals(before, file.length())
        val bytes = file.readBytes()
        val kinds = Fmp4Builder.topLevel(bytes)
        assertEquals(listOf("ftyp", "moov", "free", "sidx", "moof"), kinds.take(5))
        // first_offset is measured from the end of sidx: free comes first so that 0 lands on the moof.
        val sidx = String(bytes).indexOf("sidx") - 4
        val size = ByteBuffer.wrap(bytes, sidx, 4).int
        assertEquals("moof", String(bytes, sidx + size + 4, 4))
    }

    @Test
    fun `media bytes are untouched by indexing`() {
        val source = Fmp4Builder.file()
        val file = folder.newFile()
        file.writeBytes(spaced(source, 4096))
        val prefix = source.size - (Fmp4Builder.ftyp() + Fmp4Builder.moov()).size
        assertTrue(writeFmp4Index(file))
        val after = file.readBytes()
        assertEquals(source.takeLast(prefix), after.takeLast(prefix).toList())
    }

    @Test
    fun `a file that isn't a conversion is refused, not patched`() {
        val file = folder.newFile()
        file.writeBytes(Fmp4Builder.ftyp() + ByteArray(32))
        assertFalse(writeFmp4Index(file))
        val noRoom = File(folder.root, "no-room")
        noRoom.writeBytes(Fmp4Builder.file())
        assertFalse(writeFmp4Index(noRoom))
        assertEquals(Fmp4Builder.file().size.toLong(), noRoom.length())
    }

    @Test
    fun `running time is what the fragments add up to, before and after indexing`() {
        val file = folder.newFile()
        file.writeBytes(spaced(Fmp4Builder.file(fragments = 3), 7))
        assertEquals(60_000_000L, fmp4MeasureTicks(file))
        assertTrue(writeFmp4Index(file))
        assertEquals(60_000_000L, fmp4MeasureTicks(file))
    }

    @Test
    fun `a file with no fragments has no measured running time`() {
        val file = folder.newFile()
        file.writeBytes(Fmp4Builder.ftyp() + Fmp4Builder.moov())
        assertEquals(null, fmp4MeasureTicks(file))
    }
}
