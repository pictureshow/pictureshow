/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule

/** PLAY-17: the page a finished viewing returns to says "Finished. You watched {title} to the end.", and only that page. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailFinishedLineTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val series = MediaItem("series", "Invented series", "Series")
    private val finale = MediaItem("finale", "Invented finale", "Episode", seriesId = "series", seasonId = "season")
    private val service = CatalogModelsTest.FakeCatalog()
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    })
    private val session = CatalogSession(service, accounts)

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun detail(item: MediaItem, key: String): CatalogDetailViewModel {
        service.detail = ClientResult.Success(item)
        return CatalogDetailViewModel(account, item, session).also { store.put(key, it) }
    }

    @Test fun theFilmPageUnderThePlayerShowsTheLineAndALaterVisitDoesNot() = runTest(dispatcher) {
        val under = detail(film, "under")
        runCurrent()
        assertNull(under.state.value.finishedLine)
        session.playbackFinished(film)
        runCurrent()
        assertEquals("Finished. You watched Invented film to the end.", under.state.value.finishedLine)
        // A page opened after the viewing ended is a later visit, not the return.
        val later = detail(film, "later")
        runCurrent()
        assertNull(later.state.value.finishedLine)
        // Leaving the page ends the line.
        under.leftPage()
        runCurrent()
        assertNull(under.state.value.finishedLine)
        assertNull(session.finished.value)
    }

    @Test fun aSeriesLastEpisodeNamesTheEpisodeOnTheSeriesPage() = runTest(dispatcher) {
        val seriesPage = detail(series, "series")
        val unrelated = detail(film, "film")
        runCurrent()
        session.playbackFinished(finale)
        runCurrent()
        assertEquals("Finished. You watched Invented finale to the end.", seriesPage.state.value.finishedLine)
        assertNull(unrelated.state.value.finishedLine)
        // An unrelated page leaving doesn't end another page's line.
        unrelated.leftPage()
        runCurrent()
        assertEquals("Finished. You watched Invented finale to the end.", seriesPage.state.value.finishedLine)
    }

    @Test fun aNewViewingEndsAnEarlierLine() = runTest(dispatcher) {
        val page = detail(film, "page")
        runCurrent()
        session.playbackFinished(film)
        runCurrent()
        session.clearFinished()
        runCurrent()
        assertNull(page.state.value.finishedLine)
    }
}
