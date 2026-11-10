/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
    @Serializable private data class VectorItem(val id: String, val seriesId: String? = null)

    @Serializable private data class Row(val key: String, val title: String, val items: List<VectorItem>)

    @Test fun vectors() = kotlinx.coroutines.test.runTest {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("grid-vectors/home-browse.json")).bufferedReader().use {
            it.readText()
        }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        kotlin.test.assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            val actual = when (case.getValue("operation").jsonPrimitive.content) {
                "home-rows" -> {
                    val rows = Json.decodeFromJsonElement<List<Row>>(input.getValue("rows")).map {
                        HomeRow(it.key, it.title, it.items.map { item -> MediaItem(item.id, seriesId = item.seriesId) })
                    }
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
                "home-user-configuration" -> {
                    val libraries = Json.decodeFromJsonElement<List<MediaItem>>(input.getValue("libraries"))
                    val latest = Json.decodeFromJsonElement<Map<String, List<MediaItem>>>(input.getValue("latest"))
                    Json.encodeToJsonElement(
                        configuredHome(libraries, emptyList(), emptyList(), latest, input["configuration"] as? JsonObject)
                            .map { row ->
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
                "catalog-parent-guard", "catalog-latest-authority", "catalog-views-display-hidden" -> {
                    val paths = mutableListOf<String>()
                    var includeHidden: String? = null
                    val allowed = input["allowed"]?.jsonPrimitive?.content == "true"
                    val http = HttpClient(
                        MockEngine(
                            io.ktor.client.engine.mock.MockEngineConfig().apply {
                                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                                addHandler { request ->
                                    val path = request.url.encodedPath
                                    paths += path
                                    includeHidden = request.url.parameters["includeHidden"]
                                    when {
                                        path == "/Items/library" && !allowed -> respond("{}", HttpStatusCode.NotFound)
                                        path == "/Items/library" -> respond("""{"Id":"library","Type":"CollectionFolder"}""")
                                        path == "/Items" -> respond("{}", HttpStatusCode.Unauthorized)
                                        path == "/Items/Filters" -> respond("""{"Genres":["Visible genre"]}""")
                                        else -> respond("""{"Items":[{"Id":"choice","Name":"Visible choice"}]}""")
                                    }
                                }
                            }
                        )
                    )
                    http.use {
                        val service = CatalogClient(it, ClientIdentity("Vectors", "vector-device"))
                        val viewer = Account(Server("s", "Cinema", "https://media.example.test", "12.0.0"), User("v", "Viewer"), "fake")
                        val result = when (input.getValue("kind").jsonPrimitive.content) {
                            "views" -> service.views(viewer)
                            "facets" -> service.facets(viewer, "library")
                            "latest" -> service.latest(viewer, "library")
                            else -> service.namedItems(viewer, input.getValue("kind").jsonPrimitive.content, "library")
                        }
                        buildJsonObject {
                            put("paths", Json.encodeToJsonElement(paths))
                            put("failed", result is ClientResult.Failure)
                            if (input.getValue("kind").jsonPrimitive.content == "views") put("includeHidden", includeHidden)
                        }
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
