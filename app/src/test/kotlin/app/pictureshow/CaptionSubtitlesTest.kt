/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CaptionSubtitlesTest {
    private val account = Account(Server("s", "Server", "https://example.test", "12.0.0"), User("u", "Viewer"), "fictional")
    private fun configuration(mode: String, language: String = "") = UserConfiguration(
        Json.parseToJsonElement("""{"SubtitleMode":"$mode","SubtitleLanguagePreference":"$language"}""").jsonObject
    )

    @Test
    fun androidCaptionsUseJellyfinLanguageThenTvAndRespectNoneOrUnreadablePreferences() {
        assertEquals("fra", captionSubtitleLanguage(true, configuration("Default", "fra"), "en-US"))
        assertEquals("en-US", captionSubtitleLanguage(true, configuration("Smart"), "en-US"))
        for (mode in listOf("Always", "OnlyForced", "Default", "Smart")) {
            assertEquals("eng", captionSubtitleLanguage(true, configuration(mode, "eng"), "fr"))
        }
        assertNull(captionSubtitleLanguage(true, configuration("None", "eng"), "fr"))
        assertNull(captionSubtitleLanguage(false, configuration("Always", "eng"), "fr"))
        assertNull(captionSubtitleLanguage(true, null, "en"))
    }

    @Test
    fun regionalAndBibliographicLanguagesMatchFullCaptionsWithoutMatchingUnknownTracks() {
        val source = MediaSourceInfo(
            mediaStreams = listOf(
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 1,
                    language = "und"
                ),
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 2,
                    language = "fre",
                    isForced = true
                ),
                MediaStream(
                    type = MediaStreamType.Subtitle,
                    index = 3,
                    language = "fra"
                )
            )
        )
        assertEquals(3, captionSubtitleIndex(source, "fr_CA"))
        assertEquals(3, captionSubtitleIndex(source, "fre"))
        assertNull(captionSubtitleIndex(source, "ja"))
        assertNull(captionSubtitleIndex(source, "und"))
    }

    /** No output opens the initial decision; selected subtitles must be negotiated before decoding, including burn-in. */
    @Test
    fun matchingNonDefaultTextAndBitmapTracksAreNegotiatedWithResumeAndSourcePreserved() = runTest {
        for (codec in listOf("subrip", "pgssub")) {
            val sent = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        sent += body
                        val selected = body["SubtitleStreamIndex"]?.jsonPrimitive?.intOrNull == 4
                        respond(
                            """{"MediaSources":[{"Id":"other","SupportsDirectPlay":$selected},
                            {"Id":"source","Container":"mkv","SupportsDirectPlay":${!selected || codec == "subrip"},
                            "SupportsTranscoding":true,"TranscodingUrl":"/Videos/item/master.m3u8?SubtitleStreamIndex=${if (selected) 4 else -1}",
                            "DefaultSubtitleStreamIndex":${if (selected) 4 else -1},"MediaStreams":[
                            {"Type":"Subtitle","Index":2,"Language":"eng","Codec":"subrip","IsDefault":true},
                            {"Type":"Subtitle","Index":3,"Language":"fra","Codec":"$codec","IsForced":true},
                            {"Type":"Subtitle","Index":4,"Language":"fra","Codec":"$codec","IsExternal":${codec == "subrip"},
                            "DeliveryMethod":"${if (codec == "pgssub") "Encode" else "External"}"}]}],"PlaySessionId":"session"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
            try {
                val gateway =
                    ServerPlaybackGateway(PlaybackClient(http, ClientIdentity("TV", "device")), account, "item", {
                        DeviceCapabilities(
                            "TV",
                            emptyList(),
                            DisplayCapabilities(1920, 1080, 60.0),
                            emptyList(),
                            AudioOutput(2)
                        )
                    }, "fr-CA", markPlayed = { ClientResult.Success(Unit) })
                val outcome =
                    assertIs<PlayOutcome.Play>(
                        assertIs<ClientResult.Success<PlayOutcome>>(gateway.prepare(PlaybackRequest(startPosition = 90.seconds))).value
                    )
                assertEquals(4, outcome.decision.mediaSource.defaultSubtitleStreamIndex)
                assertEquals("source", outcome.decision.mediaSourceId)
                if (codec == "pgssub") {
                    assertEquals(app.pictureshow.core.playback.PlayMethod.Transcode, outcome.decision.method)
                    kotlin.test.assertTrue(outcome.decision.url.endsWith("SubtitleStreamIndex=4"))
                }
                assertEquals(2, sent.size)
                assertEquals("source", sent.last().getValue("MediaSourceId").jsonPrimitive.content)
                assertEquals(900_000_000L, sent.last().getValue("StartTimeTicks").jsonPrimitive.content.toLong())
                val delivery = if (codec == "pgssub") "Encode" else "External"
                assertEquals(delivery, outcome.decision.mediaSource.mediaStreams.last().deliveryMethod?.name)
                gateway.prepare(PlaybackRequest(subtitleStreamIndex = -1))
                assertEquals(3, sent.size, "Explicit Off must bypass the caption preference")
                assertEquals(-1, sent.last().getValue("SubtitleStreamIndex").jsonPrimitive.intOrNull)
            } finally {
                http.close()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitOffSurvivesAudioChangeRouteChangeAndFallbackWhileNextItemStartsItsOwnPreference() = runTest {
        val sent = mutableListOf<Int?>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val chosen = Json.parseToJsonElement(
                        request.body.toByteArray().decodeToString()
                    ).jsonObject["SubtitleStreamIndex"]?.jsonPrimitive?.intOrNull
                    sent += chosen
                    respond(
                        """{"MediaSources":[{"Id":"source","SupportsDirectPlay":true,"DefaultSubtitleStreamIndex":${chosen ?: -1},
                    "MediaStreams":[{"Type":"Subtitle","Index":4,"Language":"eng","Codec":"subrip"}]}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        val output = object : PlaybackOutput {
            override fun position() = PlaybackPosition(42_000, true)
            override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
            override fun select(type: MediaStreamType, index: Int) = true
            override fun pause() = Unit
            override fun release() = Unit
        }
        fun gateway(item: String) = ServerPlaybackGateway(
            PlaybackClient(http, ClientIdentity("TV", "device")),
            account,
            item,
            {
                DeviceCapabilities(
                    "TV",
                    emptyList(),
                    DisplayCapabilities(1920, 1080, 60.0),
                    emptyList(),
                    AudioOutput(2)
                )
            },
            "en",
            markPlayed = { ClientResult.Success(Unit) }
        )
        try {
            val session = PlaybackSession(gateway("first"), output, backgroundScope, "en")
            session.start(90_000)
            runCurrent()
            assertEquals(4, session.state.value.subtitleIndex)
            session.select(MediaStreamType.Subtitle, -1)
            session.select(MediaStreamType.Audio, 1)
            session.routeChanged()
            runCurrent()
            assertEquals(-1, session.state.value.subtitleIndex)
            assertEquals(-1, sent.last())
            session.failed(true)
            runCurrent()
            assertEquals(-1, sent.last())
            assertEquals(-1, session.state.value.subtitleIndex)
            session.close()
            runCurrent()
            val next = PlaybackSession(gateway("next"), output, backgroundScope, "en")
            next.start(0)
            runCurrent()
            assertEquals(4, next.state.value.subtitleIndex)
            assertEquals(listOf(null, 4), sent.takeLast(2))
            next.close()
            runCurrent()
        } finally {
            http.close()
        }
    }

    @Test
    fun noMatchingLanguageAndCaptionsOffKeepTheServerChoiceWithoutRenegotiation() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    requests++
                    respond(
                        """{"MediaSources":[{"Id":"source","SupportsDirectPlay":true,"DefaultSubtitleStreamIndex":2,
                "MediaStreams":[{"Type":"Subtitle","Index":2,"Language":"eng"}]}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            for (language in listOf(null, "ja")) {
                val gateway =
                    ServerPlaybackGateway(PlaybackClient(http, ClientIdentity("TV", "device")), account, "item", {
                        DeviceCapabilities(
                            "TV",
                            emptyList(),
                            DisplayCapabilities(1920, 1080, 60.0),
                            emptyList(),
                            AudioOutput(2)
                        )
                    }, language, markPlayed = { ClientResult.Success(Unit) })
                val outcome =
                    assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(gateway.prepare(PlaybackRequest())).value)
                assertEquals(2, outcome.decision.mediaSource.defaultSubtitleStreamIndex)
            }
            assertEquals(2, requests)
        } finally {
            http.close()
        }
    }
}
