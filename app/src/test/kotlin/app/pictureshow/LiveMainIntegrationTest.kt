/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class LiveMainIntegrationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("u", "Viewer"), "fake")

    @Test fun tvHomeKeepsLiveTvAndCustomizationAsSeparateReachableCommands() {
        var live = 0
        var custom = 0
        compose.setContent {
            PictureshowTheme {
                CatalogHome(
                    CatalogHomeState(loading = false, preferencesReady = true),
                    account,
                    {},
                    {},
                    {},
                    {},
                    onLiveTv = { live++ },
                    onCustomize = { custom++ }
                )
            }
        }
        compose.onNodeWithTag("home:live-tv").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("home:live-tv").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, live)
            assertEquals(0, custom)
        }
        compose.onNodeWithTag("home:live-tv").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("home:customize").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, live)
            assertEquals(1, custom)
        }
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp-port-mdpi")
    fun phoneHomeKeepsLiveTvAlongsideItsIndependentCustomizationMenu() {
        var live = 0
        var custom = 0
        compose.setContent {
            TouchTheme {
                TouchCatalogHome(
                    CatalogHomeState(loading = false, preferencesReady = true),
                    account,
                    {},
                    {},
                    onLiveTv = { live++ },
                    onCustomize = { custom++ }
                )
            }
        }
        compose.onNodeWithTag("home:live-tv").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, live)
            assertEquals(0, custom)
        }
        compose.onNodeWithTag("touch:home-menu").performScrollTo().performClick()
        compose.onNodeWithTag("touch:customize").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, live)
            assertEquals(1, custom)
        }
    }
}
