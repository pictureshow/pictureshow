/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.PlaybackPreferences
import app.pictureshow.core.playback.StreamDefaults
import app.pictureshow.core.playback.captionSubtitleIndex
import app.pictureshow.core.playback.captionSubtitleLanguage
import app.pictureshow.core.playback.completedPlaybackPosition
import app.pictureshow.core.playback.playbackEndPosition
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** All expectations are committed JSON, shared unchanged with the Apple and web implementations. */
class ApplicationParityVectorsTest {
    @Test
    fun applicationVectors() = runTest {
        val text = assertNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/application.json"))
            .bufferedReader().use { it.readText() }
        val suite = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.intOrNull)
        assertEquals("application", suite.getValue("suite").jsonPrimitive.content)
        val cases = suite.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertEquals(cases.size, cases.map { it.jsonObject.getValue("id") }.toSet().size, "Duplicate vector IDs")
        val failures = mutableListOf<String>()
        for (entry in cases) {
            val vector = entry.jsonObject
            val id = vector.getValue("id").jsonPrimitive.content
            try {
                val input = vector.getValue("input").jsonObject
                val actual = when (val operation = vector.getValue("operation").jsonPrimitive.content) {
                    "detail-arrival-publication" -> JsonPrimitive(
                        detailArrivalAwaitsAction(
                            input.getValue("loading").jsonPrimitive.booleanOrNull!!,
                            input.getValue("contentRevealed").jsonPrimitive.booleanOrNull!!,
                            input.getValue("returningFromPlayback").jsonPrimitive.booleanOrNull!!
                        )
                    )
                    "detail-request-priority" -> JsonPrimitive(
                        detailRequestFeedbackIsUrgent(
                            input.getValue("newlyAccepted").jsonPrimitive.booleanOrNull!!,
                            input.getValue("requested").jsonPrimitive.booleanOrNull!!,
                            input.getValue("available").jsonPrimitive.booleanOrNull!!
                        )
                    )
                    "catalog-arrival-animation" -> JsonPrimitive(
                        catalogArrivalUsesAnimation(
                            input.getValue("accessibilityEnabled").jsonPrimitive.booleanOrNull!!,
                            input.getValue("sourceIsPlayback").jsonPrimitive.booleanOrNull!!,
                            input.getValue("destinationIsCatalog").jsonPrimitive.booleanOrNull!!
                        )
                    )
                    "detail-action-state" -> detailActionDescription(
                        input["currentState"]?.jsonPrimitive?.contentOrNull,
                        input.getValue("available").jsonPrimitive.booleanOrNull!!
                    )?.let(::JsonPrimitive) ?: JsonNull
                    "detail-arrival-pending" -> {
                        var pending = true
                        JsonArray(
                            input.getValue("restored").jsonArray.map {
                                pending = detailArrivalStillPending(pending, it.jsonPrimitive.booleanOrNull!!)
                                JsonPrimitive(pending)
                            }
                        )
                    }
                    "detail-host-focus-handoff" -> JsonPrimitive(
                        detailSuppressesEmptyHostFocus(
                            input.getValue("ordinaryArrivalWithActions").jsonPrimitive.booleanOrNull!!,
                            input.getValue("handoffFinished").jsonPrimitive.booleanOrNull!!,
                            input.getValue("returningFromPlayback").jsonPrimitive.booleanOrNull!!,
                            input.getValue("recoveryHandoff").jsonPrimitive.booleanOrNull!!,
                            input.getValue("failed").jsonPrimitive.booleanOrNull!!
                        )
                    )
                    "detail-metadata-handoff" -> JsonPrimitive(
                        detailMetadataAwaitsHandoff(
                            input.getValue("hasActions").jsonPrimitive.booleanOrNull!!,
                            input.getValue("handoffFinished").jsonPrimitive.booleanOrNull!!,
                            input.getValue("returningFromPlayback").jsonPrimitive.booleanOrNull!!,
                            input.getValue("failed").jsonPrimitive.booleanOrNull!!
                        )
                    )
                    "detail-held-label" -> JsonPrimitive(detailHeldActionLabel(input.getValue("target").jsonPrimitive.content))
                    "detail-unavailable-label" -> JsonPrimitive(
                        detailUnavailableActionLabel(input.getValue("target").jsonPrimitive.content)
                    )
                    "detail-read-lifecycle" -> {
                        val lifecycle = DetailReadLifecycle()
                        JsonArray(input.getValue("resumes").jsonArray.map { JsonPrimitive(lifecycle.resumed()) })
                    }
                    "detail-ready-feedback" -> {
                        val feedback = DetailReadyFeedback()
                        JsonArray(
                            input.getValue("steps").jsonArray.map { step ->
                                val fields = step.jsonObject
                                feedback.update(
                                    fields.getValue("held").jsonPrimitive.booleanOrNull!!,
                                    fields["focused"]?.jsonPrimitive?.contentOrNull,
                                    fields.getValue("resumed").jsonPrimitive.booleanOrNull!!,
                                    fields["usable"]?.jsonPrimitive?.booleanOrNull ?: true
                                )?.let(::JsonPrimitive) ?: JsonNull
                            }
                        )
                    }
                    "detail-failure-key" -> detailFailureKey(
                        input.getValue("readRecovery").jsonPrimitive.booleanOrNull!!,
                        input.getValue("retrying").jsonPrimitive.booleanOrNull!!,
                        input["writeAttempt"]?.jsonPrimitive?.longOrNull
                    )?.let(::JsonPrimitive) ?: JsonNull
                    "detail-failure-presentation" -> {
                        var previous: DetailFailurePresentation? = null
                        JsonArray(
                            input.getValue("steps").jsonArray.map { step ->
                                val fields = step.jsonObject
                                previous = presentDetailFailure(
                                    previous,
                                    fields["key"]?.jsonPrimitive?.contentOrNull,
                                    fields["message"]?.jsonPrimitive?.contentOrNull
                                )
                                previous?.message?.let(::JsonPrimitive) ?: JsonNull
                            }
                        )
                    }
                    "detail-empty" -> JsonPrimitive(
                        showDetailEmptyState(
                            input["itemType"]?.jsonPrimitive?.contentOrNull,
                            input.getValue("loading").jsonPrimitive.booleanOrNull!!,
                            input.getValue("readSucceeded").jsonPrimitive.booleanOrNull!!,
                            input.getValue("readFailed").jsonPrimitive.booleanOrNull!!,
                            input.getValue("episodeCount").jsonPrimitive.intOrNull!!,
                            input.getValue("seasonCount").jsonPrimitive.intOrNull!!
                        )
                    )
                    "settings" -> settings(input)
                    "validate-bitrate" -> buildJsonObject {
                        put("valid", runCatching { PlaybackPreferences.validateBitrate(input.nullableInt("bitrateMbps")) }.isSuccess)
                    }
                    "default-stream" -> JsonPrimitive(
                        StreamDefaults.choose(
                            Json.decodeFromJsonElement(input.getValue("streams")),
                            app.pictureshow.core.playback.MediaStreamType.valueOf(input.getValue("type").jsonPrimitive.content),
                            input.nullableInt("serverIndex"),
                            input.getValue("preferredLanguage").jsonPrimitive.content
                        )
                    )
                    "resume-start" -> JsonPrimitive(
                        resumeStartPositionMs(
                            input.getValue("ticks").jsonPrimitive.long,
                            input.getValue("resume").jsonPrimitive.booleanOrNull!!,
                            input["explicitPositionMs"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
                        )
                    )
                    "caption-language" -> captionSubtitleLanguage(
                        input.getValue("enabled").jsonPrimitive.booleanOrNull!!,
                        input["configuration"]?.takeUnless { it == JsonNull }?.jsonObject?.let(::UserConfiguration),
                        input.getValue("tvLanguage").jsonPrimitive.content
                    )?.let(::JsonPrimitive) ?: JsonNull
                    "caption-index" -> captionSubtitleIndex(
                        Json.decodeFromJsonElement(input.getValue("source")),
                        input.getValue("preferredLanguage").jsonPrimitive.content
                    )?.let(::JsonPrimitive) ?: JsonNull
                    "end-position" -> buildJsonObject {
                        val position = input.getValue("positionMs").jsonPrimitive.long
                        val duration = input.getValue("durationMs").jsonPrimitive.long
                        put("bounded", playbackEndPosition(position, duration))
                        put("completed", completedPlaybackPosition(position, duration))
                    }
                    "active-segment" -> activeSegment(
                        Json.decodeFromJsonElement(input.getValue("segments")),
                        input.getValue("positionMs").jsonPrimitive.long,
                        input.getValue("modes").jsonObject.map { (type, mode) ->
                            SegmentType.valueOf(type) to SegmentMode.valueOf(mode.jsonPrimitive.content)
                        }.toMap()
                    )?.id?.let(::JsonPrimitive) ?: JsonNull
                    "next-trigger" -> nextEpisodeTriggerMs(
                        input.getValue("durationMs").jsonPrimitive.long,
                        Json.decodeFromJsonElement(input.getValue("segments"))
                    )?.let(::JsonPrimitive) ?: JsonNull
                    "next-adjacency" -> nextAdjacency(input, StandardTestDispatcher(testScheduler))
                    "user-configuration" -> userConfiguration(input)
                    else -> error("Unknown application vector operation: $operation")
                }
                assertEquals(vector.getValue("expected"), actual, id)
            } catch (failure: AssertionError) {
                failures += "$id: ${failure.message}"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failures += "$id: ${failure::class.simpleName}: ${failure.message}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", "Application parity vector failures:\n"))
    }

