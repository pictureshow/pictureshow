/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bytes 10.10 and 10.11 send for a converted caption: a byte order mark and a Region header before the first cue (OBSERVED). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class OfflineWebvttTest {
    private val body = "WEBVTT\n\nRegion: id:subtitle width:80% lines:3 regionanchor:50%,100% viewportanchor:50%,90%\n\n" +
        "00:00:01.000 --> 00:00:30.000 region:subtitle line:90%\nEnglish external subtitle one\n"

    private fun cues(bytes: ByteArray): List<CuesWithTiming> {
        val out = mutableListOf<CuesWithTiming>()
        WebvttParser().parse(bytes, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out
    }

    @Test
    fun `saved captions read the same with or without the byte order mark`() {
        val plain = cues(body.encodeToByteArray())
        val marked = cues(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.encodeToByteArray())
        assertEquals(1, plain.size)
        assertEquals("English external subtitle one", marked.single().cues.single().text.toString())
        assertEquals(plain.single().startTimeUs, marked.single().startTimeUs)
    }
}
