/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackNetwork
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Drives the actual host callbacks through serialized session replacement to PlaybackInfo HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackQualityRequestTest {
    private class Output : PlaybackOutput {
        var moment = PlaybackPosition(0, false)
        override fun position() = moment
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            moment = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() {
            moment = moment.copy(paused = true)
        }
        override fun release() = Unit
    }

    @Test fun bandwidthDropAndOneTapOverrideChangeServerRequestAndKeepPositionAndPauseWithoutNewViewing() = runTest {
        val account = Account(Server("s", "Server", "https://example.test", "12.0.0"), User("u", "Viewer"), "fictional")
        val context = RuntimeEnvironment.getApplication()
        val host = PlaybackHostState(MediaItem(id = "item", name = "Film"), 0, Media3PlaybackOutput(context, account.server), {}, {})
        host.network = PlaybackNetwork.Remote
        val requests = mutableListOf<JsonObject>()
        val caps = DeviceCapabilities("Test", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        respond(
                            """{"MediaSources":[{"Id":"src","Container":"mkv","SupportsTranscoding":true,
                            "TranscodingUrl":"/videos/item/master.m3u8?MediaSourceId=src","TranscodingSubProtocol":"hls","TranscodingContainer":"mp4"}],"PlaySessionId":"view"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        val client = PlaybackClient(http, ClientIdentity("Test", "test-device"))
        val output = Output()
        val gateway =
            ServerPlaybackGateway(client, account, "item", { caps.copy(settings = host.effectiveSettings) }) { ClientResult.Success(Unit) }
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        host.controller = session
        try {
            session.start(73_000)
            runCurrent()
            session.ready()
            runCurrent()
            output.moment = PlaybackPosition(74_000, true)
            host.stateSource = mutableStateOf(session.state.value)
            host.bandwidthSample(6_000_000, 262_144, 1)
            host.bandwidthSample(6_000_000, 262_144, 2)
            runCurrent()
            assertEquals(listOf(20_000_000, 4_000_000), requests.map { it.getValue("MaxStreamingBitrate").jsonPrimitive.int })
            assertEquals(740_000_000L, requests.last().getValue("StartTimeTicks").jsonPrimitive.long)
            assertEquals(PlaybackPosition(74_000, true), output.moment)
            session.ready()
            runCurrent()
            host.overrideQuality() // 4 Mbps override
            runCurrent()
            session.ready()
            runCurrent()
            host.overrideQuality() // 10 Mbps override ignores previous bandwidth reduction
            runCurrent()
            assertEquals(10_000_000, requests.last().getValue("MaxStreamingBitrate").jsonPrimitive.int)
            assertEquals(10_000_000, requests.last().getValue("DeviceProfile").jsonObject.getValue("MaxStreamingBitrate").jsonPrimitive.int)
            assertEquals(PlaybackPosition(74_000, true), output.moment)
        } finally {
            session.close()
            runCurrent()
            host.reportScope.cancel()
            http.close()
        }
    }
}
