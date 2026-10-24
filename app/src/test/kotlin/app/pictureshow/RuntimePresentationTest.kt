/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.tvprovider.media.tv.TvContractCompat
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clockOmitsAnImplausibleOrUnknownTotalButKeepsPositionPauseAndOrdinaryTotal() {
        assertEquals("1:00", playerClock(60_000, 4_294_982_295, false))
        assertEquals("Paused  ·  1:00", playerClock(60_000, Long.MAX_VALUE, true))
        assertEquals("1:00", playerClock(60_000, 0, false))
        assertEquals("1:00 / 2:00", playerClock(60_000, 120_000, false))
        assertEquals("1:00 / 24:00:00", playerClock(60_000, 86_400_000, false))
    }

    @Test
    fun spokenTimelineOmitsBogusTotalAndCannotSeekByABogusPercentage() {
        compose.setContent { PictureshowTheme { PlayerTimeline(60_000, 60_000, 4_294_982_295) {} } }
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:00"))
        timeline.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        timeline.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun launcherProgramOmitsBogusDurationWithoutChangingResumePosition() {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
        val item = MediaItem(
            "film",
            "Fictional sample",
            "Movie",
            runTimeTicks = 42_949_822_950_000,
            userData = ItemUserData(playbackPositionTicks = 600_000_000)
        )
        val program = watchProgram(account, item, false, 1000)
        assertFalse(program.toContentValues().containsKey(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS))
        assertEquals(60_000, program.lastPlaybackPositionMillis)
        assertEquals(120_000, watchProgram(account, item.copy(runTimeTicks = 1_200_000_000), false, 1000).durationMillis)
    }
}
