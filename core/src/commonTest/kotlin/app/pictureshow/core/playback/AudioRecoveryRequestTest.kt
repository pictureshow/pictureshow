/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AudioRecoveryRequestTest {
    @Test fun actualPlaybackInfoBodyForcesOnlyAudioConversionAndKeepsSelectedTracksAndOffset() = runTest {
        var body = ""
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    body = request.body.toByteArray().decodeToString()
                    respond(
                        """{"MediaSources":[{"Id":"source","SupportsTranscoding":true,"TranscodingUrl":"/videos/film/master.m3u8?VideoCodec=h264,hevc&AudioCodec=aac&AudioStreamIndex=2&AllowAudioStreamCopy=false&SubtitleStreamIndex=4&SubtitleMethod=External","MediaStreams":[{"Type":"Subtitle","Index":4,"Codec":"subrip","DeliveryMethod":"External"}],"TranscodingSubProtocol":"hls","TranscodingContainer":"ts"}],"PlaySessionId":"session"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
            val outcome = PlaybackClient(http, ClientIdentity("TV", "device")).prepare(
                account,
                "film",
                CapabilityFixtures.projector,
                PlaybackRequest(
                    audioStreamIndex = 2,
                    subtitleStreamIndex = 4,
                    enableDirectPlay = false,
                    enableDirectStream = false,
                    allowVideoStreamCopy = true,
                    allowAudioStreamCopy = false,
                    requireVideoStreamCopy = true
                )
            )
            val decision = assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(outcome).value).decision
            assertEquals(listOf("copy"), Url(decision.url).parameters.getAll("VideoCodec"))
            assertEquals("false", Url(decision.url).parameters["AllowAudioStreamCopy"])
            assertEquals("2", Url(decision.url).parameters["AudioStreamIndex"])
            assertEquals("4", Url(decision.url).parameters["SubtitleStreamIndex"])
            assertEquals("External", Url(decision.url).parameters["SubtitleMethod"])
            println("AUDIO_RECOVERY_REQUEST=$body")
            val sent = PlaybackJson.parseToJsonElement(body).jsonObject
            assertEquals("true", sent.getValue("AllowVideoStreamCopy").jsonPrimitive.content)
            assertEquals("false", sent.getValue("AllowAudioStreamCopy").jsonPrimitive.content)
            assertEquals("false", sent.getValue("EnableDirectPlay").jsonPrimitive.content)
            assertEquals("2", sent.getValue("AudioStreamIndex").jsonPrimitive.content)
            assertEquals("4", sent.getValue("SubtitleStreamIndex").jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun selectedImageBurnInCannotSilentlyDropCaptionsOrEncodeVideoDuringAudioRecovery() {
        val source = MediaSourceInfo(
            defaultSubtitleStreamIndex = 4,
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Subtitle, index = 4, codec = "pgssub", deliveryMethod = SubtitleDeliveryMethod.Encode)
            )
        )
        val decision = PlayDecision(
            "film",
            source,
            PlayMethod.Transcode,
            "https://example.test/master.m3u8?VideoCodec=h264&AudioCodec=aac&SubtitleMethod=Encode&SubtitleStreamIndex=4",
            emptyMap(),
            "session"
        )
        assertIs<PlayOutcome.Unplayable>(AudioOnlyRecovery.preserveVideo(decision, 4))
        // Off is explicit: the server leaves SubtitleMethod=Encode in URLs even without an active subtitle.
        val off = assertIs<PlayOutcome.Play>(AudioOnlyRecovery.preserveVideo(decision, -1)).decision
        assertEquals("copy", Url(off.url).parameters["VideoCodec"])
    }
}
