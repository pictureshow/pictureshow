/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
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
@Suppress("DEPRECATION")
class DetailPendingCueTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")

    private inner class Fixture {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        var reader = 1
        val events = mutableListOf<Pair<Int, String>>()
        val host = View(compose.activity)
        val native = object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                setSource(host, id)
                viewIdResourceName = if (id == 1) "detail:play" + if (state.value.loading) "-pending" else "" else "detail:title"
                className = if (id == 1) "android.widget.Button" else "android.view.View"
                text = if (id == 1) "Play" else film.name
                isVisibleToUser = true
                isEnabled = true
                isClickable = id == 1
                isFocusable = id == 1
                isFocused = id == 1
                isAccessibilityFocused = reader == id
            }
            override fun findFocus(focus: Int) = createAccessibilityNodeInfo(if (focus == AccessibilityNodeInfo.FOCUS_INPUT) 1 else reader)
        }
        val parent = object : FrameLayout(compose.activity) {
            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                events += event.eventType to event.text.joinToString()
                event.recycle()
                return true
            }
        }

        init {
            compose.mainClock.autoAdvance = false
            Shadows.shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(true)
            ViewCompat.setAccessibilityDelegate(
                host,
                object : AccessibilityDelegateCompat() {
                    override fun getAccessibilityNodeProvider(host: View) = AccessibilityNodeProviderCompat(native)
                }
            )
            parent.addView(host)
            compose.setContent {
                CompositionLocalProvider(LocalView provides host) {
                    PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
                }
            }
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            compose.runOnIdle { host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(1)?.recycle() }
        }

        fun settle() {
            compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithTag("detail:play").assertIsFocused()
        }
        fun cues() = events.filter { it.first == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }.map { it.second }
    }

    @Test fun theSameFocusedActionGetsOnePendingCueThenOneReady() {
        val f = Fixture()
        assertTrue(f.cues().isEmpty())
        compose.mainClock.advanceTimeBy(1_600)
        compose.runOnIdle { assertEquals(listOf("Play when ready"), f.cues()) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(listOf("Play when ready"), f.cues()) }
        f.settle()
        compose.runOnIdle { assertEquals(listOf("Play when ready", "Ready. Play"), f.cues()) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(listOf("Play when ready", "Ready. Play"), f.cues())
            assertTrue(f.events.none { it.first == AccessibilityEvent.TYPE_ANNOUNCEMENT })
        }
    }

    @Test fun aReadSettlingJustAfterThePendingCueLeavesThatCueItsTurn() {
        val f = Fixture()
        compose.mainClock.advanceTimeBy(1_600)
        compose.runOnIdle { assertEquals(listOf("Play when ready"), f.cues()) }
        f.settle()
        compose.runOnIdle { assertEquals(listOf("Play when ready"), f.cues(), "A newer cue would discard the waiting one") }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(listOf("Play when ready", "Ready. Play"), f.cues()) }
    }

    @Test fun aReaderWhoLeavesDuringTheReadyHoldDoesNotGetIt() {
        val f = Fixture()
        compose.mainClock.advanceTimeBy(1_600)
        f.settle()
        compose.runOnIdle { f.reader = 2 }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals(listOf("Play when ready"), f.cues()) }
    }

    @Test fun readyNamesAResumeActionByItsCurrentLabel() {
        val f = Fixture()
        compose.mainClock.advanceTimeBy(3_500)
        compose.runOnIdle {
            f.state.value = f.state.value.copy(
                item = film.copy(userData = film.userData.copy(playbackPositionTicks = 6_000_000_000)),
                loading = false,
                playbackPending = false,
                readSucceeded = true
            )
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            val ready = f.cues().last()
            assertTrue(ready.startsWith("Ready. Resume"), "Got: $ready")
        }
    }

    @Test fun aFastReadDoesNotAnnouncePendingOrReady() {
        val f = Fixture()
        f.settle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertTrue(f.cues().isEmpty(), "An ordinary arrival must keep its plain focus utterance") }
    }

    @Test fun returningToTheActionAfterGraceUsesFocusSpeechInsteadOfASecondPendingCue() {
        val f = Fixture()
        compose.runOnIdle { f.reader = 2 }
        compose.mainClock.advanceTimeBy(1_600)
        compose.runOnIdle { f.reader = 1 }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertTrue(f.cues().isEmpty(), "Refocusing a held control must use its ordinary focus label") }
    }

    @Test fun aReaderWhoLeavesTheActionDoesNotGetItsPendingOrReadyCue() {
        val f = Fixture()
        compose.runOnIdle { f.reader = 2 }
        compose.mainClock.advanceTimeBy(1_600)
        f.settle()
        compose.runOnIdle { assertTrue(f.cues().isEmpty(), "A heading selection must not be interrupted by the old action") }
    }
}
