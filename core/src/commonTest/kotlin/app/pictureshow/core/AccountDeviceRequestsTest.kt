package app.pictureshow.core

import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackStopInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AccountDeviceRequestsTest {
    @Test
    fun accountDeviceIsUsedByEveryTransportAndTranscodeStop() = runTest {
        // Decode the public persistence format; old clients ignore the new field and reveal the regression.
        val json = Json { ignoreUnknownKeys = true }
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests += it
                        respond(
                            when (it.url.encodedPath) {
                                "/Items/item/PlaybackInfo" -> """
                            {"MediaSources":[{"Id":"source","Container":"mp4","SupportsDirectPlay":true}],
                             "PlaySessionId":"play"}
                                """.trimIndent()
                                "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                                "/Users/viewer/Configuration" -> "{}"
                                else -> """{"Items":[],"TotalRecordCount":0}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            )
        )
        try {
            val identity = ClientIdentity("Test TV", "legacy-install")
            val client = JellyfinClient(http, identity)
            val playback = PlaybackClient(http, identity)
            for (id in listOf("account-a", "account-b", "legacy-install")) {
                val account = json.decodeFromString<Account>(
                    """{"server":{"id":"s","name":"Cinema","url":"https://media.example.test","version":"12.0.0"},"user":{"id":"viewer","name":"Viewer"},"accessToken":"fake-token","deviceId":"$id"}"""
                )
                val viewer = if (id == "legacy-install") account.copy(deviceId = null) else account
                requests.clear()
                client.me(viewer)
                client.views(viewer)
                client.search(viewer, "example", SearchKind.MOVIES)
                client.configuration(viewer)
                PlaybackExtrasClient(http, identity).segments(viewer, "item")
                val prepared = kotlin.test.assertIs<ClientResult.Success<app.pictureshow.core.playback.PlayOutcome>>(
                    playback.prepare(viewer, "item", app.pictureshow.core.playback.CapabilityFixtures.projector)
                )
                val decision = kotlin.test.assertIs<app.pictureshow.core.playback.PlayOutcome.Play>(prepared.value).decision
                assertTrue(decision.headers["Authorization"]!!.contains("DeviceId=\"$id\""))
                playback.reportStart(
                    viewer,
                    PlaybackProgressInfo(
                        itemId = "item",
                        playSessionId = "play",
                        playMethod = app.pictureshow.core.playback.PlayMethod.Transcode,
                        positionTicks = 0
                    )
                )
                playback.reportProgress(
                    viewer,
                    PlaybackProgressInfo(
                        itemId = "item",
                        playSessionId = "play",
                        playMethod = app.pictureshow.core.playback.PlayMethod.Transcode,
                        positionTicks = 0
                    )
                )
                playback.reportStopped(viewer, PlaybackStopInfo(itemId = "item", playSessionId = "play", positionTicks = 0))
                playback.stopEncoding(viewer, "play")
                client.logout(viewer)
                assertEquals(11, requests.size)
                assertTrue(requests.all { it.headers[HttpHeaders.Authorization]!!.contains("DeviceId=\"$id\"") })
                assertEquals(id, requests.single { it.url.encodedPath == "/Videos/ActiveEncodings" }.url.parameters["deviceId"])
                assertEquals(1, requests.count { it.url.encodedPath == "/Sessions/Playing" })
                assertEquals(1, requests.count { it.url.encodedPath == "/Sessions/Playing/Stopped" })
            }
        } finally {
            http.close()
        }
    }
}
