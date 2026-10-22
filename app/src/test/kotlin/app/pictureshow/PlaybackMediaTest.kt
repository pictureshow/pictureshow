/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecisions
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackInfoResponse
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackMediaTest {
    @Test
    fun decisionMapsBasePathHeadersHlsAndExternalTextWithoutUrlTokens() {
        val server = Server("server-one", "Demo server", "https://example.test/media", "12.0.0")
        val source = MediaSourceInfo(
            id = "source-one",
            transcodingUrl = "/videos/item-one/master.m3u8?ApiKey=private-token&codec=h264",
            transcodingSubProtocol = app.pictureshow.core.playback.MediaStreamProtocol.Hls,
            requiredHttpHeaders = mapOf("X-Test" to "extra"),
            mediaStreams = listOf(
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 2,
                    codec = "subrip",
                    isExternal = true,
                    isDefault = true,
                    language = "en",
                    deliveryUrl = "/Videos/item-one/Subtitles/2/Stream.srt?ApiKey=private-token"
                ),
                MediaStream(type = MediaStreamType.Subtitle, index = 3, codec = "ass", isExternal = false),
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 4,
                    codec = "pgssub",
                    isExternal = true,
                    deliveryMethod = SubtitleDeliveryMethod.Encode,
                    deliveryUrl = "/burn?api_key=private-token"
                ),
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 5,
                    codec = "webvtt",
                    isExternal = true,
                    isForced = true,
                    deliveryUrl = "Subtitles/5.vtt?x=1&api_key=private-token"
                )
            )
        )
        val decision = assertIs<PlayOutcome.Play>(
            PlayDecisions.decide(
                server,
                "item-one",
                PlaybackInfoResponse(listOf(source), "session"),
                ClientIdentity("TV", "device"),
                "private-token"
            )
        ).decision
        val media = PlaybackMedia.item(server, decision).localConfiguration!!
        assertEquals("https://example.test/media/videos/item-one/master.m3u8?codec=h264", media.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, media.mimeType)
        assertEquals(2, media.subtitleConfigurations.size)
        val subtitle = media.subtitleConfigurations.first()
        assertEquals("https://example.test/media/Videos/item-one/Subtitles/2/Stream.srt", subtitle.uri.toString())
        assertEquals("server-subtitle:2", subtitle.id)
        assertEquals("en", subtitle.language)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, subtitle.mimeType)
        assertEquals(C.SELECTION_FLAG_DEFAULT, subtitle.selectionFlags)
        assertEquals("https://example.test/media/Subtitles/5.vtt?x=1", media.subtitleConfigurations[1].uri.toString())
        assertEquals(C.SELECTION_FLAG_FORCED, media.subtitleConfigurations[1].selectionFlags)
        assertFalse(media.uri.toString().contains("private-token"))
        assertTrue(media.subtitleConfigurations.none { it.uri.toString().contains("private-token") })
        assertEquals("extra", decision.headers["X-Test"])
        assertTrue(decision.headers.getValue("Authorization").contains("Token=\"private-token\""))
        // The factory's per-instance properties must retain the same headers for every source it creates.
        val factory = PlaybackMedia.httpFactory(decision)
        val data = factory.createDataSource()
        val field = data.javaClass.getDeclaredField("defaultRequestProperties").apply { isAccessible = true }
        val properties = field.get(data) as androidx.media3.datasource.HttpDataSource.RequestProperties
        assertEquals(decision.headers, properties.snapshot)
    }

    @Test
    fun serverExtractedSubtitleUsesDeliveryUrlEvenWhenOriginalStreamIsEmbedded() {
        val server = Server("server", "Demo", "https://example.test/base", "12.0.0")
        val stream = MediaStream(
            type = MediaStreamType.Subtitle,
            index = 4,
            codec = "subrip",
            isExternal = false,
            deliveryMethod = SubtitleDeliveryMethod.External,
            deliveryUrl = "/Videos/item/Subtitles/4/Stream.srt?ApiKey=private-token"
        )
        val decision = app.pictureshow.core.playback.PlayDecision(
            "item",
            MediaSourceInfo(mediaStreams = listOf(stream)),
            app.pictureshow.core.playback.PlayMethod.DirectPlay,
            "https://example.test/base/video",
            emptyMap(),
            "session"
        )
        val subtitles = PlaybackMedia.item(server, decision).localConfiguration!!.subtitleConfigurations
        assertEquals(1, subtitles.size)
        assertEquals("https://example.test/base/Videos/item/Subtitles/4/Stream.srt", subtitles.single().uri.toString())
    }

    @Test
    fun eachManifestSegmentAndSubtitleRequestUsesHeaderAndDropsLegacyKeys() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media/") { exchange ->
            requests += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
            val bytes = "test".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/media"
            val decision = app.pictureshow.core.playback.PlayDecision(
                "item",
                MediaSourceInfo(id = "source"),
                app.pictureshow.core.playback.PlayMethod.Transcode,
                "$base/master.m3u8",
                mapOf("Authorization" to "MediaBrowser Token=\"private-token\""),
                "session"
            )
            val factory = PlaybackMedia.dataSourceFactory(decision)
            listOf("master.m3u8", "main.m3u8", "init.mp4", "segment00001.m4s", "subtitle.srt").forEach { path ->
                val data = factory.createDataSource()
                try {
                    data.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("$base/$path?ApiKey=private-token&part=1")))
                    val buffer = ByteArray(16)
                    assertEquals(4, data.read(buffer, 0, buffer.size))
                } finally {
                    data.close()
                }
            }
            assertEquals(5, requests.size)
            assertTrue(requests.all { it.first.endsWith("?part=1") && !it.first.contains("private-token") })
            assertTrue(requests.all { it.second == "MediaBrowser Token=\"private-token\"" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun serverDefaultsBeatLanguageThenLanguageAndOffApply() {
        val audio = listOf(
            MediaStream(type = MediaStreamType.Audio, index = 1, language = "en"),
            MediaStream(type = MediaStreamType.Audio, index = 2, language = "fr", isDefault = true)
        )
        assertEquals(2, PlaybackMedia.defaultStream(audio, MediaStreamType.Audio, 2, "en"))
        assertEquals(2, PlaybackMedia.defaultStream(audio, MediaStreamType.Audio, null, "en"))
        assertEquals(1, PlaybackMedia.defaultStream(audio.map { it.copy(isDefault = false) }, MediaStreamType.Audio, null, "en"))
        val iso3 = audio.map { it.copy(isDefault = false, language = if (it.index == 1) "eng" else "fra") }
        assertEquals(2, PlaybackMedia.defaultStream(iso3, MediaStreamType.Audio, null, "fr-CA"))
        assertEquals(
            2,
            PlaybackMedia.defaultStream(iso3.map { it.copy(type = MediaStreamType.Subtitle) }, MediaStreamType.Subtitle, null, "fr")
        )
        assertEquals(-1, PlaybackMedia.defaultStream(audio, MediaStreamType.Subtitle, -1, "en"))
        assertEquals(-1, PlaybackMedia.defaultStream(emptyList(), MediaStreamType.Subtitle, null, "en"))
    }
}
