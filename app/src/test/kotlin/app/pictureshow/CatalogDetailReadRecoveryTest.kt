/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogDetailReadRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")

    @Test fun movieReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Movie", true)

    @Test fun seriesReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Series", true)

    @Test fun seasonReturnDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Season", true)

    @Test fun ordinaryFailedOpenDoesNotMoveAcrossReadFailureHeldRetryAndFresh() = recovery("Movie", false)

    private fun recovery(type: String, returning: Boolean) {
        val item = MediaItem("item", "Invented item", type, overview = "A short description.", childCount = 2)
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val freshItem = if (returning) {
            item
        } else {
            item.copy(
                productionYear = 2026,
                overview = "A richer authoritative description after recovery.",
                genres = listOf("Invented genre"),
                userData = item.userData.copy(playbackPositionTicks = 300_000_000L)
            )
        }
        val state =
            mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = returning, episodes = listOf(episode)))
        var retries = 0
        var mutations = 0
        val playedItems = mutableListOf<MediaItem>()
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { mutations++ }, { mutations++ },
                    {
                        retries++
                        state.value = state.value.copy(loading = true, error = null)
                    },
                    onPlay = { fresh, _ -> playedItems += fresh }, onPendingAction = { true }, origin = item
                )
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val hero = bounds("detail:hero")
        val suffix = if (returning) "-pending" else ""
        val played = bounds("detail:played$suffix")
        val favorite = bounds("detail:favorite$suffix")
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "The server took too long to respond. Try again.") }
        assertEquals(hero, bounds("detail:hero"), "A read error must not insert a band above the published header")
        assertEquals(played, bounds("detail:played"), "The published Watched slot must remain in place")
        assertEquals(favorite, bounds("detail:favorite"), "The published Favorite slot must remain in place")
        compose.onNodeWithText("Retry").assertIsEnabled().assertIsFocused()
        val retry = compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retrying…").assertIsFocused().assertIsEnabled()
        compose.onNodeWithText("The server took too long to respond. Try again.").assertExists()
        assertEquals(retry, compose.onNodeWithText("Retrying…").fetchSemanticsNode().boundsInRoot)
        assertEquals(hero, bounds("detail:hero"), "A held Retry retains its header and a real focused control")
        compose.onNodeWithText("Retrying…").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, mutations)
            assertEquals(emptyList(), playedItems)
        }
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = "The server is still unavailable.") }
        compose.onNodeWithText("Retry").assertIsFocused()
        assertEquals(retry, compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot)
        assertEquals(hero, bounds("detail:hero"))
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(item = freshItem, loading = false, playbackPending = false, error = null) }
        compose.onNodeWithTag("detail:play").assertIsFocused().assertIsDisplayed()
        if (returning) {
            assertEquals(hero, bounds("detail:hero"), "A returning presentation stays frozen through recovery")
        } else {
            // Like an ordinary successful rc18 open, successful recovery adopts server metadata.
            compose.onNodeWithText("Resume from 0:30").assertExists()
        }
        compose.runOnIdle {
            assertEquals(2, retries)
            assertEquals(0, mutations)
            assertEquals(emptyList(), playedItems)
        }
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(listOf(if (type == "Movie") freshItem else episode), playedItems) }
        compose.onNodeWithTag("detail:favorite").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(1, mutations) }
        compose.runOnIdle { state.value = state.value.copy(loading = true, playbackPending = true) }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
    }

    @Test fun aFastReadFailureAfterFreshDataAndAnIdenticalFailedRetryKeepARealRetry() {
        val item = MediaItem("film", "Invented film", "Movie")
        val service = CatalogModelsTest.FakeCatalog().apply { detail = ClientResult.Success(item) }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        lateinit var vm: CatalogDetailViewModel
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, item, CatalogSession(service, accounts))
            compose.activity.viewModelStore.put("recovery", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme { CatalogDetail(state, {}, {}, {}, {}, vm::reload, origin = item) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        var reads = 0
        compose.runOnIdle {
            service.detailBlock = {
                reads++
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Server unavailable"))
            }
            vm.reload()
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retrying…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
        assertEquals(hero, compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun aSeasonBrowsingReadFailureIntentionallyOffersTheSameExplicitRetry() {
        val series = MediaItem("series", "Invented series", "Series", childCount = 2)
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series")
        val episode = MediaItem("episode", "Invented episode", "Episode")
        val service = CatalogModelsTest.FakeCatalog().apply {
            detail = ClientResult.Success(series)
            seasonItems = ClientResult.Success(ItemPage(listOf(season)))
            episodeBlock = { _, _ -> ClientResult.Success(ItemPage(listOf(episode))) }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        lateinit var vm: CatalogDetailViewModel
        compose.runOnUiThread {
            vm = CatalogDetailViewModel(account, series, CatalogSession(service, accounts))
            compose.activity.viewModelStore.put("season-recovery", vm)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PictureshowTheme { CatalogDetail(state, {}, vm::season, {}, {}, vm::reload, origin = series) }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:seasons:season").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:seasons:season").assertIsFocused()
        compose.runOnIdle {
            service.episodeBlock = { _, _ -> ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Episodes unavailable")) }
            vm.season(season)
        }
        compose.onNodeWithText("Retry").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Episodes unavailable").assertExists()
    }

    @Test fun thePendingDeadlineSupersedesOffersWithARealRetryAndNeverReplaysTheAcceptedAction() {
        val item = MediaItem("item", "Invented item", "Movie")
        val state = mutableStateOf(CatalogDetailState(account, item, loading = true, playbackPending = true))
        var now = 0L
        val handoff = PlaybackExitHandoff({ now }, {})
        handoff.begin("entry")
        var mutations = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    state.value, {}, {}, { mutations++ }, {}, {},
                    onPendingAction = { handoff.accessibleSelect("entry", it) },
                    onPendingQueueExpired = { handoff.expired("entry") },
                    onFocusRestored = { target, focused -> handoff.finish("entry", target, focused) }
                )
            }
        }
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { now = 30_001 }
        compose.mainClock.advanceTimeBy(30_100)
        compose.onNodeWithText("Retry").assertIsFocused()
        assertEquals(hero, compose.onNodeWithTag("detail:hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(0, mutations) }
    }
}
