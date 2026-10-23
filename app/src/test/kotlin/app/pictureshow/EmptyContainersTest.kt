/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
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
class EmptyContainersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Server", "https://example.test", "12.0.0"), User("u", "Viewer"), "fictional")
    private val empty =
        MediaItem(
            "empty",
            "Empty collection",
            "BoxSet",
            isFolder = true,
            childCount = 0,
            userData = ItemUserData(played = true, unplayedItemCount = 0)
        )

    @Test
    fun emptyCollectionGridNamesCollectionRatherThanLibrary() {
        compose.setContent { PictureshowTheme { CatalogGrid(CatalogGridState(account, empty, loading = false), {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("This collection is empty.").assertExists()
        compose.onNodeWithText("This library is empty.").assertDoesNotExist()
    }

    @Test
    fun emptyGridTextUsesParentTypeAndKeepsLibraryWording() {
        val parent = mutableStateOf(empty.copy(type = "CollectionFolder"))
        compose.setContent {
            PictureshowTheme { CatalogGrid(CatalogGridState(account, parent.value, loading = false), {}, {}, { _, _ -> }, {}) }
        }
        for ((type, message) in listOf(
            "CollectionFolder" to "This library is empty.",
            "UserView" to "This library is empty.",
            "Playlist" to "This playlist is empty.",
            "Folder" to "This folder is empty.",
            "Genre" to "Nothing here yet.",
            "Person" to "Nothing here yet."
        )) {
            compose.runOnIdle { parent.value = empty.copy(type = type) }
            compose.onNodeWithText(message).assertExists()
        }
    }

    @Test
    fun emptyCardHasNoWatchedBadgeOrSpokenWatchedStateWhilePopulatedCardKeepsBoth() {
        val item = mutableStateOf(empty)
        compose.setContent {
            PictureshowTheme {
                Column {
                    CatalogCard(item.value, account, false, {}, Modifier.testTag("empty"))
                    CatalogCard(
                        empty.copy(id = "full", name = "Full collection", childCount = 2),
                        account,
                        false,
                        {},
                        Modifier.testTag("full")
                    )
                }
            }
        }
        for (counted in listOf(empty, empty.copy(childCount = null, recursiveItemCount = 0))) {
            compose.runOnIdle { item.value = counted }
            compose.onNodeWithTag("empty").assertContentDescriptionEquals("Empty collection")
        }
        compose.onNodeWithTag("full").assertContentDescriptionEquals("Full collection, Watched, 0 unplayed")
        // The decorative badge is in the unmerged tree; only the populated card should expose one.
        assertEquals(
            1,
            compose.onAllNodes(androidx.compose.ui.test.hasText("✓ Watched"), useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun emptyDetailCannotMarkUnwatchedFromVacuousServerPlayedFlag() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, empty.copy(type = "Series", name = "Empty series"), loading = false), {
                }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.onNodeWithTag("detail:played").assertIsNotEnabled()
        compose.onNodeWithTag("detail:favorite").assertIsFocused()
    }
}
