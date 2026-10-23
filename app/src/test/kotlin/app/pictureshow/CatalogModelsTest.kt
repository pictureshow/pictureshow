package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test/base", "12.0.0"), User("u", "Jamie"), "fake-token")
    private val movie = MediaItem("film", "The Glass Comet", "Movie")
    private val movies = MediaItem("movies", "Cinema", "CollectionFolder", collectionType = "movies", isFolder = true)
    private val shows = MediaItem("shows", "Television", "CollectionFolder", collectionType = "tvshows", isFolder = true)
    private lateinit var accounts: AccountStore
    private lateinit var service: FakeCatalog
    private lateinit var session: CatalogSession

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
        accounts = AccountStore(object : TextPersistence {
            var value: String? = null
            override suspend fun read() = value
            override suspend fun write(value: String) {
                this.value = value
            }
        })
        service = FakeCatalog()
        session = CatalogSession(service, accounts)
    }

    @AfterTest
    fun teardown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> own(vm: T): T = vm.also { stores += ViewModelStore().also { store -> store.put("vm", vm) } }

    @Test
    fun freshAccountStillGetsLibrariesAndLatestInServerOrder() = runTest(dispatcher) {
        service.libraryViews = ok(ItemPage(listOf(shows, movies)))
        service.latestItems = mapOf("movies" to ok(listOf(movie)), "shows" to ok(listOf(MediaItem("series", type = "Series"))))
        val vm = own(CatalogHomeViewModel(account, session))
        advanceUntilIdle()
        assertEquals(listOf("libraries", "latest:shows", "latest:movies"), vm.state.value.rows.map { it.key })
        assertEquals(listOf("shows", "movies"), service.latestCalls)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun failedHomeRowDoesNotBlankSuccessfulRowsAndRetryRecovers() = runTest(dispatcher) {
        service.libraryViews = ok(ItemPage(listOf(movies)))
        service.resumeItems = failure()
        service.nextItems = ok(ItemPage(listOf(MediaItem("e", type = "Episode"))))
        service.latestItems = mapOf("movies" to ok(listOf(movie)))
        val vm = own(CatalogHomeViewModel(account, session))
        advanceUntilIdle()
        assertEquals(listOf("nextup", "libraries", "latest:movies"), vm.state.value.rows.map { it.key })
        assertNotNull(vm.state.value.error)
        service.resumeItems = ok(ItemPage(listOf(movie)))
        vm.reload()
        advanceUntilIdle()
        assertEquals("resume", vm.state.value.rows.first().key)
        assertNull(vm.state.value.error)
    }

    @Test
    fun latestOnlyFetchesMovieAndTvLibraries() = runTest(dispatcher) {
        service.libraryViews =
            ok(ItemPage(listOf(movies, MediaItem("music", collectionType = "music"), shows, MediaItem("sets", collectionType = "boxsets"))))
        own(CatalogHomeViewModel(account, session))
        advanceUntilIdle()
        assertEquals(listOf("movies", "shows"), service.latestCalls)
    }

    @Test
    fun pagingRequestsOnlyNearEndNeverOverlapsAndStopsAtTotal() = runTest(dispatcher) {
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.pageBlock = { q -> if (q.startIndex == 0) ok(page(0, 60, 125)) else pending.await() }
        val vm = own(CatalogGridViewModel(account, movies, session))
        advanceUntilIdle()
        vm.nearEnd(10)
        runCurrent()
        assertEquals(1, service.queries.size)
        repeat(5) { vm.nearEnd(50) }
        runCurrent()
        assertEquals(listOf(0, 60), service.queries.map { it.startIndex })
        assertTrue(vm.state.value.loading)
        pending.complete(ok(page(60, 60, 125)))
        advanceUntilIdle()
        service.pageBlock = { ok(page(120, 5, 125)) }
        vm.nearEnd(115)
        advanceUntilIdle()
        assertEquals(125, vm.state.value.items.size)
        assertEquals(125, vm.state.value.total)
        repeat(3) { vm.nearEnd(124) }
        advanceUntilIdle()
        assertEquals(listOf(0, 60, 120), service.queries.map { it.startIndex })
        assertTrue(service.queries.all { it.limit == 60 && it.includeItemTypes == "Movie" && it.recursive })
    }

    @Test
    fun failedPageRetainsItemsAndRetriesSameOffsetWithoutAutomaticLoop() = runTest(dispatcher) {
        service.pageBlock = { if (it.startIndex == 0) ok(page(0, 60, 70)) else failure() }
        val vm = own(CatalogGridViewModel(account, movies, session))
        advanceUntilIdle()
        vm.nearEnd(59)
        advanceUntilIdle()
        assertEquals(60, vm.state.value.items.size)
        assertNotNull(vm.state.value.error)
        vm.nearEnd(59)
        advanceUntilIdle()
        assertEquals(2, service.queries.size)
        service.pageBlock = { ok(page(60, 10, 70)) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 60, 60), service.queries.map { it.startIndex })
        assertEquals(70, vm.state.value.items.size)
        assertNull(vm.state.value.error)
    }

    @Test
    fun duplicatePageItemsAreNotAddedTwiceAndEmptyPagesStop() = runTest(dispatcher) {
        service.pageBlock = { q ->
            when (q.startIndex) {
                0 -> ok(ItemPage(listOf(movie, MediaItem("second")), 6))
                2 -> ok(ItemPage(listOf(movie, MediaItem("third")), 6))
                else -> ok(ItemPage(emptyList(), 6))
            }
        }
        val vm = own(CatalogGridViewModel(account, movies, session))
        advanceUntilIdle()
        vm.nearEnd(1)
        advanceUntilIdle()
        assertEquals(listOf("film", "second", "third"), vm.state.value.items.map { it.id })
        vm.nearEnd(2)
        advanceUntilIdle()
        vm.nearEnd(2)
        advanceUntilIdle()
        assertEquals(listOf(0, 2, 4), service.queries.map { it.startIndex })
    }

    @Test
    fun sortCancelsOldPageAndRestartsWithNewQueryAndFolderFilters() = runTest(dispatcher) {
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.pageBlock = { if (it.sortBy == "SortName") pending.await() else ok(ItemPage(listOf(movie), 1)) }
        val vm = own(CatalogGridViewModel(account, shows, session))
        runCurrent()
        vm.sort("PremiereDate", true)
        advanceUntilIdle()
        pending.complete(ok(ItemPage(listOf(MediaItem("stale")), 1)))
        advanceUntilIdle()
        assertEquals(listOf("film"), vm.state.value.items.map { it.id })
        assertEquals("Series", service.queries.last().includeItemTypes)
        assertEquals("PremiereDate", service.queries.last().sortBy)
        assertTrue(service.queries.last().descending)
        assertEquals(0, service.queries.last().startIndex)
        own(CatalogGridViewModel(account, movies.copy(collectionType = "boxsets"), session))
        advanceUntilIdle()
        assertEquals("BoxSet", service.queries.last().includeItemTypes)
        own(CatalogGridViewModel(account, MediaItem("folder", isFolder = true), session))
        runCurrent()
        assertNull(service.queries.last().includeItemTypes)
        assertFalse(service.queries.last().recursive)
    }

    @Test
    fun watchedAndFavoriteUpdateDetailGridAndRefreshHome() = runTest(dispatcher) {
        service.detail = ok(movie)
        service.pageBlock = { ok(ItemPage(listOf(movie), 1)) }
        service.libraryViews = ok(ItemPage(listOf(movies)))
        val home = own(CatalogHomeViewModel(account, session))
        val grid = own(CatalogGridViewModel(account, movies, session))
        val detail = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        val initialResumeCalls = service.resumeCalls
        service.actionResult = ok(ItemUserData(played = true))
        detail.played()
        advanceUntilIdle()
        assertEquals(Triple("played", "film", true), service.actions.last())
        assertTrue(detail.state.value.item!!.userData.played)
        assertTrue(grid.state.value.items.single().userData.played)
        assertTrue(service.resumeCalls > initialResumeCalls)
        assertFalse(home.state.value.loading)
        service.actionResult = ok(ItemUserData(played = true, isFavorite = true))
        detail.favorite()
        advanceUntilIdle()
        assertEquals(Triple("favorite", "film", true), service.actions.last())
        assertTrue(grid.state.value.items.single().userData.isFavorite)
        service.actionResult = ok(ItemUserData(isFavorite = true))
        detail.played()
        advanceUntilIdle()
        assertEquals(Triple("played", "film", false), service.actions.last())
        service.actionResult = ok(ItemUserData())
        detail.favorite()
        advanceUntilIdle()
        assertEquals(Triple("favorite", "film", false), service.actions.last())
    }

    @Test
    fun failedActionsRevertOptimisticStateShowErrorAndDoNotPublish() = runTest(dispatcher) {
        service.detail = ok(movie)
        val detail = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        val pending = CompletableDeferred<ClientResult<ItemUserData>>()
        service.actionBlock = { pending.await() }
        detail.played()
        runCurrent()
        assertTrue(detail.state.value.item!!.userData.played)
        assertTrue(detail.state.value.busy)
        detail.favorite()
        assertEquals(1, service.actions.size)
        pending.complete(failure())
        advanceUntilIdle()
        assertFalse(detail.state.value.item!!.userData.played)
        assertFalse(detail.state.value.busy)
        assertNotNull(detail.state.value.error)
        assertEquals(0L, session.changes.value.revision)
        service.actionBlock = null
        service.actionResult = failure()
        detail.favorite()
        advanceUntilIdle()
        assertFalse(detail.state.value.item!!.userData.isFavorite)
    }

    @Test
    fun seriesLoadsNextUpSeasonsAndSelectedSeasonEpisodes() = runTest(dispatcher) {
        val series = MediaItem("series", "Orchard Station", "Series")
        val first = MediaItem("s1", "Amber cycle", "Season", seriesId = "series")
        val second = first.copy(id = "s2", name = "Blue cycle")
        service.detail = ok(series)
        service.seasonItems = ok(ItemPage(listOf(first, second)))
        service.nextItems = ok(ItemPage(listOf(MediaItem("next", type = "Episode"))))
        val detail = own(CatalogDetailViewModel(account, series, session))
        advanceUntilIdle()
        assertEquals("series", service.nextSeries.last())
        assertEquals("s1", detail.state.value.selectedSeasonId)
        assertEquals("next", detail.state.value.nextUp.single().id)
        assertEquals("series" to "s1", service.episodeCalls.last())
        detail.season(second)
        advanceUntilIdle()
        assertEquals("s2", detail.state.value.selectedSeasonId)
        assertEquals("series" to "s2", service.episodeCalls.last())
    }

    @Test
    fun seasonDetailUsesParentSeriesAndLatestSelectionWins() = runTest(dispatcher) {
        val season = MediaItem("s1", "Amber cycle", "Season", seriesId = "series")
        service.detail = ok(season)
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.episodeBlock = { _, seasonId -> if (seasonId == "s1") pending.await() else ok(ItemPage(listOf(MediaItem("new")))) }
        val vm = own(CatalogDetailViewModel(account, season, session))
        runCurrent()
        vm.season(season.copy(id = "s2"))
        runCurrent()
        pending.complete(ok(ItemPage(listOf(MediaItem("old")))))
        advanceUntilIdle()
        assertEquals("series" to "s2", service.episodeCalls.last())
        assertEquals("new", vm.state.value.episodes.single().id)
    }

    @Test
    fun refreshInvalidatesOldEpisodeRequestBeforeStartingNewDetailLoad() = runTest(dispatcher) {
        val season = MediaItem("s1", "Amber cycle", "Season", seriesId = "series")
        service.detail = ok(season)
        val oldEpisodes = CompletableDeferred<ClientResult<ItemPage>>()
        service.episodeBlock = { _, _ -> withContext(NonCancellable) { oldEpisodes.await() } }
        val vm = own(CatalogDetailViewModel(account, season, session))
        runCurrent()
        val freshDetail = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { freshDetail.await() }
        vm.reload()
        runCurrent()
        oldEpisodes.complete(ok(ItemPage(listOf(MediaItem("stale")))))
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.episodes.isEmpty())
        service.episodeBlock = { _, _ -> ok(ItemPage(listOf(MediaItem("fresh")))) }
        freshDetail.complete(ok(season))
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals("fresh", vm.state.value.episodes.single().id)
    }

    @Test
    fun staleUnauthorizedResponseCannotExpireRefreshedCredentials() = runTest(dispatcher) {
        accounts.remember(account)
        val replacement = account.copy(accessToken = "refreshed-token")
        accounts.remember(replacement)
        session.call(account) { ClientResult.Failure(ClientError(ErrorKind.EXPIRED, "Expired")) }
        assertEquals(replacement, accounts.snapshots.value.currentAccount)
    }

    @Test
    fun anyCatalog401ExpiresOnlyAffectedAccountAndKeepsUsernameAndServer() = runTest(dispatcher) {
        val other = account.copy(user = User("other", "Robin"), accessToken = "another-token")
        accounts.remember(other)
        accounts.remember(account)
        service.pageBlock = { failure(ErrorKind.EXPIRED) }
        own(CatalogGridViewModel(account, movies, session))
        advanceUntilIdle()
        val saved = accounts.snapshots.value
        assertEquals(other, saved.accounts.first())
        assertTrue(saved.currentAccount!!.expired)
        assertEquals(account.user.name, saved.currentAccount!!.user.name)
        assertEquals(account.server, saved.currentAccount!!.server)
        assertEquals("", saved.currentAccount!!.accessToken)
        val reopened = AccountStore(object : TextPersistence {
            override suspend fun read() = kotlinx.serialization.json.Json.encodeToString(saved)
            override suspend fun write(value: String) = Unit
        }).load()
        assertTrue(reopened.currentAccount!!.expired)
    }

    private fun page(start: Int, count: Int, total: Int) = ItemPage((start until start + count).map { MediaItem("item-$it") }, total, start)
    private fun <T> ok(value: T) = ClientResult.Success(value)
    private fun failure(kind: ErrorKind = ErrorKind.NETWORK) = ClientResult.Failure(ClientError(kind, "Could not load. Retry."))

    @Test
    fun markingSeasonReloadsCascadedEpisodeStateAndChildChangesRefreshParent() = runTest(dispatcher) {
        val season = MediaItem("season", "Season 1", "Season", seriesId = "series")
        val episode = MediaItem("episode", "Episode 1", "Episode")
        service.detail = ok(season)
        service.episodeBlock = { _, _ -> ok(ItemPage(listOf(episode))) }
        val vm = own(CatalogDetailViewModel(account, season, session))
        advanceUntilIdle()
        assertFalse(vm.state.value.episodes.single().userData.played)
        val watched = ItemUserData(played = true)
        service.detail = ok(season.copy(userData = watched))
        service.episodeBlock = { _, _ -> ok(ItemPage(listOf(episode.copy(userData = watched)))) }
        service.actionResult = ok(watched)
        vm.played()
        advanceUntilIdle()
        assertEquals(Triple("played", season.id, true), service.actions.single())
        assertTrue(vm.state.value.episodes.single().userData.played)
        service.detail = ok(season)
        service.episodeBlock = { _, _ -> ok(ItemPage(listOf(episode))) }
        session.changed(episode)
        advanceUntilIdle()
        assertFalse(vm.state.value.item!!.userData.played)
        assertFalse(vm.state.value.episodes.single().userData.played)
    }

    @Test
    fun stopReportRefreshReplacesAnEarlyResumedReadForEveryPlayableDetailType() = runTest(dispatcher) {
        for (type in listOf("Movie", "Episode", "Video", "Series", "Season")) {
            val item = movie.copy(type = type, seriesId = "series")
            service.detail = ok(item)
            val vm = own(CatalogDetailViewModel(account, item, session))
            advanceUntilIdle()
            // RESUMED wins the race with the in-flight stop report.
            vm.reload()
            advanceUntilIdle()
            val saved = ItemUserData(playbackPositionTicks = 3_699_730_000, played = true)
            service.detail = ok(item.copy(userData = saved))
            session.refresh() // PlaybackSession.onFinished, after the report.
            advanceUntilIdle()
            assertEquals(saved, vm.state.value.item!!.userData, type)
            assertFalse(vm.state.value.loading, type)
        }
    }

    @Test
    fun postReportRefreshCancelsAnOlderDetailReadThatFinishesLate() = runTest(dispatcher) {
        service.detail = ok(movie)
        val vm = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        val pending = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { withContext(NonCancellable) { pending.await() } }
        vm.reload()
        runCurrent()
        service.detailBlock = null
        val saved = ItemUserData(playbackPositionTicks = 4_000_000_000)
        service.detail = ok(movie.copy(userData = saved))
        session.refresh()
        runCurrent()
        pending.complete(ok(movie))
        advanceUntilIdle()
        assertEquals(saved, vm.state.value.item!!.userData)
    }

    @Test
    fun latestServerRefreshWinsOverAnOlderLocalUserDataPatch() = runTest(dispatcher) {
        service.detail = ok(movie)
        val vm = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        session.changed(movie.copy(userData = ItemUserData(isFavorite = true)))
        advanceUntilIdle()
        val saved = ItemUserData(playbackPositionTicks = 5_000_000_000, played = true)
        service.detail = ok(movie.copy(userData = saved))
        session.refresh()
        advanceUntilIdle()
        assertEquals(saved, vm.state.value.item!!.userData)
    }

    @Test
    fun unrelatedMutationCannotReapplyAPatchFromBeforeTheStopReport() = runTest(dispatcher) {
        service.detail = ok(movie)
        val vm = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        session.changed(movie.copy(userData = ItemUserData(isFavorite = true)))
        advanceUntilIdle()
        val saved = ItemUserData(playbackPositionTicks = 5_000_000_000, played = true)
        service.detail = ok(movie.copy(userData = saved))
        session.refresh()
        advanceUntilIdle()
        session.changed(MediaItem("other", userData = ItemUserData(played = true)))
        advanceUntilIdle()
        assertEquals(saved, vm.state.value.item!!.userData)
    }

    @Test
    fun conflatedLocalMutationsStillReachEveryAffectedDetail() = runTest(dispatcher) {
        service.detailBlock = { ok(movie) }
        val vm = own(CatalogDetailViewModel(account, movie, session))
        advanceUntilIdle()
        val changed = ItemUserData(played = true)
        session.changed(movie.copy(userData = changed))
        session.changed(MediaItem("other", userData = ItemUserData(isFavorite = true)))
        advanceUntilIdle()
        assertEquals(changed, vm.state.value.item!!.userData)
    }

    @Test
    fun canceledSeriesSeasonsResponseCannotReplaceFreshNextUpAndEpisodes() = runTest(dispatcher) {
        val series = MediaItem("series", type = "Series")
        val season = MediaItem("season", type = "Season", seriesId = series.id)
        val oldNext = MediaItem("old", type = "Episode")
        service.detail = ok(series)
        service.nextItems = ok(ItemPage(listOf(oldNext)))
        val pending = CompletableDeferred<ClientResult<ItemPage>>()
        service.seasonsBlock = { withContext(NonCancellable) { pending.await() } }
        val vm = own(CatalogDetailViewModel(account, series, session))
        runCurrent()
        val fresh = oldNext.copy(id = "fresh", userData = ItemUserData(playbackPositionTicks = 4_000_000_000))
        service.nextItems = ok(ItemPage(listOf(fresh)))
        service.seasonsBlock = null
        service.seasonItems = ok(ItemPage(listOf(season)))
        service.episodeBlock = { _, _ -> ok(ItemPage(listOf(fresh))) }
        session.refresh()
        runCurrent()
        pending.complete(ok(ItemPage(emptyList())))
        advanceUntilIdle()
        assertEquals(listOf(fresh), vm.state.value.nextUp)
        assertEquals(listOf(season), vm.state.value.seasons)
        assertEquals(listOf(fresh), vm.state.value.episodes)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun postStopRefreshUpdatesContainerPrimaryEpisodeAndWatchedState() = runTest(dispatcher) {
        for (type in listOf("Series", "Season")) {
            val item = MediaItem("container", type = type, seriesId = "series")
            val season = MediaItem("season", type = "Season", seriesId = "series")
            val old = MediaItem("episode", type = "Episode")
            service.detail = ok(item)
            service.seasonItems = ok(ItemPage(listOf(season)))
            service.nextItems = ok(ItemPage(listOf(old)))
            service.episodeBlock = { _, _ -> ok(ItemPage(listOf(old))) }
            val vm = own(CatalogDetailViewModel(account, item, session))
            advanceUntilIdle()
            vm.reload()
            advanceUntilIdle()
            val saved = old.copy(userData = ItemUserData(playbackPositionTicks = 4_000_000_000, played = true))
            service.nextItems = ok(ItemPage(listOf(saved)))
            service.episodeBlock = { _, _ -> ok(ItemPage(listOf(saved))) }
            session.refresh()
            advanceUntilIdle()
            assertEquals(listOf(saved), vm.state.value.episodes, type)
            if (type == "Series") assertEquals(listOf(saved), vm.state.value.nextUp)
            assertFalse(vm.state.value.loading)
        }
    }

    internal class FakeCatalog : CatalogService {
        var libraryViews: ClientResult<ItemPage> = ClientResult.Success(ItemPage())
        var resumeItems: ClientResult<ItemPage> = ClientResult.Success(ItemPage())
        var nextItems: ClientResult<ItemPage> = ClientResult.Success(ItemPage())
        var latestItems = emptyMap<String, ClientResult<List<MediaItem>>>()
        val latestCalls = mutableListOf<String>()
        val queries = mutableListOf<BrowseQuery>()
        val actions = mutableListOf<Triple<String, String, Boolean>>()
        val nextSeries = mutableListOf<String?>()
        val episodeCalls = mutableListOf<Pair<String, String>>()
        var resumeCalls = 0
        var detail: ClientResult<MediaItem> = ClientResult.Success(MediaItem("film"))
        var seasonItems: ClientResult<ItemPage> = ClientResult.Success(ItemPage())
        var pageBlock: suspend (BrowseQuery) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
        var episodeBlock: suspend (String, String) -> ClientResult<ItemPage> = { _, _ -> ClientResult.Success(ItemPage()) }
        var actionResult: ClientResult<ItemUserData> = ClientResult.Success(ItemUserData())
        var actionBlock: (suspend () -> ClientResult<ItemUserData>)? = null
        override suspend fun views(account: Account) = libraryViews
        override suspend fun resume(account: Account): ClientResult<ItemPage> {
            resumeCalls++
            return resumeItems
        }
        override suspend fun nextUp(account: Account, seriesId: String?): ClientResult<ItemPage> {
            nextSeries += seriesId
            return nextItems
        }
        override suspend fun latest(account: Account, parentId: String): ClientResult<List<MediaItem>> {
            latestCalls += parentId
            return latestItems[parentId] ?: ClientResult.Success(emptyList())
        }
        override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> {
            queries += query
            return pageBlock(query)
        }
        var detailBlock: (suspend () -> ClientResult<MediaItem>)? = null
        override suspend fun item(account: Account, id: String) = detailBlock?.invoke() ?: detail
        var seasonsBlock: (suspend () -> ClientResult<ItemPage>)? = null
        override suspend fun seasons(account: Account, seriesId: String) = seasonsBlock?.invoke() ?: seasonItems
        override suspend fun episodes(account: Account, seriesId: String, seasonId: String): ClientResult<ItemPage> {
            episodeCalls += seriesId to seasonId
            return episodeBlock(seriesId, seasonId)
        }
        override suspend fun played(account: Account, id: String, value: Boolean): ClientResult<ItemUserData> {
            actions += Triple("played", id, value)
            return actionBlock?.invoke() ?: actionResult
        }
        override suspend fun favorite(account: Account, id: String, value: Boolean): ClientResult<ItemUserData> {
            actions += Triple("favorite", id, value)
            return actionBlock?.invoke() ?: actionResult
        }
    }
}
