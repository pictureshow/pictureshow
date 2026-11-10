/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HomeBrowseVectorsTest {
    @Serializable private data class Row(val key: String, val title: String, val items: List<MediaItem>)

    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("grid-vectors/home-browse.json")).bufferedReader().use {
            it.readText()
        }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertEquals(17, cases.size)
        for (case in cases.map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            val actual = when (case.getValue("operation").jsonPrimitive.content) {
                "home-rows" -> {
                    val rows = Json.decodeFromJsonElement<List<Row>>(input.getValue("rows")).map { HomeRow(it.key, it.title, it.items) }
                    val preferences = Json.decodeFromJsonElement<HomePreferences>(input.getValue("preferences"))
                    Json.encodeToJsonElement(
                        customizedHome(rows, preferences).map { row ->
                            buildJsonObject {
                                put("key", row.key)
                                put("ids", Json.encodeToJsonElement(row.items.map { it.id }))
                            }
                        }
                    )
                }
                "library-filter-parameters" -> parameters(Json.decodeFromJsonElement<LibraryFilters>(input))
                "home-pin-query" -> {
                    val query = Json.decodeFromJsonElement<HomePin>(input).query()
                    buildJsonObject {
                        put("parentId", query.parentId)
                        put("recursive", query.recursive)
                        put("filters", parameters(query.filters))
                    }
                }
                "home-preferences-document" -> {
                    val before = input.getValue("document").jsonObject
                    val prefs = Json.decodeFromJsonElement<HomePreferences>(input.getValue("preferences"))
                    val after = homePreferencesDocument(before, prefs)
                    buildJsonObject {
                        put(
                            "preserved",
                            before.filterKeys { it != "CustomPrefs" }.all { (k, v) -> after[k] == v } &&
                                before.getValue("CustomPrefs").jsonObject.all { (k, v) -> after.getValue("CustomPrefs").jsonObject[k] == v }
                        )
                        put("roundtrip", readHomePreferences(after) == prefs)
                    }
                }
                else -> error("Unknown operation")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
    private fun parameters(filters: LibraryFilters) =
        JsonObject(filters.parameters().filterValues { it != null }.mapValues { JsonPrimitive(it.value) })
}
