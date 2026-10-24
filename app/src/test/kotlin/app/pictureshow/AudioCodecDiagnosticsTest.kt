/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class AudioCodecDiagnosticsTest {
    @Test
    fun logsActualRemainingConfigWithoutConsumingItAndPassesOtherCodecsThrough() {
        val adapter = Proxy.newProxyInstance(
            MediaCodecAdapter::class.java.classLoader,
            arrayOf(MediaCodecAdapter::class.java)
        ) { _, _, _ -> error("The factory must return the adapter without invoking it") } as MediaCodecAdapter
        val seen = mutableListOf<MediaCodecAdapter.Configuration>()
        val factory = AudioCodecDiagnostics { config ->
            seen += config
            adapter
        }
        val bytes = ByteBuffer.wrap(byteArrayOf(0, 0x11, 0xb0.toByte(), 0)).apply {
            position(1)
            limit(3)
        }
        val aac = configuration(MimeTypes.AUDIO_AAC, bytes)
        ShadowLog.clear()
        assertSame(adapter, factory.createAdapter(aac))
        assertSame(aac, seen.single())
        assertEquals(1, bytes.position())
        assertEquals(3, bytes.limit())
        val log = ShadowLog.getLogsForTag("PictureshowPlayback").single().msg
        assertTrue(log.contains("csd0len=2 csd0=11b0 truncated=false"))
        assertTrue(log.contains("uptimeMs="))
        ShadowLog.clear()
        val flac = configuration(MimeTypes.AUDIO_FLAC, bytes)
        assertSame(adapter, factory.createAdapter(flac))
        assertSame(flac, seen.last())
        assertEquals(2, seen.size)
        assertTrue(ShadowLog.getLogsForTag("PictureshowPlayback").isEmpty())
        assertEquals(1, bytes.position())
    }

    @Test
    fun oversizedConfigRetainsItsLengthAndPrefixWithoutFloodingTheLog() {
        val bytes = ByteBuffer.wrap(ByteArray(1025) { 0x41 })
        val configuration = configuration(MimeTypes.AUDIO_AAC, bytes)
        val factory = AudioCodecDiagnostics { error("Delegate reached") }
        ShadowLog.clear()
        kotlin.test.assertFailsWith<IllegalStateException> { factory.createAdapter(configuration) }
        val log = ShadowLog.getLogsForTag("PictureshowPlayback").single().msg
        assertTrue(log.contains("csd0len=1025 csd0=${"41".repeat(16)} truncated=true"))
        assertTrue(log.length < 1025)
        assertEquals(0, bytes.position())
        assertEquals(1025, bytes.remaining())
    }

    private fun configuration(mime: String, csd: ByteBuffer): MediaCodecAdapter.Configuration {
        val format = Format.Builder().setSampleMimeType(mime).setSampleRate(48000).setChannelCount(6).build()
        val mediaFormat = MediaFormat.createAudioFormat(mime, 48000, 6).apply { setByteBuffer("csd-0", csd) }
        val info = MediaCodecInfo.newInstance("test.audio", mime, mime, null, false, false, false, false, false)
        return MediaCodecAdapter.Configuration.createForAudioDecoding(info, mediaFormat, format, null, null)
    }
}
