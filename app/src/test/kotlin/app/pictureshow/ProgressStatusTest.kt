/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Drive the actual report ledger, detail model and UI; ack changes wording, never stale-data protection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ProgressStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun movieShowsLoadingAfterAcknowledgement() = scenario("Movie")

    @Test fun seriesShowsLoadingAfterAcknowledgementIncludingLateOpenAndRetry() = scenario("Series")

    @Test fun seasonShowsLoadingAfterAcknowledgementIncludingLateOpenAndRetry() = scenario("Season")

    private fun scenario(type: String) {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
        val parent = MediaItem("parent", "Fictional sample", type, seriesId = "series")
        val episode = MediaItem("episode", "Fictional episode", "Episode", seriesId = "series", seasonId = "season")
        val season = MediaItem("season", "Season 1", "Season", seriesId = "series")
        var held = false
        var fail = false
        val read = CompletableDeferred<Unit>()
        val service = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun item(account: Account, id: String): ClientResult<MediaItem> {
                if (held && type == "Movie") read.await()
                return ClientResult.Success(parent.copy(userData = ItemUserData(played = held)))
            }
            override suspend fun nextUp(account: Account, seriesId: String?) = ClientResult.Success(ItemPage(emptyList()))
            override suspend fun seasons(account: Account, seriesId: String) = ClientResult.Success(ItemPage(listOf(season)))
            override suspend fun episodes(account: Account, seriesId: String, seasonId: String): ClientResult<ItemPage> {
                if (held) read.await()
                return if (fail) {
                    ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Could not load watch state."))
                } else {
                    ClientResult.Success(ItemPage(listOf(episode.copy(userData = ItemUserData(played = held)))))
                }
            }
        }
        val catalog = CatalogSession(
            service,
            AccountStore(object : TextPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) = Unit
            })
        )
        val store = ViewModelStore()
        var count = 0
        fun open() = CatalogDetailViewModel(account, parent, catalog).also { store.put("detail-${count++}", it) }
        val retained = open()
        val displayed = mutableStateOf(retained)
        try {
            compose.setContent {
                val model = displayed.value
                val state by model.state.collectAsState()
                PictureshowTheme { CatalogDetail(state, {}, {}, {}, {}, model::reload) }
            }
            compose.onNodeWithTag("detail:play").assertIsDisplayed()
            val first = Any()
            val second = Any()
            compose.runOnIdle {
                catalog.playbackPending(episode.id, first, setOf(parent.id))
                catalog.playbackPending(episode.id, second, setOf(parent.id))
            }
            compose.onNodeWithText("Saving progress…").assertIsDisplayed()
            compose.onNodeWithTag("detail:play").assertDoesNotExist()
            compose.runOnIdle {
                held = true
                catalog.refresh(episode.id, first)
            }
            compose.onNodeWithText("Saving progress…").assertIsDisplayed()
            compose.runOnIdle { catalog.refresh(episode.id, second) }
            // The report is acknowledged, but the authoritative item/episode read is deliberately blocked.
            compose.onNodeWithText("Loading item…").assertIsDisplayed()
            compose.onNodeWithText("Saving progress…").assertDoesNotExist()
            compose.onNodeWithTag("detail:play").assertDoesNotExist()
            compose.onNodeWithText("Loading item…").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            )
            compose.onNodeWithText("Loading item…").onParent().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithText("Loading item…").onParent().assertIsFocused()
            // A parent first opened after acknowledgement still needs its own authoritative dependent reads.
            compose.runOnIdle { displayed.value = open() }
            compose.onNodeWithText("Loading item…").assertIsDisplayed()
            compose.onNodeWithTag("detail:play").assertDoesNotExist()
            compose.runOnIdle {
                fail = type != "Movie"
                read.complete(Unit)
            }
            if (type != "Movie") {
                compose.onNodeWithText("Retry").assertIsFocused()
                compose.onNodeWithText("Loading item…").assertDoesNotExist()
                compose.onNodeWithText("Saving progress…").assertDoesNotExist()
                // With no playable episode, the retained Play slot is hidden from reader actions.
                // Favorite would be enabled without recovery blocking, independently of episodes/child count.
                compose.onNodeWithTag("detail:play").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                compose.onNodeWithTag("detail:played").assertIsNotEnabled()
                compose.onNodeWithTag("detail:favorite").assertIsNotEnabled()
                compose.runOnIdle {
                    fail = false
                    displayed.value.reload()
                    retained.reload()
                }
            }
            compose.onNodeWithTag("detail:play").assertIsDisplayed()
            compose.onNodeWithText("Mark unwatched").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(!displayed.value.state.value.playbackPending)
                assertTrue(!retained.state.value.playbackPending)
                assertTrue(retained.state.value.item?.userData?.played == true)
            }
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
}
