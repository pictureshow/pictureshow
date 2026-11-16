/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackEngine
import app.pictureshow.core.playback.PlaybackEngineChoice
import app.pictureshow.core.playback.PlaybackEngineContext
import app.pictureshow.core.playback.PlaybackRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** PLAY-60 through the real gateway: which PlaybackInfo requests go out and which engine the decision carries. */
class PlaybackEngineGatewayTest {
    private val account = Account(Server("s", "Server", "https://example.test", "10.11.0"), User("u", "Viewer"), "fictional")
    private val caps = DeviceCapabilities("Test", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))

    private fun converting(reason: String) =
        """{"MediaSources":[{"Id":"src","Container":"avi","SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,
        "MediaStreams":[{"Type":"Video","Index":0,"Codec":"mpeg4"}],
        "TranscodingUrl":"/videos/item/master.m3u8?MediaSourceId=src&TranscodeReasons=$reason","TranscodingSubProtocol":"hls","TranscodingContainer":"ts"}],"PlaySessionId":"one"}"""

    private val direct =
        """{"MediaSources":[{"Id":"src","Container":"avi","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true,
        "MediaStreams":[{"Type":"Video","Index":0,"Codec":"mpeg4"}]}],"PlaySessionId":"two"}"""

    private fun run(choice: PlaybackEngineChoice, available: Boolean, answers: List<String>): Pair<List<JsonObject>, PlayOutcome.Play> {
        val requests = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    respond(answers[requests.size - 1], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val gateway = ServerPlaybackGateway(
            PlaybackClient(http, ClientIdentity("Test", "test-device")),
            account,
            "item",
            { caps },
            null,
            { PlaybackEngineContext(choice, available) }
        ) { ClientResult.Success(Unit) }
        var outcome: PlayOutcome.Play? = null
        // Real time: the client's request timeout would fire at once under runTest's virtual clock.
        runBlocking {
            val result = gateway.prepare(PlaybackRequest())
            check(result is ClientResult.Success) { "prepare failed: $result" }
            outcome = result.value as PlayOutcome.Play
        }
        return requests to requireNotNull(outcome)
    }

    private fun containers(request: JsonObject) = request.getValue("DeviceProfile").jsonObject.getValue("DirectPlayProfiles").jsonArray
        .joinToString(",") { it.jsonObject["Container"]?.jsonPrimitive?.content.orEmpty() }

    @Test fun automaticAsksAgainWithMpvWhenOnlyTheContainerConvertsAndKeepsMpvWhenThatPlaysDirectly() {
        val (requests, outcome) = run(PlaybackEngineChoice.Automatic, true, listOf(converting("ContainerNotSupported"), direct))
        assertEquals(2, requests.size)
        assertEquals(true, "avi" in containers(requests[1]).split(','))
        assertEquals(PlaybackEngine.Mpv, outcome.decision.engine)
        assertEquals(PlayMethod.DirectPlay, outcome.decision.method)
    }

    @Test fun automaticKeepsTheBuiltInPlayerWhenMpvWouldConvertToo() {
        val (requests, outcome) = run(
            PlaybackEngineChoice.Automatic,
            true,
            listOf(converting("ContainerNotSupported"), converting("ContainerNotSupported"))
        )
        assertEquals(2, requests.size)
        assertEquals(PlaybackEngine.Media3, outcome.decision.engine)
    }

    @Test fun bitrateConversionNeverSwitchesEnginesAndNoMpvMeansOneRequest() {
        assertEquals(1, run(PlaybackEngineChoice.Automatic, true, listOf(converting("ContainerBitrateExceedsLimit"))).first.size)
        val (requests, outcome) = run(PlaybackEngineChoice.Mpv, false, listOf(converting("ContainerNotSupported")))
        assertEquals(1, requests.size)
        assertEquals(PlaybackEngine.Media3, outcome.decision.engine)
    }

    @Test fun choosingMpvSendsOnlyMpvsProfile() {
        val (requests, outcome) = run(PlaybackEngineChoice.Mpv, true, listOf(direct))
        assertEquals(1, requests.size)
        assertEquals(true, "avi" in containers(requests[0]).split(','))
        assertEquals(PlaybackEngine.Mpv, outcome.decision.engine)
    }
}
