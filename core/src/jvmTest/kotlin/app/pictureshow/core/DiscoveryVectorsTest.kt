/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.StillWatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Executes spec/vectors/discovery.json: trailers, extras, More like this, still watching, photos and screensaver art. */
class DiscoveryVectorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun items(value: JsonElement): List<MediaItem> = value.jsonArray.map { json.decodeFromJsonElement<MediaItem>(it) }
    private fun ids(values: List<MediaItem>) = JsonArray(values.map { JsonPrimitive(it.id) })
    private fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

    @Test fun discoveryRules() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/discovery.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(source).jsonObject.getValue("cases").jsonArray
        for (case in cases) {
            val c = case.jsonObject
            val id = c.getValue("id").jsonPrimitive.content
            val input = c.getValue("input").jsonObject
            val actual: JsonElement = when (c.getValue("operation").jsonPrimitive.content) {
                "detail-reads" -> detailDiscoveryReads(input.getValue("type").jsonPrimitive.content).let {
                    JsonObject(mapOf("trailers" to JsonPrimitive(it.trailers), "extras" to JsonPrimitive(it.extras), "similar" to JsonPrimitive(it.similar)))
                }
                "youtube-id" -> text(youTubeVideoId(input.getValue("url").jsonPrimitive.content))
                "trailers" -> JsonArray(
                    trailerChoices(items(input.getValue("local")), json.decodeFromJsonElement(input.getValue("remote"))).map {
                        JsonObject(
                            mapOf(
                                "key" to JsonPrimitive(it.key),
                                "title" to JsonPrimitive(it.title),
                                "itemId" to text(it.item?.id),
                                "url" to text(it.url),
                                "youTubeId" to text(it.youTubeId)
                            )
                        )
                    }
                )
                "extra-label" -> text(extraLabel((input["extraType"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content))
                "extras" -> ids(specialFeatureExtras(items(input.getValue("items"))))
                "more-like-this" -> ids(
                    moreLikeThis(
                        json.decodeFromJsonElement(input.getValue("self")),
                        items(input.getValue("similar")),
                        input.getValue("limit").jsonPrimitive.int
                    )
                )
                "still-watching" -> JsonPrimitive(
                    stillWatchingDue(
                        StillWatching.valueOf(input.getValue("policy").jsonPrimitive.content),
                        input.getValue("episodesInARow").jsonPrimitive.int,
                        input.getValue("minutesSinceInput").jsonPrimitive.long
                    )
                )
                "photo-step" -> photoStep(
                    input.getValue("index").jsonPrimitive.int,
                    input.getValue("count").jsonPrimitive.int,
                    input.getValue("forward").jsonPrimitive.boolean,
                    input.getValue("slideshow").jsonPrimitive.boolean
                )?.let(::JsonPrimitive) ?: JsonNull
                "screensaver-art" -> {
                    val ratings = input.getValue("ratings").jsonObject.mapValues { it.value.jsonPrimitive.int }
                    ids(screensaverArt(items(input.getValue("items")), input["maxAge"]?.jsonPrimitive?.intOrNull) { ratings[it] })
                }
                "photo-url" -> text(
                    photoImageUrl(
                        Server("fixture", "Fixture", input.getValue("server").jsonPrimitive.content, "12.0.0"),
                        json.decodeFromJsonElement(input.getValue("item")),
                        input.getValue("maxWidth").jsonPrimitive.int,
                        input.getValue("maxHeight").jsonPrimitive.int
                    )
                )
                else -> error("Unknown discovery operation in $id")
            }
            assertEquals(c.getValue("expected"), actual, id)
        }
    }
}
