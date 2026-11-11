/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import java.util.Locale
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [LiveClockPatternShadow::class])
class LiveLocaleCompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun formatterFollowsTheObservableCompositionLocaleAfterItChanges() {
        val configuration = mutableStateOf(Configuration().apply { setLocale(Locale.US) })
        val target = Instant.parse("2030-01-01T12:00:00Z").toEpochMilli()
        val english = LiveTime(false, Locale.US).day(target)
        val japanese = LiveTime(false, Locale.JAPAN).day(target)
        assertNotEquals(english, japanese, "The fixture must distinguish the two composition locales")
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                Text(liveTime().day(target))
            }
        }
        compose.onNodeWithText(english).assertIsDisplayed()
        compose.runOnIdle {
            configuration.value = Configuration(configuration.value).apply { setLocale(Locale.JAPAN) }
        }
        compose.onNodeWithText(japanese).assertIsDisplayed()
        compose.onNodeWithText(english).assertDoesNotExist()
    }
}
