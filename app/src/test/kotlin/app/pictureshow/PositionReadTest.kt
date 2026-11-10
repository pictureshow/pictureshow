/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
class PositionReadTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** rc30: "1:43 of 9:56", then "1:44 of 9:56" seconds later, once the seek's preview expired on the focused control. */
    @Test fun aSeekIsCapturedOnceWhetherItsPreviewExpiresOrItFinishes() {
        val preview = mutableStateOf<Long?>(null)
        val target = mutableStateOf<Long?>(null)
        val position = mutableLongStateOf(90_000)
        val paused = mutableStateOf(false)
        var spoken = -1L
        compose.setContent {
            spoken = rememberAnnouncedPosition(
                paused.value,
                preview.value ?: target.value,
                596_000,
                false,
                position.longValue
            )
        }
        compose.runOnIdle { assertEquals(90_000, spoken) }
        compose.runOnIdle { position.longValue = 100_000 }
        compose.runOnIdle { assertEquals(90_000, spoken, "The clock moving is not news") }
        compose.runOnIdle {
            preview.value = 103_000
            position.longValue = 103_000
        }
        compose.runOnIdle { assertEquals(103_000, spoken, "A seek is read") }
        compose.runOnIdle {
            target.value = 103_000
            position.longValue = 104_000
        }
        compose.runOnIdle { assertEquals(103_000, spoken) }
        compose.runOnIdle { target.value = null }
        compose.runOnIdle { assertEquals(103_000, spoken, "The player finishing the seek is not a second read") }
        compose.runOnIdle { preview.value = null }
        compose.runOnIdle { assertEquals(103_000, spoken, "The preview expiring is not a second read") }
        compose.runOnIdle { position.longValue = 105_000 }
        compose.runOnIdle { paused.value = true }
        compose.runOnIdle { assertEquals(105_000, spoken, "Pausing still captures the position") }
    }

    @Test fun aSecondSeekAHeldRepeatAndTheSameTargetAgainAreEachRead() {
        val preview = mutableStateOf<Long?>(null)
        val target = mutableStateOf<Long?>(null)
        val position = mutableLongStateOf(90_000)
        val paused = mutableStateOf(false)
        var spoken = -1L
        compose.setContent {
            spoken = rememberAnnouncedPosition(
                paused.value,
                preview.value ?: target.value,
                596_000,
                false,
                position.longValue
            )
        }
        fun seekTo(ms: Long) {
            compose.runOnIdle {
                preview.value = ms
                position.longValue = ms
            }
            compose.runOnIdle { assertEquals(ms, spoken, "Seek to $ms") }
        }
        seekTo(103_000)
        compose.runOnIdle {
            preview.value = null
            position.longValue = 110_000
        }
        compose.runOnIdle { assertEquals(103_000, spoken) }
        seekTo(113_000)
        // A held key: the server's target stays on the first step while the preview moves on.
        compose.runOnIdle { target.value = 113_000 }
        seekTo(123_000)
        seekTo(133_000)
        compose.runOnIdle { assertEquals(133_000, spoken) }
        compose.runOnIdle {
            target.value = null
            preview.value = null
            position.longValue = 140_000
        }
        compose.runOnIdle { assertEquals(133_000, spoken, "The held seek settling is not another read") }
        compose.runOnIdle { paused.value = true }
        compose.runOnIdle { assertEquals(140_000, spoken) }
        seekTo(133_000)
    }

    @Test fun backAtTheStartGivesFeedbackEvenWhenThePositionDoesNotChange() {
        val speech = mutableListOf<String>()
        val host = object : View(compose.activity) {
            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { SeekPanel(0, 60_000, {}, {}) }
            }
        }
        compose.onNodeWithTag("seek:back").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals(listOf("0:00 of 1:00"), speech) }
    }

    @Test fun aSubsecondStartAndRepeatedEndClampGiveFeedbackWhenTheClockStringDoesNotChange() {
        val speech = mutableListOf<String>()
        val position = mutableLongStateOf(400)
        val host = object : View(compose.activity) {
            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme {
                    SeekPanel(position.longValue, 60_022, {
                        position.longValue =
                            (position.longValue + it).coerceIn(0, 60_022)
                    }, {})
                }
            }
        }
        compose.onNodeWithTag("seek:back").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals(listOf("0:00 of 1:00"), speech)
            position.longValue = 60_000
        }
        compose.onNodeWithTag("seek:forward").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithTag("seek:forward").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle { assertEquals(listOf("0:00 of 1:00", "1:00 of 1:00"), speech) }
    }

    @Test fun seekDialogNodeQueriesTrackTheLiveClockWhileItsSemanticsRemainQuiet() {
        val position = mutableLongStateOf(30_000)
        lateinit var host: View
        var sought = 0L
        compose.setContent {
            PictureshowTheme {
                PlayerPanel("Seek", {}) {
                    host = LocalView.current
                    SeekPanel(position.longValue, 120_000, { sought += it }, {})
                }
            }
        }
        val back = compose.onNodeWithTag("seek:back")
        val forward = compose.onNodeWithTag("seek:forward")
        val clock = compose.onNodeWithContentDescription("Playback position")
        val ids = listOf(back, forward, clock).map { it.fetchSemanticsNode().id }
        compose.runOnIdle { position.longValue = 78_000 }
        compose.mainClock.advanceTimeBy(32)
        back.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:30 of 2:00"))
        compose.runOnIdle {
            for (id in ids) {
                val info = requireNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
                assertEquals("1:18 of 2:00", info.stateDescription?.toString())
            }
            assertEquals(
                true,
                host.accessibilityNodeProvider.performAction(ids[1], android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null)
            )
            assertEquals(10_000L, sought)
        }
    }

    @Test fun physicalEndJustBeforeTheLastTimestampReadsTheWholeEndSecond() {
        compose.setContent { PictureshowTheme { PlayerTimeline(59_980, 40_000, 60_031, ended = true) } }
        val info = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().config
        assertEquals("1:00 of 1:00", info[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
    }

    @Test fun accessibilityNodeReadsTheCurrentClockWithoutChangingQuietComposeSemantics() {
        val position = mutableLongStateOf(30_000)
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            PictureshowTheme { PlayerTimeline(position.longValue, 30_000, 120_000) }
        }
        val node = compose.onNodeWithContentDescription("Playback position")
        val id = node.fetchSemanticsNode().id
        compose.runOnIdle { position.longValue = 78_000 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            val info = requireNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
            assertEquals("1:18 of 2:00", info.stateDescription?.toString())
        }
        // Existing quiet-clock tests separately verify no semantics change on each visual tick.
    }
}
