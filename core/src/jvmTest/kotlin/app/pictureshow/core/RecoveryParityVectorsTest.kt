/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOnlyRecovery
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackSettings
import app.pictureshow.core.playback.TranscodeSeek
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Shared JSON exercises production seek/recovery policies; expectations have no update mode. */
class RecoveryParityVectorsTest {
    private val json = PlaybackJson

    @Test
    fun everyRecoveryVectorMatchesProduction() = runTest {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/recovery.json"))
        val document = json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("recovery", document.text("suite"))
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertEquals(cases.size, cases.map { it.jsonObject.text("id") }.toSet().size, "Duplicate vector IDs")
        val failures = mutableListOf<String>()
        cases.forEach { entry ->
            val vector = entry.jsonObject
            val id = vector.text("id")
            try {
                val input = vector.getValue("input").jsonObject
                val actual = when (val operation = vector.text("operation")) {
                    "seek-required" -> JsonPrimitive(TranscodeSeek.required(decision(input)))
                    "seek-start" -> JsonPrimitive(TranscodeSeek.startPositionMs(decision(input), input.long("targetMs")))
                    "seek-url" -> urlParts(TranscodeSeek.url(decision(input), input.long("startMs")))
                    "audio-encodes" -> JsonPrimitive(TranscodeSeek.audioAlreadyEncodes(decision(input)))
                    "preserve-video" -> outcome(AudioOnlyRecovery.preserveVideo(decision(input), input.optionalInt("subtitleStreamIndex")))
                    "prepare-preserve-video" -> prepare(
                        input,
                        document.getValue("context").jsonObject,
                        StandardTestDispatcher(testScheduler)
                    )
                    else -> error("Unknown recovery vector operation: $operation")
                }
                assertEquals(vector.getValue("expected"), actual, id)
            } catch (failure: AssertionError) {
                failures += "$id: ${failure.message}"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failures += "$id: ${failure::class.simpleName}: ${failure.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "Recovery parity vector failures:\n"))
    }

    private fun decision(input: JsonObject): PlayDecision {
        val value = input.getValue("decision").jsonObject
        return PlayDecision(
            itemId = value.text("itemId"),
            mediaSource = json.decodeFromJsonElement(value.getValue("source")),
            method = PlayMethod.valueOf(value.text("method")),
            url = value.text("url"),
            headers = value.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content },
            playSessionId = value.getValue("playSessionId").jsonPrimitive.contentOrNull,
            transcodeReasons = value.getValue("transcodeReasons").jsonArray.map { it.jsonPrimitive.content }
        )
    }

    /** Query member ordering is insignificant; exact key spelling, duplicate values and array order are preserved. */
    private fun urlParts(value: String): JsonElement {
        val url = Url(value)
        return buildJsonObject {
            put("scheme", url.protocol.name)
            put("host", url.host)
            put("port", url.port)
            put("path", url.encodedPath)
            put(
                "query",
                JsonObject(
                    url.parameters.entries().associate { (name, values) ->
                        name to JsonArray(values.map(::JsonPrimitive))
                    }
                )
            )
        }
    }

    private fun outcome(value: PlayOutcome): JsonElement = when (value) {
        is PlayOutcome.Unplayable -> buildJsonObject { put("unplayable", value.reason.name) }
        is PlayOutcome.Play -> buildJsonObject {
            val decision = value.decision
            put("method", decision.method.name)
            put("mediaSourceId", decision.mediaSourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("playSessionId", decision.playSessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("headers", JsonObject(decision.headers.mapValues { JsonPrimitive(it.value) }))
            put("transcodeReasons", JsonArray(decision.transcodeReasons.map(::JsonPrimitive)))
            put("url", urlParts(decision.url))
        }
    }

    private suspend fun prepare(input: JsonObject, context: JsonObject, requestDispatcher: CoroutineDispatcher): JsonElement {
        val sent = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = requestDispatcher
                addHandler { request ->
                    assertEquals("POST", request.method.value)
                    assertEquals("/jellyfin/Items/film/PlaybackInfo", request.url.encodedPath)
                    sent += json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    respond(
                        input.getValue("response").toString(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val accountFields = context.getValue("account").jsonObject
            val account = Account(
                json.decodeFromJsonElement(context.getValue("server")),
                User(accountFields.text("userId"), "Viewer"),
                accountFields.text("token"),
                deviceId = accountFields.text("deviceId")
            )
            val identityFields = context.getValue("identity").jsonObject
            val identity =
                ClientIdentity(identityFields.text("deviceName"), identityFields.text("deviceId"), identityFields.text("version"))
            val request = input.getValue("request").jsonObject
            val result = PlaybackClient(http, identity).prepare(
                account,
                "film",
                capabilities(context.getValue("capabilities").jsonObject),
                PlaybackRequest(
                    startPosition = request.long("startMs").milliseconds,
                    mediaSourceId = request.text("mediaSourceId"),
                    audioStreamIndex = request.optionalInt("audioStreamIndex"),
                    subtitleStreamIndex = request.optionalInt("subtitleStreamIndex"),
                    enableDirectPlay = request.bool("enableDirectPlay"),
                    enableDirectStream = request.bool("enableDirectStream"),
                    allowVideoStreamCopy = request.bool("allowVideoStreamCopy"),
                    allowAudioStreamCopy = request.bool("allowAudioStreamCopy"),
                    requireVideoStreamCopy = request.bool("requireVideoStreamCopy")
                )
            )
            assertEquals(1, sent.size, "Exactly one PlaybackInfo negotiation")
            val body = sent.single()
            val projectedFields = listOf(
                "UserId", "MaxStreamingBitrate", "StartTimeTicks", "MediaSourceId", "AudioStreamIndex", "SubtitleStreamIndex",
                "EnableDirectPlay", "EnableDirectStream", "AllowVideoStreamCopy", "AllowAudioStreamCopy"
            )
            return buildJsonObject {
                put("request", JsonObject(projectedFields.associateWith { body.getValue(it) }))
                put("outcome", outcome(assertIs<ClientResult.Success<PlayOutcome>>(result).value))
            }
        } finally {
            http.close()
        }
    }

    /** This suite's explicit context fixture uses only these baseline capability fields. */
    private fun capabilities(value: JsonObject): DeviceCapabilities {
        val display = value.getValue("display").jsonObject
        return DeviceCapabilities(
            name = value.text("name"),
            videoDecoders = value.getValue("videoDecoders").jsonArray.map { entry ->
                val decoder = entry.jsonObject
                VideoDecoder(
                    codec = VideoCodec.valueOf(decoder.text("codec")),
                    profiles = decoder.getValue("profiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    maxWidth = decoder.int("maxWidth"),
                    maxHeight = decoder.int("maxHeight"),
                    maxFrameRate = decoder.getValue("maxFrameRate").jsonPrimitive.double
                )
            },
            display = DisplayCapabilities(
                display.int("maxWidth"),
                display.int("maxHeight"),
                display.getValue("maxRefreshRate").jsonPrimitive.double
            ),
            audioDecoders = value.getValue("audioDecoders").jsonArray.map { entry ->
                val decoder = entry.jsonObject
                AudioDecoder(AudioCodec.valueOf(decoder.text("codec")), decoder.int("maxChannels"))
            },
            audioOutput = AudioOutput(value.getValue("audioOutput").jsonObject.int("pcmChannels")),
            settings = PlaybackSettings(value.getValue("settings").jsonObject.int("maxStreamingBitrate"))
        )
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.optionalInt(name: String): Int? = this[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean
}
