/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvNavRailTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(120)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** rc42 (leg 190): with many libraries the whole rail scrolled, and the account went off the top of the screen. */
    @Test fun theAccountStaysOnScreenWhenFocusReachesTheLastOfManyEntries() {
        val entries = buildList {
            add(RailEntry("account", "Viewer", null, "rail:account", {}, avatar = "V"))
            add(RailEntry("search", "Search", null, "rail:search", {}))
            repeat(16) { add(RailEntry("library$it", "Library $it", null, "rail:library$it", {}, groupStart = it == 0)) }
            add(RailEntry("settings", "Settings", null, "rail:settings", {}, groupStart = true))
        }
        compose.setContent { PictureshowTheme { TvNavRail(entries, onExit = {}) } }
        compose.onNodeWithTag("rail:settings").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("rail:settings").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("rail:account").assertIsDisplayed()
    }
}
