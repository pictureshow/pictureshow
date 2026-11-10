/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class HomePreferencesTest {
    private val viewer = Account(Server("s", "Cinema", "https://media.example.test/base", "12.0.0"), User("v", "Viewer"), "viewer-token")
    private val identity = ClientIdentity("Test", "device")
    private fun film(id: String, series: String? = null) = MediaItem(id, id, if (series == null) "Movie" else "Episode", seriesId = series)

    @Test fun mergeRetainsResumeOrderDeduplicatesBySeriesAndDoesNotHideResume() {
        val rows = listOf(
            HomeRow("resume", "Resume", listOf(film("a", "s"), film("b"))),
            HomeRow("nextup", "Next", listOf(film("next-s", "s"), film("c", "t"), film("d", "hidden"), film("b"))),
            HomeRow("libraries", "Libraries", listOf(film("root")))
        )
        val preferences = HomePreferences(mergeWatching = true, hiddenSeries = setOf("hidden"))
        val result = customizedHome(rows, preferences)
        assertEquals(listOf("watching", "libraries"), result.map { it.key })
        assertEquals(listOf("a", "b", "c"), result.first().items.map { it.id })
        assertEquals(
            listOf("c", "b"),
            customizedHome(rows, preferences.copy(mergeWatching = false, hiddenSeries = setOf("hidden", "s")))[1].items.map {
                it.id
            }
        )
    }

    @Test fun hiddenAndOrderedRowsKeepUnknownNewRowsInOriginalOrderAndNeverInventContents() {
        val rows = listOf("a", "b", "c", "d").map { HomeRow(it, it, listOf(film(it))) } + HomeRow("empty", "Empty", emptyList())
        assertEquals(
            listOf("c", "a", "d"),
            customizedHome(rows, HomePreferences(order = listOf("unknown", "c", "c", "a"), hidden = setOf("b"))).map {
                it.key
            }
        )
        assertTrue(customizedHome(emptyList(), HomePreferences(pins = listOf(HomePin(HomePinKind.COLLECTION, "denied")))).isEmpty())
    }

    @Test fun preferencesRoundTripWithoutLosingUnknownServerFields() {
        val before = Json.parseToJsonElement("""{"Future":{"key":3},"CustomPrefs":{"other":"keep"},"SortOrder":"Descending"}""").jsonObject
        val value = HomePreferences(
            order = listOf("libraries", "resume"),
            hidden = setOf("nextup"),
            mergeWatching = true,
            hiddenSeries = setOf("series"),
            pins = HomePinKind.entries.map { HomePin(it, "value") },
            landing = "library:one",
            libraries = mapOf(
                "one" to LibraryDisplay("DateCreated", true, true)
            )
        )
        val after = homePreferencesDocument(before, value)
        assertEquals(before["Future"], after["Future"])
        assertEquals(before["SortOrder"], after["SortOrder"])
        assertEquals(before["CustomPrefs"]!!.jsonObject["other"], after["CustomPrefs"]!!.jsonObject["other"])
        assertEquals(value, readHomePreferences(after))
        assertNull(readHomePreferences(Json.parseToJsonElement("""{"CustomPrefs":{"pictureshow.home.v1":"broken"}}""").jsonObject))
    }

    @Test fun filterRequestIntersectsAllFacetsAndPreservesPagingSortAndViewerAuthority() = runTest {
        val filters = LibraryFilters("Animation & stories", 2025, "PG-13", true, true, VideoResolution.FULL_HD, "person", "Studio & Co")
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        assertEquals("/base/Items", request.url.encodedPath)
                        assertEquals("v", request.url.parameters["userId"])
                        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().contains("viewer-token"))
                        val p = request.url.parameters
                        mapOf(
                            "genres" to "Animation & stories",
                            "years" to "2025",
                            "officialRatings" to "PG-13",
                            "isPlayed" to "false",
                            "isFavorite" to "true", "minWidth" to "1920", "maxWidth" to "3839", "personIds" to "person",
                            "studios" to "Studio & Co",
                            "sortBy" to "DateCreated", "sortOrder" to "Descending", "startIndex" to "60", "parentId" to "library"
                        ).forEach { (k, v) ->
                            assertEquals(v, p[k], k)
                        }
                        respond("""{"Items":[],"TotalRecordCount":0}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        )
        http.use {
            assertIs<ClientResult.Success<ItemPage>>(
                CatalogClient(
                    it,
                    identity
                ).items(viewer, BrowseQuery("library", sortBy = "DateCreated", descending = true, startIndex = 60, filters = filters))
            )
        }
        assertTrue(LibraryFilters().parameters().values.all { it == null })
        assertFalse(LibraryFilters().active)
        assertEquals("719", LibraryFilters(resolution = VideoResolution.SD).parameters()["maxWidth"])
        assertEquals("3840", LibraryFilters(resolution = VideoResolution.UHD).parameters()["minWidth"])
        assertNull(LibraryFilters(resolution = VideoResolution.UHD).parameters()["maxWidth"])
    }

    @Test fun everyPinUsesViewerCatalogQueryWithoutCachedContents() {
        assertEquals("box", HomePin(HomePinKind.COLLECTION, "box").query().parentId)
        assertFalse(HomePin(HomePinKind.PLAYLIST, "list").query().recursive)
        assertEquals("Adventure", HomePin(HomePinKind.GENRE, "Adventure").query().filters.genre)
        assertEquals("Workshop", HomePin(HomePinKind.STUDIO, "Workshop").query().filters.studio)
        assertTrue(HomePin(HomePinKind.FAVORITES).query().filters.favorite)
        assertTrue(HomePin(HomePinKind.GENRE, "Adventure").query().recursive)
    }

    @Test fun facetsPinsSearchAndDisplayPreferencesUseOnlyCurrentViewerEvenOnDenial() = runTest {
        val seen = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        seen += request.url.encodedPath
                        assertEquals("v", request.url.parameters["userId"])
                        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().contains("viewer-token"))
                        respond("{}", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        )
        http.use {
            val service = CatalogClient(it, identity)
            assertIs<ClientResult.Failure>(service.facets(viewer, "restricted"))
            listOf("Genres", "Studios", "Persons", "BoxSet", "Playlist").forEach { kind ->
                assertIs<ClientResult.Failure>(service.namedItems(viewer, kind, "restricted", "query", 60))
            }
            assertIs<ClientResult.Failure>(service.displayPreferences(viewer))
            assertIs<ClientResult.Failure>(service.saveDisplayPreferences(viewer, Json.parseToJsonElement("{}").jsonObject))
            assertIs<ClientResult.Failure>(SearchClient(it, identity).search(viewer, "story", SearchKind.PEOPLE))
            assertIs<ClientResult.Failure>(service.item(viewer, "restricted"))
        }
        assertEquals(10, seen.size)
    }

    @Test
    fun deniedParentNeverReachesLeakyMetadataOrLatestEndpoints() = runTest {
        val seen = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val path = request.url.encodedPath.removePrefix("/base/")
                        seen += path
                        when (path) {
                            "Items/denied" -> respond("{}", HttpStatusCode.NotFound)
                            "Items" -> respond("{}", HttpStatusCode.Unauthorized)
                            else -> respond("""{"Items":[{"Id":"secret","Name":"Forbidden title"}],"Genres":["Forbidden genre"]}""")
                        }
                    }
                }
            )
        )
        http.use {
            val service = CatalogClient(it, identity)
            assertIs<ClientResult.Failure>(service.facets(viewer, "denied"))
            listOf("Genres", "Studios", "Persons").forEach { kind ->
                assertIs<ClientResult.Failure>(service.namedItems(viewer, kind, "denied"))
            }
            assertIs<ClientResult.Failure>(service.latest(viewer, "denied"))
        }
        assertEquals(listOf("Items/denied", "Items/denied", "Items/denied", "Items/denied", "Items"), seen)
    }

    @Test fun localFallbackSurvivesRemoteRecoveryAndRemainsIsolatedByServerAndViewer() = runTest {
        var fail = true
        var remote = homePreferencesDocument(
            Json.parseToJsonElement("""{"CustomPrefs":{"unrelated":"retain"}}""").jsonObject,
            HomePreferences()
        )
        var writes = 0
        val http = HttpClient(
            MockEngine(
                io.ktor.client.engine.mock.MockEngineConfig().apply {
                    dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        if (fail) {
                            respond("{}", HttpStatusCode.ServiceUnavailable)
                        } else if (request.method == HttpMethod.Post) {
                            remote = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                            writes++
                            respond("", HttpStatusCode.NoContent)
                        } else {
                            respond(remote.toString())
                        }
                    }
                }
            )
        )
        val persistence = object : TextPersistence {
            var value: String? = null
            override suspend fun read() = value
            override suspend fun write(value: String) {
                this.value = value
            }
        }
        val accounts = AccountStore(persistence)
        accounts.remember(viewer)
        val other = viewer.copy(user = User("other", "Other"))
        accounts.remember(other)
        http.use {
            val store = HomePreferencesStore(CatalogClient(it, identity), accounts)
            val saved = store.change(viewer) { it.copy(landing = "libraries", mergeWatching = true) }
            assertEquals("libraries", saved.preferences.landing)
            assertTrue(saved.retrySave, "A failed server save must offer Retry")
            assertTrue(viewer.key in accounts.load().pendingHomePreferences)
            assertEquals(HomePreferences(), store.read(other).preferences)
            fail = false
            assertTrue(store.read(viewer).retrySave)
            assertTrue(store.read(viewer).preferences.mergeWatching) // older remote data cannot clobber pending local
            val synced = store.change(viewer) { it }
            assertEquals(1, writes)
            assertFalse(synced.retrySave, "Successful save must remove Retry")
            assertNull(synced.status)
            assertEquals(synced.preferences, readHomePreferences(remote))
            assertEquals("retain", remote["CustomPrefs"]!!.jsonObject["unrelated"]!!.toString().trim('"'))
            assertFalse(viewer.key in accounts.load().pendingHomePreferences)
            accounts.signOut(viewer.key)
            assertNull(accounts.load().homePreferences[viewer.key])
        }
    }
}
