/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DiagnosticTextChunksTest {
    @Test fun routeTextKeepsAudioTrackWhole() {
        val line = "Audio output: Media3 default route; connected outputs: HDMI. Active AudioTrack route unavailable outside playback."
        assertEquals(listOf(line), diagnosticTextChunks(line, 1.3f))
    }

    @Test fun decoderListKeepsFlacWhole() {
        val line = "FFmpeg decoders present (profile-relevant audio): AAC, AC3, EAC3, TRUEHD, DTS, FLAC, ALAC, OPUS, VORBIS, MP3, MP2"
        assertEquals(listOf(line), diagnosticTextChunks(line, 1.3f))
    }

    @Test fun decoderLimitsKeepOriginalSpaces() {
        val line = "  3840 × 2160 at 60.0 fps; up to 120.0 fps at 1080p or below; reported decoder limits"
        assertEquals(listOf(line), diagnosticTextChunks(line, 1.3f))
    }

    @Test fun midLineBlockBoundariesPreserveWordsAndEverySpace() {
        val text = (1..200).joinToString("  ") { "decoder-$it" } + "  "
        for (scale in listOf(1f, 1.3f)) {
            val chunks = diagnosticTextChunks(text, scale)
            assertTrue(chunks.size > 1)
            assertEquals(text, chunks.joinToString(""))
            chunks.zipWithNext().forEach { (before, after) ->
                assertTrue(before.last() == ' ' || after.first() == ' ', "Block boundary cuts a word: $before | $after")
            }
        }
    }

    @Test fun onlyAnOverlongTokenMayBeSplitAcrossBlocks() {
        val text = "{\"Codec\":\"" + "x".repeat(1500) + "\"}"
        for (scale in listOf(1f, 1.3f)) {
            val chunks = diagnosticTextChunks(text, scale)
            assertTrue(chunks.size > 1)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= 80 * if (scale > 1.15f) 7 else 12 })
        }
    }

    @Test fun originalNewlinesAndEmptyLinesSurviveWithinAndBetweenBlocks() {
        val lines = (1..30).map { if (it % 5 == 0) "" else "  capability $it  " }
        for (scale in listOf(1f, 1.3f)) {
            assertEquals(lines.joinToString("\n"), diagnosticTextChunks(lines.joinToString("\n"), scale).joinToString("\n"))
        }
        assertEquals(listOf(""), diagnosticTextChunks("", 1.3f))
        assertEquals(listOf("a\n\nb\n"), diagnosticTextChunks("a\n\nb\n", 1.3f))
    }

    @Test fun exactLimitTokensIndentationTabsAndCrLfKeepTheirCharacters() {
        for (text in listOf("x".repeat(80), "x".repeat(81), "  " + "x".repeat(80) + "  y  ", "a\tb\r\nc\r\n")) {
            assertEquals(listOf(text), diagnosticTextChunks(text, 1.3f))
        }
    }

    @Test fun mixedLongAndShortLinesLoseOnlyTheirStructuralBoundaryNewline() {
        val text = (1..30).joinToString("\n") { line ->
            if (line % 5 == 0) "" else (1..20).joinToString("  ") { "codec-$line-$it" }
        } + "\n"
        for (scale in listOf(1f, 1.3f)) {
            var offset = 0
            val read = StringBuilder()
            diagnosticTextChunks(text, scale).forEachIndexed { index, chunk ->
                // Separate Text nodes replace one original line separator at a line-boundary split.
                if (index > 0 && text.getOrNull(offset) == '\n') {
                    read.append('\n')
                    offset++
                }
                assertEquals(text.substring(offset, offset + chunk.length), chunk, "Unexpected text at source offset $offset")
                read.append(chunk)
                offset += chunk.length
            }
            assertEquals(text, read.toString())
        }
    }
}