    private fun settings(input: JsonObject): JsonElement {
        val values = PlaybackPreferences.decode(
            input.nullableInt("bitrateMbps"),
            input["passthrough"]?.jsonPrimitive?.booleanOrNull,
            input["subtitleSize"]?.jsonPrimitive?.contentOrNull,
            input["subtitleBackground"]?.jsonPrimitive?.contentOrNull,
            input["segmentModes"]?.jsonObject.orEmpty().filterKeys { it != "Unknown" }.map { (type, mode) ->
                SegmentType.valueOf(type) to mode.jsonPrimitive.contentOrNull
            }.toMap()
        )
        return buildJsonObject {
            put("bitrateMbps", values.bitrateMbps?.let(::JsonPrimitive) ?: JsonNull)
            put("passthrough", values.passthrough)
            put("subtitleSize", values.subtitleSize.name)
            put("subtitleBackground", values.subtitleBackground.name)
            put("segmentModes", buildJsonObject { values.segmentModes.forEach { (type, mode) -> put(type.name, mode.name) } })
            put("maxStreamingBitrate", values.deviceSettings().maxStreamingBitrate)
            put("passthroughEnabled", values.deviceSettings().passthroughEnabled)
        }
    }

    private fun userConfiguration(input: JsonObject): JsonElement {
        var configuration = UserConfiguration(input.getValue("fields").jsonObject)
        input["change"]?.takeUnless { it == JsonNull }?.jsonObject?.let { change ->
            configuration = configuration.withPreference(
                change.getValue("name").jsonPrimitive.content,
                change.getValue("value").jsonPrimitive.content
            )
        }
        return buildJsonObject {
            put("audioLanguage", configuration.audioLanguage)
            put("subtitleLanguage", configuration.subtitleLanguage)
            put("subtitleMode", configuration.subtitleMode)
            put("fields", configuration.fields)
        }
    }

