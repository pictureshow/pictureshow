/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.subtitles

import app.pictureshow.core.playback.MediaStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** SUB-DL-01..04 shared vectors against [RemoteSubtitlePolicy]. */
class SubtitleDownloadVectorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/subtitle-download.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 15)
        for (case in cases.map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            fun string(key: String) = (input[key] as? JsonPrimitive)?.contentOrNull
            fun cultures() = json.decodeFromJsonElement<List<SubtitleCulture>>(input.getValue("cultures"))
            fun streams(key: String) = json.decodeFromJsonElement<List<MediaStream>>(input.getValue(key))
            val actual: JsonElement = when (case.getValue("operation").jsonPrimitive.content) {
                "access" -> JsonPrimitive(
                    RemoteSubtitlePolicy.access(
                        input.getValue("playable").jsonPrimitive.booleanOrNull == true,
                        input.getValue("isAdministrator").jsonPrimitive.booleanOrNull,
                        input.getValue("subtitleManagement").jsonPrimitive.booleanOrNull,
                        (input.getValue("fetchers") as? JsonArray)?.map { it.jsonPrimitive.content }
                    ).name
                )
                "failure" -> JsonArray(
                    input.getValue("statuses").jsonArray.map {
                        JsonPrimitive(RemoteSubtitlePolicy.failure(it.jsonPrimitive.intOrNull).name)
                    }
                )
                "initial-language" -> JsonPrimitive(
                    RemoteSubtitlePolicy.initialLanguage(
                        string("subtitlePreference"),
                        string("audioPreference"),
                        string("deviceLanguage"),
                        cultures()
                    )
                )
                "language-choices" -> JsonArray(
                    RemoteSubtitlePolicy.languageChoices(input.getValue("first").jsonArray.map { it.jsonPrimitive.content }, cultures())
                        .map { JsonPrimitive(it.threeLetter) }
                )
                "order" -> JsonArray(
                    RemoteSubtitlePolicy.order(json.decodeFromJsonElement<List<RemoteSubtitle>>(input.getValue("results")))
                        .map { JsonPrimitive(it.id) }
                )
                "details" -> JsonArray(
                    RemoteSubtitlePolicy.details(json.decodeFromJsonElement<RemoteSubtitle>(input.getValue("result")))
                        .map { JsonPrimitive(it) }
                )
                "title" -> JsonPrimitive(RemoteSubtitlePolicy.title(json.decodeFromJsonElement<RemoteSubtitle>(input.getValue("result"))))
                "adopt" -> RemoteSubtitlePolicy.adopt(
                    streams("before"),
                    streams("after"),
                    input.getValue("selectedAudio").jsonPrimitive.intOrNull ?: -1,
                    string("language"),
                    string("format")
                )?.let {
                    buildJsonObject {
                        put("subtitleIndex", it.subtitleIndex)
                        put("audioIndex", it.audioIndex)
                    }
                } ?: JsonNull
                else -> error("Unknown subtitle-download operation")
            }
            val expected = case.getValue("expected")
            assertEquals(expected, actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
