/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.pictureshow.core.Account
import app.pictureshow.core.ApiTimeouts
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaPerson
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** CAT-08..13 in touch form: a finger tap during the wait, no focus or key queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h900dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchDetailPendingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val resumable = film.copy(userData = ItemUserData(playbackPositionTicks = 300_000_000))

    private inner class Page(initial: CatalogDetailState, origin: MediaItem? = film) {
        var state by mutableStateOf(initial)
        var shown by mutableStateOf(true)
        val plays = mutableListOf<Pair<MediaItem, Boolean>>()
        val remote = mutableListOf<String>()
        var played = 0
        var favorites = 0
        var retries = 0
        var backs = 0
        var requests = 0

        init {
            compose.mainClock.autoAdvance = false
            compose.setContent {
                MaterialTheme {
                    if (shown) {
                        TouchCatalogDetail(
                            state,
                            {},
                            {},
                            {
                                played++
                                state = state.copy(busy = true)
                            },
                            {
                                favorites++
                                state = state.copy(busy = true)
                            },
                            { retries++ },
                            { item, resume -> plays += item to resume },
                            { backs++ },
                            onPlayOnTv = { item, fromStart -> remote += "tv:${item.id}:$fromStart" },
                            onPlayNext = { remote += "next:${it.id}" },
                            onAddToQueue = { remote += "queue:${it.id}" },
                            origin = origin,
                            onPlayedRequest = { requests++ }
                        )
                    }
                }
            }
            settle()
        }

        // A paused clock draws no frame for a write the snapshot system has not been told about yet.
        fun settle(millis: Long = 100) {
            Snapshot.sendApplyNotifications()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(millis)
            compose.waitForIdle()
        }

        fun tap(target: String) {
            // The TV's actions live behind one control, so reaching one takes that control and then the menu's row.
            if (target in listOf("on-tv", "play-next", "add-to-queue", "on-tv-start")) {
                compose.onNodeWithTag("touch:detail:tv").performScrollTo().performClick()
                settle(400)
                compose.onNodeWithTag("touch:detail:$target").performClick()
                settle(400)
                return
            }
            compose.onNodeWithTag("touch:detail:$target").performScrollTo().performClick()
            settle()
        }

        fun bounds() = listOf("primary", "played", "favorite", "tv").associateWith {
            compose.onNodeWithTag("touch:detail:$it").getBoundsInRoot()
        }

        fun label(target: String) = textOf("touch:detail:$target")
    }

    private fun pending(item: MediaItem? = film, loading: Boolean = true) =
        CatalogDetailState(account, item, loading = loading, playbackPending = true)
    private fun fresh(item: MediaItem = film) = CatalogDetailState(account, item, loading = false, readSucceeded = true)

    private fun enableReader() {
        Shadows.shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(true)
    }

    private fun textOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }

    private fun cue() = textOf("touch:detail:cue")

    private fun assertStatus(text: String) {
        assertEquals(text, textOf("touch:detail:status"))
    }

    // CAT-08
    @Test fun pageStaysThroughExitAndStatusPhasesMoveNothing() {
        val page = Page(pending())
        val start = page.bounds()
        compose.onNodeWithText("Invented film").assertExists()
        assertStatus("Saving progress…")
        page.state = page.state.copy(playbackReportFinished = true)
        page.settle()
        assertStatus("Loading item…")
        page.state = pending().copy(loading = false, readSucceeded = true)
        page.settle()
        assertStatus("Item loaded. Saving progress…")
        page.settle(2_000) // held wording has replaced the plain labels
        assertEquals("Play when ready", page.label("primary"))
        assertEquals(start, page.bounds())
        page.state = fresh(resumable)
        page.settle()
        compose.onNodeWithTag("touch:detail:status").assertDoesNotExist()
        assertEquals(start, page.bounds())
    }

    // CAT-08: the new returning model clears stale data, but the route still names the item.
    @Test fun aReturningModelWithoutDataStillShowsTheRouteItem() {
        val page = Page(pending(item = null))
        compose.onNodeWithText("Invented film").assertExists()
        assertEquals("Play", page.label("primary"))
        assertEquals("Watched", page.label("played"))
        page.settle(2_000)
        assertEquals("Mark watched when ready", page.label("played"))
    }

    // The heading a returning viewer sees stays put; what the fresh read adds comes below it.
    @Test fun aFreshReadAddsBelowTheFrozenRouteHeader() {
        val page = Page(pending(item = null))
        val corrected = film.copy(name = "Corrected name", overview = "Fresh overview")
        page.state = pending(corrected, loading = false).copy(readSucceeded = true)
        page.settle()
        compose.onNodeWithText("Invented film").assertExists()
        compose.onNodeWithText("Corrected name").assertDoesNotExist()
        page.state = fresh(corrected)
        page.settle()
        compose.onNodeWithText("Invented film").assertExists()
        compose.onNodeWithText("More details").assertExists()
        compose.onNodeWithText("Corrected name").assertExists()
    }

    @Test fun theFirstArrivalShowsTheRouteCardWhileItsReadRuns() {
        val page = Page(CatalogDetailState(account, loading = true))
        compose.onNodeWithText("Invented film").assertExists()
        assertEquals("Play", page.label("primary"))
    }

    // Real text measurement: a narrow cell wraps "when ready" wording, and the reserved wording keeps the rows still.
    @Config(qualifiers = "w280dp-h900dp-port-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aLongerLabelDoesNotMoveTheGridOnANarrowPhone() {
        val page = Page(pending())
        val start = page.bounds()
        page.settle(2_000)
        assertEquals("Mark watched when ready", page.label("played"))
        assertEquals(start, page.bounds())
        page.tap("from-start")
        assertEquals(start, page.bounds())
        page.state = fresh(resumable)
        page.settle()
        assertEquals(start, page.bounds())
    }

    // CAT-09
    @Test fun aTapDuringTheWaitPlaysOnceWithFreshData() {
        val page = Page(pending())
        page.tap("primary")
        page.tap("primary")
        assertEquals("Play requested", page.label("primary"))
        // CAT, one rule for the play slot: the accepted one says so, and every other is disabled under its plain name.
        assertEquals("Play from start", page.label("from-start"))
        compose.onNodeWithTag("touch:detail:from-start").assertIsNotEnabled()
        assertTrue(page.plays.isEmpty())
        page.state = fresh(resumable)
        page.settle()
        assertEquals(listOf(resumable to true), page.plays)
    }

    @Test fun fromStartCapturedDuringTheWaitPlaysFromZero() {
        val page = Page(pending())
        page.tap("from-start")
        assertEquals("Start requested", page.label("from-start"))
        page.state = fresh(resumable)
        page.settle()
        assertEquals(listOf(resumable to false), page.plays)
    }

    @Test fun aSeriesPlaysItsFreshNextEpisodeNotARouteTarget() {
        val series = MediaItem("series", "A Series", "Series")
        val next = MediaItem("next", "Upcoming", "Episode")
        val page = Page(pending(series), origin = series)
        page.tap("primary")
        page.state = fresh(series).copy(nextUp = listOf(next))
        page.settle()
        assertEquals(listOf(next to false), page.plays)
    }

    // CAT-09: a natural-end acknowledgment that already matches cannot invert the viewer's request.
    @Test fun pendingWatchedCarriesItsDesiredValue() {
        val page = Page(pending())
        page.tap("played")
        assertEquals("Watched change requested", page.label("played"))
        page.tap("played")
        assertEquals(1, page.requests)
        page.state = fresh(film.copy(userData = ItemUserData(played = true)))
        page.settle()
        assertEquals(0, page.played)
        assertStatus("Nothing changed. This item is already watched.")
    }

    @Test fun pendingWatchedRunsWhenFreshStillDiffers() {
        val page = Page(pending())
        page.tap("played")
        page.state = fresh()
        page.settle()
        assertEquals(1, page.played)
    }

    @Test fun pendingFavoriteCarriesItsDesiredValue() {
        val page = Page(pending())
        page.tap("favorite")
        page.state = fresh(film.copy(userData = ItemUserData(isFavorite = true)))
        page.settle()
        assertEquals(0, page.favorites)
    }

    // CAT-09: distinct requests serialize, in order, each behind the write before it.
    @Test fun requestsRunInOrderBehindABusyWrite() {
        val page = Page(pending())
        page.tap("played")
        page.tap("favorite")
        page.tap("primary")
        page.state = fresh().copy(busy = true)
        page.settle()
        assertEquals(0, page.played)
        page.state = page.state.copy(busy = false)
        page.settle()
        // The harness's write goes busy, as the model's does: the next request has to wait for it.
        assertEquals(1 to 0, page.played to page.favorites)
        page.state = page.state.copy(busy = false)
        page.settle()
        assertEquals(1 to 1, page.played to page.favorites)
        assertTrue(page.plays.isEmpty())
        page.state = page.state.copy(busy = false)
        page.settle()
        assertEquals(listOf(film to false), page.plays)
    }

    // A ready control stays live during a write, and the second press keeps the value it offered.
    @Test fun aTapDuringABusyWriteJoinsTheQueueWithItsDesiredValue() {
        val page = Page(fresh())
        page.state = page.state.copy(busy = true)
        page.settle()
        page.tap("favorite")
        page.tap("favorite")
        assertEquals("Favorite change requested", page.label("favorite"))
        assertEquals(0, page.favorites)
        page.state = page.state.copy(busy = false)
        page.settle()
        assertEquals(1, page.favorites)
    }

    @Test fun remoteActionsAreCapturedToo() {
        val page = Page(pending())
        page.tap("on-tv")
        page.tap("play-next")
        page.tap("add-to-queue")
        page.tap("add-to-queue")
        assertEquals("Play on TV requested", page.label("tv"))
        compose.onNodeWithTag("touch:detail:primary").assertIsNotEnabled()
        assertTrue(page.remote.isEmpty())
        page.state = fresh(resumable)
        page.settle(500)
        assertEquals(listOf("tv:film:false", "next:film", "queue:film"), page.remote)
        assertTrue(page.plays.isEmpty())
    }

    // CAT line 25: Play from start is resume-only; the returning layout keeps its place until the position is known.
    @Test fun playFromStartNeedsSomethingToResume() {
        val page = Page(fresh(film))
        compose.onNodeWithTag("touch:detail:from-start").assertDoesNotExist()
        page.state = fresh(resumable)
        page.settle()
        compose.onNodeWithTag("touch:detail:from-start").assertExists()
    }

    @Test fun playFromStartHoldsItsPlaceDuringAReturn() {
        Page(pending())
        compose.onNodeWithTag("touch:detail:from-start").assertExists()
    }

    // Catalog B1: one primary, the state pair, and the TV's actions behind one control, every one reachable.
    @Test fun phoneArrangementGathersTheTvActionsAndKeepsEachReachable() {
        val page = Page(fresh(resumable))
        val primary = compose.onNodeWithTag("touch:detail:primary").getBoundsInRoot()
        val start = compose.onNodeWithTag("touch:detail:from-start").getBoundsInRoot()
        val played = compose.onNodeWithTag("touch:detail:played").getBoundsInRoot()
        val favorite = compose.onNodeWithTag("touch:detail:favorite").getBoundsInRoot()
        val tv = compose.onNodeWithTag("touch:detail:tv").getBoundsInRoot()
        assertEquals(primary.top, start.top)
        assertEquals(played.top, favorite.top)
        assertTrue(primary.bottom <= played.top && played.bottom <= tv.top)
        assertEquals(primary.left, tv.left)
        assertEquals(favorite.right, tv.right)
        compose.onNodeWithTag("touch:detail:on-tv").assertDoesNotExist()
        compose.onNodeWithTag("touch:detail:tv").performScrollTo().performClick()
        page.settle(400)
        listOf("on-tv", "play-next", "add-to-queue", "on-tv-start").forEach {
            compose.onNodeWithTag("touch:detail:$it").assertExists()
        }
        compose.onNodeWithTag("touch:detail:play-next").performClick()
        page.settle(400)
        assertEquals(listOf("next:film"), page.remote)
    }

    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    @Test
    fun wideArrangementHoldsOneLine() {
        Page(fresh(resumable))
        val tops = listOf("primary", "from-start", "played", "favorite", "tv").map {
            compose.onNodeWithTag("touch:detail:$it").getBoundsInRoot().top
        }
        assertEquals(1, tops.distinct().size, tops.toString())
    }

    @Test fun anAcceptedPlayDisablesTheOtherPlaysUnderTheirPlainNames() {
        val page = Page(pending())
        page.tap("from-start")
        assertEquals("Start requested", page.label("from-start"))
        assertEquals("Play", page.label("primary"))
        compose.onNodeWithTag("touch:detail:primary").assertIsNotEnabled()
    }

    // Catalog B7: the reason reads first and Retry stays pinned beside it.
    @Test fun failureBandHoldsItsReasonLeftOfRetry() {
        val page = Page(pending())
        page.settle(ApiTimeouts.REQUEST_MILLIS)
        page.settle(1_000)
        val reason = compose.onNodeWithTag("touch:detail:read-error").getBoundsInRoot()
        val retry = compose.onNodeWithTag("touch:detail:retry").getBoundsInRoot()
        assertTrue(reason.right <= retry.left)
    }

    // Tablet B3: on a series page the episodes are what the viewer came for.
    @Config(qualifiers = "w800dp-h3000dp-port-xhdpi")
    @Test
    fun seriesPageShowsEpisodesAboveTheCast() {
        val series = MediaItem(
            "show",
            "Invented show",
            "Series",
            people = listOf(MediaPerson("p", "A Person", "Actor"))
        )
        val episode = MediaItem("episode", "An episode", "Episode")
        Page(
            CatalogDetailState(
                account,
                series,
                loading = false,
                readSucceeded = true,
                episodes = listOf(episode),
                nextUp = listOf(episode)
            ),
            series
        )
        val episodes = compose.onNodeWithText("Episodes").getBoundsInRoot()
        val cast = compose.onNodeWithText("Cast & crew").getBoundsInRoot()
        assertTrue(episodes.bottom <= cast.top)
    }

    // CAT-10
    @Test fun anExpiredRequestIsExplainedAndNeverRuns() {
        val page = Page(pending())
        page.tap("primary")
        page.settle(ApiTimeouts.REQUEST_MILLIS)
        compose.onNodeWithTag("touch:detail:retry").assertIsEnabled()
        val reason = textOf("touch:detail:read-error")
        assertTrue("The previous request expired and will not run." in reason, reason)
        compose.onNodeWithTag("touch:detail:primary").assertIsNotEnabled()
        page.state = fresh(resumable)
        page.settle()
        assertTrue(page.plays.isEmpty())
    }

    @Test fun backAbandonsBeforeTheCompositionLeaves() {
        val page = Page(pending())
        page.tap("primary")
        page.tap("played")
        compose.onNodeWithText("‹ Back").performClick()
        page.settle()
        assertEquals(1, page.backs)
        page.state = fresh(resumable)
        page.settle()
        assertTrue(page.plays.isEmpty())
        assertEquals(0, page.played)
    }

    @Test fun leavingTheDestinationDropsWhatHasNotStarted() {
        val page = Page(pending())
        page.tap("primary")
        page.shown = false
        page.settle()
        page.state = fresh(resumable)
        page.settle()
        page.shown = true
        page.settle()
        assertTrue(page.plays.isEmpty())
    }

    // CAT-11
    @Test fun retryWorksWhileLoadingWithoutDuplicates() {
        val page = Page(pending().copy(loading = false, error = "Server unavailable.", readFailed = true))
        compose.onNodeWithTag("touch:detail:primary").assertIsNotEnabled()
        compose.onNodeWithTag("touch:detail:played").assertIsNotEnabled()
        compose.onNodeWithTag("touch:detail:retry").performClick()
        page.settle()
        assertEquals(1, page.retries)
        page.state = pending().copy(readAttempt = 2)
        page.settle()
        compose.onNodeWithText("Retrying…").assertExists()
        compose.onNodeWithTag("touch:detail:retry").assertIsEnabled().performClick()
        page.settle()
        assertEquals(1, page.retries)
        page.state = pending().copy(loading = false, error = "Server unavailable.", readFailed = true, readAttempt = 2)
        page.settle()
        compose.onNodeWithTag("touch:detail:retry").performClick()
        page.settle()
        assertEquals(2, page.retries)
    }

    @Test fun eachRetryOwnsANewBudget() {
        val page = Page(pending())
        val half = ApiTimeouts.REQUEST_MILLIS * 2 / 3
        page.settle(half)
        page.state = pending().copy(loading = false, error = "Server unavailable.", readFailed = true)
        page.settle()
        compose.onNodeWithTag("touch:detail:retry").performClick()
        page.settle()
        page.state = pending().copy(readAttempt = 2)
        page.settle(half)
        // Over the first budget, inside the second: the read lands and the page keeps taking requests.
        page.state = pending().copy(loading = false, readAttempt = 2, readSucceeded = true)
        page.settle()
        compose.onNodeWithTag("touch:detail:recovery").assertDoesNotExist()
        compose.onNodeWithTag("touch:detail:primary").assertIsEnabled()
        assertEquals("Play when ready", page.label("primary"))
    }

    // CAT-13
    @Test fun readAndWriteErrorsStaySeparate() {
        val page = Page(fresh())
        page.settle()
        page.state = page.state.copy(error = "Could not save.", writeAttempt = 1)
        page.settle()
        compose.onNodeWithTag("touch:detail:write-error").assertExists()
        compose.onNodeWithTag("touch:detail:retry").assertDoesNotExist()
        compose.onNodeWithTag("touch:detail:played").assertIsEnabled()
        page.tap("favorite")
        assertEquals(1, page.favorites)
        page.state = fresh().copy(loading = false, error = "Read failed.", readFailed = true, readSucceeded = false)
        page.settle()
        compose.onNodeWithTag("touch:detail:retry").assertExists()
        compose.onNodeWithTag("touch:detail:write-error").assertDoesNotExist()
        compose.onNodeWithTag("touch:detail:played").assertIsNotEnabled()
    }

    @Test fun aLoadedItemWithAHeldReportKeepsCommandsGuardedAndShowsNoError() {
        val page = Page(pending().copy(loading = false, readSucceeded = true))
        compose.onNodeWithTag("touch:detail:recovery").assertDoesNotExist()
        page.tap("primary")
        assertTrue(page.plays.isEmpty())
        page.state = page.state.copy(playbackPending = false, playbackReportFinished = true)
        page.settle()
        assertEquals(1, page.plays.size)
    }

    // CAT-06 application vectors: pending or failed reads never claim emptiness.
    @Test fun emptyIsOnlyClaimedAfterACompleteRead() {
        val series = MediaItem("series", "A Series", "Series")
        val page = Page(pending(series, loading = false), origin = series)
        compose.onNodeWithText("No episodes are available.").assertDoesNotExist()
        page.state = fresh(series).copy(readFailed = true, readSucceeded = false, error = "Read failed.")
        page.settle()
        compose.onNodeWithText("No episodes are available.").assertDoesNotExist()
        page.state = fresh(series)
        page.settle()
        compose.onNodeWithText("No episodes are available.").performScrollTo()
    }

    // Leg 104's cue contract, through the touch live region.
    @Test fun readerHearsPendingThenNamedReadyAfterThePendingCueHadItsTurn() {
        enableReader()
        val page = Page(pending())
        compose.onNodeWithTag("touch:detail:cue")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals("", cue())
        page.settle(1_500)
        assertEquals("Play when ready", cue())
        page.state = fresh(resumable)
        page.settle(500)
        assertEquals("Play when ready", cue())
        page.settle(1_200)
        assertEquals("Ready. Resume from 0:30", cue())
    }

    @Test fun aReadWithinTheGraceStaysSilentAndARequestRunsWithoutReady() {
        enableReader()
        val quick = Page(pending())
        quick.settle(500)
        quick.state = fresh()
        quick.settle(3_000)
        assertEquals("", cue())
        quick.state = pending()
        quick.settle(100)
        quick.tap("primary")
        assertEquals("Play requested", cue())
        quick.state = fresh()
        quick.settle(3_000)
        assertEquals(1, quick.plays.size)
        assertTrue("Ready" !in cue(), cue())
    }
}