    private suspend fun nextAdjacency(input: JsonObject, requestDispatcher: CoroutineDispatcher): JsonElement {
        val item = Json.decodeFromJsonElement<MediaItem>(input.getValue("item"))
        var requests = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = requestDispatcher
                addHandler { request ->
                    requests++
                    assertEquals("/base/Shows/series/Episodes", request.url.encodedPath)
                    assertEquals("current", request.url.parameters["adjacentTo"])
                    assertEquals("false", request.url.parameters["isMissing"])
                    assertEquals("true", request.url.parameters["enableUserData"])
                    assertEquals("Overview", request.url.parameters["fields"])
                    assertEquals("user", request.url.parameters["userId"])
                    respond("{\"Items\":${input.getValue("items")}}")
                }
            }
        }
        try {
            val account = Account(
                Server("server", "Parity", "https://media.example.test/base", "12.0.0"),
                User("user", "Viewer"),
                "fixture-token"
            )
            val result = assertIs<ClientResult.Success<MediaItem?>>(
                PlaybackExtrasClient(http, ClientIdentity("TV", "device")).nextEpisode(account, item)
            )
            assertEquals(if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) 1 else 0, requests)
            return result.value?.id?.let(::JsonPrimitive) ?: JsonNull
        } finally {
            http.close()
        }
    }

    private fun JsonObject.nullableInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
}
