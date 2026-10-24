/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/** Full production source/output path, stopped at the platform codec boundary. No fake extractor. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AacHlsConfigurationTest {
    @Test
    fun hlsCopyAndReencodeSendTheExtractedConfigToTheCodecOnFirstInitialization() {
        for (fixture in listOf("copied-audio-preroll.ts", "aac-fdk-surround.ts", "aac-stereo.ts")) {
            val channels = if (fixture == "aac-stereo.ts") 2 else 6
            ShadowLog.clear()
            val config = capture(fixture, channels, hls = true)
            assertEquals(channels, config.mediaFormat.getInteger("channel-count"))
            assertEquals(48000, config.mediaFormat.getInteger("sample-rate"))
            val bytes = requireNotNull(config.mediaFormat.getByteBuffer("csd-0")).duplicate().let { b ->
                ByteArray(b.remaining()).also(b::get)
            }
            assertContentEquals(byteArrayOf(0x11, if (channels == 6) 0xb0.toByte() else 0x90.toByte()), bytes, fixture)
            val diagnostics = ShadowLog.getLogsForTag("PictureshowPlayback").filter { it.msg.startsWith("audio-config ") }
            assertEquals(1, diagnostics.size, "Every AAC codec initialization must expose its actual configuration")
            assertTrue(diagnostics.single().msg.contains("csd0=${bytes.joinToString("") { "%02x".format(it) }}"))
            println(
                "$fixture codec=${config.codecInfo.name} format=${config.mediaFormat} csd=${bytes.joinToString("") {
                    "%02x".format(it)
                }}"
            )
        }
    }

    @Test
    fun progressiveCopySendsTheSameConfigWithoutAnHlsPlaylist() {
        val config = capture("copied-audio-preroll.ts", 6, hls = false)
        val buffer = requireNotNull(config.mediaFormat.getByteBuffer("csd-0")).duplicate()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        assertContentEquals(byteArrayOf(0x11, 0xb0.toByte()), bytes)
        assertEquals(6, config.mediaFormat.getInteger("channel-count"))
        assertEquals(48000, config.mediaFormat.getInteger("sample-rate"))
    }

    private fun capture(fixture: String, channels: Int, hls: Boolean): MediaCodecAdapter.Configuration {
        val context = RuntimeEnvironment.getApplication()
        val captured = AtomicReference<MediaCodecAdapter.Configuration>()
        val data = requireNotNull(javaClass.getResourceAsStream("/playback/$fixture")).use { it.readBytes() }
        val codecs = if (fixture == "copied-audio-preroll.ts") "avc1.64000a,mp4a.40.2" else "mp4a.40.2"
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Main",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="$channels"
            #EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS="$codecs",AUDIO="audio"
            media.m3u8
        """.trimIndent().toByteArray()
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:3
            #EXTINF:4.0,
            segment.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val upstream = DataSource.Factory {
            var delegate: ByteArrayDataSource? = null
            object : DataSource {
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
                override fun getUri() = delegate?.uri
                override fun open(spec: DataSpec): Long {
                    val bytes = when (spec.uri.lastPathSegment) {
                        "master.m3u8" -> master
                        "media.m3u8" -> playlist
                        else -> data
                    }
                    return ByteArrayDataSource(bytes).also { delegate = it }.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = requireNotNull(delegate).read(buffer, offset, length)
                override fun close() {
                    delegate?.close()
                }
            }
        }
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf(
                FlacBufferAudioRenderer(
                    context,
                    MediaCodecAdapter.Factory { configuration ->
                        captured.set(configuration)
                        throw IOException("Captured codec boundary")
                    },
                    MediaCodecSelector { mime, _, _ ->
                        if (mime == MimeTypes.AUDIO_AAC) {
                            listOf(MediaCodecInfo.newInstance("test.aac", mime, mime, null, false, false, false, false, false))
                        } else {
                            emptyList()
                        }
                    },
                    false,
                    handler,
                    audio,
                    DefaultAudioSink.Builder(context).build()
                )
            )
        }
        val output =
            Media3PlaybackOutput(
                context,
                Server("test", "Test", "https://example.test", "12"),
                upstream = upstream,
                renderersFactory = renderers
            )
        val decision = PlayDecision(
            "test",
            MediaSourceInfo(
                id = "test",
                transcodingSubProtocol = MediaStreamProtocol.Hls,
                mediaStreams = listOf(MediaStream(type = MediaStreamType.Audio, index = 0, codec = "aac", channels = channels))
            ),
            if (hls) PlayMethod.Transcode else PlayMethod.DirectPlay,
            "https://example.test/${if (hls) "master.m3u8" else "stream.ts"}",
            emptyMap(),
            "test"
        )
        try {
            output.open(decision, 0, 0, -1, false)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (captured.get() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
                Thread.sleep(2)
            }
            return assertNotNull(captured.get(), "No codec configuration for $fixture; error=${output.player.value?.playerError}")
        } finally {
            output.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
