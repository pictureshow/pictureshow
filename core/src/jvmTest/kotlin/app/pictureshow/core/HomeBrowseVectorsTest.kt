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
                "named-picker-focus-target" -> Json.encodeToJsonElement(
                    namedPickerFocusTarget(
                        input.getValue("hasChoices").jsonPrimitive.content == "true",
                        input.getValue("hasAny").jsonPrimitive.content == "true",
                        input.getValue("hasSelection").jsonPrimitive.content == "true",
                        input["editing"]?.jsonPrimitive?.content == "true"
                    )
                )
                "title-count-label" -> Json.encodeToJsonElement(titleCountLabel(input.getValue("count").jsonPrimitive.content.toInt()))
                "resolution-summary" -> Json.encodeToJsonElement(
                    VideoResolution.valueOf(input.getValue("resolution").jsonPrimitive.content).summary()
                )
                "reset-home-layout" -> Json.encodeToJsonElement(
                    resetHomeLayout(
                        Json.decodeFromJsonElement<HomePreferences>(input.getValue("preferences")),
                        input.getValue("confirmed").jsonPrimitive.content == "true"
                    )
                )
                "restore-all-series" -> Json.encodeToJsonElement(
                    offerRestoreAllSeries(
                        Json.decodeFromJsonElement<Set<String>>(input.getValue("hidden")),
                        Json.decodeFromJsonElement<Set<String>>(input.getValue("available"))
                    )
                )
                "next-up-series-hint" -> Json.encodeToJsonElement(nextUpSeriesHint(input.getValue("shown").jsonPrimitive.content == "true"))
                "home-pin-picker-support" -> Json.encodeToJsonElement(
                    homePinPickerSupport(
                        input.getValue("pinned").jsonPrimitive.content == "true",
                        input.getValue("count").jsonPrimitive.content.toIntOrNull()
                    )
                )
                "editor-row-hint" -> Json.encodeToJsonElement(
                    homeRowEditHint(
                        input.getValue("shown").jsonPrimitive.content == "true",
                        input.getValue("moving").jsonPrimitive.content == "true",
                        input.getValue("tv").jsonPrimitive.content == "true"
                    )
                )
                "named-picker-choices" -> Json.encodeToJsonElement(
                    namedPickerChoices(
                        Json.decodeFromJsonElement<List<VectorItem>>(input.getValue("current")).map { MediaItem(it.id) },
                        Json.decodeFromJsonElement<List<VectorItem>>(input.getValue("page")).map { MediaItem(it.id) },
                        input.getValue("term").jsonPrimitive.content,
                        input.getValue("offset").jsonPrimitive.content.toInt()
                    ).map { it.id }
                )
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
                "home-editor-pin-label" -> Json.encodeToJsonElement(
                    homeEditorPinLabel(
                        Json.decodeFromJsonElement<HomePin>(input.getValue("pin")),
                        Json.decodeFromJsonElement<List<Row>>(input.getValue("rows")).map { row ->
                            HomeRow(row.key, row.title, row.items.map { MediaItem(it.id) })
                        }
                    )
                )
                "home-landing" -> Json.encodeToJsonElement(
                    homeLanding(
                        input.getValue("ready").jsonPrimitive.content == "true",
                        input.getValue("applied").jsonPrimitive.content == "true",
                        input.getValue("selection").jsonPrimitive.content,
                        Json.decodeFromJsonElement<Set<String>>(input.getValue("libraryIds"))
                    )
                )
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
                "home-preferences-local-failure" -> {
                    val afterPost = input.getValue("afterPost").jsonPrimitive.content == "true"
                    var post = false
                    var reject = false
                    val accounts = AccountStore(object : TextPersistence {
                        var value: String? = null
                        override suspend fun read() = value
                        override suspend fun write(value: String) {
                            if (reject && (!afterPost || post)) error("Simulated unavailable device storage")
                            this.value = value
                        }
                    })
                    val viewer = Account(Server("s", "Cinema", "https://media.example.test", "12.0.0"), User("v", "Viewer"), "fake")
                    accounts.remember(viewer)
                    val remote = homePreferencesDocument(JsonObject(emptyMap()), HomePreferences(mergeWatching = true))
                    val http = HttpClient(
                        MockEngine(
                            io.ktor.client.engine.mock.MockEngineConfig().apply {
                                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                                addHandler { request ->
                                    if (request.method == io.ktor.http.HttpMethod.Post) {
                                        post = true
                                        respond("", HttpStatusCode.NoContent)
                                    } else {
                                        respond(remote.toString())
                                    }
                                }
                            }
                        )
                    )
                    http.use {
                        val store = HomePreferencesStore(CatalogClient(it, ClientIdentity("Vectors", "vector-device")), accounts)
                        reject = true
                        val result = if (afterPost) store.change(viewer) { it.copy(landing = "libraries") } else store.read(viewer)
                        buildJsonObject {
                            put("merged", result.preferences.mergeWatching)
                            put("landing", result.preferences.landing)
                            put("warned", result.status != null)
                            put("serverSaved", post)
                            put("pending", viewer.key in accounts.load().pendingHomePreferences)
                        }
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
                "editor-visible-rows" -> Json.encodeToJsonElement(
                    editableHomeKeys(
                        Json.decodeFromJsonElement(input.getValue("keys")),
                        Json.decodeFromJsonElement(input.getValue("preferences"))
                    )
                )
                "editor-move-row" -> Json.encodeToJsonElement(
                    moveHomeRow(
                        Json.decodeFromJsonElement(input.getValue("order")),
                        Json.decodeFromJsonElement(input.getValue("visible")),
                        input.getValue("key").jsonPrimitive.content,
                        input.getValue("delta").jsonPrimitive.content.toInt()
                    )
                )
                "filter-presentation" -> {
                    val filters = Json.decodeFromJsonElement<LibraryFilters>(input.getValue("filters"))
                    buildJsonObject {
                        put("keys", Json.encodeToJsonElement(filters.constraintKeys()))
                        put("active", filters.active)
                        put("empty", filteredEmptyLabel(input["collectionType"]?.jsonPrimitive?.content))
                        input["remove"]?.jsonPrimitive?.content?.let { key ->
                            put("remaining", Json.encodeToJsonElement(filters.withoutConstraint(key).constraintKeys()))
                        }
                    }
                }
                "sort-direction-label" -> JsonPrimitive(
                    sortDirectionLabel(input.getValue("descending").jsonPrimitive.content.toBooleanStrict())
                )
                else -> error("Unknown operation")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
    private fun parameters(filters: LibraryFilters) =
        JsonObject(filters.parameters().filterValues { it != null }.mapValues { JsonPrimitive(it.value) })
}
