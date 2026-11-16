/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.seerr.SeerrFailure
import app.pictureshow.core.seerr.SeerrMedia
import app.pictureshow.core.seerr.SeerrMode
import app.pictureshow.core.seerr.SeerrRequest
import app.pictureshow.core.seerr.SeerrSecret
import app.pictureshow.core.seerr.SeerrTitle
import app.pictureshow.core.seerr.SeerrUser
import app.pictureshow.core.seerr.encodeSeerrQuery
import app.pictureshow.core.seerr.normalizeSeerrUrl
import app.pictureshow.core.seerr.seerrAuthHeaders
import app.pictureshow.core.seerr.seerrCanRequest
import app.pictureshow.core.seerr.seerrError
import app.pictureshow.core.seerr.seerrLinkedUser
import app.pictureshow.core.seerr.seerrMediaStatus
import app.pictureshow.core.seerr.seerrMovieRequestable
import app.pictureshow.core.seerr.seerrPosterUrl
import app.pictureshow.core.seerr.seerrRequestDisplay
import app.pictureshow.core.seerr.seerrRequestStatus
import app.pictureshow.core.seerr.seerrSearchOffers
import app.pictureshow.core.seerr.seerrSelectableSeasons
import app.pictureshow.core.seerr.seerrSessionCookie
import app.pictureshow.core.seerr.seerrTakenSeasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Executes spec/vectors/seerr.json against the shared Seerr rules (spec/features/seerr.md). */
class SeerrVectorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/seerr.json")).bufferedReader().use {
            it.readText()
        }
        val document = Json.parseToJsonElement(text).jsonObject
        assertEquals("seerr", document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 90)
        val failures = cases.mapNotNull { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val actual = run(case.getValue("operation").jsonPrimitive.content, case.getValue("input").jsonObject)
            if (actual == case.getValue("expected")) null else "$id: expected ${case.getValue("expected")}, got $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    private fun run(operation: String, input: JsonObject): JsonElement = when (operation) {
        "url" -> nullable(runCatching { normalizeSeerrUrl(input.text("value")) }.getOrNull())
        "query" -> JsonPrimitive(encodeSeerrQuery(input.text("value")))
        "poster" -> nullable(seerrPosterUrl((input.getValue("path") as? JsonPrimitive)?.takeIf { it.isString }?.content))
        "media-status" -> JsonPrimitive(seerrMediaStatus(input.getValue("value").jsonPrimitive.int).name)
        "request-status" -> JsonPrimitive(seerrRequestStatus(input.getValue("value").jsonPrimitive.int).name)
        "request-display" -> JsonPrimitive(
            seerrRequestDisplay(
                SeerrRequest(
                    id = 1,
                    status = input.getValue("status").jsonPrimitive.int,
                    type = "movie",
                    media = SeerrMedia(status = input.getValue("mediaStatus").jsonPrimitive.int)
                )
            ).name
        )
        "can-request" -> JsonPrimitive(seerrCanRequest(input.getValue("permissions").jsonPrimitive.long, input.text("type")))
        "movie-requestable" -> JsonPrimitive(
            seerrMovieRequestable(
                SeerrTitle(1, "movie", mediaInfo = input.getValue("mediaStatus").jsonPrimitive.intOrNull?.let { SeerrMedia(status = it) })
            )
        )
        "seasons" -> {
            val title = json.decodeFromJsonElement<SeerrTitle>(input.getValue("title"))
            buildJsonObject {
                put("selectable", JsonArray(seerrSelectableSeasons(title).map { JsonPrimitive(it) }))
                put(
                    "taken",
                    JsonObject(
                        seerrTakenSeasons(title).toSortedMap().map { (season, why) ->
                            season.toString() to
                                JsonPrimitive(why.name)
                        }.toMap()
                    )
                )
            }
        }
        "offers" -> {
            val titles = json.decodeFromJsonElement<List<SeerrTitle>>(input.getValue("titles"))
            val library = input.getValue("library").jsonArray.map {
                it.jsonObject.text("name") to
                    it.jsonObject["year"]?.jsonPrimitive?.intOrNull
            }
            JsonArray(seerrSearchOffers(titles, library).map { JsonPrimitive("${it.mediaType}-${it.id}") })
        }
        "error" -> JsonPrimitive(
            seerrError(
                input.getValue("status").jsonPrimitive.int,
                input.text("body"),
                login = input.flag("login"),
                apiKey = input.flag("apiKey")
            ).name
        )
        "headers" -> try {
            val secret =
                SeerrSecret(
                    apiKey = input["apiKey"]?.jsonPrimitive?.content.orEmpty(),
                    cookie = input["cookie"]?.jsonPrimitive?.content.orEmpty()
                )
            val headers =
                seerrAuthHeaders(
                    SeerrMode.valueOf(input.text("mode")),
                    secret,
                    input.getValue("userId").jsonPrimitive.int,
                    input.flag("lookup")
                )
            JsonArray(headers.map { (name, value) -> JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(value))) })
        } catch (failure: SeerrFailure) {
            JsonPrimitive(failure.reason.name)
        }
        "link" -> {
            val users = json.decodeFromJsonElement<List<SeerrUser>>(input.getValue("users"))
            seerrLinkedUser(users, input.text("jellyfinUserId"))?.let { JsonPrimitive(it.id) } ?: JsonNull
        }
        "session-cookie" -> nullable(seerrSessionCookie(input.getValue("setCookie").jsonArray.map { it.jsonPrimitive.content }))
        else -> error("Unknown seerr operation $operation")
    }
}
