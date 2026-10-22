/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.pgs.PgsParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.tx3g.Tx3gParser
import androidx.media3.extractor.text.vobsub.VobsubParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class SubtitleSupportTest {
    @Test
    fun requestedContainerSubtitleFormatsHaveRealMedia3Parsers() {
        val factory = DefaultSubtitleParserFactory()
        val formats = mapOf(
            MimeTypes.APPLICATION_SUBRIP to SubripParser::class.java,
            MimeTypes.TEXT_SSA to SsaParser::class.java,
            MimeTypes.APPLICATION_PGS to PgsParser::class.java,
            MimeTypes.APPLICATION_VOBSUB to VobsubParser::class.java,
            MimeTypes.APPLICATION_TX3G to Tx3gParser::class.java
        )
        for ((mime, expected) in formats) {
            val init = if (mime == MimeTypes.APPLICATION_VOBSUB) {
                listOf(("size: 720x480\npalette: " + List(16) { "000000" }.joinToString(",")).encodeToByteArray())
            } else emptyList()
            val format = Format.Builder().setSampleMimeType(mime).setInitializationData(init).build()
            assertTrue(factory.supportsFormat(format), mime)
            assertEquals(expected, factory.create(format).javaClass, mime)
        }
    }

    @Test
    fun assPositionsAndBasicStylesSurviveButKaraokeTagsDoNotCreateTimedSyllables() {
        val text = """
            [Script Info]
            PlayResX: 640
            PlayResY: 480
            [V4+ Styles]
            Format: Name, Alignment, PrimaryColour, Fontsize, Bold, Italic, Underline, StrikeOut, BorderStyle, OutlineColour
            Style: Default,2,&H0000FF00,32,-1,0,-1,0,1,&H00000000
            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,{\an7\pos(160,120)\k20}Test{\k20} cue
        """.trimIndent().encodeToByteArray()
        val output = mutableListOf<CuesWithTiming>()
        SsaParser(emptyList()).parse(text, SubtitleParser.OutputOptions.allCues()) { output += it }
        val active = output.filter { it.cues.isNotEmpty() }.single()
        assertEquals(1_000_000L, active.startTimeUs)
        assertEquals(2_000_000L, active.durationUs)
        val cue = active.cues.single()
        assertEquals("Test cue", cue.text.toString())
        assertEquals(0.25f, cue.position)
        assertEquals(0.25f, cue.line)
        assertEquals(32f / 480, cue.textSize)
        val styled = cue.text as Spanned
        assertEquals(1, styled.getSpans(0, styled.length, StyleSpan::class.java).size)
        assertEquals(1, styled.getSpans(0, styled.length, UnderlineSpan::class.java).size)
        assertEquals(1, styled.getSpans(0, styled.length, ForegroundColorSpan::class.java).size)
    }
}
