/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

/** Stream C1 (catalog): title discovery, library browse/filters/rail/queue, Home layout and the photo viewer. */
class DesktopCatalogStreamTest {
    private class Engine : DesktopPlaybackEngine {
        @Volatile var opened = 0; @Volatile var ended = false
        override fun attach(window: java.awt.Window) {}
        override fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int) { opened++; ended = false }
        override fun snapshot() = EngineSnapshot(1.0, 120.0, false, opened > 0, ended = ended)
        override fun mute(muted: Boolean) {}
        override fun togglePause() {}
        override fun seek(seconds: Double) {}
        override fun volume(value: Int) {}
        override fun selectAudio(index: Int) = true
        override fun selectSubtitle(index: Int) = true
        override fun fullscreen(enabled: Boolean) {}
        override fun close() {}
    }

    /** A small Jellyfin: items by id, per-parent children with real filter, sort and paging, and stored display preferences. */
    private class Server : AutoCloseable {
        data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)
        val requests = CopyOnWriteArrayList<Request>()
        val executor = Executors.newCachedThreadPool()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${http.address.port}"
        val items = ConcurrentHashMap<String, JsonObject>()
        val children = ConcurrentHashMap<String, List<String>>()
        val views = CopyOnWriteArrayList<String>()
        @Volatile var displayPreferences: JsonObject = buildJsonObject { put("Id", "usersettings"); put("CustomPrefs", buildJsonObject {}) }
        @Volatile var configuration: JsonObject = buildJsonObject {}
        val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val special = ConcurrentHashMap<String, String>()
        @Volatile var nextUp: List<String> = emptyList()
        @Volatile var resume: List<String> = emptyList()

        fun item(id: String, name: String, type: String, vararg extra: Pair<String, JsonElement>) {
            items[id] = buildJsonObject {
                put("Id", id); put("Name", name); put("Type", type); put("SortName", name.lowercase())
                put("IsFolder", type in setOf("CollectionFolder", "BoxSet", "Folder", "Series"))
                put("RunTimeTicks", 600_000_000L)
                extra.forEach { (k, v) -> put(k, v) }
            }
        }
        fun page(ids: List<String>, query: Map<String, String>): String {
            var list = ids.mapNotNull { items[it] }
            query["includeItemTypes"]?.split(",")?.let { types -> list = list.filter { it["Type"]!!.jsonPrimitive.content in types } }
            query["genres"]?.let { g -> list = list.filter { (it["Genres"] as? JsonArray)?.any { v -> v.jsonPrimitive.content == g } == true } }
            query["isPlayed"]?.let { p -> list = list.filter { ((it["UserData"] as? JsonObject)?.get("Played")?.jsonPrimitive?.boolean ?: false).toString() == p } }
            when (query["sortBy"]) {
                "SortName" -> list = list.sortedBy { it["SortName"]!!.jsonPrimitive.content }
                "Random" -> list = list.reversed()
            }
            if (query["sortOrder"] == "Descending") list = list.reversed()
            val total = list.size
            val start = query["startIndex"]?.toInt() ?: 0
            val limit = query["limit"]?.toInt() ?: Int.MAX_VALUE
            list = list.drop(start).take(limit)
            return buildJsonObject { put("Items", JsonArray(list)); put("TotalRecordCount", total); put("StartIndex", start) }.toString()
        }
        private fun ok(ids: List<String>) = page(ids, emptyMap())
        init {
            http.executor = executor
            http.createContext("/") { e ->
                val path = e.requestURI.path
                val query = e.requestURI.rawQuery.orEmpty().split("&").filter { "=" in it }
                    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                val body = e.requestBody.bufferedReader().use { it.readText() }
                requests += Request(e.requestMethod, path, query, body)
                var status = 200
                val response = when {
                    path in failing -> { status = 500; "" }
                    path == "/System/Info/Public" -> """{"Id":"server","ServerName":"Demo cinema","ProductName":"Jellyfin Server","Version":"12.4.0"}"""
                    path == "/Users/Public" -> "[]"
                    path == "/QuickConnect/Enabled" -> "false"
                    path == "/Users/AuthenticateByName" -> """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"test-token"}"""
                    path == "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                    path == "/Users/viewer" -> buildJsonObject { put("Id", "viewer"); put("Configuration", configuration) }.toString()
                    path == "/DisplayPreferences/usersettings" && e.requestMethod == "POST" -> { displayPreferences = Json.parseToJsonElement(body).jsonObject; "" }
                    path == "/DisplayPreferences/usersettings" -> displayPreferences.toString()
                    path == "/UserViews" -> ok(views)
                    path == "/UserItems/Resume" -> ok(resume)
                    path == "/Shows/NextUp" -> ok(nextUp)
                    path == "/Items/Filters" -> """{"Genres":["Drama","Comedy"],"Years":[2001],"OfficialRatings":["PG"]}"""
                    path.endsWith("/Ancestors") -> "[]"
                    path.endsWith("/LocalTrailers") -> special["trailers:" + path.split("/")[2]] ?: "[]"
                    path.endsWith("/SpecialFeatures") -> special["extras:" + path.split("/")[2]] ?: "[]"
                    path.endsWith("/Similar") -> special["similar:" + path.split("/")[2]] ?: """{"Items":[]}"""
                    path.endsWith("/PlaybackInfo") -> """{"PlaySessionId":"session","MediaSources":[{"Id":"source","Container":"mkv","SupportsDirectPlay":true,"RunTimeTicks":1200000000,"DefaultAudioStreamIndex":1,"MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"},{"Type":"Audio","Index":1,"Codec":"aac"}]}]}"""
                    path.startsWith("/Sessions/") || path.startsWith("/Videos/") -> ""
                    path == "/Items" && query["ids"] != null -> ok(query.getValue("ids").split(","))
                    path == "/Items" && query["parentId"] != null -> {
                        val parent = query.getValue("parentId")
                        val ids = if (query["recursive"] == "true") descendants(parent) else children[parent].orEmpty()
                        page(ids, query)
                    }
                    path == "/Items" -> page(items.keys.sorted(), query + ("includeItemTypes" to (query["includeItemTypes"] ?: "Movie,Series")))
                    path.startsWith("/Items/") && items.containsKey(path.removePrefix("/Items/")) -> items.getValue(path.removePrefix("/Items/")).toString()
                    else -> { status = 404; "" }
                }
                e.responseHeaders.set("Content-Type", "application/json")
                if (response.isEmpty()) e.sendResponseHeaders(if (status == 200) 204 else status, -1)
                else { val bytes = response.toByteArray(); e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.write(bytes) }
                e.close()
            }
            http.start()
        }
        fun descendants(parent: String): List<String> = children[parent].orEmpty().flatMap { listOf(it) + descendants(it) }
        fun itemsQueries(parent: String) = requests.filter { it.path == "/Items" && it.query["parentId"] == parent }
        override fun close() { http.stop(0); executor.shutdownNow() }
    }

    private suspend fun DesktopController.await(predicate: (DesktopState) -> Boolean): DesktopState = withTimeout(10000) { state.first(predicate) }
    private suspend fun DesktopController.login(s: Server) {
        start(); await { it.route == DesktopRoute.Servers && !it.busy }
        connect(s.url); await { it.route == DesktopRoute.SignIn && !it.busy }
        signIn("Viewer", "correct"); await { it.account != null && !it.busy && it.catalog.preferences != null }
    }
    private fun directory() = Files.createTempDirectory("pictureshow-desktop-c1-").toFile()
    private fun moviesLibrary(s: Server) {
        s.item("movies", "Movies", "CollectionFolder", "CollectionType" to JsonPrimitive("movies"))
        s.views += "movies"
        s.item("folder", "A folder", "Folder")
        s.item("m1", "Alpha", "Movie", "Genres" to JsonArray(listOf(JsonPrimitive("Drama"))))
        s.item("m2", "Bravo", "Movie", "Genres" to JsonArray(listOf(JsonPrimitive("Comedy"))))
        s.item("m3", "Charlie", "Movie", "Genres" to JsonArray(listOf(JsonPrimitive("Drama"))), "UserData" to buildJsonObject { put("Played", true) })
        s.children["movies"] = listOf("folder")
        s.children["folder"] = listOf("m1", "m2", "m3")
    }

    // ------------------------------------------------------------ BROWSE-04, 01..03, HOME-06

    @Test fun moviesLibraryListsTitlesNotFoldersAndFiltersReplaceTheResultSet(): Unit = runBlocking {
        Server().use { s ->
            moviesLibrary(s)
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.browse(s.items.getValue("movies").let { Json.decodeFromJsonElement<MediaItem>(it) })
                val first = c.await { it.route == DesktopRoute.Library && !it.busy && it.items.isNotEmpty() }
                assertEquals(listOf("m1", "m2", "m3"), first.items.map { it.id }, "BROWSE-04: titles under folders, never the folder")
                val query = s.itemsQueries("movies").first { it.query["fields"] != "SortName" && it.query["sortBy"] == "SortName" }.query
                assertEquals("Movie", query["includeItemTypes"]); assertEquals("true", query["recursive"])

                c.setLibraryFilters(LibraryFilters(genre = "Drama"))
                val drama = c.await { !it.busy && it.catalog.filters.genre == "Drama" && it.items.map(MediaItem::id) == listOf("m1", "m3") }
                assertEquals(2, drama.total)
                c.setLibraryFilters(drama.catalog.filters.copy(unwatched = true))
                val combined = c.await { !it.busy && it.catalog.filters.unwatched && it.items.size == 1 }
                assertEquals("m1", combined.items.single().id, "BROWSE-02: genre and unwatched combine")
                val last = s.itemsQueries("movies").last { it.query["fields"] != "SortName" }.query
                assertEquals("Drama", last["genres"]); assertEquals("false", last["isPlayed"]); assertEquals("0", last["startIndex"])
            }
        }
    }

    @Test fun sortAndListViewAreRememberedPerLibraryOnTheServer(): Unit = runBlocking {
        Server().use { s ->
            moviesLibrary(s)
            val movies = Json.decodeFromJsonElement<MediaItem>(s.items.getValue("movies"))
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.browse(movies, "DateCreated", true)
                c.await { it.route == DesktopRoute.Library && !it.busy && it.sortBy == "DateCreated" }
                c.setLibraryListView(true)
                withTimeout(10000) { while (readHomePreferences(s.displayPreferences)?.libraries?.get("movies")?.list != true) delay(20) }
            }
            // A fresh app on another device reads the same viewer's choice from the server.
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.browse(movies)
                val state = c.await { it.route == DesktopRoute.Library && !it.busy && it.items.isNotEmpty() }
                assertEquals("DateCreated", state.sortBy); assertTrue(state.descending); assertTrue(state.catalog.listView)
            }
        }
    }

    @Test fun letterRailReadsSortNamesAndJumpLoadsThroughTheLettersFirstTitle(): Unit = runBlocking {
        Server().use { s ->
            s.item("movies", "Movies", "CollectionFolder", "CollectionType" to JsonPrimitive("movies")); s.views += "movies"
            val ids = (0 until 150).map { i -> "t$i".also { s.item(it, "${if (i < 140) "A" else "Z"} title ${100 + i}", "Movie") } }
            s.children["movies"] = ids
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.browse(Json.decodeFromJsonElement<MediaItem>(s.items.getValue("movies")))
                val loaded = c.await { it.route == DesktopRoute.Library && !it.busy && it.catalog.railIds?.size == 150 && it.items.size == 60 }
                val rail = s.itemsQueries("movies").single { it.query["fields"] == "SortName" }.query
                assertEquals("false", rail["enableImages"]); assertEquals("false", rail["enableUserData"]); assertEquals("Movie", rail["includeItemTypes"])
                assertEquals(setOf("A", "Z"), loaded.catalog.railNames.map(::jumpLetter).toSet())
                c.jumpToLetter("Z")
                val jumped = c.await { it.catalog.jumpTarget == "t140" }
                assertTrue(jumped.items.any { it.id == "t140" }, "the grid holds the target before it is focused")
                assertEquals(150, jumped.items.size)
            }
        }
    }

    // ------------------------------------------------------------ BROWSE-06

    @Test fun collectionPlayQueuesPlayableTitlesAdvancesAndStopReturnsToTheCollection(): Unit = runBlocking {
        Server().use { s ->
            s.item("set", "A box set", "BoxSet")
            s.item("q1", "First", "Movie"); s.item("q2", "Second", "Movie"); s.item("qs", "A show", "Series")
            s.children["set"] = listOf("q1", "qs", "q2")
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(s)
                c.open(Json.decodeFromJsonElement<MediaItem>(s.items.getValue("set")))
                c.await { it.route == DesktopRoute.Library && !it.busy && it.items.size == 3 }
                c.playCollection(shuffle = false)
                val first = c.await { it.playback?.item?.id == "q1" && it.playback?.ready == true }
                assertEquals("q2", first.playback?.nextEpisode?.id, "the queue's next title, not an episode lookup")
                val queued = s.requests.last { it.path == "/Items" && it.query["parentId"] == "set" && it.query["includeItemTypes"] != null }.query
                assertEquals(collectionPlayScope("BoxSet", null)!!.includeItemTypes, queued["includeItemTypes"])
                assertEquals("SortName", queued["sortBy"], "Play keeps the page's order; Shuffle asks for Random")
                c.nextEpisode()
                val second = c.await { it.playback?.item?.id == "q2" && it.playback?.ready == true }
                assertNull(second.playback?.nextEpisode, "the queue's last title ends it")
                c.stopPlayback()
                val back = c.await { it.route == DesktopRoute.Library && !it.busy }
                assertEquals("set", back.library?.id, "Stop returns to the collection that started the queue")
            }
        }
    }

    // ------------------------------------------------------------ CAT-30..32

    @Test fun titlePageShowsTrailersExtrasAndMoreLikeThisAndAFailedReadDropsOnlyItsRow(): Unit = runBlocking {
        Server().use { s ->
            s.item("film", "The film", "Movie", "RemoteTrailers" to JsonArray(listOf(buildJsonObject { put("Url", "https://www.youtube.com/watch?v=abcdefghijk"); put("Name", "Teaser") })))
            s.item("other", "Other film", "Movie")
            s.special["extras:film"] = """[{"Id":"x1","Name":"Making of","Type":"Video","ExtraType":"BehindTheScenes","RunTimeTicks":1200000000}]"""
            s.special["similar:film"] = """{"Items":[{"Id":"film","Name":"The film","Type":"Movie"},{"Id":"other","Name":"Other film","Type":"Movie"}]}"""
            s.failing += "/Items/film/LocalTrailers"
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.detail(Json.decodeFromJsonElement<MediaItem>(s.items.getValue("film")))
                val state = c.await { it.route == DesktopRoute.Detail && it.catalog.discoveryFor == "film" && it.catalog.trailers.isNotEmpty() && it.catalog.extras.isNotEmpty() && it.catalog.similar.isNotEmpty() }
                assertEquals("YouTube", trailerLine(state.catalog.trailers.single()), "a failed local read still shows the online trailer")
                assertEquals(listOf("other"), state.catalog.similar.map { it.id }, "More like this never lists the title itself")
                assertTrue(extraLine(state.catalog.extras.single()).startsWith(extraLabel("BehindTheScenes")!!))
                var opened: String? = null
                c.openTrailer(state.catalog.trailers.single()) { opened = it; false }
                val refused = c.await { it.catalog.trailerMessage != null }
                assertEquals("https://www.youtube.com/watch?v=abcdefghijk", opened)
                assertTrue(refused.catalog.trailerMessage!!.contains("web browser"))
            }
        }
    }

    @Test fun anExtraPlaysAndStopReturnsToTheTitlePage(): Unit = runBlocking {
        Server().use { s ->
            s.item("film", "The film", "Movie")
            s.item("x1", "Making of", "Video", "ExtraType" to JsonPrimitive("BehindTheScenes"))
            s.special["extras:film"] = """[{"Id":"x1","Name":"Making of","Type":"Video","ExtraType":"BehindTheScenes"}]"""
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.detail(Json.decodeFromJsonElement<MediaItem>(s.items.getValue("film")))
                val state = c.await { it.catalog.extras.isNotEmpty() && !it.busy }
                c.playExtra(state.catalog.extras.single())
                c.await { it.playback?.item?.id == "x1" && it.playback?.ready == true }
                c.stopPlayback()
                val back = c.await { it.route == DesktopRoute.Detail && !it.busy && it.detail?.id == "film" }
                assertEquals("film", back.detail?.id, "Stop returns to the title, not to the extra's own page")
            }
        }
    }

    // ------------------------------------------------------------ HOME-01..13

    @Test fun homeAppliesSavedLayoutPinsSeparateNextUpAndServerExclusions(): Unit = runBlocking {
        Server().use { s ->
            moviesLibrary(s)
            s.item("e1", "Pilot", "Episode", "SeriesId" to JsonPrimitive("show"), "SeriesName" to JsonPrimitive("Show"), "IndexNumber" to JsonPrimitive(1), "ParentIndexNumber" to JsonPrimitive(1))
            s.nextUp = listOf("e1")
            s.item("favs", "unused", "Folder")
            s.configuration = buildJsonObject { put("LatestItemsExcludes", JsonArray(listOf(JsonPrimitive("movies")))) }
            s.displayPreferences = homePreferencesDocument(s.displayPreferences,
                HomePreferences(order = listOf("nextup", "libraries"), mergeWatching = false, pins = listOf(HomePin(HomePinKind.GENRE, "Drama"))))
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                val home = c.await { it.route == DesktopRoute.Home && !it.busy && it.homeRows.any { r -> r.key.startsWith("pin:") } }
                assertEquals(listOf("nextup", "libraries", "pin:GENRE:Drama"), home.homeRows.map { it.key }, "saved order first, then the rest")
                assertNull(home.homeRows.firstOrNull { it.key.startsWith("latest:") }, "HOME-08: the server's Latest exclusion holds")

                // HOME-04: removing a series from Next up saves and drops its row.
                c.changeHome { it.copy(hiddenSeries = it.hiddenSeries + "show") }
                c.await { it.catalog.preferences?.hiddenSeries == setOf("show") && !it.catalog.saving && it.homeRows.none { r -> r.key == "nextup" } }
                assertEquals(setOf("show"), readHomePreferences(s.displayPreferences)?.hiddenSeries)
            }
        }
    }

    @Test fun separateNextUpSettingIsTheSavedMergeChoice(): Unit = runBlocking {
        Server().use { s ->
            moviesLibrary(s)
            s.item("e1", "Pilot", "Episode", "SeriesId" to JsonPrimitive("show"), "SeriesName" to JsonPrimitive("Show"), "IndexNumber" to JsonPrimitive(1), "ParentIndexNumber" to JsonPrimitive(1))
            s.nextUp = listOf("e1")
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                val merged = c.await { it.homeRows.any { r -> r.key == "watching" } }
                assertEquals("Next · S1:E1", merged.homeRows.first { it.key == "watching" }.captions["e1"])
                c.changeHome { it.copy(mergeWatching = false) }
                c.await { it.homeRows.any { r -> r.key == "nextup" } && it.homeRows.none { r -> r.key == "watching" } }
                assertEquals(false, readHomePreferences(s.displayPreferences)?.mergeWatching)
            }
        }
    }

    @Test fun savedLandingOpensTheLibraryOnceAndHomeStaysReachable(): Unit = runBlocking {
        Server().use { s ->
            moviesLibrary(s)
            s.displayPreferences = homePreferencesDocument(s.displayPreferences, HomePreferences(landing = "library:movies"))
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                val landed = c.await { it.route == DesktopRoute.Library && !it.busy && it.items.isNotEmpty() }
                assertEquals("movies", landed.library?.id)
                c.home()
                val home = c.await { it.route == DesktopRoute.Home && !it.busy }
                assertEquals(DesktopRoute.Home, home.route, "the landing applies once per session; Home is never redirected again")
            }
        }
    }

    @Test fun unreachableServerKeepsHomeRowsWithOneLine(): Unit = runBlocking {
        val s = Server()
        moviesLibrary(s)
        DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
            c.login(s)
            val before = c.await { it.route == DesktopRoute.Home && !it.busy && it.homeRows.isNotEmpty() }
            s.close()
            c.home()
            val after = c.await { it.route == DesktopRoute.Home && !it.busy && it.catalog.homeNotice != null }
            assertEquals(before.homeRows.map { it.key }, after.homeRows.map { it.key })
            assertEquals("Can't reach Demo cinema. Showing what was loaded earlier.", after.catalog.homeNotice)
            assertNull(after.error)
        }
    }

    // ------------------------------------------------------------ PHOTO-01/02

    @Test fun photoOpensAmongItsFolderAndStepsStopAtTheEndsUnlessSlideshow(): Unit = runBlocking {
        Server().use { s ->
            s.item("album", "Album", "Folder")
            listOf("p1", "p2", "p3").forEach { s.item(it, it, "Photo", "ParentId" to JsonPrimitive("album"), "ImageTags" to buildJsonObject { put("Primary", "tag") }) }
            s.children["album"] = listOf("p1", "p2", "p3")
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                c.open(Json.decodeFromJsonElement<MediaItem>(s.items.getValue("p3")))
                val open = c.await { it.route == DesktopRoute.Photo && it.catalog.photos.size == 3 }
                assertEquals(2, open.catalog.photoIndex)
                assertFalse(c.stepPhoto(forward = true, slideshow = false), "a manual move stops at the last photo")
                assertTrue(c.stepPhoto(forward = true, slideshow = true))
                assertEquals(0, c.state.value.catalog.photoIndex, "a slideshow wraps to the first")
                c.back()
                c.await { it.route != DesktopRoute.Photo }
            }
        }
    }

    /** Pilot 172 D1: a grid's photo arrives without ParentId (Jellyfin omits it from lists), yet opens among all of its album. */
    @Test fun photoFromAListWithoutParentIdStillOpensAmongItsAlbum(): Unit = runBlocking {
        Server().use { s ->
            s.item("album", "Album", "Folder")
            listOf("p1", "p2", "p3").forEach { s.item(it, it, "Photo", "ParentId" to JsonPrimitive("album"), "ImageTags" to buildJsonObject { put("Primary", "tag") }) }
            s.children["album"] = listOf("p1", "p2", "p3")
            DesktopController(directory(), playbackFactory = { Engine() }).use { c ->
                c.login(s)
                val listed = Json.decodeFromJsonElement<MediaItem>(JsonObject(s.items.getValue("p2") - "ParentId"))
                assertNull(listed.parentId)
                c.open(listed)
                val open = c.await { it.route == DesktopRoute.Photo && !it.busy }
                assertEquals(listOf("p1", "p2", "p3"), open.catalog.photos.map { it.id })
                assertEquals(1, open.catalog.photoIndex)
                assertTrue(c.stepPhoto(forward = true, slideshow = false))
            }
        }
    }

    // ------------------------------------------------------------ pure helpers

    @Test fun queueNextAndPhotoPositionLines() {
        val a = MediaItem("a", "A", "Movie"); val b = MediaItem("b", "B", "Movie")
        assertEquals(b, desktopQueueNext(listOf(a, b), a))
        assertNull(desktopQueueNext(listOf(a, b), b))
        assertNull(desktopQueueNext(listOf(a), b))
        assertEquals("3 of 12", photoPosition(2, 12, false, 5))
        assertEquals("3 of 12 · Slideshow, every 5 seconds", photoPosition(2, 12, true, 5))
        assertEquals("vimeo.com", trailerLine(TrailerChoice("r", "T", url = "https://www.vimeo.com/1")))
    }
}
