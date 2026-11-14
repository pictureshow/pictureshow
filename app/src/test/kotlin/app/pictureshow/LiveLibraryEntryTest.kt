/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class LiveLibraryEntryTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("u", "Viewer"), "fake")
    private val live = MediaItem("server-live", "Live TV", "UserView", collectionType = "livetv")
    private val ordinary = MediaItem("movies", "Live TV", "CollectionFolder", collectionType = "movies")

    @Test fun typedServerLiveViewUsesTheSharedDestinationWithoutHijackingAnOrdinaryLibraryName() {
        assertEquals("live-tv", itemRoute(live))
        assertEquals("live-tv", itemRoute(live.copy(type = "CollectionFolder", collectionType = "LiveTv")))
        assertTrue(itemRoute(ordinary).startsWith("item?item="))
        assertTrue(itemRoute(live.copy(type = "Movie")).startsWith("item?item="))
    }

    @Test fun tvHomeServerViewActivatesTheSameLiveDestinationAsTheToolbar() {
        var route = ""
        compose.setContent {
            PictureshowTheme {
                CatalogHome(
                    CatalogHomeState(
                        loading = false,
                        preferencesReady = true,
                        rows = listOf(HomeRow("libraries", "Libraries", listOf(live), library = true))
                    ),
                    account,
                    { route = itemRoute(it) },
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithTag("home:libraries:server-live").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("live-tv", route) }
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp-port-mdpi")
    fun phoneLibrariesKeepsOneCanonicalLiveCardAndPreservesAnOrdinarySameNamedLibrary() {
        var liveOpens = 0
        var opened = ""
        compose.setContent {
            TouchTheme {
                TouchCatalogHome(
                    CatalogHomeState(loading = false, libraries = listOf(live, ordinary)),
                    account,
                    { opened = it.id },
                    {},
                    librariesOnly = true,
                    onLiveTv = { liveOpens++ }
                )
            }
        }
        compose.onNodeWithTag("touch:card:server-live").assertDoesNotExist()
        compose.onNodeWithTag("touch:card:destination:live-tv").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("touch:card:movies").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, liveOpens)
            assertEquals("movies", opened)
        }
    }
}
