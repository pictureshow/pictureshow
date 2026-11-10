/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AdvancedPlaybackSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tvSubtitleControlsChangeActualAppearanceAndExposeConsequences() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                AdvancedPlaybackSettings("Subtitle appearance", prefs, false, { prefs = it(prefs) }, {})
            }
        }
        compose.onNodeWithText("Text color: System").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals("White", prefs.subtitleStyle.foreground) }
        compose.onNodeWithText("Image subtitle opacity: 100%").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {
            check(it())
        }
        compose.runOnIdle { assertEquals(80, prefs.subtitleStyle.imageOpacity) }
        compose.onNodeWithText("Override authored ASS styling: Off").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) {
            check(it())
        }
        compose.runOnIdle { assertEquals(true, prefs.subtitleStyle.overrideEmbedded) }
    }

    @Test fun touchBurnChoiceShowsTruthfulAlwaysBurnConsequence() {
        var prefs by mutableStateOf(PlaybackPreferences())
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                AdvancedPlaybackSettings("Subtitle conversion", prefs, true, { prefs = it(prefs) }, {})
            }
        }
        compose.onNodeWithText("Burn subtitles").performClick()
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText(
            "Every selected subtitle is burned into video by the server. This requires video conversion; " +
                "local appearance and timing controls cannot change it."
        ).assertExists()
    }
}
