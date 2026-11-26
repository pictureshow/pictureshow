/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Executes spec/vectors/home-hero.json: HOME-20..23 (hero backdrop and logo, facts line, summary, BlurHash). */
class HomeHeroVectorsTest {
    private val wire = Json { ignoreUnknownKeys = true }

    private fun ref(value: ImageRef?): JsonElement = value?.let {
        buildJsonObject {
            put("itemId", it.itemId)
            put("type", it.type)
            put("tag", it.tag)
        }
    } ?: JsonNull

    @Test fun vectors() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/home-hero.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(source).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val id = case.getValue("id").jsonPrimitive.content
            val input = case.getValue("input").jsonObject
            fun item() = wire.decodeFromJsonElement<MediaItem>(input.getValue("item"))
            val actual: JsonElement = when (val operation = case.getValue("operation").jsonPrimitive.content) {
                "hero-art" -> buildJsonObject {
                    put("backdrop", ref(heroBackdrop(item())))
                    put("logo", ref(heroLogo(item())))
                }
                "hero-facts" -> heroFacts(item(), input.getValue("nowEpochMillis").jsonPrimitive.long).let {
                    buildJsonObject {
                        put("episodeCode", it.episodeCode)
                        put("year", it.year)
                        put("runtime", it.runtime)
                        put("officialRating", it.officialRating)
                        put("communityRating", it.communityRating)
                        put("endsAtEpochMillis", it.endsAtEpochMillis)
                    }
                }
                "hero-summary" -> heroSummary(item())?.let(::JsonPrimitive) ?: JsonNull
                "blurhash" -> {
                    val hash = input.getValue("hash").jsonPrimitive.content
                    val width = input.getValue("width").jsonPrimitive.int
                    val height = input.getValue("height").jsonPrimitive.int
                    buildJsonObject {
                        put(
                            "components",
                            BlurHash.components(hash)?.let { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }
                                ?: JsonNull
                        )
                        put("averageColor", BlurHash.averageColor(hash)?.let { JsonPrimitive(rgbHex(it)) } ?: JsonNull)
                        put(
                            "pixels",
                            BlurHash.decode(hash, width, height)?.let { pixels -> JsonArray(pixels.map { JsonPrimitive(rgbHex(it)) }) }
                                ?: JsonNull
                        )
                    }
                }
                else -> error("$id: unknown operation $operation")
            }
            val expected = case.getValue("expected")
            assertEquals(normalize(expected), normalize(actual), id)
        }
    }

    /** Numbers compare by value (7.5 and 7.50, 2014 and 2014.0). */
    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(value.map(::normalize))
        is JsonPrimitive -> if (!value.isString && value.content.toDoubleOrNull() != null) {
            JsonPrimitive(value.content.toDouble())
        } else {
            value
        }
        else -> value
    }
}
