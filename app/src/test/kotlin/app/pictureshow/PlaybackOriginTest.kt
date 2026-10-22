/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.net.Uri
import androidx.media3.datasource.DataSpec
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class PlaybackOriginTest {
    @Test
    fun crossOriginRedirectDropsAuthorizationAndRequiredCredentialsOnTheSecondRequest() {
        MockWebServer().use { origin ->
            MockWebServer().use { external ->
                origin.start()
                external.start()
                origin.enqueue(
                    MockResponse().setResponseCode(
                        302
                    ).addHeader("Location", external.url("/subtitle.vtt?%61pi_key=secret&%58-Emby-Token=secret&part=1"))
                )
                external.enqueue(MockResponse().setBody("text"))
                val server = server(origin)
                read(server, decision(origin.url("/start").toString()), origin.url("/start").toString())
                assertCredentials(origin, true)
                assertCredentials(external, false, "/subtitle.vtt?part=1")
            }
        }
    }

    @Test
    fun sameOriginRedirectKeepsAuthenticationAndStripsLegacyQueryTokens() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/part?X-Emby-Token=secret&part=2"))
            origin.enqueue(MockResponse().setBody("text"))
            read(server(origin), decision(origin.url("/start").toString()), origin.url("/start").toString())
            assertCredentials(origin, true)
            assertCredentials(origin, true, "/part?part=2")
        }
    }

    @Test
    fun externalUrlSubtitleIsMappedButGetsNoServerCredentialsEvenWithExplicitDataSpecAuthorization() {
        MockWebServer().use { origin ->
            MockWebServer().use { external ->
                origin.start()
                external.start()
                external.enqueue(MockResponse().setBody("text"))
                val subtitle = MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 4,
                    codec = "webvtt",
                    isExternalUrl = true,
                    deliveryUrl = external.url("/caption.vtt?%61pi_key=secret").toString()
                )
                val decision = decision(origin.url("/stream").toString(), listOf(subtitle))
                val item = PlaybackMedia.item(server(origin), decision, "Example feature")
                assertEquals("Example feature", item.mediaMetadata.title)
                val uri = item.localConfiguration!!.subtitleConfigurations.single().uri
                assertFalse(uri.toString().contains("secret"))
                read(server(origin), decision, uri.toString(), mapOf("Authorization" to "unexpected-secret"))
                assertCredentials(external, false, "/caption.vtt")
                assertEquals(0, origin.requestCount)
            }
        }
    }

    @Test
    fun samePortOtherHostAlsoReceivesNoToken() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse().setBody("text"))
            val server = Server("server", "Demo", "http://localhost:${origin.port}/base", "12.0.0")
            read(server, decision("${server.url}/stream"), "http://127.0.0.1:${origin.port}/caption.vtt")
            assertCredentials(origin, false)
        }
    }

    private fun assertCredentials(server: MockWebServer, authenticated: Boolean, path: String? = null) {
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        if (authenticated) {
            assertEquals("MediaBrowser Token=\"secret\"", request.getHeader("Authorization"))
            assertEquals("source-secret", request.getHeader("X-Source-Credential"))
        } else {
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Source-Credential"))
        }
        if (path != null) assertEquals(path, request.path)
    }

    private fun server(server: MockWebServer) = Server("server", "Demo", server.url("/base").toString(), "12.0.0")

    private fun decision(url: String, streams: List<MediaStream> = emptyList()) = PlayDecision(
        "item",
        MediaSourceInfo(mediaStreams = streams),
        PlayMethod.DirectPlay,
        url,
        mapOf("Authorization" to "MediaBrowser Token=\"secret\"", "X-Source-Credential" to "source-secret"),
        "session"
    )

    private fun read(server: Server, decision: PlayDecision, url: String, headers: Map<String, String> = emptyMap()) {
        val source = PlaybackMedia.dataSourceFactory(server, decision).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(url)).setHttpRequestHeaders(headers).build())
            assertEquals(4, source.read(ByteArray(4), 0, 4))
        } finally {
            source.close()
        }
    }
}
