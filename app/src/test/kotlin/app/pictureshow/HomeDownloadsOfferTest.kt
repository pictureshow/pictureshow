/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeDownloadsOfferTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Sam"), "fake-token")
    private val failed = CatalogHomeState(loading = false, error = "Could not reach the server.", unreachable = true)

    @Test
    fun theServerOutOfReachWithDownloadsSavedLeadsWithThem() {
        var watched = 0
        var retried = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CatalogHome(failed, account, {}, { retried++ }, {}, {}, onWatchDownloads = { watched++ })
            }
        }
        compose.onNodeWithText("Could not reach the server. Your downloads still play.").assertExists()
        compose.onNodeWithTag("home:watch-downloads").assertIsFocused()
        compose.onNodeWithTag("home:watch-downloads").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("home:retry").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, watched)
        assertEquals(1, retried)
    }

    @Test
    fun aViewerWithNoDownloadsKeepsHomesOwnError() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { CatalogHome(failed, account, {}, {}, {}, {}) } }
        compose.onNodeWithText("Could not reach the server.").assertExists()
        compose.onNodeWithTag("home:watch-downloads").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsFocused()
    }
}
