/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.exoplayer.ExoPlayer
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerPanelAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun explicitSeekActionsMoveTenSecondsAndRefreshTheirSpokenPosition() {
        val position = mutableLongStateOf(30_000)
        compose.setContent {
            PictureshowTheme {
                SeekPanel(position.longValue, 120_000, {
                    position.longValue =
                        (position.longValue + it).coerceIn(0, 120_000)
                }, {})
            }
        }
        compose.onNodeWithTag("seek:back").assertIsFocused()
        compose.onNodeWithTag("seek:forward").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertEquals(40_000, position.longValue)
        compose.onNodeWithTag("seek:forward").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:40 of 2:00"))
        compose.onNodeWithTag("seek:back").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertEquals(30_000, position.longValue)
    }

    @Test fun selectedTrackGetsInputFocusAndOnlyOneSelectionAnnouncement() {
        var selected = -1
        compose.setContent {
            PictureshowTheme { TrackChoices(listOf(-1 to "Off", 1 to "English", 2 to "Commentary"), 2, { selected = it }) }
        }
        val track = compose.onNodeWithTag("track:2").assertIsFocused().assertIsSelected()
        track.assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        assertEquals(listOf("Commentary"), track.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(null, track.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text))
        track.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertEquals(2, selected)
    }

    @Test fun nativeFocusEntryChoosesSelectedTrackBeforeTheWindowEffect() {
        val entry = FocusRequester()
        lateinit var focus: FocusManager
        compose.setContent {
            focus = LocalFocusManager.current
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = false
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PictureshowTheme {
                    Column(Modifier.focusRequester(entry).focusGroup()) {
                        TrackChoices(listOf(-1 to "Off", 1 to "English", 2 to "Commentary"), 2, {})
                    }
                }
            }
        }
        // Model the native Compose-view entry that TalkBack follows before the window effect.
        compose.runOnIdle {
            focus.clearFocus(force = true)
            assertTrue(entry.requestFocus())
        }
        compose.onNodeWithTag("track:2").assertIsFocused().assertIsSelected()
        compose.onNodeWithTag("track:-1").assertIsNotFocused()
    }

    @Test fun chapterModalHasBoundedListAndFocusesTheCurrentChapter() {
        val account = Account(Server("s", "Screen room", "https://media.example.test", "12"), User("u", "Viewer"), "fake")
        var target = -1L
        compose.setContent {
            PictureshowTheme {
                PlayerPanel("Chapters", {}, scrollable = false) {
                    // Robolectric does not grant a Compose Dialog's window focus. Model the
                    // focused window here; the separate transition test covers waiting for it.
                    val parent = LocalWindowInfo.current
                    val window = object : WindowInfo by parent {
                        override val isWindowFocused: Boolean get() = true
                    }
                    CompositionLocalProvider(LocalWindowInfo provides window) {
                        ChapterPanel(account, "m", listOf(Chapter(name = "Opening"), Chapter(600_000_000, "Another scene")), 65_000) {
                            target = it
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Chapters").assertDoesNotExist()
        compose.onNodeWithTag("chapter:1").assertIsDisplayed().assertIsFocused().performSemanticsAction(SemanticsActions.OnClick) {
            assertTrue(it())
        }
        assertEquals(60_000, target)
    }

    @Test fun modalTreeAndSelectedInputFocusExistBeforeWindowEntry() {
        var dialogWindow: Window? = null
        var focused by mutableStateOf(false)
        compose.setContent {
            PictureshowTheme {
                PlayerPanel("Audio", {}, scrollable = false) {
                    dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
                    val parent = LocalWindowInfo.current
                    val window = object : WindowInfo by parent {
                        override val isWindowFocused: Boolean get() = focused
                    }
                    CompositionLocalProvider(LocalWindowInfo provides window) {
                        TrackChoices(listOf(-1 to "Off") + (1..30).map { it to "Track $it" }, 30, {}, title = "Audio")
                    }
                }
            }
        }
        compose.runOnIdle {
            val window = requireNotNull(dialogWindow)
            assertEquals("Audio", window.attributes.title.toString())
            assertEquals(0f, window.attributes.dimAmount)
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // A native modal window supplies the boundary; its full tree is ready for its first focus event.
        compose.onNodeWithTag("player:panel").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Audio"))
        compose.onNodeWithTag("player:panel").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        compose.onNodeWithTag("track:30").assertIsDisplayed().assertIsFocused().assertIsSelected()
        compose.runOnIdle { focused = true }
        compose.onNodeWithTag("track:30").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Audio").assertDoesNotExist()
        compose.onNodeWithTag("player:panel").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Audio"))
        compose.onNodeWithTag("player:panel").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test fun selectedTrackRemainsTheOnlyTargetUntilAccessibilityAcknowledgesIt() {
        verifyInitialTrackAccessibilityGate(acknowledge = true)
    }

    @Test fun trackChoicesFailOpenWhenTheServiceDoesNotAcknowledgeFocus() {
        verifyInitialTrackAccessibilityGate(acknowledge = false)
    }

    private fun verifyInitialTrackAccessibilityGate(acknowledge: Boolean) {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        Shadows.shadowOf(manager).setEnabled(true)
        var focused by mutableStateOf(false)
        var acknowledged = false
        val provider = object : AccessibilityNodeProvider() {
            override fun findFocus(focus: Int): AccessibilityNodeInfo? =
                if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && acknowledged) {
                    @Suppress("DEPRECATION")
                    AccessibilityNodeInfo.obtain().apply {
                        isVisibleToUser = true
                        isFocusable = true
                        isClickable = true
                        isAccessibilityFocused = true
                    }
                } else {
                    null
                }
        }
        val view = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider
        }
        compose.setContent {
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = focused
            }
            CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window) {
                PictureshowTheme {
                    TrackChoices(listOf(1 to "English", 2 to "Commentary"), 2, {}, footer = {
                        androidx.tv.material3.Text("Additional controls", Modifier.testTag("footer"))
                    })
                }
            }
        }
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        val exposed = SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility)
        compose.onNodeWithTag("track:2").assertIsSelected().assert(exposed)
        compose.onNodeWithTag("track:1", useUnmergedTree = true).assert(hidden)
        // Hold the clock below the fail-open deadline while the window receives focus.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focused = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("track:1", useUnmergedTree = true).assert(hidden)
        if (acknowledge) {
            compose.runOnIdle { acknowledged = true }
            compose.mainClock.advanceTimeBy(100)
        } else {
            compose.mainClock.advanceTimeBy(1_600)
        }
        compose.onNodeWithTag("track:1").assert(exposed).assertIsDisplayed()
        compose.onNodeWithTag("footer").assertIsDisplayed()
        compose.onNodeWithTag("track:2").assertIsFocused().assertIsSelected()
    }

    @Test fun chapterArrivalUsesOneTargetAcrossABoundaryUntilFocusIsAcknowledged() {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        Shadows.shadowOf(manager).setEnabled(true)
        var focused by mutableStateOf(false)
        var position by mutableLongStateOf(30_000)
        val view = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : AccessibilityNodeProvider() {
                override fun findFocus(focus: Int): AccessibilityNodeInfo? {
                    @Suppress("DEPRECATION")
                    return AccessibilityNodeInfo.obtain().apply {
                        isVisibleToUser = true
                        isFocusable = true
                        isClickable = true
                        isAccessibilityFocused = true
                    }
                }
            }
        }
        val account = Account(Server("s", "Screen room", "https://media.example.test", "12"), User("u", "Viewer"), "fake")
        compose.setContent {
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = focused
            }
            CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window) {
                PictureshowTheme {
                    ChapterPanel(account, "m", listOf(Chapter(name = "Opening"), Chapter(600_000_000, "Another scene")), position) {}
                }
            }
        }
        compose.onNodeWithTag("chapter:0").assertIsSelected()
        compose.runOnIdle { position = 65_000 }
        // Arrival remains a coherent snapshot while its only accessibility target is pending.
        compose.onNodeWithTag("chapter:0").assertIsSelected()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        compose.onNodeWithTag("chapter:1", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focused = true }
        compose.mainClock.advanceTimeBy(100)
        // Complete the layout after scrollToItem, then deliver the acknowledgement frame.
        compose.runOnIdle { }
        compose.mainClock.advanceTimeBy(100)
        // Once acknowledged, all choices and the live current-chapter state resume.
        compose.onNodeWithTag("chapter:1").assertIsSelected()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        compose.onNodeWithTag("chapter:0").assertIsFocused().assertIsNotSelected()
    }

    @Test fun enablingAccessibilityInAnOpenTrackPanelStillRevealsOtherChoices() {
        verifyAccessibilityEnabledWhileOpen(chapters = false)
    }

    @Test fun enablingAccessibilityInAnOpenChapterPanelStillRevealsOtherChoices() {
        verifyAccessibilityEnabledWhileOpen(chapters = true)
    }

    private fun verifyAccessibilityEnabledWhileOpen(chapters: Boolean) {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        Shadows.shadowOf(manager).setEnabled(false)
        val view = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : AccessibilityNodeProvider() {
                override fun findFocus(focus: Int): AccessibilityNodeInfo? = null
            }
        }
        val account = Account(Server("s", "Screen room", "https://media.example.test", "12"), User("u", "Viewer"), "fake")
        lateinit var compositionManager: AccessibilityManager
        var enabledSeen = false
        compose.setContent {
            compositionManager = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            enabledSeen = rememberAccessibilityEnabled()
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = true
            }
            CompositionLocalProvider(LocalView provides view, LocalWindowInfo provides window) {
                PictureshowTheme {
                    if (chapters) {
                        ChapterPanel(account, "m", listOf(Chapter(name = "Opening"), Chapter(600_000_000, "Another scene")), 30_000) {}
                    } else {
                        TrackChoices(listOf(1 to "English", 2 to "Commentary"), 2, {})
                    }
                }
            }
        }
        val other = if (chapters) "chapter:1" else "track:1"
        val exposed = SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility)
        compose.onNodeWithTag(other).assert(exposed)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            var notified = false
            val listener = AccessibilityManager.AccessibilityStateChangeListener { notified = it }
            compositionManager.addAccessibilityStateChangeListener(listener)
            try {
                Shadows.shadowOf(compositionManager).setEnabled(true)
                assertTrue(compositionManager.isEnabled, "The Compose-local manager must be enabled")
                assertTrue(notified, "The platform state callback must be delivered")
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            } finally {
                compositionManager.removeAccessibilityStateChangeListener(listener)
            }
        }
        compose.mainClock.advanceTimeUntil(1_000) { enabledSeen }
        compose.mainClock.advanceTimeUntil(1_000) {
            compose.onNodeWithTag(other, useUnmergedTree = true).fetchSemanticsNode().config
                .contains(SemanticsProperties.HideFromAccessibility)
        }
        compose.onNodeWithTag(other, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        compose.mainClock.advanceTimeBy(1_700)
        compose.onNodeWithTag(other).assert(exposed).assertIsDisplayed()
    }

    @Test fun preparingWithoutAPlayerUsesANeutralState() {
        compose.setContent {
            PictureshowTheme {
                val playback = rememberPlayerPlayback(null, {}, {})
                PlaybackToggle(playback.paused, playback.showPlayButton) {}
            }
        }
        compose.onNodeWithText("Play").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not playing"))
    }

    @Test fun metadataReplacementPreservesRealPlayerPositionStateAndSource() {
        compose.runOnIdle {
            val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
            try {
                val media = androidx.media3.common.MediaItem.fromUri("https://media.example.test/video.mp4")
                player.setMediaItem(media)
                player.seekTo(12_345)
                player.playWhenReady = true
                updatePlaybackMetadata(player, MediaItem("m", "A Film", "Movie"))
                assertEquals(12_345, player.currentPosition)
                assertTrue(player.playWhenReady)
                assertEquals(media.localConfiguration, player.currentMediaItem?.localConfiguration)
                assertEquals("A Film", player.mediaMetadata.title.toString())
                updatePlaybackMetadata(player, MediaItem("m", "A Film", "Movie"))
                assertEquals(12_345, player.currentPosition)
            } finally {
                player.release()
            }
        }
    }

    @Test fun notificationNamesAnEpisodeAndUsesRealMediaKindForAFilm() {
        val episode = playbackMetadata(
            MediaItem("e", "A Story", "Episode", seriesName = "A Series", parentIndexNumber = 2, indexNumber = 3)
        )
        assertEquals("A Series", episode.artist.toString())
        assertTrue(episode.title.toString().contains("A Story"))
        assertTrue(episode.title.toString().contains("2"))
        val movie = playbackMetadata(MediaItem("m", "A Film", "Movie"))
        assertEquals("A Film", movie.title.toString())
        assertEquals("Movie", movie.artist.toString())
    }
}
