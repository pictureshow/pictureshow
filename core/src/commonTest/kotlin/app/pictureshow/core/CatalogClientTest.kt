package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** All titles, identifiers, metadata and users below are invented for this test suite. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogClientTest {
    private val server = Server("test-server", "Screen room", "https://media.example.test/base/jellyfin/", "12.0.0")
    private val account = Account(server, User("test-user", "Jamie"), "fictional-token")
    private val identity = ClientIdentity("TV", "fictional-device")
    private val movie = """{"Id":"film-a","Name":"The Glass Comet","Type":"Movie","ProductionYear":2025,
        "OfficialRating":"PG","CommunityRating":7.6,"RunTimeTicks":72000000000,"Overview":"A glassmaker charts an invented sky.",
        "Genres":["Clockwork fiction"],"ImageTags":{"Primary":"poster-a"},"BackdropImageTags":["back-a"],
        "UserData":{"PlaybackPositionTicks":37230000000,"IsFavorite":true,"Played":false},"UnknownFutureField":{}}"""
    private val episode = """{"Id":"episode-a","Name":"A Paper Moon","Type":"Episode","SeriesId":"series-a",
        "SeriesName":"Orchard Station","SeasonId":"season-a","IndexNumber":2,"ParentIndexNumber":1,
        "RunTimeTicks":24000000000,"ParentBackdropItemId":"series-a","ParentBackdropImageTags":["series-back"],
        "UserData":{"PlaybackPositionTicks":12000000000,"Played":false}}"""
    private val series = """{"Id":"series-a","Name":"Orchard Station","Type":"Series","IsFolder":true,
        "UserData":{"UnplayedItemCount":8,"Played":false},"ImageTags":{"Primary":"series-poster"}}"""
    private val season = """{"Id":"season-a","Name":"Amber cycle","Type":"Season","SeriesId":"series-a","IndexNumber":1,
        "IsFolder":true,"UserData":{"UnplayedItemCount":4}}"""
    private fun page(vararg items: String, total: Int = items.size, start: Int = 0) =
        """{"Items":[${items.joinToString(",")}],"TotalRecordCount":$total,"StartIndex":$start}"""

    private fun TestScope.client(body: String, inspect: (io.ktor.client.request.HttpRequestData) -> Unit = {}): JellyfinClient {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    inspect(request)
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        backgroundScope.coroutineContext.job.invokeOnCompletion { http.close() }
        return JellyfinClient(http, identity)
    }

    @Test
    fun parsesViewsResumeNextUpAndPlainArrayLatestShapes() = runTest {
        val views = page("""{"Id":"view-a","Name":"Invented Cinema","Type":"CollectionFolder","CollectionType":"movies"}""")
        assertEquals("movies", assertIs<ClientResult.Success<ItemPage>>(client(views).views(account)).value.items.single().collectionType)
        val resume = assertIs<ClientResult.Success<ItemPage>>(client(page(movie)).resume(account)).value.items.single()
        assertEquals(37230000000, resume.userData.playbackPositionTicks)
        assertTrue(resume.userData.isFavorite)
        val next = assertIs<ClientResult.Success<ItemPage>>(client(page(episode)).nextUp(account)).value.items.single()
        assertEquals("Orchard Station", next.seriesName)
        val movies = assertIs<ClientResult.Success<List<MediaItem>>>(client("[$movie]").latest(account, "view-a")).value
        assertEquals("Movie", movies.single().type)
        val tv = assertIs<ClientResult.Success<List<MediaItem>>>(client("[$series,$season,$episode]").latest(account, "view-b")).value
        assertEquals(listOf("Series", "Season", "Episode"), tv.map { it.type })
    }

    @Test
    fun parsesPagedMoviesSeriesAndSingleItemDetails() = runTest {
        val movies =
            assertIs<ClientResult.Success<ItemPage>>(client(page(movie, total = 3201, start = 60)).items(account, BrowseQuery("view-a")))
        assertEquals(3201, movies.value.totalRecordCount)
        assertEquals(60, movies.value.startIndex)
        val shows = assertIs<ClientResult.Success<ItemPage>>(client(page(series)).items(account, BrowseQuery("view-b"))).value
        assertEquals(8, shows.items.single().userData.unplayedItemCount)
        val detail = assertIs<ClientResult.Success<MediaItem>>(client(movie).item(account, "film-a")).value
        assertEquals(2025, detail.productionYear)
        assertEquals("PG", detail.officialRating)
        assertEquals(7.6, detail.communityRating)
        assertEquals(72000000000, detail.runTimeTicks)
        assertEquals(listOf("Clockwork fiction"), detail.genres)
        assertEquals("A glassmaker charts an invented sky.", detail.overview)
        assertEquals("Series", assertIs<ClientResult.Success<MediaItem>>(client(series).item(account, "series-a")).value.type)
        assertEquals(2, assertIs<ClientResult.Success<MediaItem>>(client(episode).item(account, "episode-a")).value.indexNumber)
    }

    @Test
    fun parsesSeasonsEpisodesAndAbsentOrNullOptionalProperties() = runTest {
        assertEquals(
            "season-a",
            assertIs<ClientResult.Success<ItemPage>>(client(page(season)).seasons(account, "series-a")).value.items.single().id
        )
        val missing = """{"Id":"special-a","Name":"Loose Leaf","Type":"Episode","ImageTags":null,"Genres":null,"UserData":null}"""
        val episodes = assertIs<ClientResult.Success<ItemPage>>(
            client(page(episode, missing)).episodes(account, "series-a", "season-a")
        ).value
        val special = episodes.items.last()
        assertNull(special.indexNumber)
        assertNull(special.parentIndexNumber)
        assertEquals(emptyMap(), special.imageTags)
        assertEquals(emptyList(), special.genres)
        assertEquals(ItemUserData(), special.userData)
        assertNull(special.runTimeTicks)
        assertNull(special.userData.playedPercentage)
    }

    @Test
    fun browseRequestKeepsBasePathPagingSortAndAuthorization() = runTest {
        var observed = false
        val client = client(page()) { request ->
            observed = true
            assertEquals("/base/jellyfin/Items", request.url.encodedPath)
            assertEquals("test-user", request.url.parameters["userId"])
            assertEquals("view & a", request.url.parameters["parentId"])
            assertEquals("Series", request.url.parameters["includeItemTypes"])
            assertEquals("true", request.url.parameters["recursive"])
            assertEquals("DateCreated", request.url.parameters["sortBy"])
            assertEquals("Descending", request.url.parameters["sortOrder"])
            assertEquals("120", request.url.parameters["startIndex"])
            assertEquals("60", request.url.parameters["limit"])
            assertEquals("true", request.url.parameters["enableTotalRecordCount"])
            assertTrue(request.url.parameters["fields"]!!.contains("Overview"))
            assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("fictional-token"))
        }
        client.items(account, BrowseQuery("view & a", "Series", true, "DateCreated", true, 120, 60))
        assertTrue(observed)
    }

    @Test
    fun allReadEndpointsSendTheirRequiredPathsAndParameters() = runTest {
        val seen = mutableListOf<Pair<String, Map<String, String?>>>()
        val client = client(page()) { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("test-user", request.url.parameters["userId"])
            seen += request.url.encodedPath.removePrefix("/base/jellyfin/") to request.url.parameters.names().associateWith {
                request.url.parameters[it]
            }
        }
        client.views(account)
        client.resume(account)
        client.nextUp(account, "series-a")
        client.latest(account, "view-a")
        client.item(account, "film-a")
        client.seasons(account, "series-a")
        client.episodes(account, "series-a", "season-a")
        assertEquals(
            listOf(
                "UserViews",
                "UserItems/Resume",
                "Shows/NextUp",
                "Items/Latest",
                "Items/film-a",
                "Shows/series-a/Seasons",
                "Shows/series-a/Episodes"
            ),
            seen.map { it.first }
        )
        assertEquals("Video", seen[1].second["mediaTypes"])
        assertEquals("24", seen[1].second["limit"])
        assertEquals("series-a", seen[2].second["seriesId"])
        assertEquals("view-a", seen[3].second["parentId"])
        assertEquals("season-a", seen[6].second["seasonId"])
    }

    @Test
    fun watchedAndFavoriteUsePostAndDeleteAndDecodeUserData() = runTest {
        val seen = mutableListOf<Pair<String, HttpMethod>>()
        val client = client("""{"Played":true,"IsFavorite":true,"PlaybackPositionTicks":0}""") {
            seen += it.url.encodedPath.substringAfter("/base/jellyfin/") to it.method
        }
        assertTrue(assertIs<ClientResult.Success<ItemUserData>>(client.played(account, "film-a", true)).value.played)
        client.played(account, "film-a", false)
        assertTrue(assertIs<ClientResult.Success<ItemUserData>>(client.favorite(account, "film-a", true)).value.isFavorite)
        client.favorite(account, "film-a", false)
        assertEquals(
            listOf(
                "UserPlayedItems/film-a" to HttpMethod.Post,
                "UserPlayedItems/film-a" to HttpMethod.Delete,
                "UserFavoriteItems/film-a" to HttpMethod.Post,
                "UserFavoriteItems/film-a" to HttpMethod.Delete
            ),
            seen
        )
    }

    @Test
    fun unauthorizedIsExpiredAndUnreadableDataIsReported() = runTest {
        val expired = JellyfinClient(
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { respond("", HttpStatusCode.Unauthorized) }
                }
            },
            identity
        )
        assertEquals(ErrorKind.EXPIRED, assertIs<ClientResult.Failure>(expired.views(account)).error.kind)
        assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<ClientResult.Failure>(client("{broken").resume(account)).error.kind)
        assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<ClientResult.Failure>(client("{}").item(account, "x")).error.kind)
    }

    @Test
    fun homeRowsUseServerOrderHideEmptyAndRestrictLatestToMoviesAndTv() {
        val movies = MediaItem("v-m", "Cinema", collectionType = "movies")
        val shows = MediaItem("v-t", "Shows", collectionType = "tvshows")
        val music = MediaItem("v-a", "Sound cabinet", collectionType = "music")
        val item = MediaItem("item")
        val rows =
            assembleHome(
                listOf(shows, music, movies),
                listOf(item),
                listOf(item),
                mapOf(
                    "v-m" to listOf(item),
                    "v-a" to listOf(item),
                    "v-t" to listOf(item)
                )
            )
        assertEquals(listOf("resume", "nextup", "libraries", "latest:v-t", "latest:v-m"), rows.map { it.key })
        assertEquals(
            listOf("libraries", "latest:v-m"),
            assembleHome(listOf(movies), emptyList(), emptyList(), mapOf("v-m" to listOf(item))).map {
                it.key
            }
        )
        assertTrue(assembleHome(emptyList(), emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun labelsHandleTicksMissingEpisodeNumbersAndSpecials() {
        assertEquals("Resume from 1:02:03", resumeLabel(37230000000))
        assertEquals("Resume from 2:03", resumeLabel(1230000000))
        assertEquals("Resume from 0:00", resumeLabel(-1))
        assertEquals("1h 2m", runtimeLabel(37230000000))
        assertEquals("2m", runtimeLabel(1230000000))
        assertEquals("", runtimeLabel(null))
        val item = MediaItem("e", type = "Episode", indexNumber = 2, parentIndexNumber = 1)
        assertEquals("S1:E2", episodeLabel(item))
        assertEquals("Special E2", episodeLabel(item.copy(parentIndexNumber = 0)))
        assertEquals("Special", episodeLabel(item.copy(parentIndexNumber = 0, indexNumber = null)))
        assertEquals("E2", episodeLabel(item.copy(parentIndexNumber = null)))
        assertEquals("S1", episodeLabel(item.copy(indexNumber = null)))
        assertEquals("Episode", episodeLabel(item.copy(indexNumber = null, parentIndexNumber = null)))
    }

    @Test
    fun progressUsesPercentageOrTicksAndClamps() {
        val item = MediaItem("e", runTimeTicks = 100, userData = ItemUserData(playbackPositionTicks = 25))
        assertEquals(0.25f, progress(item))
        assertEquals(0.75f, progress(item.copy(userData = item.userData.copy(playedPercentage = 75.0))))
        assertEquals(1f, progress(item.copy(userData = item.userData.copy(playbackPositionTicks = 200))))
        assertEquals(0f, progress(item.copy(runTimeTicks = null)))
        assertEquals(0f, progress(item.copy(runTimeTicks = 0)))
    }

    @Test
    fun imageUrlsKeepBaseTagPixelBoundsAndNeverIncludeToken() {
        val item = MediaItem("film-a", imageTags = mapOf("Primary" to "tag & one"))
        val url = Url(requireNotNull(imageUrl(server, item, 320, 480)))
        assertEquals("/base/jellyfin/Items/film-a/Images/Primary", url.encodedPath)
        assertEquals("tag & one", url.parameters["tag"])
        assertEquals("320", url.parameters["maxWidth"])
        assertEquals("480", url.parameters["maxHeight"])
        assertEquals("85", url.parameters["quality"])
        assertFalse(url.toString().contains("token"))
        val fallback = MediaItem("e", type = "Episode", parentBackdropItemId = "series-a", parentBackdropImageTags = listOf("bg"))
        assertTrue(imageUrl(server, fallback, 400)!!.contains("Items/series-a/Images/Backdrop"))
        assertTrue(imageUrl(server, fallback.copy(imageTags = mapOf("Primary" to "ep")), 400)!!.contains("Items/e/Images/Primary"))
        assertNull(imageUrl(server, MediaItem("no-art"), 320))
    }
}
