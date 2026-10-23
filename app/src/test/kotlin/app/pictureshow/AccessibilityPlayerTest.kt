/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun playbackClockDoesNotChangeItsSpokenPositionOnEachVisualTickAndAccessibleSeekWorks() {
        val position = mutableLongStateOf(30_000)
        var seek = -1L
        compose.setContent { PictureshowTheme { PlayerTimeline(position.longValue, 30_000, 120_000) { seek = it } } }
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:30 of 2:00"))
        compose.runOnIdle { position.longValue = 31_000 }
        timeline.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:30 of 2:00"))
        timeline.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.5f)) }
        assertEquals(60_000, seek)
        timeline.performSemanticsAction(SemanticsActions.SetProgress) { assertEquals(false, it(Float.NaN)) }
        assertEquals(60_000, seek)
    }

    @Test
    fun accessibleNextEpisodeWaitsForViewerEvenAfterTheEpisodeEnds() {
        val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Viewer"), "fake")
        var plays = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                NextEpisodeControl(account, MediaItem("next", "Next Story", "Episode"), true, true, false, {
                }, { plays++ }, autoPlay = false)
            }
        }
        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle { assertEquals(0, plays) }
        compose.onNodeWithTag("next:play").performSemanticsAction(SemanticsActions.OnClick) { kotlin.test.assertTrue(it()) }
        compose.runOnIdle { assertEquals(1, plays) }
    }

    @Test
    fun playPauseButtonAnnouncesItsCurrentStateAsWellAsItsAction() {
        val paused = mutableStateOf(true)
        compose.setContent { PictureshowTheme { PlaybackToggle(paused.value, paused.value) { paused.value = !paused.value } } }
        compose.onNodeWithText(
            "Play"
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused")
        ).performSemanticsAction(SemanticsActions.OnClick) {
            kotlin.test.assertTrue(it())
        }
        compose.onNodeWithText("Pause").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
    }

    @Test
    fun tvAccessibilityDetectionDoesNotRequireTouchExploration() {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        Shadows.shadowOf(manager).setEnabled(true)
        var enabled = false
        compose.setContent { enabled = rememberAccessibilityEnabled() }
        compose.runOnIdle { assertTrue(enabled) }
        assertEquals(false, manager.isTouchExplorationEnabled)
    }

    @Test
    fun trackLanguageDoesNotHideAudioDescriptionOrCommentaryTitle() {
        val stream =
            MediaStream(type = MediaStreamType.Audio, index = 1, language = "eng", title = "Audio description", codec = "aac", channels = 2)
        assertEquals("English · AAC stereo · Audio description", trackLabel(stream))
        assertEquals("English · AAC stereo · Commentary", trackLabel(stream.copy(title = "Commentary")))
        assertEquals("English · AAC stereo", trackLabel(stream.copy(title = "English")))
    }
}
